package com.paymentsapp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway — единственный вход в API сервисов: снаружи доступен только он, сервисы — внутри кластера.
 * Маршруты «путь → сервис», тайм-ауты и лимиты — в application.yml; проверка токена Keycloak, пользователь
 * из токена и ограничение частоты запросов — пакет security.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
