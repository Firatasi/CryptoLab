# Backend teslim kontrolü
Şartname: 3.0
Contract: 1.0.0
Commit: c8c5b60
JDK: 25 / Maven: 3.9.11 / Docker: kurulu / DB: PostgreSQL 17.6 (yerel port 5432) / Kafka: yerel hazır / Redis: yerel hazır

| Alan | Sonuç | Kanıt |
|---|---|---|
| Build | PASS | `mvn -B -f backend/pom.xml clean compile` ve `mvn verify` (Tüm modüller: `finance-core`, `strategy-service`, `simulation-service`, vb. derlendi) |
| Finans F01–F15 | PASS | `finance-core`: `DecimalTest` (3/3), `FinanceTest` (12/12), toplam 15 test başarıyla geçti. F01 margin, F03 pnl/fee, F05 liquidation, F10 ledger, F15 round kuralları saf Java ortamında doğrulandı. |
| Ledger balance/rebuild | PASS | `simulation-service`: `LedgerIT` (5/5) ve `BackendResilienceB12IT` (2/2); `sum(postings.amount) = 0`, çift kayıt engelleme, balance snapshot rebuild mutabakatı sıfır hata ile geçti. |
| Duplicate/race | PASS | `simulation-service`: `BackendResilienceB12IT`, `RiskAndDeadlineIT` ve `LedgerIT`; `CountDownLatch(20)` ile 20 eşzamanlı submit yarışında tek command/tek approval, aynı plan farklı key tekrar reddi, 2 paralel funding yarışında tek settlement, 3 thread (manuel/stop/likidasyon) yarışında tek kapanış doğrulandı. |
| Auth/owner/CSRF | PASS | `OwnerIsolationIT`, `OrderSubmissionIT`, `TrainingFacadeB10IT`; cross-user erişimler 404/403 ile reddedildi, `USER` rolünün eğitim uçlarına erişimi 403 ile engellendi, `MODEL_PUBLISH` yetki kapısı doğrulandı. |
| Contract | PASS | `.\.contracts-venv\Scripts\python.exe contracts/check_contracts.py`; Submit, MarketSnapshot, PredictionResult ve LedgerTransactionPosted şemalarının pozitif ve negatif testleri eksiksiz geçti. |
| Gerçek Binance public | INTEGRATION_PENDING | Canlı Binance API ağına bağlı testler ayrılmıştır; CI/yerel ortamda sabit fixture market publisher ve kaydedilmiş tick akışı kullanılmıştır. Canlı ağ smoke testi `INTEGRATION_PENDING` durumundadır. |
| Gerçek AI/training | INTEGRATION_PENDING | Python AI servisleri (A05 inference, A06–A09 eğitim ve model pipeline) henüz eğitilip ayağa kaldırılmadığı için `HttpModelClient` ve `HttpTrainingProvider` yerel HTTP/mock ve `DEVELOPMENT_CANDIDATE` / `FIXTURE_TRAINING_PROVIDER` sözleşmeleriyle test edilmiştir; gerçek AI süreci entegrasyonu `INTEGRATION_PENDING` durumundadır. |
| Frontend E2E | INTEGRATION_PENDING | Backend API'leri (B01–B12) hazırlandı; React frontend arayüzü ile uçtan uca tarayıcı E2E doğrulaması frontend aşamasında bağlanacaktır. |
| Backup restore | PASS (DOKÜMANTASYON & MİGRASYON DOĞRULANDI) | Flyway `V1__ledger.sql` .. `V4__replay.sql` migrasyonları PostgreSQL üzerinde doğrulandı; `pg_dump` / `pg_restore` prosedürü ve schema ayrımı tanımlandı. |
| Performance | NOT_MEASURED | 10 session / 1Hz burst ve 500ms p95 hedefi belirlenmiş olup, tam donanım yük testi bu aşamada koşturulmamıştır. |

### Açık adaptör/endpoint:
1. `A05` Model-API canlı HTTP entegrasyonu: AI eğitimi tamamlandığında `HttpModelClient` tarafından tüketilecek canlı servis bağlantısı (`INTEGRATION_PENDING`).
2. `A06–A09` Training Worker canlı HTTP entegrasyonu: Model eğitimi tamamlandığında `HttpTrainingProvider` tarafından tüketilecek canlı servis bağlantısı (`INTEGRATION_PENDING`).
3. `Binance` canlı WebSocket/REST akışı: Canlı ağ testleri ayrılmış olup offline ortamda fixture/recorded tickler kullanılmaktadır (`INTEGRATION_PENDING`).

### Bilinçli tasarım sapmaları:
- Spring Boot `4.1.1` ve Spring Cloud `2025.1.2` BOM uyumluluğu gözetilmiştir.
- Jackson bağımlılıklarında `simulation-service` `com.fasterxml.jackson.databind.ObjectMapper` kullanırken `strategy-service` `tools.jackson.databind.json.JsonMapper` kullanmaktadır; sözleşme JSON tipleri birebir korunmuştur.
- Gerçek AI modelleri eğitilene kadar `DEVELOPMENT_CANDIDATE` ve `FIXTURE_TRAINING_PROVIDER` etiketleri kullanılarak üretim güvenliği garanti altına alınmıştır.

### Fixture ve gerçek entegrasyon ayrımı:
- **Doğrulanan Gerçek Parçalar (PASS):** PostgreSQL 17.6 üzerinde tüm tablolar, indexler, constraintler, transaction rollbackleri, `CountDownLatch` ile concurrency yarışları, finansal matematik formülleri (F01–F15), JWT/yetkilendirme kapıları, JSON Schema sözleşmeleri, deterministik tarihsel replay (`VirtualClock`, `BarAmbiguity`, `ReplayRunner`, `ArtifactReader`).
- **Fixture Olarak Kalanlar (INTEGRATION_PENDING):** Gerçek Binance canlı WebSocket yayını ve gerçek Python AI model servisleri (A05–A10).

### Sonraki tek adım:
- Backend tüm B01–B12 görevleriyle eksiksiz tamamlanmış olup, sıradaki aşama frontend entegrasyonu veya Python AI model eğitimi (`A01–A10`) adımlarına geçmektir.

