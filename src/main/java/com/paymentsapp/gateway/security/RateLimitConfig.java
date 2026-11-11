package com.paymentsapp.gateway.security;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Ограничение частоты запросов (фильтр RequestRateLimiter, настройки — application.yml): у каждого пользователя
 * своё «ведро» токенов в Redis — пополняется со скоростью replenishRate в секунду, вмещает burstCapacity.
 * Пустое ведро — 429 Too Many Requests. Ключ — пользователь из токена (sub): один клиент не забьёт API для всех.
 * Redis недоступен — запросы пропускаются (лучше без лимита, чем без API).
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken().getSubject())
                // Без токена в /api не попасть; на всякий случай — общий ключ, а не пропуск без лимита
                .defaultIfEmpty("anonymous");
    }
}
