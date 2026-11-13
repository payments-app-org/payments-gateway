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

    static final String SECURITY_PREFIX = "spring.security.";

    /**
     * Без отдельных спанов Spring Security (spring.security.filterchains, authorizations, http.secured.requests): на
     * каждый запрос — пробы Kubernetes тоже — они давали свои трассы «security filterchain» и забивали Jaeger.
     * Отказ в доступе виден и так: статус 401/403 у спана запроса и в метриках http.server.requests.
     * (Бин SecurityObservationSettings на реактивную цепочку фильтров не действует — поэтому фильтр по имени)
     */
    @Bean
    ObservationPredicate skipSecurityObservations() {
        return (name, context) -> !name.startsWith(SECURITY_PREFIX);
    }
}
