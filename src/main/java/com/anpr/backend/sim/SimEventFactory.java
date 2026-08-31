package com.anpr.backend.sim;

import com.anpr.backend.camera.Camera;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/** Assembles one perception-node event for a vehicle passing a camera. */
@Component
public class SimEventFactory {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final String HEX = "0123456789abcdef";

    /** Frame width/height the nodes report bounding boxes in. */
    private static final int FRAME_W = 1920;
    private static final int FRAME_H = 1080;

    /** Globally unique, so two vehicles never collide on (camera_id, tracklet_id). */
    private final AtomicLong trackletCounter = new AtomicLong();

    private final VehicleFactory vehicleFactory;
    private final PlateDegrader plateDegrader;

    public SimEventFactory(VehicleFactory vehicleFactory, PlateDegrader plateDegrader) {
        this.vehicleFactory = vehicleFactory;
        this.plateDegrader = plateDegrader;
    }

    /**
     * @param firstSeen when the vehicle entered the camera's view -- wall-clock now in
     *                  live mode, a past instant in backfill
     * @param embeddingNoiseSigma per-component gaussian noise added to the vehicle's base
     *                            embedding before renormalising. Across 512 dimensions the
     *                            noise norm is sigma*sqrt(512), so same-vehicle cosine falls
     *                            as roughly 1/(1 + 512*sigma^2) -- a small change here moves
     *                            appearance similarity a long way.
     * @param forceNullPlate scenario override: drop the plate here regardless of the rate
     */
    public SimEventPayload build(SimVehicle vehicle,
                                 Camera camera,
                                 Instant firstSeen,
                                 double ocrErrorRate,
                                 double nullPlateRate,
                                 double embeddingNoiseSigma,
                                 boolean forceNullPlate,
                                 RandomGenerator random) {

        Instant lastSeen = firstSeen.plusMillis(2000 + random.nextInt(3001));   // 2-5 s dwell

        boolean nullPlate = forceNullPlate || random.nextDouble() < nullPlateRate;
        SimEventPayload.Plate plate = nullPlate ? null : buildPlate(vehicle, ocrErrorRate, random);

        return new SimEventPayload(
                "sim_" + randomHex(8, random),
                camera.getCameraId(),
                new SimEventPayload.CameraLocation(camera.getLat(), camera.getLon(), camera.getName()),
                ISO.format(firstSeen),
                ISO.format(lastSeen),
                "%s_trk_%08d".formatted(camera.getCameraId(), trackletCounter.incrementAndGet()),
                plate,
                buildVehicle(vehicle, embeddingNoiseSigma, random),
                round(0.90 + random.nextDouble() * 0.09),
                "replay",
                null);
    }

    private SimEventPayload.Plate buildPlate(SimVehicle vehicle, double ocrErrorRate, RandomGenerator random) {
        boolean corrupt = random.nextDouble() < ocrErrorRate;
        PlateDegrader.Read read = corrupt
                ? plateDegrader.corrupt(vehicle.plate(), random)
                : new PlateDegrader.Read(vehicle.plate(), -1);

        double confidence = read.corrupted()
                ? round(0.45 + random.nextDouble() * 0.20)
                : round(0.85 + random.nextDouble() * 0.14);

        List<Double> charConfidences = new ArrayList<>(read.text().length());
        for (int i = 0; i < read.text().length(); i++) {
            charConfidences.add(i == read.corruptedIndex()
                    ? round(0.46 + random.nextDouble() * 0.08)          // the character it got wrong
                    : round(0.86 + random.nextDouble() * 0.13));
        }

        return new SimEventPayload.Plate(
                read.text(),
                confidence,
                charConfidences,
                plateDegrader.rawReads(read.text(), vehicle.plate(), random));
    }

    private SimEventPayload.Vehicle buildVehicle(SimVehicle vehicle, double embeddingNoiseSigma,
                                                 RandomGenerator random) {
        float[] embedding = vehicleFactory.noisyEmbedding(
                vehicle.baseEmbedding(), embeddingNoiseSigma, random);
        return new SimEventPayload.Vehicle(
                vehicle.type(),
                round(0.80 + random.nextDouble() * 0.15),
                vehicle.colour(),
                round(0.80 + random.nextDouble() * 0.15),
                vehicle.boxed(embedding),
                bbox(vehicle.type(), random));
    }

    /** A box roughly the size the vehicle would occupy, somewhere sensible in frame. */
    private List<Integer> bbox(String type, RandomGenerator random) {
        int width = switch (type) {
            case "motorcycle" -> 70 + random.nextInt(60);
            case "truck", "bus" -> 320 + random.nextInt(240);
            default -> 160 + random.nextInt(160);
        };
        int height = switch (type) {
            case "motorcycle" -> 90 + random.nextInt(70);
            case "truck", "bus" -> 260 + random.nextInt(200);
            default -> 120 + random.nextInt(120);
        };
        int x1 = random.nextInt(FRAME_W - width);
        int y1 = random.nextInt(FRAME_H - height);
        return List.of(x1, y1, x1 + width, y1 + height);
    }

    private static String randomHex(int length, RandomGenerator random) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(HEX.charAt(random.nextInt(HEX.length())));
        }
        return out.toString();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
