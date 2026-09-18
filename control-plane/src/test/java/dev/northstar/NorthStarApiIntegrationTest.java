package dev.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NorthStarApiIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("northstar")
        .withUsername("northstar")
        .withPassword("northstar");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;

    private String url(String path) { return "http://127.0.0.1:" + port + path; }

    @Test
    void hostTelemetryAndIdempotentCommandFlow() {
        UUID hostId = UUID.randomUUID();

        var registration = Map.of(
            "id", hostId.toString(),
            "hostname", "integration-host",
            "agentVersion", "test",
            "osName", "Linux",
            "architecture", "x86_64"
        );
        var registered = http.postForEntity(url("/api/v1/hosts"), registration, Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var telemetry = Map.of(
            "cpuPercent", 42.5,
            "memoryUsedBytes", 1024,
            "memoryTotalBytes", 4096,
            "load1m", 1.25,
            "uptimeSeconds", 120,
            "processCount", 88
        );
        var sample = http.postForEntity(url("/api/v1/hosts/" + hostId + "/telemetry"), telemetry, Map.class);
        assertThat(sample.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "integration-command-1");
        Map<String,Object> command = Map.of("hostId", hostId.toString(), "type", "PING", "payload", "{}");

        var first = http.exchange(url("/api/v1/commands"), HttpMethod.POST, new HttpEntity<>(command, headers), Map.class);
        var replay = http.exchange(url("/api/v1/commands"), HttpMethod.POST, new HttpEntity<>(command, headers), Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    }

    @Test
    void invalidTelemetryGetsStructured400() {
        UUID hostId = UUID.randomUUID();
        http.postForEntity(url("/api/v1/hosts"), Map.of("id",hostId.toString(),"hostname","validation-host"), Map.class);

        var invalid = Map.of(
            "cpuPercent", 150,
            "memoryUsedBytes", -1,
            "memoryTotalBytes", 4096,
            "load1m", 1,
            "uptimeSeconds", 1,
            "processCount", 1
        );
        var response = http.postForEntity(url("/api/v1/hosts/" + hostId + "/telemetry"), invalid, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }
}
