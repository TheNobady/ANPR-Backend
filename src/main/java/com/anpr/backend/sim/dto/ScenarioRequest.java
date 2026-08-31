package com.anpr.backend.sim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** A scripted set of vehicles, run on top of whatever background traffic is flowing. */
public record ScenarioRequest(@JsonProperty("vehicles") List<ScenarioVehicle> vehicles) {

    /**
     * @param route             cameras to visit in order
     * @param forceNullPlateAt  cameras where the plate read is dropped deterministically,
     *                          so the "identity recovered without a plate" case can be
     *                          rehearsed instead of waited for
     */
    public record ScenarioVehicle(
            @JsonProperty("plate") String plate,
            @JsonProperty("type") String type,
            @JsonProperty("colour") String colour,
            @JsonProperty("route") List<String> route,
            @JsonProperty("startDelaySeconds") Integer startDelaySeconds,
            @JsonProperty("forceNullPlateAt") List<String> forceNullPlateAt,
            /**
             * Names this vehicle's appearance. Two vehicles with different seeds look
             * reliably different, which is what makes a scripted clone pair fire the
             * appearance-mismatch check every run rather than by luck.
             */
            @JsonProperty("embeddingSeed") String embeddingSeed
    ) {}
}
