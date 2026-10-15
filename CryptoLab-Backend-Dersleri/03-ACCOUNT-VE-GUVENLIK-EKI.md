# ACC-01 — Account ve güvenlik mimarisi eki

Revizyon 4 · 14.10.2026

Bu ek kullanıcının Account, geçmiş işlemler, toplam kâr/zarar ve application.properties talebini uygular. `kaynaklar/` içindeki dört dosya orijinal Şartname 3.0 snapshot'ıdır; değişmeden korunur. Kaynaklardaki eski modül/süreç listesi yerine ACC-01 ve DISC-01 ile genişletilmiş aşağıdaki liste geçerlidir. Diğer finans, veri sahipliği, onay ve güvenlik kuralları korunur. Mevcut endpoint/event sözleşmeleri 1.0.0 aynı kalır; yeni Account okuma endpoint'leri ayrı 1.0.0 ek sözleşmesidir.

## Etkin modüller

| Modül | Süreç/port | Veri sahipliği |
|---|---|---|
| discovery-server | 8761 | Eureka registry; iş DB’si yok |
| finance-core | Kütüphane | Saf hesap, DB yok |
| identity-service | 8081 | identity_db: kullanıcı/rol/parola/refresh |
| market-data-service | 8082 | market_db: katalog/piyasa/coverage |
| simulation-service | 8083 | simulation_db: session/wallet/order/position/ledger/risk |
| strategy-service | 8084 | strategy_db: analysis/proposal/training facade |
| reporting-service | 8085 | reporting_db: kalıcı geçmiş, read model ve raporlar |
| account-service | 8086 | Stateless kullanıcı okuma API'si; bağımsız finans DB'si yok |
| api-gateway | 8080 | Route/CORS/limit; iş DB'si yok |
| test-support | Test kütüphanesi | Test fixture'ları |

On Maven modülü, sekiz çalışan süreç. Model/training DB AI paketinde kalır. Yeni Account servisi mevcut Identity'yi yeniden adlandırmaz; auth endpoint'leri taşınmaz.

## Tüm paketlere uygulanan kararlar

- Her iş servisinde api/application/domain/infrastructure ayrımı sürer. DTO, mapper, service, repository port/adaptör, config ve exception doğru katman altında tutulur.
- Interface dış sınır/değiştirilebilir davranış için açılır; her sınıfa mekanik ServiceImpl veya gereksiz repository eklenmez.
- Account stateless olduğu için kalıcılık repository'si Reporting'dedir. Account başka servisin DB/entity/repository'sine erişmez.
- JWT yalnız Identity'de imzalanır. Gateway ve downstream servislerde imza/izinli alg/iss/aud/exp/nbf/subject doğrulanır. Owner her sorguda doğrulanmış principal'dan gelir.
- Wallet/risk onayı tek Simulation transaction'ında kalır. Reporting/Account sonuçları risk hesabının otoritesi değildir.
- Geçmiş silinmez; session reset yeni session demektir. Reporting kaydı rebuild edilebilir read model'dir; ledger audit kaynağı Simulation'dır.
- Toplam net gerçekleşen = realized − trade fees + signed funding − liquidation fees. Açık PnL eklenince totalNetPnl olur; insurance ayrı kalır. Initial deposit kâr değildir.
- LIVE_PAPER/USDT, replay ve başka para birimleriyle karıştırılmaz. Eksik kapsam BUILDING, eski tamamlanmış veri STALE; erişilemeyen servis 503 olur. Sahte sıfır yok.
- Token/header/parola loglanmaz. Account'ta cache/shared state'e user bearer konmaz. Hesap cevabında Cache-Control: no-store kullanılır.

## Properties standardı

Bütün Spring servis ayarları `src/main/resources/application.properties` dosyasındadır. Profil gerekiyorsa `application-dev.properties` / `application-test.properties` kullanılır. Environment placeholder değerleri korunur. YAML'daki hiyerarşi noktalı anahtarlara açılmıştır; listeler gerektiğinde `[0]` indisleriyle yazılır. Spring application YAML kopyası bırakılmaz.

Docker Compose `compose.yaml` ve GitHub Actions workflow `.yml` kendi araçlarının formatıdır; application.properties'e çevrilmez. Kubernetes eklenirse onun manifestleri de Spring uygulama config'i değildir. Bu istisna uygulama ayarlarını yeniden YAML'a taşımak değildir.

## Ders sırası ve kapsam

B01 → B01A → B02–B08 → **B08A** → B09–B12. Eski 84 görev kimliği korunur; B08A’da 8, B01A’da 8 ek görev vardır. Toplam 14 ders dosyası. B01 Account iskeletini, B04 auth sınırını, B08 gateway/reporting bağlantısını, B08A account kodlarını, B12 entegrasyon kabulünü kapsar.

Bu MD paketi mevcut proje reposuna otomatik kod uygulamaz. Örneklerin önceki Boot 3.5/Jackson 2 tabanı hâlâ Boot 4.1.1 projesine doğrulanmış göç değildir; mevcut POM sürümünü düşürme. Kalan adapter/endpoint/gerçek entegrasyon işleri DOGRULAMA ve başlangıç kılavuzunda açık tutulur.

## Resmi kaynak

[Spring Security — JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html): imza, issuer/timestamp doğrulaması, audience kontrolü ve doğrudan JWKS yapılandırması. Servis/veri sahipliği tablosu projenin bu ihtiyaç için mimari kararıdır; her projeye zorunlu tek paket düzeni iddiası değildir.

## DISC-01 ile bağlantı

Account Reporting’e port sabitlemez; serviceId üzerinden LoadBalancer kullanır. Eureka JWT üretmez ve finans tutmaz. Issuer/JWKS güven ayarları sabit kalır. Localhost başlangıcı ve hata politikası B01A’dadır.
