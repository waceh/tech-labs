package io.github.waceh.caching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpTest {
    @Autowired TestRestTemplate http;
    @Test void endpointReturnsProductAndStats() {
        var response = http.getForEntity("/products/http-smoke", ProductService.Product.class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("http-smoke", response.getBody().id());
        assertTrue(http.getForObject("/stats", String.class).contains("originReads"));
    }
}
