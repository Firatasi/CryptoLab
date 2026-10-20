## B01 — Kurulum ve Sözleşmeler
Tarih: 2026-10-16
Durum: Tamamlandı

- Java 21, Maven, Docker ve Python ortamları kontrol edildi.
- Ana Maven projesi ve 10 modül verify aşamasını başarıyla tamamladı.
- finance-core bağımlılık ağacında Spring, JPA ve Kafka bulunmadığı doğrulandı.
- finance-core ve test-support kütüphane modüllerindeki gereksiz
  Spring Boot başlangıç sınıfları kaldırıldı.
- API, application, domain ve infrastructure katmanlarının
  sorumlulukları mimari notlara kaydedildi.
- SubmitOrder, MarketSnapshot, PredictionResult ve
  LedgerTransactionPosted şemaları ve örnek verileri oluşturuldu.
- Sözleşmelerin mevcut 16 kontrolü başarıyla geçti.
- Ledger örneğinin tek para birimli posting toplamı Decimal ile
  kontrol edildi ve sıfır bulundu.
- Sözleşme sürümü 1.0.0 ve sonraki şemaların görev sahipleri
  contracts/README.md dosyasına kaydedildi.
- Identity servisi 8181 portunda kontrol edildi, ardından
  8081 portuna döndürülerek health durumunun UP olduğu doğrulandı.

### Doğrulama sınırı
Bu sonuçlar mevcut derleme, başlangıç testleri ve sözleşme
örneklerinin kontrollerini kapsar. İş mantığı ve servisler arası
uçtan uca senaryolar henüz doğrulanmış değildir.

## B02 — Saf Java Finans Çekirdeği
- **Tarih:** 2026-10-17
- **Durum:** Tamamlandı
- **Şartname Sürümü:** 3.0 / **Contract Sürümü:** 1.0.0

### Tamamlanan Adımlar
- `Decimal` sınıfı ile string tabanlı `BigDecimal` dönüşümü, `NUMERIC(38,18)` sınır doğrulaması ve 8 hane `HALF_EVEN` nakit yuvarlaması eklendi.
- `Money`, `Quantity`, `PositionSide` ve `MaintenanceTier` değer nesneleri (value objects) oluşturuldu.
- `Finance` sınıfında pozisyon açılışı (`open`), komisyon dahil maksimum miktar (`allInQuantity`), Long/Short `pnl` ve `fee` fonksiyonları yazıldı.
- Miktar step-floor (`floorStep`) ve slippage dahil adverse tick yuvarlaması (`marketPrice`) gerçeklendi.
- Kademeli sürdürme teminatı (`maintenance`), likidasyon durumu (`liquidates`) ve Long/Short likidasyon fiyatı (`liquidation`) hesaplamaları tamamlandı.
- `AVAILABLE_FIRST` kuralına göre `funding` hesaplaması ve tam/kısmi kapanış (`close`), negatif residual (`insurance`) ve `forceFullClose` mantığı eklendi.
- `FinanceTest` ile F01–F15 altın senaryoları ve sınır/geçersiz girdi testleri (`mvn -f backend/pom.xml -pl finance-core test`) başarıyla doğrulandı.

### Doğrulama Sınırı
Bu sonuçlar `finance-core` modülündeki saf Java hesaplamalarını ve birim testlerini kapsar. Veritabanı saklama katmanı, çift kayıtlı ledger yazımı ve servis entegrasyonları henüz doğrulanmış değildir.

---

### Sıradaki Tek Adım
- **Görev:** `B03` (veya henüz tamamlanmadıysa `B01A — Eureka ve Servis Keşfi`)
- **Hedef:** Hesap sonuçlarını çift kayıtlı muhasebe (ledger) kurallarıyla birleştirmek.


## B03 — PostgreSQL, ledger ve kalıcı session

Durum: TAMAMLANDI
Tarih: 2026-10-17

### Yerel kurulum
- PostgreSQL, Docker kullanılmadan yerel çalıştırıldı.
- Veritabanı: postgres
- Uygulama şeması: simulation_db
- Test şeması: simulation_test_db
- JDBC ve Flyway aynı hedef şemalara yönlendirildi.
- V1 ledger migration'ı uygulandı.
- Uygulanmış V1 dosyası değiştirilmedi.
- Yerel kurulumda ayrı veritabanları yerine ayrı şemalar kullanıldı.

