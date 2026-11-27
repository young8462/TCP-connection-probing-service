package com.yueyou.tcpprobe.probe;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchProbeServiceTest {

    @Test
    void returnsCompletedResultsWhenAnEarlierTargetExceedsTheDeadline() throws Exception {
        TcpProbe probe = mock(TcpProbe.class);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(probe.probe("slow", 80)).thenAnswer(call -> {
            blocked.countDown();
            release.await();
            return new ProbeResult("slow", 80, "reachable", 0);
        });
        when(probe.probe("fast", 80)).thenReturn(new ProbeResult("fast", 80, "reachable", 0));

        BatchProbeService service = new BatchProbeService(probe, 2, 2, TimeUnit.SECONDS.toNanos(1));
        try {
            List<ProbeResult> results = service.probe(Arrays.asList(target("slow"), target("fast")));
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            assertEquals("slow", results.get(0).getHost());
            assertEquals("deadline_exceeded", results.get(0).getStatus());
            assertEquals("reachable", results.get(1).getStatus());
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void rejectsEntireBatchWhenOnlySomeTargetsWouldFit() throws Exception {
        TcpProbe probe = mock(TcpProbe.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(probe.probe("running", 80)).thenAnswer(call -> {
            started.countDown();
            release.await();
            return new ProbeResult("running", 80, "reachable", 0);
        });

        BatchProbeService service = new BatchProbeService(probe, 1, 2, TimeUnit.SECONDS.toNanos(5));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<List<ProbeResult>> active = caller.submit(() ->
                    service.probe(Collections.singletonList(target("running"))));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () ->
                    service.probe(Arrays.asList(target("rejected-first"), target("rejected-second"))));
            release.countDown();
            assertEquals("reachable", active.get(2, TimeUnit.SECONDS).get(0).getStatus());
            verify(probe, never()).probe(eq("rejected-first"), anyInt());
            verify(probe, never()).probe(eq("rejected-second"), anyInt());
        } finally {
            release.countDown();
            caller.shutdownNow();
            service.shutdown();
        }
    }

    private InetSocketAddress target(String host) {
        return InetSocketAddress.createUnresolved(host, 80);
    }
}
