package com.yueyou.tcpprobe.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Collections;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProbeController.class)
class ProbeControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void reportsHealth() throws Exception {
        mvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"));
    }

    @Test
    void acceptsValidBatchWithoutInventingProbeResults() throws Exception {
        submit("{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":1},"
                + "{\"host\":\"::1\",\"port\":65535}]}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error.code").value("probe_not_implemented"));
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

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/probe").contentType(APPLICATION_JSON).content(body));
    }
}
