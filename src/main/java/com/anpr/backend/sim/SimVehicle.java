package com.anpr.backend.sim;

import java.util.List;

/**
 * A simulated vehicle's fixed identity. Everything here is decided at spawn and stays
 * constant for the whole journey -- that constancy is what later phases have to
 * rediscover from degraded observations.
 *
 * @param simVehicleId  the simulator's own id for this vehicle -- the ground truth the
 *                      resolver is scored against, never sent in the payload
 * @param plate         the true plate, e.g. {@code CH01AB1234}
 * @param type          car, motorcycle, truck or bus
 * @param colour        paint colour
 * @param baseEmbedding 512 floats, unit length
 */
public record SimVehicle(String simVehicleId, String plate, String type, String colour,
                         float[] baseEmbedding) {

    public int embeddingDimension() {
        return baseEmbedding.length;
    }

    /** Convenience for the payload builder, which needs boxed floats for Jackson. */
    public List<Float> boxed(float[] values) {
        List<Float> out = new java.util.ArrayList<>(values.length);
        for (float v : values) {
            out.add(v);
        }
        return out;
    }
}
