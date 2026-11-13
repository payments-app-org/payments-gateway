package com.paymentsapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;

class ObservationConfigTest {

    private final ObservationPredicate predicate = new ObservationConfig().skipActuatorRequests();

    @Test
    void skipsActuatorRequestsButKeepsApiRequests() {
        assertThat(predicate.test("http.server.requests", request("/actuator/health/readiness"))).isFalse();
        assertThat(predicate.test("http.server.requests", request("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", request("/api/payments"))).isTrue();
        assertThat(predicate.test("http.client.requests", new Observation.Context())).isTrue();
    }

    @Test
    void skipsSpringSecurityObservations() {
        ObservationPredicate security = new ObservationConfig().skipSecurityObservations();

        assertThat(security.test("spring.security.filterchains", new Observation.Context())).isFalse();
        assertThat(security.test("spring.security.authorizations", new Observation.Context())).isFalse();
        assertThat(security.test("http.server.requests", new Observation.Context())).isTrue();
    }

    private static ServerRequestObservationContext request(String path) {
        return new ServerRequestObservationContext(
                MockServerHttpRequest.get(path).build(), new MockServerHttpResponse(), Map.of());
    }
}
