package com.yueyou.tcpprobe.probe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class BatchProbeService {

    private final TcpProbe tcpProbe;
    private final ThreadPoolExecutor executor;
    private final Semaphore slots;
    private final long deadlineNanos;

    @Autowired
    public BatchProbeService(TcpProbe tcpProbe) {
        this(tcpProbe, 8, 40, TimeUnit.SECONDS.toNanos(5));
    }

    BatchProbeService(TcpProbe tcpProbe, int workers, int capacity, long deadlineNanos) {
        this.tcpProbe = tcpProbe;
        this.deadlineNanos = deadlineNanos;
        this.slots = new Semaphore(capacity);
        this.executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity));
    }

    public List<ProbeResult> probe(List<InetSocketAddress> targets) {
        long deadline = System.nanoTime() + deadlineNanos;
        if (!slots.tryAcquire(targets.size())) {
            throw new RejectedExecutionException("Insufficient probe capacity for the batch");
        }

        List<FutureTask<ProbeResult>> tasks = new ArrayList<>(targets.size());
        try {
            for (InetSocketAddress target : targets) {
                tasks.add(submit(target, deadline));
            }

            List<ProbeResult> results = new ArrayList<>(targets.size());
            for (int index = 0; index < targets.size(); index++) {
                results.add(await(tasks.get(index), targets.get(index), deadline));
            }
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Batch wait interrupted", exception);
        } finally {
            slots.release(targets.size() - tasks.size());
            for (FutureTask<ProbeResult> task : tasks) {
                if (task.cancel(true) && executor.remove(task)) {
                    slots.release();
                }
            }
        }
    }

    private FutureTask<ProbeResult> submit(InetSocketAddress target, long deadline) {
        FutureTask<ProbeResult> task = new FutureTask<ProbeResult>(() -> {
            if (deadline - System.nanoTime() <= 0) {
                return expired(target);
            }
            ProbeResult result = tcpProbe.probe(target.getHostString(), target.getPort());
            return deadline - System.nanoTime() > 0 ? result : expired(target);
        }) {
            @Override
            public void run() {
                try {
                    super.run();
                } finally {
                    // Running socket and DNS operations can outlive cancellation.
                    slots.release();
                }
            }
        };
        executor.execute(task);
        return task;
    }

    private ProbeResult await(FutureTask<ProbeResult> task, InetSocketAddress target, long deadline)
            throws InterruptedException {
        try {
            return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            return expired(target);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Unexpected TCP probe failure", exception.getCause());
        }
    }

    private ProbeResult expired(InetSocketAddress target) {
        return new ProbeResult(target.getHostString(), target.getPort(), "deadline_exceeded", 0);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
