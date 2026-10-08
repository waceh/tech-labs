package io.github.waceh.caching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SingleFlightTest {
    private static void await(CountDownLatch gate) {
        try { assertTrue(gate.await(5, TimeUnit.SECONDS), "gate timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static <T> T result(CompletableFuture<T> f) throws Exception {
        return f.get(5, TimeUnit.SECONDS);
    }

    @Test void overlappingMissesCompareBaselineAndSingleFlight() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (boolean coalesce : new boolean[]{false, true}) {
                var gate = new CountDownLatch(1);
                var entered = new CountDownLatch(coalesce ? 1 : 100);
                var service = new ProductService(executor, () -> { entered.countDown(); await(gate); return null; });
                var calls = new ArrayList<CompletableFuture<ProductService.Product>>();
                try {
                    for (int i = 0; i < 100; i++) calls.add(service.get("hot", coalesce));
                    await(entered);
                    assertEquals(coalesce ? 1 : 100, service.originReads());
                } finally { gate.countDown(); }
                for (var call : calls) assertEquals("hot", result(call).id());
                result(service.get("hot", coalesce));
                assertEquals(coalesce ? 1 : 100, service.originReads());
                System.out.printf("singleFlight=%s requests=100 originReads=%d%n", coalesce, service.originReads());
            }
        }
    }

    @Test void concurrentAdmissionElectsOneLeader() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var flight = new SingleFlight<String, String>(executor);
            var start = new CountDownLatch(1);
            var admitted = new CountDownLatch(100);
            var release = new CountDownLatch(1);
            var reads = new AtomicInteger();
            List<Future<CompletableFuture<String>>> tasks = new ArrayList<>();
            try {
                for (int i = 0; i < 100; i++) tasks.add(executor.submit(() -> {
                    await(start);
                    var f = flight.execute("hot", () -> { reads.incrementAndGet(); await(release); return "ok"; });
                    admitted.countDown();
                    return f;
                }));
                start.countDown(); await(admitted);
                assertEquals(1, flight.inFlightCount());
            } finally { start.countDown(); release.countDown(); }
            for (var task : tasks) assertEquals("ok", result(task.get(5, TimeUnit.SECONDS)));
            assertEquals(1, reads.get());
            assertEquals(0, flight.inFlightCount());
        }
    }

    @Test void differentKeysDoNotCoalesce() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var entered = new CountDownLatch(2);
            var flight = new SingleFlight<String, String>(executor);
            var a = flight.execute("a", () -> { entered.countDown(); await(gate); return "a"; });
            var b = flight.execute("b", () -> { entered.countDown(); await(gate); return "b"; });
            try { await(entered); assertEquals(2, flight.inFlightCount()); }
            finally { gate.countDown(); }
            assertEquals("a", result(a)); assertEquals("b", result(b));
        }
    }

    @Test void failureIsSharedAndRetryCanStart() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var flight = new SingleFlight<String, String>(executor);
            var error = new IllegalStateException("origin down");
            var a = flight.execute("a", () -> { await(gate); throw error; });
            var b = flight.execute("a", () -> "must not run");
            gate.countDown();
            assertSame(error, assertThrows(ExecutionException.class, () -> result(a)).getCause());
            assertSame(error, assertThrows(ExecutionException.class, () -> result(b)).getCause());
            assertEquals(0, flight.inFlightCount());
            assertEquals("recovered", result(flight.execute("a", () -> "recovered")));
        }
    }

    @Test void callerTimeoutDoesNotPoisonSharedWork() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var flight = new SingleFlight<String, String>(executor);
            var a = flight.execute("a", () -> { await(gate); return "ok"; }).orTimeout(20, TimeUnit.MILLISECONDS);
            var b = flight.execute("a", () -> "must not run");
            try {
                assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class, () -> result(a)).getCause());
                assertFalse(b.isDone());
                assertEquals(1, flight.inFlightCount());
            } finally { gate.countDown(); }
            assertEquals("ok", result(b));
        }
    }

    @Test void callerCancellationDoesNotCancelFollowers() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var flight = new SingleFlight<String, String>(executor);
            var a = flight.execute("a", () -> { await(gate); return "ok"; });
            var b = flight.execute("a", () -> "must not run");
            try { assertTrue(a.cancel(true)); assertFalse(b.isDone()); }
            finally { gate.countDown(); }
            assertEquals("ok", result(b));
        }
    }

    @Test void rejectedSubmissionCleansUpAndCanRetry() throws Exception {
        var reject = new AtomicBoolean(true);
        var flight = new SingleFlight<String, String>(task -> {
            if (reject.getAndSet(false)) throw new RejectedExecutionException("full");
            task.run();
        });
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(ExecutionException.class, () -> result(flight.execute("a", () -> "bad"))).getCause());
        assertEquals(0, flight.inFlightCount());
        assertEquals("ok", result(flight.execute("a", () -> "ok")));
    }

    @Test void separateInstancesEachLoadSameKey() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var entered = new CountDownLatch(2);
            var one = new ProductService(executor, () -> { entered.countDown(); await(gate); return null; });
            var two = new ProductService(executor, () -> { entered.countDown(); await(gate); return null; });
            var a = one.get("hot", true); var b = two.get("hot", true);
            try { await(entered); assertEquals(1, one.originReads()); assertEquals(1, two.originReads()); }
            finally { gate.countDown(); }
            result(a); result(b);
        }
    }

    @Test void singleFlightDoesNotCacheCompletedResult() throws Exception {
        var flight = new SingleFlight<String, Integer>(Runnable::run);
        var reads = new AtomicInteger();
        assertEquals(1, result(flight.execute("a", reads::incrementAndGet)));
        assertEquals(2, result(flight.execute("a", reads::incrementAndGet)));
    }
}
