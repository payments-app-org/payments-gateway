package com.paymentsapp.gateway;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * Запросы к /actuator/** (пробы Kubernetes, сбор метрик Prometheus) — без трасс и метрик запросов, как у сервисов
 * в payments-platform: иначе в Jaeger их больше, чем настоящих запросов. У gateway WebFlux — свой тип контекста.
 */
@Configuration(proxyBeanMethods = false)
class ObservationConfig {

    static final String ACTUATOR_PREFIX = "/actuator";

    @Bean
    ObservationPredicate skipActuatorRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier() != null
                && request.getCarrier().getPath().value().startsWith(ACTUATOR_PREFIX));
    }
}
