# CryptoLab — Katman sorumlulukları

## B01.3

- api: HTTP giriş/çıkışı, request/response DTO'ları ve biçim doğrulaması.
- application: Kullanım senaryoları, işlem sırası ve transaction sınırı.
- domain: Framework'ten bağımsız iş modelleri ve kuralları.
- infrastructure: Veritabanı, mesajlaşma, önbellek ve dış HTTP adaptörleri.

## Bağımlılık kuralları

- api, application katmanını kullanır.
- application, domain modellerini ve ihtiyaç duyduğu arayüzleri kullanır.
- infrastructure, application arayüzlerini uygular.
- domain, api veya infrastructure sınıflarına bağımlı olmaz.
- Servislerin entity ve repository sınıfları birbirleriyle paylaşılmaz.
- finance-core; Spring, veritabanı, Kafka veya HTTP bağımlılığı içermez.
- Henüz ihtiyaç duyulmayan sınıflar sırf paketleri doldurmak için oluşturulmaz.