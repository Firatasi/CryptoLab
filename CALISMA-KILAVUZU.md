# CryptoLab — Üç sohbetle, elle kodlayarak çalışma kılavuzu

12 Ekim 2026 · Görev planı 1.0 · Referans: yüklenen CryptoLab.zip, şartname 3.0.

Bu paket uygulama kodu üretmez. Var olan dört şartnameyi değiştirmeden, backend/frontend/AI işlerini sırayla elle yazacağın görevlere ayırır. Görevlerin tamamı başlangıçta TODO’dur; önceki belgedeki sayısal kontrol sonuçları senin uygulama testlerinin geçtiği anlamına gelmez.

## 1. Açacağın sohbetler ve vereceğin dosyalar

| Sohbet | O sohbete ekle | İlk görev |
|---|---|---|
| CryptoLab — Backend Öğrenme | README.md, backend/01-BACKEND.md, backend/BACKEND-GOREVLER.md | B01.1 |
| CryptoLab — Frontend Öğrenme | README.md, frontend/02-FRONTEND.md, frontend/FRONTEND-GOREVLER.md | F01.1 |
| CryptoLab — AI Öğrenme | README.md, ai/03-AI.md, ai/AI-GOREVLER.md | A01.1 |

[Backend görevleri](backend/BACKEND-GOREVLER.md) · [Frontend görevleri](frontend/FRONTEND-GOREVLER.md) · [AI görevleri](ai/AI-GOREVLER.md) · [Ana şartname](README.md).

Her görev rehberi kendi öğretmen talimatını içerir. Yalnız görev rehberini paylaşmak başlangıç yöntemini anlatır; exact DTO/formül ayrıntıları için ana ve paket şartnamesini de ekle. Asistan ilgili şartnameyi görmediyse gördüğünü varsaymamalı; senden o bölümü istemeli.

ChatGPT’de aynı proje altında sohbet açmak, üç sohbetin otomatik olarak birbirinin son mesajını veya bilgisayarındaki dosyaları gördüğü anlamına gelmez. Kodun asıl kaynağı bilgisayarındaki tek CryptoLab klasörü/git deposudur. Her sohbet değiştiğinde güncel dosyaları, diff’i veya kontrol noktasını sen taşırsın. Asistan erişemediği kodu incelemiş veya testi çalıştırmış sayılmaz.

## 2. Dosya yapısı ve belge otoritesi

Mevcut yapı korunur: kökte README.md; backend/01-BACKEND.md; frontend/02-FRONTEND.md; ai/03-AI.md. Yanlarına yalnız görev rehberleri eklendi. Ortak `contracts/`, `infra/` ve `progress/` klasörlerini kodlama sırasında B01’de sen oluşturacaksın; bu teslimde uygulama iskeleti veya sahte ilerleme dosyası yaratılmadı.

Sıra: ana şartname ortak API/event/tipleri belirler; backend şartnamesi finans matematiğini belirler; ilgili paket şartnamesi uygulama sorumluluklarını belirler. Görev rehberi bunların çalışma sırasıdır. Sınıf/dosya adları öğretici öneridir; public endpoint/topic/alan/enum isimleri normatiftir.

**Mevcut ZIP’te ad taşıma notu:** Ana şartname artık README.md adında, paket şartnameleri alt klasörlerde. Eski metinlerin içinde kalan `00-ANA-PROJE.md` gibi bağlantılar eski konuma işaret edebilir. Belge içerikleri bu görev paketinde byte düzeyinde korunmuştur; doğru konumlar yukarıdaki tablodur. Yeni rehberlerin bağlantıları mevcut konumlara göredir. Yanlış link yeni veya eksik gereksinim anlamına gelmez.

## 3. Öğretmenle çalışma protokolü

