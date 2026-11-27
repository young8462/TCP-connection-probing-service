package com.yueyou.tcpprobe.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yueyou.tcpprobe.TcpProbeApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = {TcpProbeApplication.class, ProbeApiEndToEndTest.ClockConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProbeApiEndToEndTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AtomicLong probeClock;

    @BeforeEach
    void refillBucket() {
        probeClock.addAndGet(TimeUnit.SECONDS.toNanos(2));
    }

    @Test
    void probesOneTargetOverHttp() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = listener.getLocalPort();

            JsonNode results = results(postProbe(port));
            assertEquals(1, results.size());
            assertResult(results.get(0), port, "reachable");
        }
    }

    @Test
    void returnsMixedBatchInInputOrder() throws Exception {
        try (ServerSocket listener = new ServerSocket(); Socket unavailable = new Socket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            unavailable.bind(new InetSocketAddress("127.0.0.1", 0));
            int openPort = listener.getLocalPort();
            int unavailablePort = unavailable.getLocalPort();

            JsonNode results = results(postProbe(openPort, unavailablePort, openPort));
            assertEquals(3, results.size());
            assertResult(results.get(0), openPort, "reachable");
            String failure = results.get(1).path("status").asText();
            assertTrue(Arrays.asList("connection_refused", "timeout").contains(failure),
                    "Unexpected unavailable status: " + failure);
            assertResult(results.get(1), unavailablePort, failure);
            assertResult(results.get(2), openPort, "reachable");
        }
    }

    @Test
    void rejectsBurstOverflowAndAcceptsRequestsAfterRefill() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = listener.getLocalPort();

            for (int request = 0; request < 10; request++) {
                assertResult(results(postProbe(port)).get(0), port, "reachable");
            }

            ResponseEntity<JsonNode> rejected = postProbe(port);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
            assertEquals("1", rejected.getHeaders().getFirst("Retry-After"));
            assertEquals("rate_limit_exceeded", body(rejected).path("error").path("code").asText());

            ResponseEntity<JsonNode> health = http.getForEntity("/healthz", JsonNode.class);
            assertEquals(HttpStatus.OK, health.getStatusCode());
            assertEquals("up", body(health).path("status").asText());

            probeClock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
            assertResult(results(postProbe(port)).get(0), port, "reachable");
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, postProbe(port).getStatusCode());
        }
    }

    private ResponseEntity<JsonNode> postProbe(int... ports) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode targets = request.putArray("targets");
        for (int port : ports) {
            targets.addObject().put("host", "127.0.0.1").put("port", port);
        }
        return http.postForEntity("/probe", request, JsonNode.class);
    }

    private JsonNode results(ResponseEntity<JsonNode> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode results = body(response).path("results");
        assertTrue(results.isArray());
        return results;
    }

    private JsonNode body(ResponseEntity<JsonNode> response) {
        JsonNode body = response.getBody();
        assertNotNull(body);
        return body;
    }

    private void assertResult(JsonNode result, int port, String status) {
        assertEquals("127.0.0.1", result.path("host").asText());
        assertEquals(port, result.path("port").asInt());
        assertEquals(status, result.path("status").asText());
        assertTrue(result.path("duration_ms").isIntegralNumber());
        assertTrue(result.path("duration_ms").asLong() >= 0);
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        AtomicLong probeClock() {
            return new AtomicLong();
        }

        @Bean
        @Primary
        ProbeRateLimiter testProbeRateLimiter(AtomicLong probeClock) {
            return new ProbeRateLimiter(probeClock::get);
        }
    }
}