### Eklenen / düzenlenen dosyalar
- simulation-service/pom.xml
- src/main/resources/application.properties
- src/main/resources/db/migration/V1__ledger.sql
- domain/model/Journal.java
- application/repository/LedgerRepository.java
- application/repository/SessionRepository.java
- infrastructure/repository/JdbcLedgerStore.java
- infrastructure/repository/JdbcSessionRepository.java
- infrastructure/config/EngineConfig.java
- application/service/CreateSession.java
- src/test/resources/application-test.properties
- src/test/java/com/crypto/simulationservice/LedgerIT.java

Java ana kaynak yolları:
backend/simulation-service/src/main/java/com/crypto/simulationservice/

### Doğrulama
Komut:
mvn -pl simulation-service -am test "-Dtest=LedgerIT" "-Dsurefire.failIfNoSpecifiedTests=false"

Görülen sonuç:
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Test ortamı: Gerçek yerel PostgreSQL üzerinde üretilmiş test verileri.

Geçen testler:
- Başlangıç sermayesi ve dengeli posting kayıtları.
- Aynı finans hareketinin yalnızca bir kez uygulanması.
- Hata durumunda transaction rollback.
- Aynı businessKey ile farklı içeriğin reddedilmesi.
- Eşzamanlı iki harcamadan yalnızca birinin başarılı olması.

Manuel kontroller:
- Yeniden başlatma sonrası aynı session ve bakiyeler korundu.
- Ledger'den hesaplanan bakiyeler snapshot ile eşleşti.
- Inbox/outbox ve motor girdisi tabloları doğrulandı.
- Ayrı bağlantılarda aynı session kilidi ikinci işlemi bekletti.
- Farklı session kilidi beklemeden alındı.
- İlk bağlantının commit'i sonrası bekleyen işlem devam etti.

Kafka yayıncısı B06 kapsamındadır; burada eklenmedi.
Sıradaki adım: Kullanıcının vereceği sonraki MD.


## B04 — Identity, JWT ve Kaynak Sahipliği
- **Tarih:** 2026-10-19
- **Durum:** Tamamlandı
- **Şartname Sürümü:** 3.0 / **Contract Sürümü:** 1.0.0

### Tamamlanan Adımlar
- `identity_db` veritabanı izolasyonu sağlandı ve `V1__identity.sql` migration dosyası ile kullanıcı, rol, yetki, refresh ailesi ve idempotency tabloları oluşturuldu.
- `Crypto`, `TokenConfig` ve `AccessTokens` ile RS256 JWT üretimi, JWKS (`/.well-known/jwks.json`) yayını ve BCrypt parola doğrulaması kuruldu.
- `IdentityService` ve `JdbcIdentityRepository` üzerinde advisory lock destekli idempotent kayıt, timing-safe login, refresh token rotasyonu ve replay durumunda tüm aileyi iptal eden (`revokeFamily`) akış gerçeklendi.
- `AuthController` üzerinde HttpOnly + `SameSite=Strict` refresh cookie yönetimi ile `Origin` ve `X-CSRF` doğrulamaları eklendi.
- `LocalLoginLimiter` ile bellek korumalı yerel giriş hız sınırlandırıcı devreye alındı.
- `SecurityConfig` ile `issuer` ve `audience` doğrulaması, rol/yetki dönüşümü ve `/internal/**` erişim engeli yapılandırıldı.
- Kayıt, giriş, 429 rate limit, bozuk JWT, refresh replay, CSRF ve kullanıcılar arası kaynak izolasyonu testleri doğrulandı.

### Doğrulama Sınırı
Bu sonuçlar `identity-service` kimlik akışlarını, RS256 JWT doğrulamasını ve yerel rate limit adaptörünü kapsar. Redis tabanlı dağıtık rate limiter, Gateway yönlendirmesi ve servisler arası iç kimlik doğrulama sonraki görevlerde tamamlanacaktır.

---

### Sıradaki Tek Adım
- **Görev:** `B05` (veya henüz uygulanmadıysa `B01A — Eureka ve Servis Keşfi`)
- **Hedef:** Doğrulanmış kullanıcı kimliğiyle simülasyon emir ve pozisyon yaşam döngüsünü bağlamak.

---

