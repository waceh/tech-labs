package io.github.waceh.caching;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** 동일 객체/Key의 진행 중인 작업만 공유한다. 완료 결과의 캐시는 별도 책임이다. */
public final class SingleFlight<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final Executor executor;

    public SingleFlight(Executor executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    public CompletableFuture<V> execute(K key, Supplier<V> loader) {
        Objects.requireNonNull(loader);
        var candidate = new CompletableFuture<V>();
        var existing = inFlight.putIfAbsent(key, candidate);
        if (existing != null) return existing.copy();
        // I/O는 ConcurrentHashMap의 compute 콜백/락 안에서 수행하지 않는다.
        try {
            executor.execute(() -> {
                V value;
                try {
                    value = loader.get(); // 캐시 게시까지 loader 내부에서 완료해야 한다.
                } catch (Throwable failure) {
                    inFlight.remove(key, candidate);
                    candidate.completeExceptionally(failure);
                    return;
                }
                // 실제 작업 종료 후 제거한다. 후속 요청은 캐시 재확인 또는 새 작업을 수행한다.
                inFlight.remove(key, candidate);
                candidate.complete(value);
            });
        } catch (RuntimeException rejected) {
            inFlight.remove(key, candidate);
            candidate.completeExceptionally(rejected);
        }
        // cancel/orTimeout/complete가 공유 Future를 변경하지 않도록 요청별 복사본을 반환한다.
        return candidate.copy();
    }

    public int inFlightCount() { return inFlight.size(); }
}
