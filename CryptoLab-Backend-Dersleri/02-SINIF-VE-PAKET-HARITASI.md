# Sınıf ve paket haritası

Bu harita ders kodlarının bu revizyondaki yerini gösterir. `kaynaklar/` altındaki şartname kopyaları tarihsel kaynak olarak değiştirilmemiştir.

Java package ve dosya yolu birlikte değişir. IDE’de Refactor → Move kullan; aynı sınıfın eski ve yeni kopyalarını beraber bırakma. HTTP endpoint, JSON alan adı, tablo ve topic adı paket refactor işleminin parçası değildir.

`com.cryptolab` bu belgelerin örnek köküdür. Mevcut projenin kökü `com.crypto` ise bütün servis köklerinde tutarlı biçimde kendi kökünü kullan; groupId ile package teknik olarak farklı olabilir fakat örnekleri iki farklı kökle karıştırma.

| Ders | Sınıf | Yeni Java paketi |
|---|---|---|
| B01 | `IdentityServiceApplication` | `com.cryptolab.identity` |
| B01 | `MarketDataServiceApplication` | `com.cryptolab.marketdata` |
| B01 | `SimulationServiceApplication` | `com.cryptolab.simulation` |
| B01 | `StrategyServiceApplication` | `com.cryptolab.strategy` |
| B01 | `ReportingServiceApplication` | `com.cryptolab.reporting` |
| B01 | `ApiGatewayApplication` | `com.cryptolab.apigateway` |
| B02 | `Decimal` | `com.cryptolab.finance` |
| B02 | `Money` | `com.cryptolab.finance` |
| B02 | `Quantity` | `com.cryptolab.finance` |
| B02 | `PositionSide` | `com.cryptolab.finance` |
| B02 | `MaintenanceTier` | `com.cryptolab.finance` |
| B02 | `Finance` | `com.cryptolab.finance` |
| B02 | `FinanceTest` | `com.cryptolab.finance` |
| B03 | `Journal` | `com.cryptolab.simulation.domain.model` |
| B03 | `EngineConfig` | `com.cryptolab.simulation.infrastructure.config` |
| B03 | `JdbcLedgerStore` | `com.cryptolab.simulation.infrastructure.repository` |
| B03 | `CreateSession` | `com.cryptolab.simulation.application.service` |
| B03 | `LedgerIT` | `com.cryptolab.simulation` |
| B03 | `LedgerRepository` | `com.cryptolab.simulation.application.repository` |
| B03 | `SessionRepository` | `com.cryptolab.simulation.application.repository` |
| B03 | `JdbcSessionRepository` | `com.cryptolab.simulation.infrastructure.repository` |
| B04 | `RegisterRequest` | `com.cryptolab.identity.api.dto.request` |
| B04 | `Crypto` | `com.cryptolab.identity.application.security` |
| B04 | `TokenConfig` | `com.cryptolab.identity.infrastructure.config` |
| B04 | `AccessTokens` | `com.cryptolab.identity.infrastructure.security` |
| B04 | `LocalLoginLimiter` | `com.cryptolab.identity.infrastructure.ratelimit` |
| B04 | `IdentityService` | `com.cryptolab.identity.application.service` |
| B04 | `AuthController` | `com.cryptolab.identity.api.controller` |
| B04 | `SecurityConfig` | `com.cryptolab.identity.infrastructure.config` |
| B04 | `IdentityRepository` | `com.cryptolab.identity.application.repository` |
| B04 | `JdbcIdentityRepository` | `com.cryptolab.identity.infrastructure.repository` |
| B04 | `RegisterCommand` | `com.cryptolab.identity.application.command` |
| B04 | `UserQueryService` | `com.cryptolab.identity.application.service` |
| B04 | `UserResponse` | `com.cryptolab.identity.api.dto.response` |
| B04 | `AuthMapper` | `com.cryptolab.identity.api.mapper` |
| B04 | `LoginLimiter` | `com.cryptolab.identity.application.port` |
| B04 | `TokenIssuer` | `com.cryptolab.identity.application.port` |
| B05 | `ExecutionPlan` | `com.cryptolab.simulation.domain.model` |
| B05 | `PlanHash` | `com.cryptolab.simulation.application.service` |
| B05 | `ExecutionGate` | `com.cryptolab.simulation.domain.service` |
| B05 | `SubmitOrderRequest` | `com.cryptolab.simulation.api.dto.request` |
| B05 | `SubmitOrder` | `com.cryptolab.simulation.application.service` |
| B05 | `OrderController` | `com.cryptolab.simulation.api.controller` |
| B05 | `FillAccounting` | `com.cryptolab.simulation.domain.service` |
| B05 | `ApiErrors` | `com.cryptolab.simulation.api.exception` |
| B05 | `ManualPreviewRequest` | `com.cryptolab.simulation.api.dto.request` |
| B05 | `PreviewFactory` | `com.cryptolab.simulation.domain.service` |
| B05 | `DecimalJsonConfig` | `com.cryptolab.simulation.infrastructure.config` |
| B05 | `LimitDecision` | `com.cryptolab.simulation.domain.service` |
| B05 | `SubmitOrderCommand` | `com.cryptolab.simulation.application.command` |
| B05 | `AcceptedOrderResponse` | `com.cryptolab.simulation.api.dto.response` |
| B05 | `OrderMapper` | `com.cryptolab.simulation.api.mapper` |
| B05 | `OrderSubmissionRepository` | `com.cryptolab.simulation.application.repository` |
| B05 | `JdbcOrderSubmissionRepository` | `com.cryptolab.simulation.infrastructure.repository` |
| B05 | `BusinessException` | `com.cryptolab.simulation.domain.exception` |
| B05 | `ApiError` | `com.cryptolab.simulation.api.dto.response` |
| B06 | `MarketSource` | `com.cryptolab.marketdata.application.port` |
| B06 | `FixtureAdapter` | `com.cryptolab.marketdata.infrastructure.client.fixture` |
| B06 | `OutboxPublisher` | `com.cryptolab.marketdata.infrastructure.messaging` |
| B06 | `MarketIngress` | `com.cryptolab.simulation.infrastructure.messaging` |
| B06 | `MarketListener` | `com.cryptolab.simulation.infrastructure.messaging` |
| B06 | `BinanceMetadataDecoder` | `com.cryptolab.marketdata.infrastructure.mapper` |
| B06 | `PublicMarketHttp` | `com.cryptolab.marketdata.infrastructure.client` |
| B06 | `SnapshotCache` | `com.cryptolab.marketdata.infrastructure.cache` |
| B07 | `RiskDecision` | `com.cryptolab.simulation.domain.service` |
| B07 | `RiskPolicyEvaluator` | `com.cryptolab.simulation.domain.service` |
| B07 | `FundingBoundary` | `com.cryptolab.simulation.domain.model` |
| B07 | `ExitDecision` | `com.cryptolab.simulation.domain.service` |
| B07 | `DeadlineScheduler` | `com.cryptolab.simulation.application.service` |
| B07 | `DeadlineRepository` | `com.cryptolab.simulation.application.repository` |
| B07 | `JdbcDeadlineRepository` | `com.cryptolab.simulation.infrastructure.repository` |
| B08 | `LedgerProjection` | `com.cryptolab.reporting.infrastructure.repository` |
| B08 | `ReportMetrics` | `com.cryptolab.reporting.domain.service` |
| B08 | `RedisLoginLimiter` | `com.cryptolab.identity.infrastructure.ratelimit` |
| B08 | `RouteTable` | `com.cryptolab.apigateway.infrastructure.config` |
| B08 | `ProxyController` | `com.cryptolab.apigateway.api.controller` |
| B08 | `SseRing` | `com.cryptolab.simulation.infrastructure.streaming` |
| B08 | `CsvCell` | `com.cryptolab.reporting.infrastructure.export` |
| B09 | `PredictionResult` | `com.cryptolab.strategy.domain.model` |
| B09 | `ModelClient` | `com.cryptolab.strategy.application.port` |
| B09 | `FakeModelClient` | `com.cryptolab.strategy.infrastructure.client.fixture` |
| B09 | `NumericPolicy` | `com.cryptolab.strategy.domain.service` |
| B09 | `AnalysisFlow` | `com.cryptolab.strategy.application.service` |
| B09 | `HttpModelClient` | `com.cryptolab.strategy.infrastructure.client` |
| B10 | `TrainingProvider` | `com.cryptolab.strategy.application.port` |
| B10 | `TrainingController` | `com.cryptolab.strategy.api.controller` |
| B10 | `HttpTrainingProvider` | `com.cryptolab.strategy.infrastructure.client` |
| B10 | `TrainingJsonController` | `com.cryptolab.strategy.api.controller` |
| B11 | `VirtualClock` | `com.cryptolab.simulation.application.replay` |
| B11 | `ReplayInput` | `com.cryptolab.simulation.domain.model.replay` |
| B11 | `ReplayRunner` | `com.cryptolab.simulation.application.replay` |
| B11 | `FidelityReport` | `com.cryptolab.simulation.domain.model.replay` |
| B11 | `BarAmbiguity` | `com.cryptolab.simulation.domain.service.replay` |
| B11 | `ArtifactReader` | `com.cryptolab.simulation.infrastructure.artifact` |
| B11 | `ReplayScope` | `com.cryptolab.simulation.application.replay` |
| B11 | `ReplayRunnerTest` | `com.cryptolab.simulation.application.replay` |
| B04 | `LoginRequest` | `com.cryptolab.identity.api.dto.request` |
| B04 | `TokenResponse` | `com.cryptolab.identity.api.dto.response` |
| B10 | `ContractGate` | `com.cryptolab.strategy.api.validation` |

