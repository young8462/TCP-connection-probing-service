package com.yueyou.tcpprobe.controller;

import com.yueyou.tcpprobe.probe.BatchProbeService;
import com.yueyou.tcpprobe.probe.TcpProbe;
import com.yueyou.tcpprobe.ratelimit.ProbeRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.RejectedExecutionException;

import static org.hamcrest.Matchers.isIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProbeController.class)
@Import({TcpProbe.class, BatchProbeService.class})
class ProbeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProbeRateLimiter limiter;

    @BeforeEach
    void allowProbeRequests() {
        when(limiter.tryAcquire()).thenReturn(true);
    }

    @Test
    void reportsHealth() throws Exception {
        mvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"));
        verifyNoInteractions(limiter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/probe", "/probe/"})
    void rateLimitsBeforeParsingTheRequestBody(String path) throws Exception {
        when(limiter.tryAcquire()).thenReturn(false);
        mvc.perform(post(path).contentType(APPLICATION_JSON).content("{"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
    }

    @Test
    void reportsMixedBatchInInputOrderAndPreservesDuplicates() throws Exception {
        try (ServerSocket listener = new ServerSocket(); Socket unavailable = new Socket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            unavailable.bind(new InetSocketAddress("127.0.0.1", 0));
            String reachable = localTarget(listener.getLocalPort());
            submit("{\"targets\":[" + reachable + "," + localTarget(unavailable.getLocalPort())
                    + "," + reachable + "]}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results.length()").value(3))
                    .andExpect(jsonPath("$.results[0].port").value(listener.getLocalPort()))
                    .andExpect(jsonPath("$.results[0].status").value("reachable"))
                    .andExpect(jsonPath("$.results[1].port").value(unavailable.getLocalPort()))
                    .andExpect(jsonPath("$.results[1].status").value(isIn(Arrays.asList("connection_refused", "timeout"))))
                    .andExpect(jsonPath("$.results[2].port").value(listener.getLocalPort()))
                    .andExpect(jsonPath("$.results[2].status").value("reachable"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{} {}"})
    void rejectsMalformedJson(String body) throws Exception {
        submit(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"targets\":[]}", "{\"targets\":{}}"})
    void requiresNonEmptyTargetArray(String body) throws Exception {
        submit(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("targets"));
    }

    @Test
    void rejectsOversizedBatch() throws Exception {
        String target = "{\"host\":\"localhost\",\"port\":80}";
        submit("{\"targets\":[" + String.join(",", Collections.nCopies(21, target)) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("targets"));
    }

    @Test
    void rejectsNullTarget() throws Exception {
        submit("{\"targets\":[null]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("targets[0]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "0", "65536", "4294967376", "80.5", "\"80\""})
    void rejectsInvalidPort(String port) throws Exception {
        submit("{\"targets\":[{\"host\":\"localhost\",\"port\":" + port + "}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("targets[0].port"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"bad host\"", "\"https://localhost\"", "123"})
    void rejectsInvalidHost(String host) throws Exception {
        submit("{\"targets\":[{\"host\":" + host + ",\"port\":80}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("targets[0].host"));
    }

    @Test
    void reportsReachableTargetAndClosesSocket() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = listener.getLocalPort();
            submitLocalTarget(port)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results[0].host").value("127.0.0.1"))
                    .andExpect(jsonPath("$.results[0].port").value(port))
                    .andExpect(jsonPath("$.results[0].status").value("reachable"))
                    .andExpect(jsonPath("$.results[0].duration_ms").isNumber());
            listener.setSoTimeout(1000);
            try (Socket connection = listener.accept()) {
                connection.setSoTimeout(1000);
                assertEquals(-1, connection.getInputStream().read());
            }
        }
    }

    @Test
    void reportsUnavailableTargetInResult() throws Exception {
        try (Socket boundWithoutListener = new Socket()) {
            boundWithoutListener.bind(new InetSocketAddress("127.0.0.1", 0));
            submitLocalTarget(boundWithoutListener.getLocalPort())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results[0].status").value(isIn(Arrays.asList("connection_refused", "timeout"))));
        }
    }

    @Test
    void reportsCapacityExhaustionAsServiceUnavailable() throws Exception {
        BatchProbeService overloaded = mock(BatchProbeService.class);
        when(overloaded.probe(anyList())).thenThrow(new RejectedExecutionException());
        standaloneSetup(new ProbeController(overloaded)).build()
                .perform(post("/probe").contentType(APPLICATION_JSON)
                        .content("{\"targets\":[" + localTarget(80) + "]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("probe_capacity_unavailable"));
    }

    private ResultActions submitLocalTarget(int port) throws Exception {
        return submit("{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + port + "}]}");
    }

    private String localTarget(int port) {
        return "{\"host\":\"127.0.0.1\",\"port\":" + port + "}";
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/probe").contentType(APPLICATION_JSON).content(body));
    }
}
