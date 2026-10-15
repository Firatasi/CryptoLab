# CryptoLab — Backend ders paketi

Revizyon 4 · 14.10.2026 · 12 ana görev + B01A + B08A · Şartname 3.0 · Contract 1.0.0 · Türkçe öğretici kod referansı

## Önce bunu oku

Bu paket, backend görevlerini sırayla elle yazman için hazırlanmıştır. Her ana görev ayrı Markdown dosyasındadır. Dosya yolları, sınıf kodları, kod içinde neden açıklamaları, uygulama sırası ve kontrol adımları bulunur. Dört kaynak belgenin değişmeden saklanan kopyaları `kaynaklar/` klasöründedir.

**Kapsam sınırı:** 14 dersin kapsamı belgelenmiştir; ancak bütün backend için eksiksiz, kopyala-çalıştır kaynak kod paketi değildir. Bazı adaptör ve endpoint'ler tam kod yerine algoritma/uygulama adımı olarak verilmiştir. Bunları aşağıdaki açık işler tablosunda görebilirsin. Bu nedenle asıl isteğindeki “her görevin bütün kodları” kapsamı bu sürümde henüz bütünüyle tamamlanmış sayılmaz.

**Sürüm sınırı:** Taslaktaki kodlar Java 21 / Spring Boot 3.5.16 / Jackson 2 eğitim tabanındadır. Mevcut projen Boot 4.1.1 ise POM'unu bu örnekle değiştirme. Jackson importları ve Spring test/starter bağımlılıkları dahil sürüm uyarlaması gerekir. Mevcut projeyi korumak önceliklidir; bu dosyalar Boot 4 için derlenmiş kod olarak sunulmamaktadır.

## Revizyon 4 — Account ve properties

`account-service` (8086), kullanıcı işlem geçmişi ve kâr/zarar okuma API'si eklendi. JWT Identity'de, ledger Simulation'da, kalıcı geçmiş/rapor Reporting'de kalır. B08A, B08 ile B09 arasındadır. Ayrıntılı sınırlar [ACC-01](03-ACCOUNT-VE-GUVENLIK-EKI.md) içinde.

Tüm Spring uygulama ayarları `application.properties` biçimine dönüştürüldü. Compose ve CI kendi YAML formatında kalır. B01 modül listesi ve B08 gateway route'u güncellendi.

## Önceki revizyonda gelişenler

- Önceki 12 dersin başına kavram, yazma sırası, sorumluluk tablosu ve küçük pratik eklendi.
- Dört ana katman korunarak controller/dto/mapper/service/repository/config/exception alt paketleri düzenlendi; kod içindeki package/import ve dosya yolları birlikte güncellendi.
- CreateSession, SubmitOrder, IdentityService ve DeadlineScheduler için repository sınırları ayrıldı. AuthController içindeki SQL UserQueryService ve repository'ye taşındı.
- OrderMapper/AuthMapper, command/response tipleri ve güvenli hata zarfı örnekleri eklendi.
- LoginLimiter/TokenIssuer portları ile teknik implementasyon seçimi görünür hale getirildi.

Önce `01-PAKET-VE-KOD-STANDARDI.md`, sonra `02-SINIF-VE-PAKET-HARITASI.md` dosyalarını oku. Ardından B01'den ilerle. Önceki endpoint ve finans kuralları korunur; yeni Account okuma endpoint ve Reporting scope metadata eki ACC-01 ile tanımlandı. Daha önceki eksik entegrasyonlar bu refactor ile otomatik tamamlanmış sayılmaz.

## Nasıl ilerleyeceğiz?

1. B01 dosyasını aç; ilk olarak B01.1 ortam kontrolünü yap.
2. Dosyada verilen yollar CryptoLab proje köküne göredir. `backend/` altındaki bütün modüller aynı projede yaşar.
3. Önce sınıfın neden var olduğunu oku, sonra kodu elle yaz. Yeni bir görev için ikinci bağımsız proje açma.
4. Bir blok “eklenecek alanlar” diyorsa mevcut POM/properties'a birleştir; dosyanın tamamını silerek değiştirme. Properties içinde aynı anahtarı tekrar tanımlama; Compose içinde ikinci `services:` bölümü oluşturma.
5. Sonraki aşama öncekinin üzerine eklenir. Paketlerin hepsini tek seferde kopyalamak, henüz yazmadığın adapter bean'leri nedeniyle uygulamayı başlatmayabilir.
6. Her görevde komut çıktısını ve gerçekten geçen/kalınan kontrolleri `progress/BACKEND.md` içine yaz.
7. Ayrı sohbet açarken ilgili Bxx dosyasını, kaynak görev planını ve güncel ilerleme kaydını ekle. Henüz kodlamadığın işleri tamamlanmış diye işaretleme.

Örnek başlangıç mesajı:

> Bu görev dosyasını mevcut mimari ve sürümlerimi koruyarak benimle adım adım işle. Kodu ben yazacağım. Her seferinde tek küçük adım ver; sınıfı neden oluşturduğumuzu ve kodu açıkla. Çıktımı kontrol ettikten sonra devam et. Belgedeki açık adaptörleri hazır kabul etme.

## Ders sırası

Java blok sayısı, benzersiz sınıf sayısı veya tamamlanmış özellik sayısı değildir; kısa eklemeler ve testler de blok sayısına dahildir.