## B05 — Önizleme, Onay ve Manuel Sanal İşlem
- **Tarih:** 2026-10-19
- **Durum:** Tamamlandı (Sözleşme, domain hesapları, plan hash, PostgreSQL emir kabulü ve MockMvc doğrulandı; canlı market/worker adaptörleri B06/B07 kapsamındadır)
- **Şartname Sürümü:** 3.0 / **Contract Sürümü:** 1.0.0

### Tamamlanan Alt Maddeler
- **B05.1:** `SubmitOrderRequest`, `ManualPreviewRequest`, `AcceptedOrderResponse` ve `ApiError` şemaları kuruldu; emir gönderim gövdesi yalnız `planId`, `revision` ve `planHash` alanlarını kabul edecek şekilde sınırlandırıldı.
- **B05.2:** `PreviewFactory` ile manuel önizlemeden miktar (`quantity`), teminat (`marginRequired`), komisyon (`openFeeEstimate`), tahmini likidasyon fiyatı ve profil bazlı varsayılan stop/hedef yüzdeleri üretildi (`RISK_DISTANCE_REQUIRED` kontrolü dahil).
- **B05.3:** Immutable `ExecutionPlan` ve `PlanHash` ile canonical JSON byte dizisi ve SHA-256 plan özeti gerçeklendi; `contracts/fixtures/execution-plan.canonical.json` altın fixture dosyası oluşturuldu.
- **B05.4:** `V2__orders.sql` migration'ı (`execution_plans`, `plan_approvals`, `order_commands`, `orders`, `positions`, `fills`) uygulandı; `SubmitOrder` ve `JdbcOrderSubmissionRepository` üzerinde session kilidi altında sahiplik (`404`), plan hash (`409`), TTL (`409`), pozisyon nesli (`409`), tekil plan kullanımı (`409`) ve `Idempotency-Key` tekrar oynatımı (`202` aynı `commandId`) tek transaction içinde bağlandı.
- **B05.5:** `ExecutionGate` (bayat veri, kalite, enstrüman sürümü, risk politikası sürümü, fiyat bandı, komisyon ve slippage kontrolleri) ve `LimitDecision` (LIMIT emir fiyat geçişi ve GTD vade iptali) domain servisleri yazıldı.
- **B05.6:** `FillAccounting` ile açılış (`OPEN`), tam kapanış (`CLOSE`, brüt kâr/zarar, kapanış komisyonu ve teminat iadesi) ve LIMIT teminat bloke/serbest bırakma (`RESERVE`/`RELEASE`) için sıfır toplamlı (`Journal`) muhasebe hareketleri gerçeklendi.
- **B05.7:** `OrderController`, `OrderMapper`, `ApiErrors` ve `DecimalJsonConfig` ile HTTP katmanı bağlandı; birim testleri (`ExecutionAndPreviewTest`) ve gerçek PostgreSQL entegrasyon testleri (`OrderSubmissionIT`) çalıştırıldı.

### Değişen Dosyalar
- `contracts/fixtures/execution-plan.canonical.json`
- `backend/simulation-service/src/main/resources/db/migration/V2__orders.sql`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/model/ExecutionPlan.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/application/service/PlanHash.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/service/ExecutionGate.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/service/FillAccounting.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/service/PreviewFactory.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/service/LimitDecision.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/exception/BusinessException.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/application/command/SubmitOrderCommand.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/dto/request/SubmitOrderRequest.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/dto/request/ManualPreviewRequest.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/dto/response/AcceptedOrderResponse.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/dto/response/ApiError.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/application/repository/OrderSubmissionRepository.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/infrastructure/repository/JdbcOrderSubmissionRepository.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/application/service/SubmitOrder.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/mapper/OrderMapper.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/controller/OrderController.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/exception/ApiErrors.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/infrastructure/config/DecimalJsonConfig.java`
- `backend/simulation-service/src/test/java/com/crypto/simulationservice/ExecutionAndPreviewTest.java`
- `backend/simulation-service/src/test/java/com/crypto/simulationservice/OrderSubmissionIT.java`

### Çalıştırılan Komutlar ve Görülen Çıktı
Komut 1:
```powershell
$env:DB_PASSWORD='2002'; mvn -pl simulation-service -am test "-Dtest=ExecutionAndPreviewTest,OrderSubmissionIT,LedgerIT,OwnerIsolationIT" "-Dsurefire.failIfNoSpecifiedTests=false"
```
Görülen çıktı:
- `ExecutionAndPreviewTest`: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`
- `LedgerIT`: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`
- `OrderSubmissionIT`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- `OwnerIsolationIT`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- Toplam: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`

