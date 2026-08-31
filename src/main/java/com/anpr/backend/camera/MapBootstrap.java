package com.anpr.backend.camera;

import java.util.List;

/**
 * Where the map should open. Derived from the active cameras rather than configured, so
 * changing {@code CAMERA_SET} moves the frontend's viewport with no frontend change and
 * no hardcoded geography anywhere in the client.
 */
public record MapBootstrap(List<Double> bbox, List<Double> center, int suggestedZoom) {

    /** Widest span we will ever suggest a zoom for, and the tightest. */
    private static final int MIN_ZOOM = 2;
    private static final int MAX_ZOOM = 18;

    /** A single camera has no span; treat it as a small neighbourhood rather than a point. */
    private static final double MIN_SPAN_DEGREES = 0.01;

    public static MapBootstrap over(List<Camera> cameras) {
        List<Camera> located = cameras.stream()
                .filter(c -> c.getLat() != null && c.getLon() != null)
                .toList();
        if (located.isEmpty()) {
            return new MapBootstrap(List.of(), List.of(), MIN_ZOOM);
        }

        double minLon = located.stream().mapToDouble(Camera::getLon).min().orElseThrow();
        double maxLon = located.stream().mapToDouble(Camera::getLon).max().orElseThrow();
        double minLat = located.stream().mapToDouble(Camera::getLat).min().orElseThrow();
        double maxLat = located.stream().mapToDouble(Camera::getLat).max().orElseThrow();

        double centerLon = (minLon + maxLon) / 2.0;
        double centerLat = (minLat + maxLat) / 2.0;

        // Longitude degrees shrink towards the poles; compare spans in the same units.
        double lonSpan = (maxLon - minLon) * Math.cos(Math.toRadians(centerLat));
        double span = Math.max(Math.max(maxLat - minLat, lonSpan), MIN_SPAN_DEGREES);

        // Web-mercator tiles halve the visible span per zoom level from 360 degrees at 0.
        int zoom = (int) Math.floor(Math.log(360.0 / span) / Math.log(2.0));

        return new MapBootstrap(
                List.of(minLon, minLat, maxLon, maxLat),
                List.of(centerLon, centerLat),
                Math.clamp(zoom, MIN_ZOOM, MAX_ZOOM));
    }
}
