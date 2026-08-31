package com.anpr.backend.graph.gen;

import java.util.List;

/**
 * One OSRM route.
 *
 * @param distanceM     road distance in metres
 * @param durationS     free-flow driving time in seconds
 * @param coordinates   the LineString as {lon, lat} pairs
 * @param geometryJson  the raw GeoJSON geometry, passed straight to ST_GeomFromGeoJSON
 */
public record OsrmRoute(double distanceM, double durationS, List<double[]> coordinates, String geometryJson) {

    /** Compass bearing the vehicle leaves on, from the first two shape points. */
    public double initialBearing() {
        return Bearings.between(coordinates.get(0), coordinates.get(1));
    }

    /** Compass bearing the vehicle arrives on, from the last two shape points. */
    public double finalBearing() {
        int n = coordinates.size();
        return Bearings.between(coordinates.get(n - 2), coordinates.get(n - 1));
    }
}