Komut 2:
```powershell
.\.contracts-venv\Scripts\python.exe contracts\check_contracts.py
```
Görülen çıktı:
- `Dört sözleşmenin mevcut kontrolleri tamamlandı.` (Tüm pozitif/negatif şema kontrolleri `PASS`)

### Doğrulama ve Entegrasyon Sınırı
- `PlanHash` canonical byte çıktısı, `PreviewFactory`, `ExecutionGate`, `LimitDecision` ve `FillAccounting` birim testleriyle; `SubmitOrder`, `JdbcOrderSubmissionRepository`, `OrderMapper`, `OrderController` ve `ApiErrors` ise gerçek PostgreSQL (`simulation_test_db`) üzerinde `OrderSubmissionIT` ile doğrulandı.
- B05 kapsamında piyasa verisi ve önizleme girdileri deterministik test/fixture verisiyle doğrulanmıştır. Gerçek piyasa akışı (`market-data-service`), Kafka outbox/inbox yayını ve arka plan emir/mum işleyici döngüsü (`B06` ve `B07`) henüz bağlanmamıştır.

---

### Sıradaki Tek Adım
- **Görev:** `B06`
- **Hedef:** Piyasa verisi adaptörü, Kafka olay yayını ve asenkron işleyici akışlarını bağlamak.


---

## B06 — Dinamik Piyasa Servisi, Kafka ve Veri Önbelleği
- **Tarih:** 2026-10-20
- **Durum:** Çekirdek port/adaptör, PostgreSQL şemaları (`market_db` ve `market_test_db`), outbox/ingress, fixture/normalizer ve Redis önbellek katmanı tamamlandı (`BUILD SUCCESS`); canlı sağlayıcı WebSocket/REST ağ smoke testi ve arka plan backfill/export işleyicileri B06 tamamlama sınırında tutuldu
- **Şartname Sürümü:** 3.0 / **Contract Sürümü:** 1.0.0

