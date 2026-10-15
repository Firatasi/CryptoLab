# Revizyon 4 — Doğrulama raporu

14.10.2026 · DISC-01 / ACC-01 · Öğretici Markdown paketi; çalışan backend deposu teslimi değildir.

## Yapılan değişiklikler

- discovery-server (8761), B01A dersi, server POM/başlangıç sınıfı/properties ve localhost çalışma rehberi eklendi.
- B01 aggregator 10 modüle çıktı. Yedi client uygulamasına Eureka starter ve kayıt/health ayarları; Gateway/Account’a LoadBalancer starter eklendi.
- Gateway RouteTable artık port yerine sabit allowlist serviceId döndürür. GatewayTargetResolver registry’den örnek seçer; ProxyController onu kullanır. GatewayErrors erişim/kayıt hatalarını güvenli HTTP hatasına çevirir.
- Account HttpAccountReportReader sabit Reporting base URL yerine discovery kullanır. Byte sınırı, tüm body timeout, redirect yasağı ve kullanıcı bearer aktarımı korundu.
- Eski finans, veri sahipliği, kullanıcı onayı ve JWT/owner sınırları korunur. Issuer/JWKS trust ayarları serviceId’ye dönüştürülmedi.
- Localhost/Docker farkı, registry cache/self-preservation, port değişimi, startup sırası, hata ve güvenlik testleri açıklandı.
- Tek dosyalık mimari rehberi güncellendi ve ZIP’e de eklendi. Kaynak snapshot’ları değiştirilmedi.

## Çalıştırılan kontroller

| Kontrol | Sonuç | Kanıt kapsamı |
|---|---|---|
| Kaynak bütünlüğü | PASS | Dört kaynak MD önceki sürümle byte bazında aynı |
| Görevler | PASS | Eski 84 + Account 8 + Eureka 8 = 100 görev kimliği |
| Ders envanteri | PASS | 14 ders; ZIP içinde toplam 25 MD |
| Kod blokları | PASS | Yalnız B01–B12/B01A/B08A derslerinde 195 blok; Markdown çitleri, JSON/XML parse |
| JSON Schema | PASS | Önceki dört fixture ve negatif submit kontrolleri |
| Java tip/import taraması | PASS | Tarayıcının tanıdığı 114 üst seviye tip benzersiz; 111 açık proje importu mevcut tipe/nested üyeye çözümleniyor |
| Saf Java derleme | PASS | 58 bağımsız tip ortamın JDK 17 derleyicisiyle derlendi |
| Maven/config statik denetimi | PASS | 10 modül; yedi client starter/kayıt ayarı; iki LoadBalancer tüketicisi; properties blokları içinde tekrar eden anahtar yok |
| Sabit hedef kaldırma | PASS | Gateway sabit port tablosu ve Account reporting-base-url ayarı kaldırılmış; iki tüketicide discovery bağlantısı mevcut |
| Gateway allowlist | PASS | 7 geçerli path doğru serviceId; 6 geçersiz/prefix/traversal yolu reddedildi |
| Finans davranışı | PASS | F01–F15 ve sınır/geçersiz girdi harness kontrolleri |
| Account body sınırı | PASS | Tam limit, taşmada iptal ve erken iptal harness kontrolleri |

Yukarıdaki statik kontroller Spring property binding, bağımlılık çözümü veya gerçek runtime davranışını kanıtlamaz. Java import taraması tam type checking değildir. Saf Java alt kümesi framework/Jackson/JUnit sınıflarını kapsamaz. RouteTable testi LoadBalancer veya gerçek Eureka testinin yerine geçmez.

## Sürüm ve gerçek çalıştırma sınırı

**BUILD_UNVERIFIED / EUREKA_RUNTIME_NOT_RUN.** Ortamda Maven yok; sekiz Spring süreci, gerçek Eureka kayıt/heartbeat/fetch, LoadBalancer bean wiring ve 8185 port değişimi testi çalıştırılmadı. B01A.7 bunları kullanıcının ortamında somut komut ve beklenen sonuçlarla tanımlar. “Entegrasyon sorunsuzdur” garantisi verilmez.

Mevcut kullanıcı POM’u Boot 4.1.1 için Cloud BOM 2025.1.2 olarak belgelenmiştir. Resmî uyumluluk tablosu kontrol edildi. Önceki derslerin Boot 3.5.16/Jackson 2 eğitim kodu ise 2025.0.3 hattında tutulur. Bu eğitim POM’u gerçek Boot 4 projesinin üzerine yazılmaz. Eski Jackson/Spring kodlarının Boot 4 uyarlaması ve tam reactor build’i hâlâ tamamlanması gereken kapıdır; Eureka eki bu göçü yaptığı iddiasında değildir.

## Önceden açık kalan uygulama işleri

Reporting owner-wide endpoint/repository, metadata bootstrap, kapsam manifesti, değerleme/snapshot ve imzalı cursor; bazı preview/fill/cancel/close/funding worker bağlantıları; gerçek provider stream/backfill/export; AI job/training adaptörleri; replay execution writer ve uçtan uca akışlar hâlâ 00-BASLANGIC.md ve ilgili derslerde açıkça belirtilir.

PostgreSQL/Kafka/Redis/Testcontainers, JUnit engine, security filter/refresh/CSRF/owner, gerçek HTTP timeout, SSE bağlantısı, backup/restore ve performans testleri çalıştırılmadı. Bunlar yalnız dosya güncellendi diye PASS sayılmaz. Gerçek kullanıcı reposunda dosya/POM değiştirilmedi; güncellenen teslimler ders ZIP’i ve mimari MD’dir.
