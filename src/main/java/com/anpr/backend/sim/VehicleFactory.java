package com.anpr.backend.sim;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Builds plausible vehicles. The weightings are the point: a Chandigarh camera sees
 * mostly white and silver cars on CH plates, and identity resolution that only works
 * on a uniform distribution is not identity resolution.
 */
@Component
public class VehicleFactory {

    public static final int EMBEDDING_DIMENSION = 512;

    /** Chandigarh first, then the states that actually commute into it. */
    private static final List<Weighted<String>> STATE_CODES = List.of(
            new Weighted<>("CH", 55), new Weighted<>("PB", 18), new Weighted<>("HR", 15),
            new Weighted<>("UP", 7), new Weighted<>("DL", 5));

    private static final List<Weighted<String>> TYPES = List.of(
            new Weighted<>("car", 70), new Weighted<>("motorcycle", 20),
            new Weighted<>("truck", 7), new Weighted<>("bus", 3));

    private static final List<Weighted<String>> COLOURS = List.of(
            new Weighted<>("white", 30), new Weighted<>("silver", 24), new Weighted<>("grey", 14),
            new Weighted<>("black", 13), new Weighted<>("blue", 8), new Weighted<>("red", 7),
            new Weighted<>("brown", 4));

    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";   // no I or O: they read as 1 and 0

    private final java.util.concurrent.atomic.AtomicLong vehicleIds =
            new java.util.concurrent.atomic.AtomicLong();

    /** Simulator-side identity, unique for the life of the process. */
    public String nextVehicleId() {
        return "sim_veh_%08d".formatted(vehicleIds.incrementAndGet());
    }

    public SimVehicle newVehicle(RandomGenerator random) {
        return new SimVehicle(
                nextVehicleId(),
                randomPlate(random),
                pick(TYPES, random),
                pick(COLOURS, random),
                unitEmbedding(random));
    }

    /** Two letters, two digits, two letters, four digits -- the real Indian format. */
    public String randomPlate(RandomGenerator random) {
        StringBuilder plate = new StringBuilder(10);
        plate.append(pick(STATE_CODES, random));
        plate.append(random.nextInt(10)).append(random.nextInt(10));
        plate.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        plate.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        for (int i = 0; i < 4; i++) {
            plate.append(random.nextInt(10));
        }
        return plate.toString();
    }

    /**
     * A reproducible unit embedding for a named seed.
     *
     * <p>Two scenario vehicles given different seeds are reliably dissimilar, and the same
     * seed always reproduces the same vehicle. Without this a scripted clone pair would get
     * two random vectors that happen to be dissimilar most of the time -- fine for a test,
     * useless for a rehearsal that has to work on stage.
     */
    public float[] seededEmbedding(String seed) {
        return unitEmbedding(new java.util.Random(seed.hashCode() * 0x9E3779B97F4A7C15L));
    }

    /** A random direction on the unit sphere, which is what a real appearance embedding looks like. */
    public float[] unitEmbedding(RandomGenerator random) {
        float[] embedding = new float[EMBEDDING_DIMENSION];
        double sumSquares = 0;
        for (int i = 0; i < embedding.length; i++) {
            double v = random.nextGaussian();
            embedding[i] = (float) v;
            sumSquares += v * v;
        }
        return normalise(embedding, sumSquares);
    }

    /** The same vehicle seen again: the base vector, jittered and renormalised. */
    public float[] noisyEmbedding(float[] base, double sigma, RandomGenerator random) {
        float[] out = new float[base.length];
        double sumSquares = 0;
        for (int i = 0; i < base.length; i++) {
            double v = base[i] + random.nextGaussian() * sigma;
            out[i] = (float) v;
            sumSquares += v * v;
        }
        return normalise(out, sumSquares);
    }

    private static float[] normalise(float[] values, double sumSquares) {
        double norm = Math.sqrt(sumSquares);
        if (norm == 0) {
            values[0] = 1f;
            return values;
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) (values[i] / norm);
        }
        return values;
    }

    private static <T> T pick(List<Weighted<T>> options, RandomGenerator random) {
        int total = options.stream().mapToInt(Weighted::weight).sum();
        int roll = random.nextInt(total);
        for (Weighted<T> option : options) {
            roll -= option.weight();
            if (roll < 0) {
                return option.value();
            }
        }
        return options.get(options.size() - 1).value();
    }

    private record Weighted<T>(T value, int weight) {
    }
}
