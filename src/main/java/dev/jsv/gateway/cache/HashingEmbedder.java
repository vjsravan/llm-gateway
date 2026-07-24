package dev.jsv.gateway.cache;

import java.util.Locale;

/**
 * Deterministic local embedder using the hashing trick over word bigrams.
 *
 * <p>Not a semantic model — it captures lexical overlap, not meaning, so "cancel my
 * order" and "I'd like a refund" will not match. It exists so the cache is testable and
 * the demo runs offline with reproducible hit rates. Swap in a real embedding endpoint
 * behind the same interface for production; the cache logic is unchanged.
 *
 * <p>Bigrams rather than unigrams because unigram bags collapse word order entirely,
 * and "is the flight held" would collide with "held is the flight" at similarity 1.0.
 */
public class HashingEmbedder implements Embedder {

    private final int dimensions;

    public HashingEmbedder() {
        this(256);
    }

    public HashingEmbedder(int dimensions) {
        if (dimensions < 16) throw new IllegalArgumentException("dimensions too small: " + dimensions);
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] vec = new float[dimensions];
        String[] tokens = tokenize(text);

        for (String token : tokens) {
            accumulate(vec, token, 1.0f);
        }
        for (int i = 0; i + 1 < tokens.length; i++) {
            accumulate(vec, tokens[i] + "_" + tokens[i + 1], 1.5f);
        }

        normalise(vec);
        return vec;
    }

    private void accumulate(float[] vec, String feature, float weight) {
        int h = feature.hashCode();
        int idx = Math.floorMod(h, dimensions);
        // A second hash bit decides the sign, which keeps collisions from all pushing
        // the vector the same direction.
        float sign = ((h >>> 31) & 1) == 0 ? 1.0f : -1.0f;
        vec[idx] += sign * weight;
    }

    private static void normalise(float[] vec) {
        double sumSq = 0.0;
        for (float v : vec) sumSq += v * v;
        if (sumSq == 0.0) return;
        double norm = Math.sqrt(sumSq);
        for (int i = 0; i < vec.length; i++) {
            vec[i] = (float) (vec[i] / norm);
        }
    }

    private static String[] tokenize(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ");
        String[] raw = cleaned.trim().split("\\s+");
        return raw.length == 1 && raw[0].isEmpty() ? new String[0] : raw;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
