package com.yueyou.tcpprobe.probe;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ProbeResult {

    private final String host;
    private final int port;
    private final String status;
    private final long durationMs;

    public ProbeResult(String host, int port, String status, long durationMs) {
        this.host = host;
        this.port = port;
        this.status = status;
        this.durationMs = durationMs;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getStatus() {
        return status;
    }

    @JsonProperty("duration_ms")
    public long getDurationMs() {
        return durationMs;
    }
}
