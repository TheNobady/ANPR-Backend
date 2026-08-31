package com.anpr.backend.trajectory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.List;
import java.util.Map;

/**
 * A vehicle's route as GeoJSON.
 *
 * <p>Features alternate Point (sighting), LineString (hop), Point, LineString, ... in
 * sequence order, so the frontend can render straight through the list without sorting or
 * pairing anything up.
 *
 * <p>Geometry is spliced in as raw JSON exactly as PostGIS rendered it. Parsing it into
 * Java objects only to serialise it again would cost time and precision for nothing.
 */
public record TrajectoryGeoJson(
        @JsonProperty("type") String type,
        @JsonProperty("features") List<Feature> features
) {

    public static TrajectoryGeoJson of(List<Feature> features) {
        return new TrajectoryGeoJson("FeatureCollection", features);
    }

    public record Feature(
            @JsonProperty("type") String type,
            @JsonRawValue @JsonProperty("geometry") String geometry,
            @JsonProperty("properties") Map<String, Object> properties
    ) {
        public static Feature of(String geometry, Map<String, Object> properties) {
            return new Feature("Feature", geometry, properties);
        }
    }
}