- Kodları, testleri, migration’ları ve yapılandırmaları sen yazacaksın. Asistan kendiliğinden repo oluşturmaz, dosya değiştirmez, kurulum yapmaz veya tüm görevi senin yerine tamamlamaz.
- Her mesajda bir küçük alt adım: amaç → gerekli kavram → senin yazacağın parça → deneme → paylaşacağın çıktı. Bütün servisi tek cevapta verme.
- Bir alt adım pratikte büyürse B05.3a gibi küçük parçalara ayrılır; asıl gereksinim eksilmez. Sabit saat/süre garantisi yoktur.
- Önce denemeni iste; takılırsan ipucu, sonra algoritma/pseudocode, gerekirse küçük örnek. Tam dosya yalnız açıkça istediğinde. Dosyayı senin yerine yazmak için ayrıca açık isteğin gerekir.
- Kod incelemesinde problemli satır/davranış, neden, test ve senin düzeltmen açıklanır. “Şu dosyayı komple bununla değiştir” varsayılan öğretim yöntemi değildir.
- Yeni kavram ilk kullanımda Türkçe ve somut örnekle anlatılır. Öğrenilmiş konu her mesajda uzun tekrar edilmez.
- Test çıktısı görülmeden PASS/DONE yazılmaz. Yalnız tasarım konuşulduysa DESIGN_REVIEWED; derlendiği görülmediyse BUILD_UNVERIFIED.
- Başarılı alt adım sonrası kalan iş söylenir. Yeni ana göreve geçiş için mevcut kabul listesinin kanıtı aranır; her küçük dosyada gereksiz izin sorulmaz.
- Sistem/grafik kartı/sürücü ayarlarıyla oynama, otomatik paket yükseltme veya gizli ücretli servis açma yoktur. Ortamda değişiklik önerilecekse amacı ve kullanıcının çalıştıracağı komutun etkisi açıklanır.

## 4. Üç paket için ortak koruma kuralları

Yalnız sanal işlem; gerçek bot veya borsa emir anahtarı yok. Dinamik varlık kataloğu, sabit coin enum yok. Zaman ufku 1m/15m/1h/1d/1w; candle/lookback ayrı. PDF yalnız eğitim hazırlığı/eğitimi; inference sırasında PDF/RAG yok. Java finans çekirdeği hesap otoritesi; frontend ve Python ikinci para motoru yazmaz. Mark risk içindir, bid/ask fill içindir. Kullanıcı yeni girişi onaylar; scan/train emir yaratmaz. Sonuç 202 ise fill tamamlandı denmez. Decimal para string; idempotency+ledger transaction ve generation kontrolü korunur. Manuel stop/hedef null olabilmesi profil kurallarına bağlıdır; AI planında SL/TP gerekir.

## 5. Görev sayısı ve ana rota

| Paket | Görev sayısı | Başlangıç → bitiş |
|---|---:|---|
| Backend | 12 | Sözleşme/iskelet → finance → ledger → identity → manuel işlem → market/Kafka → risk → rapor/gateway → AI → training facade → replay → entegrasyon |
| Frontend | 10 | React temeli → auth → session/market → emir → pozisyon → stream → AI/tarama → rapor/replay → eğitim → E2E |
| AI | 10 | Python/contract → feature → label/split → numeric eğitim → inference → PDF hazırlığı → reviewed dataset → fine-tuning → değerlendirme/registry → entegrasyon |

Bu ana görevler tek oturumluk küçük işler değildir; her birinde 5–8 alt adım vardır. İlerlemenin birimi örneğin B03.2’dir, tüm B03’ü tek seferde bitirmek zorunda değilsin.

## 6. Paketler arası buluşma noktaları

Önce B01 ortak sözleşme ve fixture başlangıcı yapılır. F01 ve A01 yalnız boş proje/öğrenme hazırlığı için daha erken başlayabilir; bağımsız public DTO icat etmezler.

