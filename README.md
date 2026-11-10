# payments-gateway

API Gateway — единственный вход в API сервисов. Снаружи доступен только он; сервисы видны лишь внутри кластера
(и compose).

```
браузер → Traefik (вход в кластер) → payments-gateway → account, pricing, payment, history, biller, autopayments
```

- Порт: `8080`
- Spring Cloud Gateway на WebFlux (Netty). Spring Boot 4.0 — не 4.1, как у остальных: Spring Cloud выпускается под
  конкретную ветку Boot, последняя стабильная (2025.1) — под 4.0. Перейдём на 4.1 вместе со Spring Cloud 2026.0.
- Образ: `ghcr.io/payments-app-org/payments-gateway:<sha коммита main>`

## Что делает

- **Маршруты** «путь → сервис» — `application.yml` (`spring.cloud.gateway.server.webflux.routes`), адреса сервисов —
  переменные `ACCOUNT_URL`, `PRICING_URL`, `PAYMENT_URL`, `HISTORY_URL`, `BILLER_URL`, `AUTOPAYMENTS_URL`.
  Новый публичный путь сервиса — маршрут здесь.
- **Только публичный API.** `/api/internal/**` (резервы, зачисления, итоги процессинга) ни один маршрут не описывает —
  снаружи это 404. Сервисы вызывают такие методы друг у друга напрямую, минуя gateway.
- **Тайм-ауты**: соединение 1 с, ответ 30 с (`httpclient.*`).
- Заголовки (`X-User-Id`, `X-Idempotency-Key`) передаются сервисам как есть.

Дальше здесь же: проверка токена (Keycloak) и ограничение частоты запросов (rate limit в Redis).

## Сборка и тесты

Тесты маршрутов — на настоящем HTTP: вместо сервиса — маленький сервер JDK. Java ставить не нужно:

```bash
docker run --rm -v "$PWD":/src -v payments-m2:/root/.m2 -w /src maven:3.9-eclipse-temurin-21 mvn -B -s .mvn/settings.xml verify
```

Запустить вместе с остальными — из `payments-local`: `scripts/up.sh gateway`.

## CI

`.github/workflows/ci.yml` — шаблон `java-service.yml` из [payments-ci](https://github.com/payments-app-org/payments-ci):
тесты → проверка зависимостей → образ и его проверка; в `main` — публикация образа и выкатка по кнопке (окружение
`prod`). Слияние в `main` — только при зелёной проверке `ci / ok`.

Устройство всего приложения — [payments-local/CLAUDE.md](https://github.com/payments-app-org/payments-local/blob/main/CLAUDE.md).
