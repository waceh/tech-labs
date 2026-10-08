package io.github.waceh.caching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
public class LabApplication {
    public static void main(String[] args) { SpringApplication.run(LabApplication.class, args); }

    @Bean(destroyMethod = "close")
    ExecutorService loaders() { return Executors.newVirtualThreadPerTaskExecutor(); }

    @Bean
    ProductService products(ExecutorService loaders) {
        return new ProductService(loaders, () -> {
            try { Thread.sleep(300); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("origin interrupted", e);
            }
            return null;
        });
    }
}
