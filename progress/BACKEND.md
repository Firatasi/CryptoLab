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


---

## B08 — Reporting, Redis, Gateway ve SSE
- **Tarih:** 2026-10-20
- **Durum:** Tamamlandı (`reporting_db` / `reporting_test_db` şemaları, `LedgerProjection`, `ReportMetrics`, `CsvCell`, `RedisLoginLimiter`, `RouteTable` / `ProxyController` ve `SseRing` doğrulandı)
- **Şartname Sürümü:** 3.0 / **Contract Sürümü:** 1.0.0

### Tamamlanan Alt Maddeler
- **B08.1–B08.3:** `reporting-service` içinde `V1__projections.sql` (`session_owners`, `account_metadata`, `ledger_projection`, `account_projection`, `projection_checkpoints`, `inbox`, `equity_samples`, `trade_summaries`, `notifications`, `report_jobs`) hem `reporting_db` hem de `reporting_test_db` şemalarına uygulandı. `LedgerProjection` ile yalnızca `LedgerTransactionPosted` olaylarında hesap bakiyelerinin güncellenmesi (`PositionClosed` olayında kârın ikinci kez eklenmemesi), mükerrer `eventId` idempotensisi ve `PROJECTION_SEQUENCE_GAP` kontrolü gerçeklendi; `ReportMetrics` ile `winRate`, `profitFactor` ve `maxDrawdown` saf hesapları yazıldı.
- **B08.4:** `identity-service` içinde atomik `INCR` + `EXPIRE` çalışan `login-limit.lua` ve `@Primary RedisLoginLimiter` (Redis erişilemez olduğunda `LocalLoginLimiter` fallback'iyle) devreye alındı.
- **B08.5:** `api-gateway` içinde `RouteTable` (`auth`, `market`, `sim`, `strategy`, `reports`, `account` ve `/.well-known/jwks.json` allowlist'i, `/internal/**` ve path traversal reddi), `GatewayTargetResolver`, `GatewayErrors` ve `ProxyController` (bounded body sınırı ve güvenli başlık filtrelemesi) oluşturuldu.
- **B08.6–B08.7:** `simulation-service` içinde `epoch:sequence` tabanlı `SseRing` (`resync.required` ve heartbeat desteğiyle) ve `reporting-service` içinde formül enjeksiyonunu (`=+-@`, `\t`, `\r`) engelleyen `CsvCell` yazıldı.

### Değişen Dosyalar
- `backend/reporting-service/pom.xml`
- `backend/reporting-service/src/main/resources/application.properties`
- `backend/reporting-service/src/main/resources/db/migration/V1__projections.sql`
- `backend/reporting-service/src/main/java/com/crypto/reportingservice/infrastructure/repository/LedgerProjection.java`
- `backend/reporting-service/src/main/java/com/crypto/reportingservice/domain/service/ReportMetrics.java`
- `backend/reporting-service/src/main/java/com/crypto/reportingservice/infrastructure/export/CsvCell.java`
- `backend/identity-service/pom.xml`
- `backend/identity-service/src/main/resources/login-limit.lua`
- `backend/identity-service/src/main/java/com/crypto/identityservice/infrastructure/ratelimit/RedisLoginLimiter.java`
- `backend/identity-service/src/main/java/com/crypto/identityservice/infrastructure/ratelimit/LocalLoginLimiter.java`
- `backend/api-gateway/src/main/java/com/crypto/apigateway/infrastructure/config/RouteTable.java`
- `backend/api-gateway/src/main/java/com/crypto/apigateway/infrastructure/client/GatewayTargetResolver.java`
- `backend/api-gateway/src/main/java/com/crypto/apigateway/api/exception/GatewayErrors.java`
- `backend/api-gateway/src/main/java/com/crypto/apigateway/api/controller/ProxyController.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/infrastructure/streaming/SseRing.java`
- `backend/api-gateway/src/test/java/com/crypto/apigateway/GatewayB08Test.java`
- `backend/simulation-service/src/test/java/com/crypto/simulationservice/SseRingTest.java`

---

## B08A — Account: Kullanıcı İşlem Geçmişi ve Kâr/Zarar Görünümü
- **Tarih:** 2026-10-20
- **Durum:** Tamamlandı (`account-service` katmanları, `V2__account_scope.sql`, `AccountSummaryQueryRepository` ve `ReportingAccountController` doğrulandı)
- **Şartname Sürümü:** 3.0 + ACC-01 / **Contract Sürümü:** 1.0.0

### Tamamlanan Alt Maddeler
- **B08A.1–B08A.3:** Stateless `account-service` (8086 portu) POM, `application.properties` ve RS256 JWKS tabanlı `AccountSecurityConfig` (issuer, audience, expiry ve UUID `sub` doğrulaması) tamamlandı.
- **B08A.4–B08A.5:** `reporting-service` içinde `V2__account_scope.sql` additive migration'ı, `AccountSummaryQueryRepository` (`LIVE_PAPER`/`USDT` kapsamı, `netRealizedPnl = realizedPnl - totalFees + signedFunding - liquidationFees`, `totalNetPnl = netRealizedPnl + unrealizedPnl`, `insuranceAdjustment` ayrımı, `BUILDING`/`READY` kapsam kontrolü ve HMAC imzalı `(opened_at DESC, position_id DESC)` cursor sayfalama) ve `ReportingAccountController` (`/api/v1/reports/me/account-summary` ve `/api/v1/reports/me/trade-history`) gerçeklendi.
- **B08A.6–B08A.8:** `AccountSummary`, `TradeHistory`, `AccountFailure`, `AccountReportReader`, `AccountQueryService`, `BoundedBody`, `HttpAccountReportReader`, `AccountMapper`, `AccountController` ve `AccountErrors` sınıfları yazıldı; `AccountB08ATest` ve `ReportingB08AndB08ATest` ile doğrulandı.

### Değişen Dosyalar
- `backend/reporting-service/src/main/resources/db/migration/V2__account_scope.sql`
- `backend/reporting-service/src/main/java/com/crypto/reportingservice/infrastructure/repository/AccountSummaryQueryRepository.java`
- `backend/reporting-service/src/main/java/com/crypto/reportingservice/api/controller/ReportingAccountController.java`
- `backend/reporting-service/src/main/java/com/crypto/reportingservice/infrastructure/config/ReportingSecurityConfig.java`
- `backend/reporting-service/src/test/resources/application-test.properties`
- `backend/reporting-service/src/test/java/com/crypto/reportingservice/ReportingServiceApplicationTests.java`
- `backend/reporting-service/src/test/java/com/crypto/reportingservice/ReportingB08AndB08ATest.java`
- `backend/account-service/pom.xml`
- `backend/account-service/src/main/resources/application.properties`
- `backend/account-service/src/main/java/com/crypto/accountservice/domain/model/AccountSummary.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/domain/model/TradeHistory.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/domain/exception/AccountFailure.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/application/port/AccountReportReader.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/application/service/AccountQueryService.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/api/dto/response/AccountSummaryResponse.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/api/dto/response/TradeHistoryResponse.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/api/dto/response/AccountError.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/api/mapper/AccountMapper.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/api/controller/AccountController.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/infrastructure/client/BoundedBody.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/infrastructure/client/HttpAccountReportReader.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/api/exception/AccountErrors.java`
- `backend/account-service/src/main/java/com/crypto/accountservice/infrastructure/config/AccountSecurityConfig.java`
- `backend/account-service/src/test/resources/application-test.properties`
- `backend/account-service/src/test/java/com/crypto/accountservice/AccountServiceApplicationTests.java`
- `backend/account-service/src/test/java/com/crypto/accountservice/AccountB08ATest.java`

### Çalıştırılan Komutlar ve Görülen Çıktı
Komut:
```powershell
$env:DB_PASSWORD='2002'; mvn -pl reporting-service,account-service,api-gateway,identity-service,simulation-service -am test "-Dtest=ReportingB08AndB08ATest,ReportingServiceApplicationTests,AccountB08ATest,AccountServiceApplicationTests,GatewayB08Test,ApiGatewayApplicationTests,IdentityFlowIT,SseRingTest,RiskAndDeadlineIT" "-Dsurefire.failIfNoSpecifiedTests=false"
```
Görülen çıktı:
- `api-gateway`: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` (`SUCCESS`)
- `identity-service`: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` (`SUCCESS`)
- `simulation-service`: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` (`SUCCESS`)
- `reporting-service`: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` (`SUCCESS`)
- `account-service`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` (`SUCCESS`)
- Toplam: `BUILD SUCCESS`

### Fixture ve Gerçek Entegrasyon Sınırı
- `reporting_db` ve `reporting_test_db` şemaları yerel PostgreSQL (`postgres` veritabanı) üzerinde oluşturuldu; `LedgerProjection`, `AccountSummaryQueryRepository` (100/10.05/-1/-20 altın örneği, owner izolasyonu, HMAC cursor sayfalama), `ReportMetrics`, `CsvCell`, `AccountQueryService`, `BoundedBody`, `RouteTable` ve `SseRing` testlerle doğrulandı.
- Canlı Eureka üzerinden çoklu süreç (`account-service` -> `reporting-service` -> `simulation-service` audit manifest bootstrap) uçtan uca akışı sonraki entegrasyon aşamalarında doğrulanacaktır.

---

### Sıradaki Tek Adım
- **Görev:** `B09`
- **Hedef:** Kullanıcının paylaşacağı bir sonraki görev MD'si ile devam etmek.


---

## B09 — Strategy, Piyasa Tarama ve AI Bağlantısı
- **Tarih:** 2026-10-22
- **Durum:** Tamamlandı (`PredictionResult`, `ModelClient`, `FakeModelClient`, `HttpModelClient`, `NumericPolicy`, `AnalysisFlow`, kalıcı `AnalysisWorker`, tekil backfill / `WAITING_DATA`, `ScanService` cursor/partial/cancel, `ExplanationTemplate` TTL koruması ve `AiPreviewService` exact onay hattı doğrulandı; canlı A05 model servisi henüz eğitilmediği için `INTEGRATION_PENDING`)
- **Şartname Sürümü:** 3.0 / **Contract Sürümü:** 1.0.0

### Tamamlanan Alt Maddeler
- **B09.1:** `PredictionResult` değişmez kuralları (`READY` vs `INSUFFICIENT_DATA`/`OUT_OF_DOMAIN`/`STALE_DATA`, sonlu sayısal çıktı ve horizon kontrolü) ve `contracts/fixtures/prediction-result.json` sözleşme uyumu doğrulandı; `ModelClient` portu ve tolerant `JsonMapper` kullanan `HttpModelClient` (`X-Service-Secret`, `X-Request-Id`, `X-Deadline`, 1 MiB yanıt sınırı, redirect reddi) gerçeklendi.
- **B09.2:** Açık etiketli `FakeModelClient` (`FIXTURE_MODEL`, `DEVELOPMENT_CANDIDATE`) ile `QUEUED -> RUNNING -> SUCCEEDED (WAIT)` (0 proposal, 0 order), `READY` tahminlerde `LONG`/`SHORT` öneri üretimi ve teknik model hatalarında `FAILED` (`MODEL_UNAVAILABLE`, `action = null`) ayrımı `JdbcAnalysisJobRepository` ve `AnalysisWorker` üzerinde kuruldu.
- **B09.3:** Eksik veri kapsamında `WAITING_DATA` durumuna geçiş ve `V3__backfill_and_scan_items.sql` altındaki `backfill_requests` tablosu ile `(instrument_id, horizon_seconds, as_of)` başına tekil (single-flight) backfill kaydı; analiz başlangıcında `session_generation`, `market_dataset_id`, `market_snapshot_id`, `model_bundle_id`, `feature_version`, `feature_schema_hash` ve `bundle_release_status` alanlarının sabitlenmesi (pin) ve `FEATURE_SCHEMA_MISMATCH` kapısı doğrulandı.
- **B09.4:** `policy_artifacts` tablosunda saklanan sürümlü `NumericPolicy.Artifact` ile net edge, maksimum volatilite, minimum veri kapsamı ve doğrulanmış `Decimal` referans fiyat / ATR üzerinden `stopLoss`, `takeProfit` ve `suggestedLeverage` niyeti üretildi; miktar/marjin/komisyon otoritesi `Simulation` servisine bırakıldı.
- **B09.5:** `simulation-service` içinde `AiPreviewRequest`, `ProposalReader`, `PreviewFactory.createFromAiProposal`, `PreviewFactory.createFromModifiedProposal` ve `AiPreviewService` gerçeklendi. `AI_PROPOSAL` önizlemesinde zorunlu SL/TP kontrolü (`AI_PROPOSAL_BRACKET_REQUIRED`), `RiskPolicyEvaluator.size(...)` ile özkaynak/kullanılabilir bakiye tabanlı miktar hesaplama, süresi dolmuş öneri (`PLAN_EXPIRED`), generation uyuşmazlığı (`PLAN_REVOKED`), revision uyuşmazlığı (`NOT_FOUND`) ve kullanıcı tarafından değiştirilen önerinin `source = "MANUAL"` + `originProposalId` olarak yeni `planId`/`planHash` üretmesi ve `SubmitOrder` exact onay hattına bağlanması doğrulandı.
- **B09.6:** `ScanJobRepository`, `JdbcScanJobRepository` ve `ScanService` ile kararlı universe snapshot ve deterministik cursor (`cursor:<universeVersion>:<index>`) tabanlı sayfalama (`pageLimit <= 200`), 200+ enstrümanlı evrende çoklu sayfa taraması, bütçe bittiğinde `PARTIAL` + cursor döndürülmesi (tüm piyasa tarandı iddiası yapılmaması) ve `cancel` çağrısında açık pozisyonlara dokunmadan yeni analiz planlamanın durdurulması (`CANCELLED`) doğrulandı.
- **B09.7:** Numeric sonuç ve policy niyeti hazır olduğunda öneri `validUntil` TTL süresinin derhal başlatılması; `ExplanationClient` zaman aşımına uğradığında veya hata verdiğinde TTL süresini uzatmadan yalnız doğrulanmış `reasonCodes` ve `observedFeatureSummary` alanlarını kullanan deterministik `ExplanationTemplate` (`explanationStatus = "TEMPLATE"`) fallback'ine geçilmesi doğrulandı.

### Değişen ve Eklenen Dosyalar
- `backend/strategy-service/src/main/resources/db/migration/V1__strategy.sql`
- `backend/strategy-service/src/main/resources/db/migration/V2__analysis_context.sql`
- `backend/strategy-service/src/main/resources/db/migration/V3__backfill_and_scan_items.sql`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/domain/model/PredictionResult.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/domain/service/NumericPolicy.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/domain/service/ExplanationTemplate.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/port/ModelClient.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/port/SessionContextReader.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/port/MarketCoveragePort.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/port/ExplanationClient.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/port/MarketUniversePort.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/repository/AnalysisJobRepository.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/repository/ScanJobRepository.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/service/AnalysisFlow.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/service/AnalysisJobTransactions.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/service/AnalysisWorker.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/application/service/ScanService.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/repository/JdbcAnalysisJobRepository.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/repository/JdbcScanJobRepository.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/client/HttpModelClient.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/client/fixture/FakeModelClient.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/client/fixture/FixtureSessionContextReader.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/client/fixture/FixtureMarketCoveragePort.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/client/fixture/FixtureExplanationClient.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/client/fixture/FixtureMarketUniversePort.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/config/ModelClientConfig.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/infrastructure/config/StrategyBeansConfig.java`
- `backend/strategy-service/src/main/java/com/crypto/strategyservice/api/controller/StrategyController.java`
- `backend/strategy-service/src/test/java/com/crypto/strategyservice/StrategyFlowTest.java`
- `backend/strategy-service/src/test/java/com/crypto/strategyservice/HttpModelClientTest.java`
- `backend/strategy-service/src/test/java/com/crypto/strategyservice/AnalysisJobRepositoryIT.java`
- `backend/strategy-service/src/test/java/com/crypto/strategyservice/StrategyB09IT.java`
- `backend/strategy-service/src/test/java/com/crypto/strategyservice/StrategyServiceApplicationTests.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/dto/request/AiPreviewRequest.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/api/dto/request/ManualPreviewRequest.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/application/port/ProposalReader.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/application/service/AiPreviewService.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/domain/service/PreviewFactory.java`
- `backend/simulation-service/src/main/java/com/crypto/simulationservice/infrastructure/client/FixtureProposalReader.java`
- `backend/simulation-service/src/test/java/com/crypto/simulationservice/AiProposalPreviewIT.java`

### Çalıştırılan Komutlar ve Görülen Çıktı
Komut:
```powershell
$env:DB_PASSWORD='2002'; $env:STRATEGY_TEST_DB_PASSWORD='2002'; mvn -f backend/pom.xml -pl strategy-service,simulation-service -am test "-Dtest=StrategyFlowTest,HttpModelClientTest,AnalysisJobRepositoryIT,StrategyB09IT,StrategyServiceApplicationTests,AiProposalPreviewIT,ExecutionAndPreviewTest,OrderSubmissionIT" "-Dsurefire.failIfNoSpecifiedTests=false"
```
Görülen çıktı:
- `simulation-service`: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` (`AiProposalPreviewIT`: 3, `ExecutionAndPreviewTest`: 6, `OrderSubmissionIT`: 3) — `SUCCESS`
- `strategy-service`: `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0` (`AnalysisJobRepositoryIT`: 5, `HttpModelClientTest`: 6, `StrategyB09IT`: 6, `StrategyFlowTest`: 11, `StrategyServiceApplicationTests`: 1) — `SUCCESS`
- Toplam: `BUILD SUCCESS`

### Fixture ve Gerçek Entegrasyon Sınırı
- **PASS (Doğrulananlar):** Yerel PostgreSQL üzerinde `strategy_test_db` (`V1__strategy.sql`, `V2__analysis_context.sql`, `V3__backfill_and_scan_items.sql`) ve `simulation_test_db` şemalarında tüm kalıcı job/proposal/scan/preview/approval akışları, `HttpModelClient` yerel HTTP sözleşme testleri ve `FakeModelClient` (`DEVELOPMENT_CANDIDATE` / `FIXTURE_MODEL`) etiketli fixture testleri çalıştırılıp doğrulandı.
- **INTEGRATION_PENDING (Canlı AI / A05 Sınırı):** Gerçek Python AI servisi (`A05`) henüz eğitilmediği ve ayağa kaldırılmadığı için canlı `A05` süreciyle uçtan uca entegrasyon `INTEGRATION_PENDING` olarak kaydedilmiştir; `DEVELOPMENT_CANDIDATE` fixture çıktıları `ACTIVE` üretim modeli kalite onayı (`A09`) sayılmaz.

---

### Sıradaki Tek Adım
- **Görev:** `B10` (veya kullanıcının paylaşacağı sıradaki görev MD'si)
- **Hedef:** Kullanıcının paylaşacağı bir sonraki görev MD'si ile devam etmek.
