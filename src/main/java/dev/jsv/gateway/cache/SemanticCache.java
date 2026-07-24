package dev.jsv.gateway.cache;

import dev.jsv.gateway.provider.Completion;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Caches completions by semantic similarity rather than exact key match.
 *
 * <p>Exact-match caching barely helps on natural-language traffic: "what's the status
 * of AWB 125-4482?" and "status for AWB 125-4482?" are different strings and would both
 * hit the provider. Matching on embedding distance collapses those into one call.
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

    /** A cached completion plus the vector it was keyed on. */
    public record Entry(String prompt, Completion completion, float[] vector, Instant storedAt) {
        boolean isExpired(Instant now, Duration ttl) {
            return now.isAfter(storedAt.plus(ttl));
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
    private final LinkedHashMap<String, Entry> entries;

    private long hits = 0;
    private long misses = 0;
    private long evictions = 0;
    private double similaritySum = 0.0;

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
     * Looks for a semantically equivalent cached completion.
     *
     * <p>Linear scan over live entries. Fine to a few thousand entries; beyond that this
     * wants an ANN index (HNSW or a vector store), and the interface here does not
     * change when you swap it.
     */
    public Optional<Hit> lookup(String prompt) {
        float[] query = embedder.embed(prompt);
        Instant now = Instant.now();

        lock.writeLock().lock();  // write lock: LRU access-order mutates on read
        try {
            purgeExpired(now);

            String bestKey = null;
            double bestSimilarity = -1.0;

            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                double similarity = Embedder.cosine(query, e.getValue().vector());
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestKey = e.getKey();
                }
            }

            if (bestKey != null && bestSimilarity >= threshold) {
                Entry entry = entries.get(bestKey);  // touch for LRU
                hits++;
                similaritySum += bestSimilarity;
                return Optional.of(new Hit(entry.completion(), bestSimilarity, entry.prompt()));
            }

            misses++;
            return Optional.empty();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void put(String prompt, Completion completion) {
        float[] vector = embedder.embed(prompt);
        lock.writeLock().lock();
        try {
            entries.put(prompt, new Entry(prompt, completion, vector, Instant.now()));
            evictIfNeeded();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired(now, ttl)) {
                it.remove();
            }
        }
    }

    private void evictIfNeeded() {
        // LinkedHashMap in access order puts the coldest entry first.
        List<String> doomed = new ArrayList<>();
        int overflow = entries.size() - maxEntries;
        if (overflow <= 0) return;
        for (String key : entries.keySet()) {
            if (doomed.size() >= overflow) break;
            doomed.add(key);
        }
        doomed.forEach(entries::remove);
        evictions += doomed.size();
    }

    public Stats stats() {
        lock.readLock().lock();
        try {
            long total = hits + misses;
            return new Stats(
                    hits,
                    misses,
                    total == 0 ? 0.0 : (double) hits / total,
                    entries.size(),
                    evictions,
                    hits == 0 ? 0.0 : similaritySum / hits
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
        } finally {
            lock.writeLock().unlock();
        }
    }

    public double threshold() {
        return threshold;
    }
}
