# api-gateway

Шлюз Kite (5.1): єдина точка входу `/api/v1` на порту 8080. Перевіряє JWT від
Keycloak, рахує rate limit у Redis і веде запит у сервіс, який володіє шляхом.
Spring Cloud Gateway на WebFlux, Spring Boot 3.5, Java 21.

## Що робить

| Що | Як | Де в коді |
| -- | -- | --------- |
| Маршрути | Таблиця шлях → сервіс за 9.2–9.5; шлях іде в сервіс без змін. Шляху, якого немає в таблиці, шлюз не пропускає (404). Один шлях ділиться за методом: `POST /shipments` — shipment-service, `GET /shipments` (дашборд «мені / від мене») — tracking-service | `routing/RouteTable` |
| JWT | Підпис за JWKS realm-у `kite`, `iss`, строк дії, audience `kite-api`. Публічні шляхи 9.2 (`security: []` в OpenAPI) — без токена; токен там навіть не розбирається, щоб прострочений не заважав `/auth/refresh`. Ролі перевіряють самі сервіси: токен іде до них як є | `security/SecurityConfig` |
| Rate limit | Token bucket у Redis на `sub` з токена або на IP. `default` — 20 запитів/с, сплеск 40; `auth` (вхід) — 10 на хвилину. Без Redis ліміт не діє, але API працює | `ratelimit/RateLimitFilter` |
| Тайм-аути | 2 с на з'єднання і на відповідь сервіса (5.4, п. 7) → 504 | `application.yml` |
| Circuit breaker | Resilience4j на кожен маршрут: 50% збоїв за останні 20 викликів (від 10) → маршрут вимкнено на 10 с → 503 | `routing/RoutesConfig` |
| Помилки | Усі відмови шлюзу — `application/problem+json` з `code` і `requestId` (9.1): `AUTH_REQUIRED`, `AUTH_INVALID_TOKEN`, `RATE_LIMITED`, `UPSTREAM_TIMEOUT`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_CIRCUIT_OPEN` | `web/Problems` |
| `X-Request-Id` | Приймається від клієнта (якщо схожий на ідентифікатор) або генерується; іде в сервіс і у відповідь | `web/RequestIdFilter` |

Ліміти, адреси сервісів і параметри breaker-а — у `kite.gateway.*` в
`application.yml`. Адресу сервіса локально міняє змінна:
`KITE_GATEWAY_SERVICES_SHIPMENT=http://localhost:8082`.

## Команди

```bash
make test       # юніт-тести: таблиця маршрутів, контекст
make test-it    # + GatewayIT: Redis у Testcontainers, підписані JWT, фейковий сервіс
make verify     # усе, що робить CI
make run        # запустити з Maven; Keycloak на :8081 і Redis на :6379 зі стенда
```

Перевірка на стенді:

```bash
TOKEN=$(../kite-infra/scripts/token.sh client)
curl -i localhost:8080/api/v1/shipments                                # 401 AUTH_REQUIRED
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/shipments  # до shipment-service
```

## Контракти

Моделі API, інтерфейси контролерів і класи подій CloudEvents приходять
залежністю `ua.kite:kite-contracts`, закріпленою за тегом — своїх копій
`openapi.yaml` і JSON Schema сервіс не тримає (5.4, п. 6):

```xml
<kite-contracts.version>0.1.0</kite-contracts.version>
```

```bash
make contracts            # підтягнути пакет за поточним тегом
make contracts V=0.3.0    # перейти на нову версію: Makefile + pom.xml
make contracts-check      # версія закріплена, копій схем немає, залежність резолвиться
```

Нова версія приходить сама: після релізу `kite-contracts` розсилає подію
всім репозиторіям із топіком `kite-contracts`, і workflow `contracts-bump`
відкриває PR з `make contracts V=<версія>`. Версія одна на всі репозиторії.
Руками те саме: `make contracts V=0.3.0`. Якщо тегу немає, `Makefile` і
маніфест лишаються як були.

`contracts-check` проганяє спільний CI. Самі схеми лежать у тому самому jar
(`/kite/contracts/openapi`, `/kite/contracts/events`), тож валідація події не
потребує копії файла в репозиторії — так це і перевіряє `ContractsWiringTest`.

Пакет живе в GitHub Packages, а він віддає Maven лише з токеном. У CI
`settings.xml` розставляє спільний workflow; локально потрібен свій:

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>ВАШ-ЛОГІН</username>
      <password>ghp_… (scope read:packages)</password>
    </server>
  </servers>
</settings>
```

Порядок наскрізної зміни (5.6): спершу адитивна зміна в `kite-contracts` з
новим тегом, потім версія тут, і тільки потім продюсер починає слати нове
поле.

## Коли з'являться події

Писати подію треба в одній транзакції зі зміною, у таблицю `outbox`; окремий
процес публікує її в Kafka. Споживач тримає `processed_event(event_id)` і
пропускає повтори. Класи подій і реєстр топіків уже є в `kite-contracts`:
`ua.kite.contracts.model.ShipmentStatusChangedEvent`, `KiteEventType`.
