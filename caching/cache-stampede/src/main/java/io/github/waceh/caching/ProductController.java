package io.github.waceh.caching;

import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
public class ProductController {
    private final ProductService products;
    public ProductController(ProductService products) { this.products = products; }

    @GetMapping("/products/{id}")
    public CompletableFuture<ProductService.Product> get(
            @PathVariable String id, @RequestParam(defaultValue = "true") boolean singleFlight) {
        return products.get(id, singleFlight).orTimeout(2, TimeUnit.SECONDS);
    }

    @GetMapping("/stats")
    public Map<String, Integer> stats() { return Map.of("originReads", products.originReads()); }
}
