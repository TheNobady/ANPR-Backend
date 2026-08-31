package com.anpr.backend.graph;

import com.anpr.backend.camera.CameraService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final CameraEdgeRepository edgeRepository;
    private final CameraService cameraService;

    public GraphController(CameraEdgeRepository edgeRepository, CameraService cameraService) {
        this.edgeRepository = edgeRepository;
        this.cameraService = cameraService;
    }

    /** The whole camera graph, ready to paste into geojson.io. */
    @GetMapping
    public GraphGeoJson graph() {
        return GraphGeoJson.of(edgeRepository.findAllWithGeoJson(), cameraService.countActive());
    }
}
