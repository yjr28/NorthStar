package dev.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentCommandIdempotencyIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("northstar").withUsername("northstar").withPassword("northstar");
    private static final String OPERATOR_TOKEN="concurrent-operator-token-000001";
    private static final String AGENT_TOKEN="concurrent-agent-token-000001";

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("northstar.security.operator-api-key", () -> OPERATOR_TOKEN);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    private String url(String path) { return "http://127.0.0.1:" + port + path; }
    private HttpHeaders operatorHeaders(){HttpHeaders h=new HttpHeaders();h.set("X-NorthStar-Operator-Key",OPERATOR_TOKEN);return h;}

    @Test void concurrentRetriesResolveToExactlyOneCommand() throws Exception {
        UUID hostId=UUID.randomUUID();
        var registration=Map.of("id",hostId.toString(),"hostname","concurrent-idempotency-host","agentToken",AGENT_TOKEN);
        var registered=http.exchange(url("/api/v1/hosts"),HttpMethod.POST,new HttpEntity<>(registration,operatorHeaders()),Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String key="concurrent-command-"+UUID.randomUUID();
        Map<String,Object> command=Map.of("hostId",hostId.toString(),"type","PING","payload","{}");
        int attempts=12;
        CountDownLatch ready=new CountDownLatch(attempts);
        CountDownLatch start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(attempts);
        List<Future<ResponseEntity<Map>>> futures=new ArrayList<>();
        try {
            for(int i=0;i<attempts;i++) futures.add(pool.submit(()->{
                HttpHeaders headers=operatorHeaders();headers.set("Idempotency-Key",key);
                ready.countDown();start.await();
                return http.exchange(url("/api/v1/commands"),HttpMethod.POST,new HttpEntity<>(command,headers),Map.class);
            }));
            ready.await();start.countDown();
            Set<Object> ids=new HashSet<>();int created=0;
            for(var future:futures){var response=future.get();assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED,HttpStatus.OK);if(response.getStatusCode()==HttpStatus.CREATED)created++;ids.add(response.getBody().get("id"));}
            assertThat(created).isEqualTo(1);
            assertThat(ids).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
}
