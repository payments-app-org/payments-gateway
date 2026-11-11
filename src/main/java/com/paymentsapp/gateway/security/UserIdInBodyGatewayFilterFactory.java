package com.paymentsapp.gateway.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Фильтр маршрута {@code UserIdInBody}: пользователь в теле JSON ({@code "userId"}) — только свой.
 * Нужен там, где API принимает пользователя в теле (расчёт комиссии и курса в pricing: тот же метод вызывают
 * и сервисы друг у друга). Нет поля — подставляем id из токена, чужой id — 403.
 * Тело читается целиком — фильтр ставим только на маршруты с небольшим JSON (application.yml, маршрут pricing-quotes).
 */
@Component
public class UserIdInBodyGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    private final ModifyRequestBodyGatewayFilterFactory modifyRequestBody;

    public UserIdInBodyGatewayFilterFactory(ModifyRequestBodyGatewayFilterFactory modifyRequestBody) {
        this.modifyRequestBody = modifyRequestBody;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public GatewayFilter apply(Object config) {
        return modifyRequestBody.apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setRewriteFunction(Map.class, Map.class, (exchange, body) -> exchange.getPrincipal()
                        .filter(JwtAuthenticationToken.class::isInstance)
                        .map(principal -> ((JwtAuthenticationToken) principal).getToken().getSubject())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                        .map(userId -> withUser((Map<String, Object>) body, userId))));
    }

    static Map<String, Object> withUser(Map<String, Object> body, String userId) {
        Map<String, Object> result = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        Object sent = result.get(UserIdentityFilter.USER_PARAM);
        if (sent != null && !sent.toString().equalsIgnoreCase(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Чужой пользователь в теле запроса");
        }
        result.put(UserIdentityFilter.USER_PARAM, userId);
        return result;
    }
}
