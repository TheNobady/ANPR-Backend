package com.anpr.backend.alert;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RestrictedZone(
        @JsonProperty("id") long id,
        @JsonProperty("zone") String zone,
        @JsonProperty("vehicle_types") List<String> vehicleTypes,
        @JsonProperty("active_from_hour") Integer activeFromHour,
        @JsonProperty("active_to_hour") Integer activeToHour,
        @JsonProperty("reason") String reason,
        @JsonProperty("is_active") boolean active
) {

    /** Null or empty means every type is restricted. */
    public boolean coversType(String vehicleType) {
        return vehicleTypes == null || vehicleTypes.isEmpty()
                || (vehicleType != null && vehicleTypes.contains(vehicleType));
    }

    /**
     * Null hours mean the restriction is always in force. A window that wraps midnight
     * (22 to 6) is treated as the union of the two spans rather than an empty set.
     */
    public boolean coversHour(int hour) {
        if (activeFromHour == null || activeToHour == null) {
            return true;
        }
        return activeFromHour <= activeToHour
                ? hour >= activeFromHour && hour <= activeToHour
                : hour >= activeFromHour || hour <= activeToHour;
    }
}