| Kapı | Gerekli çalışmalar | Kanıt / kullanım |
|---|---|---|
| K0 | B01 | Contract 1.0.0 başlangıcı + ortak fixture; F/A aynı sürümü okur |
| K1 | B05 ve B04 | Auth+manuel preview/submit/close; F02–F05 gerçek API’ye bağlanır |
| K2 | B06–B08 | Market/Kafka/risk/report/gateway; F06–F08 canlı veri ve rapora bağlanır |
| K3 | B09 + A05 + F07 | Sözleşmeye uygun numeric analiz→proposal→onay; A09 öncesi geliştirme bundle’ı açık etiketli |
| K4 | B10 + A06–A08 + F09 | Eğitim kaynak/örnek/run akışı; henüz başarılı fine-tuning varsayılmaz |
| K5 | B11 + A09 + F08 | Java replay, maliyetli eval ve izinli model yayınlama |
| K6 | B12 + F10 + A10 | Tam ürün testleri, doğrulanmış sürümler ve bitirme demosu |

B12/F10/A10 ortak final doğrulama oturumlarıdır: birbirinin DONE olmasını bekleyen üç kapalı ön koşul değildir. B11/F09/A09 yerel yetenekleri hazır olunca üç final görev IN_PROGRESS yapılır; ortak testin kanıtı her kayda ayrı yazılır.

K3/K4 geliştirme kapısıdır; active modelin kalite kapısını atlama yetkisi değildir. B09 fake modelle, B10 fake training provider ile, B11 sentetik policy intent ile yazılabilir. AI A09’un B11’e ihtiyacı bu yüzden dairesel bekleme yaratmaz. F08/F09 mock ile geliştirilebilir; gerçek entegrasyon durumu ayrıca açık kalır.

**Pratik çalışma sırası:** B01 → F01/A01 → B02–B05 → F02–F05 → B06–B08 → F06 ve F08 rapor kısmı → A02–A05 → B09/F07 → A06–A08/B10/F09 → B11/A09/F08 replay → B12/F10/A10. Aynı anda üç kod işini yürütmek zorunda değilsin; sohbeti değiştirirken kontrol noktası taşı.

## 7. Sözleşme sahipliği ve ortak klasör

`contracts/` için koordinatör Backend sohbetidir; fixture anlamı ana şartnameden gelir. Frontend veya AI eksik alan bulursa kendi alanını ekleyip ilerlemez; şu değişiklik notunu hazırlar: sorun, mevcut kontrakt sürümü, önerilen alan, gerekçe, etkilenen üretici/tüketici, migration/test. Kullanıcı Backend sohbetine taşır; karar uygulanırsa bütün paketlerin fixture ve tipleri aynı sürüme çekilir.

Backend `backend/` ve ortak altyapının sahibi; Frontend `frontend/`; AI `ai/`. Ortak dosyada aynı anda farklı sohbetlerin önerilerini uygulama. Hangi commit/diff’in güncel olduğunu görev kaydına yaz. Contracts bir kez oluşturulup sonsuza dek donmaz; yeni endpoint şemaları ana şartnamedeki anlamla, ilgili görevde eklenir. K0’da gerekli başlangıç şemaları ve kalanların owner listesi açık bulunur.

Eksik provider/library sürümü uygulama sırasında resmi dokümana bakılarak seçilir, lock’a kaydedilir. Rehber npm/Maven/Python komutunun senin ortamında çalıştığını varsaymaz. Yeni yazılım önerisi ana stack değişikliği anlamına geliyorsa ayrı karar gerekir.

## 8. İlerleme durumları ve kanıt

TODO → IN_PROGRESS → IMPLEMENTED_WITH_FIXTURE → INTEGRATION_PENDING → DONE. BLOCKED yalnız gerekli gerçek bilgi/bağımlılık yoksa; eksik sebebi ve devam edilebilen alt görev yazılır. Her görev her ara durumu geçirmek zorunda değildir; yalnız unit finance gibi işler direkt DONE olabilir. Dış paket hazır değilse yerel fixture kabulü kanıtlandıktan sonra sonraki geliştirme görevine geçilebilir; bekleyen entegrasyon borcu kayıtta kalır ve görev DONE yapılmaz.