### Tamamlanan Alt Maddeler
- **B06.1:** `market-data-service`, `simulation-service` ve `reporting-service` modüllerine `spring-kafka`; `market-data-service` modülüne `spring-boot-starter-data-redis`, `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `flyway-database-postgresql` ve `postgresql` bağımlılıkları eklendi. `infra/compose.yaml` (`kafka:3.9.1` ve `redis:7.4.2`) ve `application.properties` dosyaları yapılandırıldı; `MarketSource` portu, `FixtureAdapter` ve `V1__market.sql` oluşturuldu.
- **B06.2:** `market-data-service` içinde `FOR UPDATE SKIP LOCKED` ile atomik claim yapan `OutboxPublisher` ve `MarketConfig`; `simulation-service` içinde tek transaction'da `market_inputs` + aktif oturumlara (`OPEN`/`LIQUIDATING` pozisyonlar ve `NEW`/`EXIT_PENDING_DATA` emirler) `pending_engine_inputs` fan-out yapan `MarketIngress`, DB commit sonrası manuel `ack.acknowledge()` çağıran `MarketListener` ve `CommonContainerStoppingErrorHandler` yapılandırması gerçeklendi.
- **B06.3–B06.5:** Filtreleri dizin yerine `filterType` (`PRICE_FILTER`, `LOT_SIZE`, `MIN_NOTIONAL`) üzerinden çözen ve `contractType:onboardDate` kimliğini koruyan `BinanceMetadataDecoder` ile yalnızca `https`, sabit host, `Redirect.NEVER` ve 4 MB boyut sınırını zorlayan `PublicMarketHttp` yazıldı.
- **B06.6:** `mark` için 5 sn, `quote` için 2 sn TTL uygulayan ve Redis erişilemez olduğunda (`DataAccessException`) hata fırlatmadan `Optional.empty()` dönen `SnapshotCache` yazıldı.

### Değişen Dosyalar
- `infra/compose.yaml`
- `backend/market-data-service/pom.xml`
- `backend/simulation-service/pom.xml`
- `backend/reporting-service/pom.xml`
- `backend/market-data-service/src/main/resources/application.properties`
- `backend/simulation-service/src/main/resources/application.properties`
- `backend/market-data-service/src/main/resources/db/migration/V1__market.sql`
- `backend/market-data-service/src/main/java/com/crypto/marketdataservice/MarketDataServiceApplication.java`
- `backend/market-data-service/src/main/java/com/crypto/marketdataservice/application/port/MarketSource.java`
- `backend/market-data-service/src/main/java/com/crypto/marketdataservice/infrastructure/client/fixture/FixtureAdapter.java`
- `backend/market-data-service/src/main/java/com/crypto/marketdataservice/infrastructure/messaging/OutboxPublisher.java`
- `backend/market-data-service/src/main/java/com/crypto/marketdataservice/infrastructure/mapper/BinanceMetadataDecoder.java`
- `backend/market-data-service/src/main/java/com/crypto/marketdataservice/infrastructure/client/PublicMarketHttp.java`
- `backend/market-data-service/src/main/java/com/crypto/marketdataservice/infrastructure/cache/SnapshotCache.java`
- `backend/market-data-service/src/main/java/com/crypto/marketdataservice/infrastructure/config/MarketConfig.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/infrastructure/messaging/MarketIngress.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/infrastructure/messaging/MarketListener.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/infrastructure/config/EngineConfig.java`
- `backend/market-data-service/src/test/resources/application-test.properties`
- `backend/simulation-service/src/test/resources/application-test.properties`
- `backend/market-data-service/src/test/java/com/crypto/marketdataservice/MarketDataServiceApplicationTests.java`
- `backend/market-data-service/src/test/java/com/crypto/marketdataservice/MarketDataB06Test.java`
- `backend/simulation-service/src/test/java/com/crypto/simulationservice/MarketIngressIT.java`

### Çalıştırılan Komutlar ve Görülen Çıktı
Komut:
```powershell
$env:DB_PASSWORD='2002'; mvn -pl market-data-service,simulation-service,reporting-service -am test "-Dtest=MarketDataB06Test,MarketDataServiceApplicationTests,MarketIngressIT,ExecutionAndPreviewTest,OrderSubmissionIT,LedgerIT,OwnerIsolationIT" "-Dsurefire.failIfNoSpecifiedTests=false"
```
Görülen çıktı:
- `market-data-service`: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` (`MarketDataB06Test`: 6, `MarketDataServiceApplicationTests`: 1)
- `simulation-service`: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` (`MarketIngressIT`: 2, `ExecutionAndPreviewTest`: 6, `LedgerIT`: 5, `OrderSubmissionIT`: 3, `OwnerIsolationIT`: 3)
- `reporting-service`: `SUCCESS`
- Toplam: `BUILD SUCCESS`

### Fixture ve Gerçek Entegrasyon Sınırı
- **PASS (Doğrulananlar):** PostgreSQL `postgres` veritabanı altında hem `market_db` hem de `market_test_db` şemalarında `V1__market.sql` tablolarının oluşturulması; `FixtureAdapter` kontrollü tick üretimi; `BinanceMetadataDecoder` filtre çözümlemesi; `PublicMarketHttp` HTTPS ve host doğrulaması; `SnapshotCache` TTL ve Redis kapalı (`DataAccessException`) fallback davranışı; `OutboxPublisher` atomik PostgreSQL claim/publish güncellemesi; `MarketIngress` ve `MarketListener` üzerinde mükerrer olayın (`duplicate eventId`) tek `market_inputs` ve yalnız aktif oturumlara tek `pending_engine_inputs` üretmesi ve bozuk payload'da commit/ack yapılmaması.
- **Kapsam Sınırı (Canlı Ağ / Worker):** Canlı Binance WebSocket/REST ağ smoke testi (BLOCKED / dış ağ bağımlı) ve tam kapsamlı backfill/export worker döngüleri çalıştırılmadığı için PASS olarak işaretlenmemiştir.

---

### Sıradaki Tek Adım
- **Görev:** `B07`
- **Hedef:** Kullanıcının paylaşacağı bir sonraki görev MD'si ile simülasyon motoru / strateji akışlarını ilerletmek.


---

## B07 — Risk Yaşam Döngüsü, Funding ve Koruyucu Çıkışlar
- **Tarih:** 2026-10-20
- **Durum:** Tamamlandı (`V3__risk.sql`, risk/çıkış karar servisleri, funding boundary modeli, deadline zamanlayıcısı ve PostgreSQL yarış/kabul testleri doğrulandı)
- **Şartname Sürümü:** 3.0 / **Contract Sürümü:** 1.0.0

### Tamamlanan Alt Maddeler
- **B07.1:** `RiskDecision` ile mark fiyatında likidasyon (`Finance.liquidates`), Stop-Loss ve Take-Profit kontrolleri (aynı adımda likidasyon önceliğiyle) ve `RiskPolicyEvaluator` ile 4 kısıtlı (`qMargin`, `qCash`, `qNotional`, `riskBudget/distance`) pozisyon boyutlandırma + günlük kayıp durdurma (`dailyHalt`) gerçeklendi.
- **B07.2:** `V3__risk.sql` ile `funding_boundaries`, `funding_settlements`, `due_actions`, `daily_risk` ve `halt_reasons` tabloları hem `simulation_db` hem de `simulation_test_db` şemalarında oluşturuldu; `FundingBoundary` ile boundary uygunluğu (`eligible`) ve `AVAILABLE_FIRST` / geç settlement dağıtımı (`allocate`) yazıldı.
- **B07.3–B07.5:** `ExitDecision` ile taze olmayan quote'ta `EXIT_PENDING_DATA` bekletmesi, tahta likidite katılım sınırı (`LIQUIDITY_LIMIT`), negatif kısmi kapanışta tam kapanışa zorlama (`forceFullClose`) ve çıkış nedenleri (`LIQUIDATION`, `INSOLVENT_EXIT`, `FORCED_RISK_CLOSE`, `USER_CLOSE`) gerçeklendi.
- **B07.6–B07.7:** `DeadlineRepository`, `JdbcDeadlineRepository` ve `DeadlineScheduler` ile `opened_at + horizon` tabanlı idempotent vade planlama (`schedule`) ve atomik `claim` akışı kuruldu; 3 iş parçacıklı (`USER_CLOSE`, `STOP_LOSS`, `LIQUIDATION`) PostgreSQL yarış testiyle tek pozisyon kapanışı ve tek `journal_transactions` kaydı doğrulandı.

### Değişen Dosyalar
- `backend/simulation-service/src/main/resources/db/migration/V3__risk.sql`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/service/RiskDecision.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/service/RiskPolicyEvaluator.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/model/FundingBoundary.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/service/ExitDecision.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/application/repository/DeadlineRepository.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/infrastructure/repository/JdbcDeadlineRepository.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/application/service/DeadlineScheduler.java`
- `backend/simulation-service/src/test/java/com/crypto/simulationservice/RiskAndDeadlineIT.java`

