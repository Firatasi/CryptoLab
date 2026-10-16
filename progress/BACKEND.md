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