Bir görevin DONE olması: checklist tamamlandı + anlamlı test çıktısı görüldü + kullanıcı kendi kodunun akışını açıklayabildi + zorunlu gerçek entegrasyon varsa geçti. Mock ile geçen görev tüm sistem bitti anlamına gelmez. Gerçek veri erişimi kısıtlıysa adapter integration BLOCKED, offline fixture çalışması ayrı tamamlanır.

Her paketin kendi `progress/BACKEND.md`, `progress/FRONTEND.md`, `progress/AI.md` kaydını kullanıcı oluşturur. Bunlar uygulama kodlama oturumunda yazılacak kayıt yollarıdır; bu teslimde gerçekleşmiş çalışma yoktur.

## 9. Her sohbet sonunda kopyalanacak kontrol noktası

```text
CRYPTO-LAB KONTROL NOKTASI
Paket / görev / alt adım:
Durum:
Şartname sürümü: 3.0
Contract sürümü ve commit/hash:
Çalışılan branch/commit (git kullanılıyorsa):
Elle yazdığım/değiştirdiğim dosyalar:
Çalıştırdığım komut ve gözlenen sonuç:
Geçen testler / çalıştırılmayanlar:
Fixture mı, gerçek entegrasyon mu:
Bulunan hata / açık varsayım:
Diğer pakete gönderilecek kontrakt bilgisi:
Bir sonraki tek alt adım:
```

Yeni sohbette bu kaydı, ilgili dosyaları ve hata çıktısını ver. Secret/API key/parola paylaşma; gerekli alanları maskele. “B05 bitti” tek başına yeterli kanıt değildir. Asistan geçmiş sohbeti görmüyorsa bu kontrol noktasından devam eder; işi yeniden sıfırdan başlatmaz.

## 10. Başlangıç mesajı

Her paket rehberinin başlangıcındaki mentor mesajını ilgili sohbete yapıştır. İlk yanıt ortam ve son durumun kısa kontrolüyle yalnız ilk alt adımı vermeli. Bu çalışma kılavuzu veya görevler, asistanın uygulama kodunu otomatik üretmesine izin vermez; istenen çıktı rehberlik ve kod incelemesidir.

## 11. Bu teslimde yapılanlar

Dört mevcut şartname içerik değişmeden korunur. Üç paket görev rehberi ve bu koordinasyon kılavuzu eklenir. Görev referansları ve yeni rehber bağlantıları kontrol edilir; uygulama derleme/test/eğitim yapılmaz. Başlangıçta bütün görevler tamamlanmamıştır.

Kontrol kaydı: 32 ana görev ve 223 işaretlenmemiş alt görev; yeni rehberlerde 13 yerel bağlantı ve görev numaraları doğrulandı. Yüklenen dört şartnamenin içerik hash’leri korunuyor. Bu kontrol doküman düzenine aittir; uygulama testleri çalıştırılmadı.


| Klasör | Ne için kullanılacak? |
|---|---|
| `backend` | Java servisleri ve yardımcı Maven modülleri |
| `frontend` | İleride kullanıcı arayüzü |
| `ai` | İleride Python AI uygulaması |
| `contracts` | Servislerin alışveriş yaptığı JSON verilerinin kuralları ve örnekleri |
| `infra` | İleride Docker ve altyapı yapılandırmaları |
| `progress` | Tamamlanan işler ve doğrulama sonuçları |


| Katman | Görevi | Buraya koymayacağımız işler |
|---|---|---|
| `api` | HTTP isteğini alır, giriş biçimini doğrular, cevabı döner. | SQL ve kâr/zarar hesabı |
| `application` | Bir kullanım senaryosunun adımlarını ve transaction sınırını yönetir. | Doğrudan SQL veya dış servis JSON’unu çözümleme |
| `domain` | İş kurallarını, modellerini ve kararlarını içerir. | Spring, HTTP, veritabanı erişimi |
| `infrastructure` | Veritabanı, Kafka, Redis ve dış HTTP bağlantılarını uygular. | Finansal iş kararlarının sahipliği |