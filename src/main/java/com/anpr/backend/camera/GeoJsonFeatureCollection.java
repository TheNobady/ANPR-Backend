package com.anpr.backend.camera;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * RFC 7946 FeatureCollection, ready to drop onto a map.
 *
 * <p>Carries the map bootstrap alongside the features: {@code bbox}, {@code center} and
 * {@code suggested_zoom} are computed from the active cameras, so the frontend centres
 * itself on whatever city {@code CAMERA_SET} names without knowing any geography.
 */
public record GeoJsonFeatureCollection(
        @JsonProperty("type") String type,
        @JsonProperty("camera_set") String cameraSet,
        @JsonProperty("bbox") List<Double> bbox,
        @JsonProperty("center") List<Double> center,
        @JsonProperty("suggested_zoom") int suggestedZoom,
        @JsonProperty("features") List<Feature> features
) {

    public static GeoJsonFeatureCollection of(List<Camera> cameras, String cameraSet) {
        MapBootstrap bootstrap = MapBootstrap.over(cameras);
        return new GeoJsonFeatureCollection(
                "FeatureCollection",
                cameraSet,
                bootstrap.bbox(),
                bootstrap.center(),
                bootstrap.suggestedZoom(),
                cameras.stream().map(Feature::from).toList());
    }

    public record Feature(
            @JsonProperty("type") String type,
            @JsonProperty("geometry") Point geometry,
            @JsonProperty("properties") Map<String, Object> properties
    ) {

        static Feature from(Camera camera) {
            Point geometry = (camera.getLon() == null || camera.getLat() == null)
                    ? null
                    : new Point("Point", List.of(camera.getLon(), camera.getLat()));

            Map<String, Object> properties = new java.util.LinkedHashMap<>();
            properties.put("camera_id", camera.getCameraId());
            properties.put("name", camera.getName());
            properties.put("heading_degrees", camera.getHeadingDegrees());
            properties.put("zone", camera.getZone());
            properties.put("is_provisional", camera.isProvisional());
            properties.put("is_active", camera.isActive());
            properties.put("last_event_at", camera.getLastEventAt());
            return new Feature("Feature", geometry, properties);
        }
    }

    /** GeoJSON coordinates are [longitude, latitude] -- in that order. */
    public record Point(
            @JsonProperty("type") String type,
            @JsonProperty("coordinates") List<Double> coordinates
    ) {}
}
