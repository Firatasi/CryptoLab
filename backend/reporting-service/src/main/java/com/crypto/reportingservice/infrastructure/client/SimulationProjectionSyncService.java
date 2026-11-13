package com.crypto.reportingservice.infrastructure.client;

import com.crypto.reportingservice.infrastructure.client.ReportingSnapshotWriter.SnapshotPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Service
public class SimulationProjectionSyncService {

    private static final Logger log = LoggerFactory.getLogger(SimulationProjectionSyncService.class);

    private final ObjectProvider<LoadBalancerClient> loadBalancerProvider;
    private final ReportingSnapshotWriter writer;
    private final ObjectMapper json;
    private final String simulationServiceId;
    private final Duration timeout;
    private final HttpClient http;

    public SimulationProjectionSyncService(
            ObjectProvider<LoadBalancerClient> loadBalancerProvider,
            ReportingSnapshotWriter writer,
            ObjectMapper json,
            @Value("${reporting.simulation-service-id:simulation-service}") String simulationServiceId,
            @Value("${reporting.sync-connect-timeout-ms:1500}") int connectMs,
            @Value("${reporting.sync-request-timeout-ms:3000}") int requestMs
    ) {
        this.loadBalancerProvider = loadBalancerProvider;
        this.writer = writer;
        this.json = json;
        this.simulationServiceId = simulationServiceId;
        this.timeout = Duration.ofMillis(Math.max(500, requestMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(300, connectMs)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void syncIfAvailable(String bearerToken, UUID subject, UUID sessionId) {
        if (bearerToken == null || bearerToken.isBlank() || subject == null) {
            return;
        }
        LoadBalancerClient lb = loadBalancerProvider.getIfAvailable();
        if (lb == null) {
            return;
        }
        var instance = lb.choose(simulationServiceId);
        if (instance == null) {
            return;
        }

        var uriBuilder = UriComponentsBuilder.fromUriString("http://" + simulationServiceId)
                .replacePath("/api/v1/sim/reporting/snapshot");
        if (sessionId != null) {
            uriBuilder.queryParam("sessionId", sessionId);
        }

        try {
            var request = HttpRequest.newBuilder(lb.reconstructURI(instance, uriBuilder.build().encode().toUri()))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                SnapshotPayload payload = json.readValue(response.body(), SnapshotPayload.class);
                writer.persistSnapshot(subject, payload);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Simulation reporting sync failed for owner={} session={}: {}", subject, sessionId, e.getMessage());
        }
    }
}
