package com.anpr.backend.camera;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A fixed (or auto-registered) camera. The {@code geom} column is a generated
 * PostGIS point derived from lat/lon and is therefore not mapped here.
 */
@Entity
@Table(name = "cameras")
@Getter
@Setter
public class Camera {

    @Id
    @Column(name = "camera_id", nullable = false, updatable = false)
    private String cameraId;

    @Column(name = "name")
    private String name;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lon")
    private Double lon;

    @Column(name = "heading_degrees")
    private Integer headingDegrees;

    @Column(name = "zone")
    private String zone;

    @Column(name = "is_provisional", nullable = false)
    private boolean provisional;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