| Dosya | Konu | Java kod bloğu |
|---|---|---:|
| [B01-Kurulum-ve-Sozlesmeler.md](B01-Kurulum-ve-Sozlesmeler.md) | Ortam, paket sınırları ve kontrakt başlangıcı | 7 |
| [B01A-Eureka-ve-Servis-Kesfi.md](B01A-Eureka-ve-Servis-Kesfi.md) | Eureka, localhost kayıt/keşif ve LoadBalancer | 3 |
| [B02-Finans-Cekirdegi.md](B02-Finans-Cekirdegi.md) | Saf Java finans çekirdeği | 7 |
| [B03-PostgreSQL-ve-Ledger.md](B03-PostgreSQL-ve-Ledger.md) | PostgreSQL, ledger ve kalıcı session | 8 |
| [B04-Kimlik-ve-Guvenlik.md](B04-Kimlik-ve-Guvenlik.md) | Identity, JWT ve kaynak sahipliği | 18 |
| [B05-Manuel-Sanal-Islem.md](B05-Manuel-Sanal-Islem.md) | Önizleme, onay ve manuel sanal işlem | 20 |
| [B06-Piyasa-Kafka-Redis.md](B06-Piyasa-Kafka-Redis.md) | Dinamik piyasa servisi, Kafka ve veri önbelleği | 9 |
| [B07-Risk-ve-Koruyucu-Cikislar.md](B07-Risk-ve-Koruyucu-Cikislar.md) | Risk yaşam döngüsü, funding ve koruyucu çıkışlar | 8 |
| [B08-Rapor-Gateway-SSE.md](B08-Rapor-Gateway-SSE.md) | Reporting, Redis, gateway ve SSE | 7 |
| [B08A-Account-Gecmis-ve-KarZarar.md](B08A-Account-Gecmis-ve-KarZarar.md) | Account: kullanıcı işlem geçmişi ve kâr/zarar görünümü | 14 |
| [B09-Strategy-ve-AI.md](B09-Strategy-ve-AI.md) | Strategy, piyasa tarama ve AI bağlantısı | 6 |
| [B10-Egitim-Facade.md](B10-Egitim-Facade.md) | Eğitim yönetimi facade’ı | 5 |
| [B11-Tarihsel-Replay.md](B11-Tarihsel-Replay.md) | Tarihsel replay ve model değerlendirme köprüsü | 8 |
| [B12-Entegrasyon-ve-Teslim.md](B12-Entegrasyon-ve-Teslim.md) | Entegrasyon, dayanıklılık ve backend teslimi | 0 |

## Tam koddan sonra kalan bağlantılar

| Görev | Birlikte yazılırken tamamlanacak kısım |
|---|---|
| B01A | Eureka’nın gerçek sekiz süreçte kayıt, port değişimi, hata ve yeniden kayıt testleri |
| B01 | Kullanıcının mevcut sürümlerine uyarlama, kendi ortamında Maven reactor derlemesi |
| B03 | Servis başına DB kurulumu, transaction/lock/rebuild deneylerinin gerçek PostgreSQL ile yürütülmesi |
| B04 | Her downstream servise security/ownership uyarlaması, güvenli anahtar ve rol yapılandırması |
| B05 | Preview kalıcı adaptörü; fill/limit/cancel/close worker; owner filtreli query DTO ve endpoint'leri |
| B06 | Gerçek provider stream/reconnect/backfill, katalog kalıcılığı, veri export işleri ve abonelik yönetimi |
| B07 | Funding settlement, risk ve close kararlarının tek session transaction'ına bağlanması; halt/resume/close-all |
| B08 | Projection bootstrap/rebuild, rapor endpoint'leri, SSE HTTP bağlantısı ve bounded proxy streaming |
| B08A | Reporting owner-wide endpoint/repository, metadata bootstrap/coverage manifest, imzalı cursor ve Account E2E |
| B09 | Kalıcı analysis job worker, AI proposal–preview bağlantısı, tarama kuyruğu |
| B10 | ContractGate şema adaptörü, binary kaynak sayfası download, eğitim projection/polling bağlantısı |
| B11 | Replay iş API'si, tarihsel input adaptörü, gerçek execution writer ve artifact çıktıları |
| B12 | Gerçek servislerle E2E, crash/race, backup/restore ve performans kanıtları |

B02 saf hesap çekirdeği için kod ve test örnekleri içerir. Hiçbir dış servis testi bu pakette yapılmış kabul edilmez. Ayrıca B07'de geç funding tahsilatı için belirtilen tasarım kararı kaynak şartnamenin otomatik değişikliği değildir; uygulamadan önce açıkça karara bağlanmalıdır.

## Kontrol durumu

`DOGRULAMA.md` dosyasında yalnız bu ortamda gerçekten yapılan kontroller yazılıdır. Özellikle saf Java derlemesi, tam Spring/Maven derlemesiyle aynı şey değildir.

## Revizyon 4 — DISC-01

B01’den hemen sonra B01A uygulanır. Yeni discovery-server ile 10 modül / 8 süreç vardır. Gateway ve Account hedeflerini Eureka/LoadBalancer üzerinden seçer. Kullanıcının Boot 4.1.1 POM’u için Cloud 2025.1.2; eski 3.5.16 eğitim tabanı için 2025.0.3 kullanılır. Bu iki hattı karıştırma; eski Jackson 2 örneklerinin Boot 4 entegrasyon kapısı açık kalır. Orijinal 84 görev + Account 8 + Eureka 8 = 100 alt görev.
