package com.anpr.backend.camera;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cameras")
public class CameraController {

    private final CameraService cameraService;
    private final CameraSetLoader cameraSetLoader;

    public CameraController(CameraService cameraService, CameraSetLoader cameraSetLoader) {
        this.cameraService = cameraService;
        this.cameraSetLoader = cameraSetLoader;
    }

    /**
     * The active deployment's cameras as a GeoJSON FeatureCollection, plus the bbox,
     * centre and zoom the frontend needs to open its map. Cameras from a previous
     * CAMERA_SET are excluded: they are history, not part of this deployment.
     */
    @GetMapping
    public GeoJsonFeatureCollection cameras() {
        return GeoJsonFeatureCollection.of(
                cameraService.findActive(), cameraSetLoader.activeSetName());
    }
}
