package com.paymentsapp.gateway.security;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Кто делает запрос — решает токен, а не клиент.
 * <ul>
 *   <li>Заголовок {@code X-User-Id}, который сервисы читают как «текущий пользователь», gateway ставит сам — из claim
 *       {@code sub} токена (в Keycloak id пользователя = id в сервисе account). Присланный клиентом — выбрасывается:
 *       иначе любой подставил бы чужой id.</li>
 *   <li>В пути {@code /api/users/{userId}/…} и параметре {@code ?userId=} — только свой id, чужой — 403.</li>
 * </ul>
 * Сервисы за gateway токен сами не проверяют и доверяют {@code X-User-Id}: снаружи до них не добраться, а внутри
 * кластера звонить им может только тот, кому разрешила сервисная сетка (Istio, mTLS + AuthorizationPolicy).
 */
@Component
public class UserIdentityFilter implements GlobalFilter, Ordered {

    public static final String USER_HEADER = "X-User-Id";
    static final String USER_PARAM = "userId";
    /** /api/users/{uuid}/… — ресурсы пользователя. /api/users/roundup/options и т. п. (не uuid) — общие справочники */
    private static final Pattern USER_PATH =
            Pattern.compile("^/api/users/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken().getSubject())
                .map(userId -> {
                    checkOwnership(exchange.getRequest(), userId);
                    return withUser(exchange, userId);
                })
                // Без токена (actuator) — только убираем заголовок; /api без токена сюда не доходит (401)
                .defaultIfEmpty(withUser(exchange, null))
                .flatMap(chain::filter);
    }

    private static void checkOwnership(ServerHttpRequest request, String userId) {
        Matcher path = USER_PATH.matcher(request.getPath().value());
        if (path.matches() && !path.group(1).equalsIgnoreCase(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Чужой пользователь в пути запроса");
        }
        List<String> params = request.getQueryParams().getOrDefault(USER_PARAM, List.of());
        if (params.stream().anyMatch(value -> !value.equalsIgnoreCase(userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Чужой пользователь в параметрах запроса");
        }
    }

    private static ServerWebExchange withUser(ServerWebExchange exchange, String userId) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_HEADER);
                    if (userId != null) {
                        headers.set(USER_HEADER, userId);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    /** Раньше маршрутизации и остальных фильтров: дальше запрос идёт уже с правильным пользователем */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
