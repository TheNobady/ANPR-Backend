package com.anpr.backend.graph.gen;

public final class Bearings {

    private Bearings() {
    }

    /** Initial great-circle bearing, in degrees clockwise from north. Points are {lon, lat}. */
    public static double between(double[] from, double[] to) {
        double lat1 = Math.toRadians(from[1]);
        double lat2 = Math.toRadians(to[1]);
        double dLon = Math.toRadians(to[0] - from[0]);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    /** Smallest angle between two compass bearings, 0..180. */
    public static double difference(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }
}
