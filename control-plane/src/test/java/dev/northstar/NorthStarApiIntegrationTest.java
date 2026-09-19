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
        .withDatabaseName("northstar").withUsername("northstar").withPassword("northstar");
    private static final String OPERATOR_TOKEN="integration-operator-token-000001";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("northstar.security.operator-api-key", () -> OPERATOR_TOKEN);
    }
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    private String url(String path) { return "http://127.0.0.1:" + port + path; }
    private static final String TOKEN="integration-agent-token-000001";
    private HttpHeaders agentHeaders(String token){HttpHeaders h=new HttpHeaders();h.set("X-NorthStar-Agent-Token",token);return h;}
    private HttpHeaders operatorHeaders(){HttpHeaders h=new HttpHeaders();h.set("X-NorthStar-Operator-Key",OPERATOR_TOKEN);return h;}
    private void register(UUID id,String name){
        http.exchange(url("/api/v1/hosts"),HttpMethod.POST,new HttpEntity<>(Map.of("id",id.toString(),"hostname",name,"agentToken",TOKEN),operatorHeaders()),Map.class);
    }

    @Test void hostTelemetryAndIdempotentCommandFlow() {
        UUID hostId = UUID.randomUUID(); register(hostId,"integration-host");
        var telemetry=Map.of("cpuPercent",42.5,"memoryUsedBytes",1024,"memoryTotalBytes",4096,"load1m",1.25,"uptimeSeconds",120,"processCount",88);
        var sample=http.exchange(url("/api/v1/hosts/"+hostId+"/telemetry"),HttpMethod.POST,new HttpEntity<>(telemetry,agentHeaders(TOKEN)),Map.class);
        assertThat(sample.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        HttpHeaders headers=operatorHeaders();headers.set("Idempotency-Key","integration-command-1");
        Map<String,Object> command=Map.of("hostId",hostId.toString(),"type","PING","payload","{}");
        var first=http.exchange(url("/api/v1/commands"),HttpMethod.POST,new HttpEntity<>(command,headers),Map.class);
        var replay=http.exchange(url("/api/v1/commands"),HttpMethod.POST,new HttpEntity<>(command,headers),Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(replay.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    }

    @Test void operatorRoutesRequireConfiguredCredential(){
        UUID hostId=UUID.randomUUID();
        var body=Map.of("id",hostId.toString(),"hostname","operator-boundary-host","agentToken",TOKEN);
        var missing=http.postForEntity(url("/api/v1/hosts"),body,Map.class);
        HttpHeaders invalidHeaders=new HttpHeaders();invalidHeaders.set("X-NorthStar-Operator-Key","wrong-operator-token");
        var invalid=http.exchange(url("/api/v1/hosts"),HttpMethod.POST,new HttpEntity<>(body,invalidHeaders),Map.class);
        var valid=http.exchange(url("/api/v1/hosts"),HttpMethod.POST,new HttpEntity<>(body,operatorHeaders()),Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(valid.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test void agentWritesRequireCredentialAndRotationInvalidatesOldToken(){
        UUID hostId=UUID.randomUUID();register(hostId,"secure-host");
        var telemetry=Map.of("cpuPercent",1,"memoryUsedBytes",1,"memoryTotalBytes",2,"load1m",0,"uptimeSeconds",1,"processCount",1);
        var missing=http.postForEntity(url("/api/v1/hosts/"+hostId+"/telemetry"),telemetry,Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String replacement="replacement-agent-token-000002";
        var rotated=http.exchange(url("/api/v1/hosts/"+hostId+"/credentials/rotate"),HttpMethod.POST,new HttpEntity<>(Map.of("newToken",replacement),agentHeaders(TOKEN)),Void.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var old=http.exchange(url("/api/v1/hosts/"+hostId+"/heartbeat"),HttpMethod.POST,new HttpEntity<>(null,agentHeaders(TOKEN)),Void.class);
        var fresh=http.exchange(url("/api/v1/hosts/"+hostId+"/heartbeat"),HttpMethod.POST,new HttpEntity<>(null,agentHeaders(replacement)),Void.class);
        assertThat(old.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(fresh.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test void invalidTelemetryGetsStructured400() {
        UUID hostId=UUID.randomUUID();register(hostId,"validation-host");
        var invalid=Map.of("cpuPercent",150,"memoryUsedBytes",-1,"memoryTotalBytes",4096,"load1m",1,"uptimeSeconds",1,"processCount",1);
        var response=http.exchange(url("/api/v1/hosts/"+hostId+"/telemetry"),HttpMethod.POST,new HttpEntity<>(invalid,agentHeaders(TOKEN)),Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }
}
