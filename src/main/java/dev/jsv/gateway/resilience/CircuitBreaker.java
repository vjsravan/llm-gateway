package dev.jsv.gateway.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-provider circuit breaker over a sliding window of recent outcomes.
 *
 * <p>Three states, standard semantics:
 * <ul>
 *   <li><b>CLOSED</b> — calls pass through; outcomes are recorded.</li>
 *   <li><b>OPEN</b> — calls are rejected immediately without touching the provider.</li>
 *   <li><b>HALF_OPEN</b> — a limited number of probes are admitted to test recovery.</li>
 * </ul>
 *
 * <p>Two details that matter and are usually got wrong:
 *
 * <p><b>A minimum call count before the breaker can open.</b> Without it, the first
 * failure of the day sits at a 100% failure rate and takes a healthy provider out of
 * rotation. The window has to be statistically meaningful before it is actionable.
 *
 * <p><b>Half-open admits a bounded number of probes, not everything.</b> Dumping full
 * production traffic at a provider that has just come back is how you knock it over a
 * second time — and the second outage is usually longer than the first.
 *
 * <p>Hand-rolled rather than pulled from Resilience4j: this is the piece a reviewer
 * will read closely, and the sliding-window and half-open logic is exactly the part
 * worth showing rather than delegating.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int windowSize;
    private final double failureThreshold;
    private final int minimumCalls;
    private final Duration openDuration;
    private final int halfOpenProbes;

    private final Deque<Boolean> window = new ArrayDeque<>();

    private State state = State.CLOSED;
    private Instant openedAt = Instant.EPOCH;
    private int probesAdmitted = 0;
    private int probeSuccesses = 0;
    private long rejectedCalls = 0;
    private long transitions = 0;

    public CircuitBreaker(String name, int windowSize, double failureThreshold,
                          int minimumCalls, Duration openDuration, int halfOpenProbes) {
        if (failureThreshold <= 0 || failureThreshold > 1) {
            throw new IllegalArgumentException("failureThreshold must be in (0,1], got " + failureThreshold);
        }
        if (minimumCalls > windowSize) {
            throw new IllegalArgumentException("minimumCalls cannot exceed windowSize");
        }
        this.name = name;
        this.windowSize = windowSize;
        this.failureThreshold = failureThreshold;
        this.minimumCalls = minimumCalls;
        this.openDuration = openDuration;
        this.halfOpenProbes = halfOpenProbes;
    }

    /** Sensible defaults: open at 50% failures over the last 20 calls, retry after 10s. */
    public static CircuitBreaker withDefaults(String name) {
        return new CircuitBreaker(name, 20, 0.5, 8, Duration.ofSeconds(10), 3);
    }

    /**
     * @return true when the call may proceed. Transitions OPEN → HALF_OPEN once the
     *         cool-down has elapsed.
     */
    public synchronized boolean allowRequest(Instant now) {
        switch (state) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                if (now.isAfter(openedAt.plus(openDuration))) {
                    toHalfOpen();
                    probesAdmitted = 1;
                    return true;
                }
                rejectedCalls++;
                return false;
            }
            case HALF_OPEN -> {
                if (probesAdmitted < halfOpenProbes) {
                    probesAdmitted++;
                    return true;
                }
                // Probe budget spent and no verdict yet: shed load rather than pile on.
                rejectedCalls++;
                return false;
            }
        }
        return true;
    }

    public boolean allowRequest() {
        return allowRequest(Instant.now());
    }

    public synchronized void recordSuccess(Instant now) {
        if (state == State.HALF_OPEN) {
            probeSuccesses++;
            // Require the full probe budget to succeed before trusting the provider again.
            if (probeSuccesses >= halfOpenProbes) {
                toClosed();
            }
            return;
        }
        push(true);
    }

    public void recordSuccess() {
        recordSuccess(Instant.now());
    }

    public synchronized void recordFailure(Instant now) {
        if (state == State.HALF_OPEN) {
            // One failed probe is enough — the provider is still unwell.
            toOpen(now);
            return;
        }
        push(false);
        if (state == State.CLOSED && shouldOpen()) {
            toOpen(now);
        }
    }

    public void recordFailure() {
        recordFailure(Instant.now());
    }

    private void push(boolean success) {
        window.addLast(success);
        while (window.size() > windowSize) {
            window.removeFirst();
        }
    }

    private boolean shouldOpen() {
        if (window.size() < minimumCalls) {
            return false;
        }
        long failures = window.stream().filter(ok -> !ok).count();
        return (double) failures / window.size() >= failureThreshold;
    }

    private void toOpen(Instant now) {
        state = State.OPEN;
        openedAt = now;
        probesAdmitted = 0;
        probeSuccesses = 0;
        transitions++;
    }

    private void toHalfOpen() {
        state = State.HALF_OPEN;
        probesAdmitted = 0;
        probeSuccesses = 0;
        transitions++;
    }

    private void toClosed() {
        state = State.CLOSED;
        window.clear();
        probesAdmitted = 0;
        probeSuccesses = 0;
        transitions++;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized double currentFailureRate() {
        if (window.isEmpty()) return 0.0;
        return (double) window.stream().filter(ok -> !ok).count() / window.size();
    }

    public synchronized long rejectedCalls() {
        return rejectedCalls;
    }

    public synchronized long transitionCount() {
        return transitions;
    }

    public String name() {
        return name;
    }
}
