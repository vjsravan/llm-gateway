package dev.jsv.gateway.cache;

/**
 * Turns text into a unit-length vector for similarity comparison.
 *
 * <p>Abstracted so the semantic cache can run against a real embedding endpoint in
 * production and a local deterministic one in tests and demos. The cache logic — which
 * is what actually needs testing — must not depend on a network call.
 */
public interface Embedder {

    float[] embed(String text);

    int dimensions();

    /**
     * Cosine similarity. Both vectors are expected to be L2-normalised, which reduces
     * this to a dot product.
     */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        // Clamp: floating-point drift can push a self-comparison a hair above 1.0.
        return Math.max(-1.0, Math.min(1.0, dot));
    }
}
