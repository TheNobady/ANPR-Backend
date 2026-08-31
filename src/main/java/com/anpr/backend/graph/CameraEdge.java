package com.anpr.backend.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A directed, drivable link between two cameras.
 *
 * <p>{@code path_geom} is deliberately unmapped: Hibernate has no PostGIS geometry
 * type here, and the only consumer -- {@code GET /api/graph} -- wants it as GeoJSON,
 * which Postgres produces with {@code ST_AsGeoJSON}. {@link CameraEdgeRepository}
 * reads that projection directly.
 */
@Entity
@Table(name = "camera_edges")
@Getter
@Setter
public class CameraEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "from_camera_id", nullable = false)
    private String fromCameraId;

    @Column(name = "to_camera_id", nullable = false)
    private String toCameraId;

    @Column(name = "road_distance_m", nullable = false)
    private double roadDistanceM;

    @Column(name = "t_typical_s", nullable = false)
    private int tTypicalS;

    @Column(name = "t_min_s", nullable = false)
    private int tMinS;

    @Column(name = "t_max_s", nullable = false)
    private int tMaxS;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "intermediate_cameras", nullable = false)
    private String[] intermediateCameras;

    @Column(name = "generated_at", insertable = false, updatable = false)
    private Instant generatedAt;
}
