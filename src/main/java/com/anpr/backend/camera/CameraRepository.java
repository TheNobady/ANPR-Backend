package com.anpr.backend.camera;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CameraRepository extends JpaRepository<Camera, String> {

    List<Camera> findAllByOrderByCameraIdAsc();

    List<Camera> findByActiveTrueOrderByCameraIdAsc();

    @Query("select count(c) from Camera c where c.active = true")
    long countActive();
}
