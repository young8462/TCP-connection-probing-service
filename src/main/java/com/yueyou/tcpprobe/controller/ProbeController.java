package com.yueyou.tcpprobe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ProbeController {

    private static final int MAX_TARGETS = 20;

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Collections.singletonMap("status", "up");
    }

    @PostMapping(path = "/probe", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> probe(@RequestBody JsonNode request) {
        JsonNode targets = request.path("targets");
        if (!targets.isArray() || targets.size() < 1 || targets.size() > MAX_TARGETS) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "targets",
                    "Provide between 1 and " + MAX_TARGETS + " targets.");
        }

        for (int index = 0; index < targets.size(); index++) {
            JsonNode target = targets.get(index);
            String field = "targets[" + index + "]";
            if (!target.isObject()) {
                return error(HttpStatus.BAD_REQUEST, "invalid_request", field,
                        "Target must be an object.");
            }

            JsonNode host = target.path("host");
            if (!host.isTextual() || !host.textValue().matches("[A-Za-z0-9.:_%\\-]{1,253}")) {
                return error(HttpStatus.BAD_REQUEST, "invalid_request", field + ".host",
                        "Provide a hostname or IP address.");
            }

            JsonNode port = target.path("port");
            if (!port.isIntegralNumber() || !port.canConvertToInt()
                    || port.intValue() < 1 || port.intValue() > 65535) {
                return error(HttpStatus.BAD_REQUEST, "invalid_request", field + ".port",
                        "Port must be an integer between 1 and 65535.");
            }
        }

        return error(HttpStatus.NOT_IMPLEMENTED, "probe_not_implemented", null,
                "TCP probing is not implemented yet.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformedBody() {
        return error(HttpStatus.BAD_REQUEST, "invalid_json", null,
                "Provide a valid JSON request body.");
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String field, String message) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("code", code);
        if (field != null) {
            details.put("field", field);
        }
        details.put("message", message);
        return ResponseEntity.status(status).body(Collections.singletonMap("error", details));
    }
}
