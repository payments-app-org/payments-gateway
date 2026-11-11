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
- **Вход — только с токеном Keycloak** (`security/SecurityConfig`): `/api/**` требует
  `Authorization: Bearer <access-токен>`. Gateway сам проверяет подпись (ключи Keycloak по `OIDC_JWK_SET_URI`),
  срок, издателя (`OIDC_ISSUER_URI` — публичный адрес, как в токене) и получателя `aud = payments-api`.
  Нет токена или он плохой — 401. `/actuator/**` — без токена (пробы, Prometheus; снаружи недоступен).
- **Пользователь — из токена, не от клиента** (`security/UserIdentityFilter`): `X-User-Id` для сервисов gateway
  ставит из claim `sub` (id пользователя в Keycloak = id в account), присланный клиентом выбрасывает. В пути
  `/api/users/{userId}/…` и параметре `?userId=` — только свой id, иначе 403. В теле расчёта комиссии и курса
  (маршрут `pricing-quotes`, фильтр `UserIdInBody`) — то же: нет поля — подставляем, чужой — 403.
- **Ограничение частоты** (`RequestRateLimiter`, `security/RateLimitConfig`): 20 запросов в секунду на пользователя,
  кратко до 40; счётчики — в Redis (`REDIS_HOST`), общие для всех подов. Превысил — 429. Redis недоступен — запросы
  проходят без лимита.
- `X-Idempotency-Key` и остальные заголовки передаются сервисам как есть.

Сервисы за gateway токен не проверяют и доверяют `X-User-Id`: снаружи до них не добраться, а внутри кластера
звонить им может только тот, кому разрешила сервисная сетка (Istio: mTLS и AuthorizationPolicy).

## Сборка и тесты

Тесты маршрутов и токенов — на настоящем HTTP: вместо сервисов и Keycloak — маленький сервер JDK (отдаёт и ключ
подписи, токены тест подписывает сам). Java ставить не нужно:

```bash
docker run --rm -v "$PWD":/src -v payments-m2:/root/.m2 -w /src maven:3.9-eclipse-temurin-21 mvn -B -s .mvn/settings.xml verify
```

Запустить вместе с остальными — из `payments-local`: `scripts/up.sh gateway`.

## CI

`.github/workflows/ci.yml` — шаблон `java-service.yml` из [payments-ci](https://github.com/payments-app-org/payments-ci):
тесты → проверка зависимостей → образ и его проверка; в `main` — публикация образа и выкатка по кнопке (окружение
`prod`). Слияние в `main` — только при зелёной проверке `ci / ok`.

Устройство всего приложения — [payments-local/CLAUDE.md](https://github.com/payments-app-org/payments-local/blob/main/CLAUDE.md).
