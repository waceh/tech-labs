package io.github.waceh.caching;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class ProductService {
    public record Product(String id, String name) {}
    private final Cache<String, Product> cache = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(30)).build();
    private final SingleFlight<String, Product> flight;
    private final Executor executor;
    private final Supplier<Void> beforeRead;
    private final AtomicInteger originReads = new AtomicInteger();

    public ProductService(Executor executor, Supplier<Void> beforeRead) {
        this.executor = executor;
        this.flight = new SingleFlight<>(executor);
        this.beforeRead = beforeRead;
    }

    public CompletableFuture<Product> get(String key, boolean coalesce) {
        Product hit = cache.getIfPresent(key);
        if (hit != null) return CompletableFuture.completedFuture(hit);
        if (!coalesce) return CompletableFuture.supplyAsync(() -> load(key), executor);
        return flight.execute(key, () -> {
            // 외부 miss와 leader 선출 사이에 선행 작업이 캐시를 채우는 경쟁을 방지한다.
            Product rechecked = cache.getIfPresent(key);
            return rechecked != null ? rechecked : load(key);
        });
    }

    private Product load(String key) {
        originReads.incrementAndGet();
        beforeRead.get(); // 지연/동기화 제어를 주입하는 모의 저장소. 실제 DB가 아니다.
        Product value = new Product(key, "product-" + key);
        cache.put(key, value);
        return value;
    }

    public int originReads() { return originReads.get(); }
}
