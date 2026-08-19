package dev.jsv.gateway.cache;

import dev.jsv.gateway.provider.Completion;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Caches completions by semantic similarity rather than exact key match.
 *
 * <p>Exact-match caching barely helps on natural-language traffic: "what's the status
 * of AWB 125-4482?" and "status for AWB 125-4482?" are different strings and would both
 * hit the provider. Matching on embedding distance collapses those into one call.
 *
 * <p><b>Entries are scoped to a tenant and the scope is not optional.</b> A shared cache
 * on a multi-tenant gateway hands one customer's answer to another — the prompts collide
 * precisely because different customers ask the same questions, so the failure is likeliest
 * on exactly the traffic the cache is best at. There is deliberately no tenant-less
 * overload: a signature that cannot express the bug is worth more than a comment warning
 * against it.
 *
 * <p><b>The threshold is a correctness decision, not a tuning knob.</b> Set it too low
 * and the cache serves a confidently wrong answer to a question nobody asked — a far
 * worse failure than a cache miss, and one that is nearly invisible in aggregate
 * metrics. It is deliberately conservative by default, and every hit records the
 * similarity that produced it so bad matches are auditable after the fact.
 *
 * <p>Entries carry a TTL because model outputs go stale, and eviction is LRU with a
 * hard cap so an unbounded prompt space cannot exhaust the heap.
 */
public class SemanticCache {

    /** Cache identity: the same prompt from two tenants is two entries, never one. */
    public record Key(String tenantId, String prompt) {}

    /** A cached completion plus the vector it was keyed on. */
    public record Entry(Key key, Completion completion, float[] vector, Instant storedAt) {
        boolean isExpired(Instant now, Duration ttl) {
            return now.isAfter(storedAt.plus(ttl));
        }

        public String prompt() {
            return key.prompt();
        }
    }

    /** A cache hit, carrying the similarity so callers can log and audit it. */
    public record Hit(Completion completion, double similarity, String matchedPrompt) {}

    private final Embedder embedder;
    private final double threshold;
    private final Duration ttl;
    private final int maxEntries;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // accessOrder=true makes this an LRU: iteration order is least-recently-used first.
    // It is the authority on recency and on the global entry cap.
    private final LinkedHashMap<Key, Entry> entries;

    // Scan index, holding the same Entry references. Lookup walks only the requesting
    // tenant's entries, which is both the isolation boundary and — since a tenant's slice
    // is a fraction of the whole — most of the reason a lookup is cheap. Iterating this
    // does not disturb the LRU, so the scan can run under a read lock; a LinkedHashMap in
    // access order cannot, because get() structurally modifies it.
    private final Map<String, Map<Key, Entry>> byTenant = new HashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final DoubleAdder similaritySum = new DoubleAdder();