## Taşınan sınıflar

| Sınıf | Önce | Şimdi |
|---|---|---|
| `Journal` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.model` |
| `EngineConfig` | `com.cryptolab.simulation.infrastructure` | `com.cryptolab.simulation.infrastructure.config` |
| `JdbcLedgerStore` | `com.cryptolab.simulation.infrastructure` | `com.cryptolab.simulation.infrastructure.repository` |
| `CreateSession` | `com.cryptolab.simulation.application` | `com.cryptolab.simulation.application.service` |
| `RegisterRequest` | `com.cryptolab.identity.api` | `com.cryptolab.identity.api.dto.request` |
| `Crypto` | `com.cryptolab.identity.infrastructure` | `com.cryptolab.identity.application.security` |
| `TokenConfig` | `com.cryptolab.identity.infrastructure` | `com.cryptolab.identity.infrastructure.config` |
| `AccessTokens` | `com.cryptolab.identity.application` | `com.cryptolab.identity.infrastructure.security` |
| `LocalLoginLimiter` | `com.cryptolab.identity.application` | `com.cryptolab.identity.infrastructure.ratelimit` |
| `IdentityService` | `com.cryptolab.identity.application` | `com.cryptolab.identity.application.service` |
| `AuthController` | `com.cryptolab.identity.api` | `com.cryptolab.identity.api.controller` |
| `SecurityConfig` | `com.cryptolab.identity.infrastructure` | `com.cryptolab.identity.infrastructure.config` |
| `ExecutionPlan` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.model` |
| `PlanHash` | `com.cryptolab.simulation.application` | `com.cryptolab.simulation.application.service` |
| `ExecutionGate` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.service` |
| `SubmitOrderRequest` | `com.cryptolab.simulation.api` | `com.cryptolab.simulation.api.dto.request` |
| `SubmitOrder` | `com.cryptolab.simulation.application` | `com.cryptolab.simulation.application.service` |
| `OrderController` | `com.cryptolab.simulation.api` | `com.cryptolab.simulation.api.controller` |
| `FillAccounting` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.service` |
| `ApiErrors` | `com.cryptolab.simulation.api` | `com.cryptolab.simulation.api.exception` |
| `ManualPreviewRequest` | `com.cryptolab.simulation.api` | `com.cryptolab.simulation.api.dto.request` |
| `PreviewFactory` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.service` |
| `DecimalJsonConfig` | `com.cryptolab.simulation.infrastructure` | `com.cryptolab.simulation.infrastructure.config` |
| `LimitDecision` | `com.cryptolab.simulation.application` | `com.cryptolab.simulation.domain.service` |
| `MarketSource` | `com.cryptolab.marketdata.domain` | `com.cryptolab.marketdata.application.port` |
| `FixtureAdapter` | `com.cryptolab.marketdata.infrastructure` | `com.cryptolab.marketdata.infrastructure.client.fixture` |
| `OutboxPublisher` | `com.cryptolab.marketdata.infrastructure` | `com.cryptolab.marketdata.infrastructure.messaging` |
| `MarketIngress` | `com.cryptolab.simulation.infrastructure` | `com.cryptolab.simulation.infrastructure.messaging` |
| `MarketListener` | `com.cryptolab.simulation.infrastructure` | `com.cryptolab.simulation.infrastructure.messaging` |
| `BinanceMetadataDecoder` | `com.cryptolab.marketdata.infrastructure` | `com.cryptolab.marketdata.infrastructure.mapper` |
| `PublicMarketHttp` | `com.cryptolab.marketdata.infrastructure` | `com.cryptolab.marketdata.infrastructure.client` |
| `SnapshotCache` | `com.cryptolab.marketdata.infrastructure` | `com.cryptolab.marketdata.infrastructure.cache` |
| `RiskDecision` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.service` |
| `RiskPolicyEvaluator` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.service` |
| `FundingBoundary` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.model` |
| `ExitDecision` | `com.cryptolab.simulation.domain` | `com.cryptolab.simulation.domain.service` |
| `DeadlineScheduler` | `com.cryptolab.simulation.application` | `com.cryptolab.simulation.application.service` |
| `LedgerProjection` | `com.cryptolab.reporting.application` | `com.cryptolab.reporting.infrastructure.repository` |
| `ReportMetrics` | `com.cryptolab.reporting.domain` | `com.cryptolab.reporting.domain.service` |
| `RedisLoginLimiter` | `com.cryptolab.identity.infrastructure` | `com.cryptolab.identity.infrastructure.ratelimit` |
| `RouteTable` | `com.cryptolab.apigateway` | `com.cryptolab.apigateway.infrastructure.config` |
| `ProxyController` | `com.cryptolab.apigateway` | `com.cryptolab.apigateway.api.controller` |
| `SseRing` | `com.cryptolab.simulation.infrastructure` | `com.cryptolab.simulation.infrastructure.streaming` |
| `CsvCell` | `com.cryptolab.reporting.domain` | `com.cryptolab.reporting.infrastructure.export` |
| `PredictionResult` | `com.cryptolab.strategy.domain` | `com.cryptolab.strategy.domain.model` |
| `ModelClient` | `com.cryptolab.strategy.application` | `com.cryptolab.strategy.application.port` |
| `FakeModelClient` | `com.cryptolab.strategy.infrastructure` | `com.cryptolab.strategy.infrastructure.client.fixture` |
| `NumericPolicy` | `com.cryptolab.strategy.domain` | `com.cryptolab.strategy.domain.service` |
| `AnalysisFlow` | `com.cryptolab.strategy.application` | `com.cryptolab.strategy.application.service` |
| `HttpModelClient` | `com.cryptolab.strategy.infrastructure` | `com.cryptolab.strategy.infrastructure.client` |
| `TrainingProvider` | `com.cryptolab.strategy.application` | `com.cryptolab.strategy.application.port` |
| `TrainingController` | `com.cryptolab.strategy.api` | `com.cryptolab.strategy.api.controller` |
| `HttpTrainingProvider` | `com.cryptolab.strategy.infrastructure` | `com.cryptolab.strategy.infrastructure.client` |
| `TrainingJsonController` | `com.cryptolab.strategy.api` | `com.cryptolab.strategy.api.controller` |
| `VirtualClock` | `com.cryptolab.simulation.replay` | `com.cryptolab.simulation.application.replay` |
| `ReplayInput` | `com.cryptolab.simulation.replay` | `com.cryptolab.simulation.domain.model.replay` |
| `ReplayRunner` | `com.cryptolab.simulation.replay` | `com.cryptolab.simulation.application.replay` |
| `FidelityReport` | `com.cryptolab.simulation.replay` | `com.cryptolab.simulation.domain.model.replay` |
| `BarAmbiguity` | `com.cryptolab.simulation.replay` | `com.cryptolab.simulation.domain.service.replay` |
| `ArtifactReader` | `com.cryptolab.simulation.replay` | `com.cryptolab.simulation.infrastructure.artifact` |
| `ReplayScope` | `com.cryptolab.simulation.replay` | `com.cryptolab.simulation.application.replay` |
| `ReplayRunnerTest` | `com.cryptolab.simulation.replay` | `com.cryptolab.simulation.application.replay` |

