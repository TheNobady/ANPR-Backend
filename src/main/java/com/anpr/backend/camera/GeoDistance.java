package com.anpr.backend.camera;

/** Great-circle distance, computed in-process so the ingest fast path stays a single round trip. */
public final class GeoDistance {

    private static final double EARTH_RADIUS_METRES = 6_371_008.8;

    private GeoDistance() {
    }

    public static double metresBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
