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