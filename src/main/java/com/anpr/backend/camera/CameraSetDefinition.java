package com.anpr.backend.camera;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Shape of {@code camera-sets/<name>.json}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CameraSetDefinition(
        @JsonProperty("city") String city,
        @JsonProperty("cameras") List<CameraDefinition> cameras
) {

    /**
     * {@code junction} and {@code watches} are documentation for whoever maintains the
     * file -- they record why a camera sits where it does. Nothing reads them.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CameraDefinition(
            @JsonProperty("camera_id") String cameraId,
            @JsonProperty("name") String name,
            @JsonProperty("lat") Double lat,
            @JsonProperty("lon") Double lon,
            @JsonProperty("heading_degrees") Integer headingDegrees,
            @JsonProperty("zone") String zone
    ) {

        /** @throws IllegalStateException naming the offending field, so a bad file fails loudly */
        void validate(int index, String source) {
            require(cameraId != null && !cameraId.isBlank(), index, source, "camera_id is required");
            require(name != null && !name.isBlank(), index, source, "name is required");
            require(lat != null && lat >= -90 && lat <= 90, index, source,
                    "lat must be present and between -90 and 90");
            require(lon != null && lon >= -180 && lon <= 180, index, source,
                    "lon must be present and between -180 and 180");
            require(headingDegrees != null && headingDegrees >= 0 && headingDegrees <= 359,
                    index, source, "heading_degrees must be present and between 0 and 359");
        }

        private static void require(boolean condition, int index, String source, String message) {
            if (!condition) {
                throw new IllegalStateException(
                        "camera set %s, entry %d: %s".formatted(source, index, message));
            }
        }
    }
}
