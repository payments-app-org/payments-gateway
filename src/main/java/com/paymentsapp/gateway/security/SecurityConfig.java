package com.paymentsapp.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Кто может пройти через gateway.
 * <ul>
 *   <li>{@code /api/**} — только с действующим access-токеном Keycloak ({@code Authorization: Bearer <JWT>}).
 *       Gateway проверяет подпись (ключи Keycloak), срок, издателя (iss) и получателя (aud = payments-api) —
 *       настройки {@code spring.security.oauth2.resourceserver.jwt.*}. Нет токена или он плохой — 401.</li>
 *   <li>{@code /actuator/**} — без токена: пробы Kubernetes и сбор метрик Prometheus. Снаружи кластера actuator
 *       недоступен: вход (Traefik) ведёт в gateway только {@code /api}.</li>
 *   <li>Всё остальное — 403.</li>
 * </ul>
 * Сессий нет (stateless): каждый запрос несёт токен сам, поэтому нет и защиты от CSRF — она нужна, когда браузер
 * сам прикладывает cookie сессии, а токен в заголовок кладёт только наш код.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .build();
    }
}