## ACC-01 yeni sınıfları

| Ders | Sınıf | Paket |
|---|---|---|
| B01 | `AccountServiceApplication` | `com.cryptolab.account` |
| B08A | `AccountSummary` | `com.cryptolab.account.domain.model` |
| B08A | `TradeHistory` | `com.cryptolab.account.domain.model` |
| B08A | `AccountFailure` | `com.cryptolab.account.domain.exception` |
| B08A | `AccountReportReader` | `com.cryptolab.account.application.port` |
| B08A | `AccountQueryService` | `com.cryptolab.account.application.service` |
| B08A | `AccountSummaryResponse` | `com.cryptolab.account.api.dto.response` |
| B08A | `TradeHistoryResponse` | `com.cryptolab.account.api.dto.response` |
| B08A | `AccountMapper` | `com.cryptolab.account.api.mapper` |
| B08A | `AccountController` | `com.cryptolab.account.api.controller` |
| B08A | `HttpAccountReportReader` | `com.cryptolab.account.infrastructure.client` |
| B08A | `BoundedBody` | `com.cryptolab.account.infrastructure.client` |
| B08A | `AccountError` | `com.cryptolab.account.api.dto.response` |
| B08A | `AccountErrors` | `com.cryptolab.account.api.exception` |
| B08A | `AccountSecurityConfig` | `com.cryptolab.account.infrastructure.config` |

## DISC-01 sınıfları

| Ders | Sınıf | Paket |
|---|---|---|
| B01A | `DiscoveryServerApplication` | `com.cryptolab.discovery` |
| B01A | `GatewayTargetResolver` | `com.cryptolab.apigateway.infrastructure.client` |
| B01A | `GatewayErrors` | `com.cryptolab.apigateway.api.exception` |

RouteTable serviceId allowlist döndürür; ProxyController resolver kullanır. Account HttpAccountReportReader port yerine discovery ile hedef seçer.
