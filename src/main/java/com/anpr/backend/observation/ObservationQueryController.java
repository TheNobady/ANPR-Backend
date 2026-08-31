package com.anpr.backend.observation;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Debug read access to what has actually landed in the observations table. */
@RestController
@RequestMapping("/api/events")
public class ObservationQueryController {

    private static final int MAX_LIMIT = 500;

    private final ObservationRepository observationRepository;

    public ObservationQueryController(ObservationRepository observationRepository) {
        this.observationRepository = observationRepository;
    }

    @GetMapping("/recent")
    public List<ObservationDto> recent(@RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(name = "camera_id", required = false) String cameraId) {
        Pageable page = PageRequest.ofSize(Math.clamp(limit, 1, MAX_LIMIT));
        List<Observation> observations = (cameraId == null || cameraId.isBlank())
                ? observationRepository.findAllByOrderByFirstSeenAtDescIdDesc(page)
                : observationRepository.findByCameraIdOrderByFirstSeenAtDescIdDesc(cameraId, page);
        return observations.stream().map(ObservationDto::from).toList();
    }
}
