package com.anpr.backend.graph;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.List;
import java.util.Map;

/**
 * The camera graph as a GeoJSON FeatureCollection, with the edge and camera counts
 * carried alongside so a client can sanity-check a load without walking the features.
 */
public record GraphGeoJson(
        @JsonProperty("type") String type,
        @JsonProperty("edge_count") int edgeCount,
        @JsonProperty("camera_count") long cameraCount,
        @JsonProperty("features") List<Feature> features
) {

    public static GraphGeoJson of(List<CameraEdgeRepository.CameraEdgeGeoRow> rows, long cameraCount) {
        List<Feature> features = rows.stream().map(Feature::from).toList();
        return new GraphGeoJson("FeatureCollection", features.size(), cameraCount, features);
    }

    public record Feature(
            @JsonProperty("type") String type,
            /** PostGIS already rendered this; splicing it in avoids a parse-and-rebuild. */
            @JsonRawValue @JsonProperty("geometry") String geometry,
            @JsonProperty("properties") Map<String, Object> properties
    ) {

        static Feature from(CameraEdgeRepository.CameraEdgeGeoRow row) {
            Map<String, Object> properties = new java.util.LinkedHashMap<>();
            properties.put("from_camera_id", row.getFrom_camera_id());
            properties.put("to_camera_id", row.getTo_camera_id());
            properties.put("road_distance_m", row.getRoad_distance_m());
            properties.put("t_typical_s", row.getT_typical_s());
            properties.put("t_min_s", row.getT_min_s());
            properties.put("t_max_s", row.getT_max_s());
            properties.put("intermediate_cameras",
                    row.getIntermediate_cameras() == null
                            ? List.of()
                            : List.of(row.getIntermediate_cameras()));
            return new Feature("Feature", row.getPath_geojson(), properties);
        }
    }
}
