package com.anpr.backend.graph.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Minimal client for the public OSRM demo server.
 *
 * <p>Only ever constructed under the {@code graphgen} profile -- the running system has
 * no dependency on any routing service.
 */
public class OsrmClient {

    private static final Logger log = LoggerFactory.getLogger(OsrmClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public OsrmClient(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Empty when OSRM could not route the pair, or answered with something unusable. */
    public Optional<OsrmRoute> route(double fromLat, double fromLon, double toLat, double toLon) {
        String url = "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson"
                .formatted(baseUrl, fromLon, fromLat, toLon, toLat);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "anpr-backend-graphgen/0.1")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OSRM returned HTTP {} for {}", response.statusCode(), url);
                return Optional.empty();
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("OSRM call failed for {}: {}", url, e.toString());
            return Optional.empty();
        }
    }

    private Optional<OsrmRoute> parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (!"Ok".equals(root.path("code").asText())) {
            log.warn("OSRM code={} message={}", root.path("code").asText(), root.path("message").asText());
            return Optional.empty();
        }
        JsonNode route = root.path("routes").path(0);
        if (route.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode coordinates = route.path("geometry").path("coordinates");
        if (!coordinates.isArray() || coordinates.size() < 2) {
            return Optional.empty();
        }
        List<double[]> points = new ArrayList<>(coordinates.size());
        for (JsonNode c : coordinates) {
            points.add(new double[]{c.get(0).asDouble(), c.get(1).asDouble()});   // lon, lat
        }
        return Optional.of(new OsrmRoute(
                route.path("distance").asDouble(),
                route.path("duration").asDouble(),
                points,
                route.path("geometry").toString()));
    }
}
