package com.paymentsapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Маршруты gateway на настоящем HTTP: вместо сервиса account — маленький сервер JDK, который запоминает,
 * какой запрос к нему пришёл.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutesTest {

    private static final AtomicReference<String> receivedPath = new AtomicReference<>();
    private static final AtomicReference<String> receivedUser = new AtomicReference<>();
    private static final HttpServer account = startAccount();

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("ACCOUNT_URL", () -> "http://localhost:" + account.getAddress().getPort());
    }

    @AfterAll
    static void stopAccount() {
        account.stop(0);
    }

    @BeforeEach
    void reset() {
        receivedPath.set(null);
        receivedUser.set(null);
    }

    @Test
    void forwardsPublicApiToServiceWithPathAndHeaders() {
        client().get().uri("/api/users/42?full=true")
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"id\":42}");

        assertThat(receivedPath.get()).isEqualTo("/api/users/42?full=true");
        assertThat(receivedUser.get()).isEqualTo("user-1");
    }

    @Test
    void internalApiIsNotReachableFromOutside() {
        client().post().uri("/api/internal/reservations")
                .exchange()
                .expectStatus().isNotFound();

        assertThat(receivedPath.get()).isNull();
    }

    @Test
    void unknownPathIsNotFound() {
        client().get().uri("/api/unknown")
                .exchange()
                .expectStatus().isNotFound();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port"))
                .build();
    }

    private static HttpServer startAccount() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                receivedPath.set(exchange.getRequestURI().toString());
                receivedUser.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                byte[] body = "{\"id\":42}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