    public SemanticCache(Embedder embedder, double threshold, Duration ttl, int maxEntries) {
        if (threshold <= 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in (0,1], got " + threshold);
        }
        this.embedder = embedder;
        this.threshold = threshold;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** Conservative defaults: 0.95 similarity, 15-minute TTL, 5000 entries. */
    public static SemanticCache withDefaults(Embedder embedder) {
        return new SemanticCache(embedder, 0.95, Duration.ofMinutes(15), 5_000);
    }

    /**
     * Looks for a semantically equivalent cached completion belonging to {@code tenantId}.
     *
     * <p>{@code maxTokens} is honoured rather than ignored: a completion generated under a
     * larger cap can be longer than the current caller asked for, and silently returning it
     * breaks a limit the caller set deliberately. Entries that do not fit are skipped, not
     * truncated — a truncated cached answer is a different answer.
     *
     * <p>Embedding happens before any lock is taken; the scan holds only a read lock, and
     * the write lock is taken briefly on a hit to record LRU recency. The previous version
     * held the write lock across the entire scan, which made the cache a global mutex that
     * every request in the gateway serialised behind.
     *
     * <p>Scan is linear within a tenant. Fine to a few thousand entries per tenant; beyond
     * that this wants an ANN index (HNSW or a vector store), and the interface here does
     * not change when you swap it.
     */
    public Optional<Hit> lookup(String tenantId, String prompt, int maxTokens) {
        float[] query = embedder.embed(prompt);
        Instant now = Instant.now();

        Key bestKey = null;
        Entry bestEntry = null;
        double bestSimilarity = -1.0;

        lock.readLock().lock();
        try {
            Map<Key, Entry> scope = byTenant.get(tenantId);
            if (scope == null || scope.isEmpty()) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            for (Map.Entry<Key, Entry> e : scope.entrySet()) {
                Entry entry = e.getValue();
                // Expiry is filtered here rather than purged, so the read path stays
                // read-only; put() does the reclaiming.
                if (entry.isExpired(now, ttl)) {
                    continue;
                }
                if (entry.completion().outputTokens() > maxTokens) {
                    continue;
                }
                double similarity = Embedder.cosine(query, entry.vector());
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestKey = e.getKey();
                    bestEntry = entry;
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        if (bestKey == null || bestSimilarity < threshold) {
            misses.incrementAndGet();
            return Optional.empty();
        }

        lock.writeLock().lock();
        try {
            // Re-check: an eviction or overwrite may have landed between the two locks.
            Entry current = entries.get(bestKey);  // touch for LRU
            if (current == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            bestEntry = current;
        } finally {
            lock.writeLock().unlock();
        }

        hits.incrementAndGet();
        similaritySum.add(bestSimilarity);
        return Optional.of(new Hit(bestEntry.completion(), bestSimilarity, bestEntry.prompt()));
    }

    public void put(String tenantId, String prompt, Completion completion) {
        Key key = new Key(tenantId, prompt);
        float[] vector = embedder.embed(prompt);
        Entry entry = new Entry(key, completion, vector, Instant.now());

        lock.writeLock().lock();
        try {
            purgeExpired(Instant.now());
            entries.put(key, entry);
            byTenant.computeIfAbsent(tenantId, k -> new HashMap<>()).put(key, entry);
            evictIfNeeded();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> e = it.next();
            if (e.getValue().isExpired(now, ttl)) {
                it.remove();
                unindex(e.getKey());
            }
        }
    }

    private void evictIfNeeded() {
        // LinkedHashMap in access order puts the coldest entry first.
        int overflow = entries.size() - maxEntries;
        if (overflow <= 0) return;
        List<Key> doomed = new ArrayList<>(overflow);
        for (Key key : entries.keySet()) {
            if (doomed.size() >= overflow) break;
            doomed.add(key);
        }
        for (Key key : doomed) {
            entries.remove(key);
            unindex(key);
        }
        evictions.addAndGet(doomed.size());
    }

    /** Keeps the scan index from outliving the LRU it mirrors. */
    private void unindex(Key key) {
        Map<Key, Entry> scope = byTenant.get(key.tenantId());
        if (scope == null) return;
        scope.remove(key);
        if (scope.isEmpty()) {
            byTenant.remove(key.tenantId());
        }
    }

    public Stats stats() {
        lock.readLock().lock();
        try {
            long h = hits.get();
            long total = h + misses.get();
            return new Stats(
                    h,
                    misses.get(),
                    total == 0 ? 0.0 : (double) h / total,
                    entries.size(),
                    evictions.get(),
                    h == 0 ? 0.0 : similaritySum.sum() / h
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    public record Stats(long hits, long misses, double hitRate, int size, long evictions,
                        double meanHitSimilarity) {}

    public void clear() {
        lock.writeLock().lock();
        try {
            entries.clear();
            byTenant.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Entries currently held for one tenant. Exposed so isolation is directly assertable. */
    public int sizeFor(String tenantId) {
        lock.readLock().lock();
        try {
            Map<Key, Entry> scope = byTenant.get(tenantId);
            return scope == null ? 0 : scope.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public double threshold() {
        return threshold;
    }
}
