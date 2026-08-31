package com.anpr.backend.observation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface ObservationRepository extends JpaRepository<Observation, Long> {

    List<Observation> findAllByOrderByFirstSeenAtDescIdDesc(Pageable pageable);

    List<Observation> findByCameraIdOrderByFirstSeenAtDescIdDesc(String cameraId, Pageable pageable);

    @Query("select max(o.receivedAt) from Observation o")
    Instant findLastIngestAt();
}
