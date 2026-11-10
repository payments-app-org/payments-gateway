package com.paymentsapp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway — единственный вход в API сервисов: снаружи доступен только он, сервисы — внутри кластера.
 * Маршруты «путь → сервис» и тайм-ауты — в application.yml. Сейчас gateway только разводит запросы;
 * проверка токена (Keycloak) и ограничение частоты запросов появятся здесь же.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