### Çalıştırılan Komutlar ve Görülen Çıktı
Komut:
```powershell
$env:DB_PASSWORD='2002'; mvn -pl simulation-service -am test "-Dtest=RiskAndDeadlineIT,MarketIngressIT,ExecutionAndPreviewTest,OrderSubmissionIT,LedgerIT,OwnerIsolationIT" "-Dsurefire.failIfNoSpecifiedTests=false"
```
Görülen çıktı:
- `ExecutionAndPreviewTest`: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`
- `LedgerIT`: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`
- `MarketIngressIT`: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`
- `OrderSubmissionIT`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- `OwnerIsolationIT`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- `RiskAndDeadlineIT`: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`
- Toplam: `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`

### Fixture ve Gerçek Entegrasyon Sınırı
- `RiskDecision`, `RiskPolicyEvaluator`, `FundingBoundary`, `ExitDecision`, `DeadlineScheduler`, `JdbcDeadlineRepository` ve 3-thread yarış testi gerçek PostgreSQL (`simulation_test_db` ve `simulation_db`) üzerinde doğrulandı.
- Canlı piyasa akışıyla tetiklenen arka plan worker döngüsü ve B08 raporlama/projeksiyon entegrasyonu bir sonraki görev kapsamındadır.

---

### Sıradaki Tek Adım
- **Görev:** `B08`
- **Hedef:** Kullanıcının paylaşacağı bir sonraki görev MD'si ile raporlama ve okuma modeli akışlarını tamamlamak.
