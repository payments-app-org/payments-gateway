package com.paymentsapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Маршруты и проверка токенов gateway на настоящем HTTP. Маленький сервер JDK играет сразу двух соседей:
 * сервисы (запоминает, какой запрос к ним пришёл) и Keycloak (отдаёт открытый ключ подписи по /jwks).
 * Токены подписываем сами тем же ключом — как это делает Keycloak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutesTest {

    private static final String ISSUER = "http://test.localhost/auth/realms/payments";
    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_USER = "00000000-0000-0000-0000-000000000002";

    private static final RSAKey signingKey = generateKey();
    private static final AtomicReference<String> receivedPath = new AtomicReference<>();
    private static final AtomicReference<String> receivedUser = new AtomicReference<>();
    private static final AtomicReference<String> receivedBody = new AtomicReference<>();
    private static final HttpServer upstream = startUpstream();

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + upstream.getAddress().getPort();
        registry.add("ACCOUNT_URL", () -> base);
        registry.add("PRICING_URL", () -> base);
        registry.add("OIDC_ISSUER_URI", () -> ISSUER);
        registry.add("OIDC_JWK_SET_URI", () -> base + "/jwks");
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void reset() {
        receivedPath.set(null);
        receivedUser.set(null);
        receivedBody.set(null);
    }

    @Test
    void forwardsToServiceWithUserFromTokenNotFromClient() {
        client().get().uri("/api/users/" + USER + "/accounts?full=true")
                .headers(h -> h.setBearerAuth(token(USER, "payments-api")))
                .header("X-User-Id", OTHER_USER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"id\":42}");

        assertThat(receivedPath.get()).isEqualTo("/api/users/" + USER + "/accounts?full=true");
        assertThat(receivedUser.get()).isEqualTo(USER);
    }

    @Test
    void withoutTokenIsUnauthorized() {
        client().get().uri("/api/users/" + USER)
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(receivedPath.get()).isNull();
    }

    @Test
    void tokenForAnotherAudienceIsRejected() {
        client().get().uri("/api/users/" + USER)
                .headers(h -> h.setBearerAuth(token(USER, "another-app")))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void foreignUserInPathIsForbidden() {
        client().get().uri("/api/users/" + OTHER_USER + "/accounts")
                .headers(h -> h.setBearerAuth(token(USER, "payments-api")))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(receivedPath.get()).isNull();
    }

    @Test
    void sharedUserPathsAreNotUserResources() {
        client().get().uri("/api/users/roundup/options")
                .headers(h -> h.setBearerAuth(token(USER, "payments-api")))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void foreignUserInQueryIsForbidden() {
        client().get().uri("/api/pricing/tariffs?userId=" + OTHER_USER)
                .headers(h -> h.setBearerAuth(token(USER, "payments-api")))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void userInBodyIsTakenFromToken() {
        client().post().uri("/api/pricing/commission")
                .headers(h -> h.setBearerAuth(token(USER, "payments-api")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":100}")
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedBody.get()).contains("\"userId\":\"" + USER + "\"").contains("\"amount\":100");
    }

    @Test
    void foreignUserInBodyIsForbidden() {
        client().post().uri("/api/pricing/commission")
                .headers(h -> h.setBearerAuth(token(USER, "payments-api")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userId\":\"" + OTHER_USER + "\",\"amount\":100}")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(receivedPath.get()).isNull();
    }

    @Test
    void internalApiIsNotReachableFromOutside() {
        client().post().uri("/api/internal/reservations")
                .headers(h -> h.setBearerAuth(token(USER, "payments-api")))
                .exchange()
                .expectStatus().isNotFound();

        assertThat(receivedPath.get()).isNull();
    }

    @Test
    void actuatorHealthNeedsNoToken() {
        client().get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port"))
                .build();
    }

    /** Access-токен, как его выпускает Keycloak: подпись RS256, издатель, получатель, sub = id пользователя */
    private static String token(String subject, String audience) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/api", exchange -> {
                receivedPath.set(exchange.getRequestURI().toString());
                receivedUser.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
