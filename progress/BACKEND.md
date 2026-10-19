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