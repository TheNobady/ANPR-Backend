package com.anpr.backend.common;

import com.anpr.backend.camera.CameraService;
import com.anpr.backend.ingest.IngestRejectStore;
import com.anpr.backend.observation.ObservationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class SystemHealthController {

    private final CameraService cameraService;
    private final ObservationRepository observationRepository;
    private final IngestRejectStore rejectStore;
    private final FailureCounters failures;

    public SystemHealthController(CameraService cameraService,
                                  ObservationRepository observationRepository,
                                  IngestRejectStore rejectStore,
                                  FailureCounters failures) {
        this.failures = failures;
        this.cameraService = cameraService;
        this.observationRepository = observationRepository;
        this.rejectStore = rejectStore;
    }

    @GetMapping("/system")
    public SystemHealthResponse system() {
        return new SystemHealthResponse(
                cameraService.count(),
                cameraService.countActive(),
                observationRepository.count(),
                observationRepository.findLastIngestAt(),
                rejectStore.count(),
                failures.snapshot());
    }
}
