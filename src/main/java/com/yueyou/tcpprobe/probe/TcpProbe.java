package com.yueyou.tcpprobe.probe;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

@Service
public class TcpProbe {

    private static final int CONNECT_TIMEOUT_MS = 1000;

    public ProbeResult probe(String host, int port) {
        long started = System.nanoTime();
        String status = connect(host, port);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new ProbeResult(host, port, status, durationMs);
    }

    private String connect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return "reachable";
        } catch (SocketTimeoutException exception) {
            return "timeout";
        } catch (UnknownHostException exception) {
            return "dns_error";
        } catch (ConnectException exception) {
            return "connection_refused";
        } catch (IOException exception) {
            return "io_error";
        }
    }
}
