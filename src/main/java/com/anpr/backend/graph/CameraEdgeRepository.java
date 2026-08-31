package com.anpr.backend.graph;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CameraEdgeRepository extends JpaRepository<CameraEdge, Long> {

    /**
     * Every edge of the active deployment, with its path already rendered as GeoJSON by
     * PostGIS. Ordered so the FeatureCollection is stable between calls.
     *
     * <p>Restricted to active cameras: after a CAMERA_SET switch the previous city's edges
     * are still on disk for its history, but they are not part of this deployment's graph
     * and putting them on the map would be a lie.
     */
    @Query(value = """
            SELECT e.from_camera_id,
                   e.to_camera_id,
                   e.road_distance_m,
                   e.t_typical_s,
                   e.t_min_s,
                   e.t_max_s,
                   e.intermediate_cameras,
                   ST_AsGeoJSON(e.path_geom) AS path_geojson
              FROM camera_edges e
              JOIN cameras cf ON cf.camera_id = e.from_camera_id AND cf.is_active
              JOIN cameras ct ON ct.camera_id = e.to_camera_id   AND ct.is_active
             ORDER BY e.from_camera_id, e.to_camera_id
            """, nativeQuery = true)
    List<CameraEdgeGeoRow> findAllWithGeoJson();

    /** Projection over the native query above. */
    interface CameraEdgeGeoRow {
        String getFrom_camera_id();
        String getTo_camera_id();
        double getRoad_distance_m();
        int getT_typical_s();
        int getT_min_s();
        int getT_max_s();
        String[] getIntermediate_cameras();
        String getPath_geojson();
    }
}
