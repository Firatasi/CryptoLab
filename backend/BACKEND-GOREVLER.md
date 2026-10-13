# CryptoLab — Backend için sıralı elle kodlama görevleri

Plan 1.0 · Şartname 3.0 · 12 ana görev. **Bütün görevler TODO olarak başlar.**

Bu rehber yalnız backend paketinin çalışma planıdır. [Ana şartname](../README.md), [backend şartnamesi](01-BACKEND.md) ve [ortak çalışma kılavuzu](../CALISMA-KILAVUZU.md) birlikte kullanılır. Dosya/sınıf isimleri öneridir; API, topic, veri tipi ve finans formülleri kaynak şartnamelerden aynen uygulanır.

## Backend sohbetine başlangıç talimatı

```text
Bu sohbet CryptoLab backend öğrenme sohbetimdir. README.md, 01-BACKEND.md ve
BACKEND-GOREVLER.md esas alınacak. Kodları, testleri, migration ve config'i ben
elle yazacağım. Ben açıkça istemedikçe dosya oluşturma/değiştirme, komut çalıştırma,
projeyi otomatik kodlama veya tam servis çözümü verme.
Bir mesajda yalnız bir alt görev ver: kısa kavram anlatımı, yazacağım parça,
beklenen davranış, deneyeceğim test ve sana göndereceğim çıktı.
Önce denememi bekle; hata varsa nedenini anlatıp benim düzeltmemi sağla.
Gerekirse ipucu→pseudocode→küçük örnek sırasıyla yardım et.
Test çıktısını görmeden tamamlandı deme. Mock ile gerçek entegrasyonu ayır.
Frontend/AI klasörlerini bu sohbette değiştirme; contracts değişikliği için
ortak kılavuzdaki karar kaydını kullan. Gerçek bot ekleme; finans kurallarını
basitleştirme. İlk kez başlıyorsak B01.1; kontrol noktası verdiysem oradan devam et.
```

Asistan her görevin sonunda ilgili kavrama dair aşağıdaki kısa açıklama sorusunu sorar. Amaç sınav yapmak değil, yazdığın kodu neden yazdığını anlayıp anlamadığını görmektir. Bir görevin tüm alt maddelerini aynı mesajda çözüm kodu olarak göndermez.

Not: Backend şartnamesindeki **F01–F15 finans fixture kodları**, diğer sohbetin F01–F10 frontend görev kodlarıyla aynı numara ailesini kullanır. Bu rehberde “F01–F15 fixture” yazdığında finans testleri kastedilir; “F02’ye devir” gibi ifadeler frontend görevini belirtir.

## Harita ve bağımlılıklar

| Görev | Ürün çıktısı | Ön koşul | Kaynak bölümler |
|---|---|---|---|
| B01 | Maven/contract/fixture başlangıcı | Yok | Backend 1–2; README 6–10,16 |
| B02 | Saf finance-core | B01 | Backend 14–16 |
| B03 | DB/ledger/session altyapısı | B02 | Backend 2,5,9 |
| B04 | Identity/JWT/ownership | B03 | Backend 3,8 |
| B05 | Manuel sanal işlem dikey akışı | B04 | Backend 5; README 7.2,11 |
| B06 | Dinamik Market + Kafka | B05 | Backend 4,9,10 |
| B07 | Funding/likidasyon/bracket/deadline | B06 | Backend 5,14,15,17 |
| B08 | Reporting/Redis/Gateway/SSE | B07 | Backend 7–11 |
| B09 | Strategy/scan/AI contract | B08 | Backend 6; README 8 |
| B10 | TRAINER facade | B09 | Backend 6,8; README 7.4 |
| B11 | Replay ve AI eval köprüsü | B10 | Backend 15,17; README 16.2 |
| B12 | Entegrasyon/dayanıklılık/teslim | B11 | Backend 11,13,16 |

B09 gerçek AI bağlantısı A05; B10 gerçek eğitim A06–A08; B11 model intent bağlantısı A09 ile birleşir. Bu işler önce ortak fixture’larla yazılabilir. Üç paket gerçek entegrasyonu B12’de zorunlu doğrulanır. B11’in başlamak için A09’u beklemesi gerekmez; sentetik intent kullanır.

## B01 — Ortam, paket sınırları ve kontrakt başlangıcı

**Hedef:** İş koduna girmeden hangi servisin neyi sahiplenebileceğini ve paketlerin nasıl anlaşacağını kurmak. Öğreneceklerin Maven reactor, modül, DTO/schema ayrımı ve contract fixture.

**Elle yazılacak aday dosyalar:** `backend/pom.xml`, `backend/finance-core/pom.xml`, servis modüllerinin pom ve application skeleton’ları; `contracts/README.md`, `contracts/http/submit-order.schema.json`, event/internal-ai şema başlangıçları; `contracts/fixtures/` ve `progress/BACKEND.md`. Henüz iş servisi implementasyonu yok.

- [ ] **B01.1** OS, JDK, Maven veya wrapper, Docker/container runtime ve boş disk durumunu sen kontrol et; sürümleri paylaş. Asistan uyumlu sürüm planı sunsun, kendiliğinden kurulum yapmasın.
- [ ] **B01.2** Tek proje kökünü ve Maven modül ağacını yaz; finance-core’un Spring’den bağımsız olduğunu dependency tree’de göster.
- [ ] **B01.3** domain/application/infrastructure/api katmanlarını küçük bir sorumluluk tablosuyla tarif et. Entity’leri servisler arasında paylaşma.
- [ ] **B01.4** README’deki Decimal/UUID/Instant/enum ve strict submit şemasını dosyaya aktar; format validator’ın gerçekten çalışmasını test et.
- [ ] **B01.5** MarketSnapshot, PredictionResult ve LedgerTransactionPosted için birer ortak fixture/schema oluştur. Bütün API’leri bir günde yazma; henüz eklenmeyen şemaların görev sahibini kaydet.
- [ ] **B01.6** Başlangıç contract sürümünü 1.0.0 olarak kaydet; build ve schema kontrol çıktısını paylaş.

**Deneme/kabul:** Geçerli submit kabul; body’ye quantity eklenince, revision=0 veya bozuk UUID ile reject. Fixture dosyasında secret yok. finance-core application class veya DB’ye bağımlı değil. Henüz oluşturulmamış wrapper komutunu varmış gibi çalıştırma; doğru komut ortam kontrolü sonrası seçilir.

**Açıklama sorusu:** “Java entity’sini doğrudan frontend’e göndermek yerine neden sözleşme var?”
**Devir:** F01/A01’e contract sürümü, schema dosyaları ve fixture örnekleri. **İlk küçük iş:** Sadece B01.1 ortam çıktıları; toplu proje kodu yok.

## B02 — Saf Java finans çekirdeği

**Hedef:** HTTP olmadan long/short ve para doğruluğunu anlamak. Öğreneceklerin BigDecimal, value object, rounding, saf fonksiyon ve invariant.

**Dosyalar:** finance-core altında `Money`, `Quantity`, `PositionSide`, `PnlCalculator`, `MarginCalculator`, `MaintenanceTier`, `LiquidationCalculator`, `FundingCalculator`; aynı kapsamda JUnit testleri. Adlar öneri; matematik Backend 14’tekiyle aynı.

- [ ] **B02.1** String’den Decimal oluşturmayı, scale/precision farkını ve neden double kullanılmadığını küçük denemeyle göster.
- [ ] **B02.2** Notional, initial margin, long/short PnL ve açık/kapalı fee fonksiyonlarını ayrı yaz; “1000 teminat” ile “1000 notional” ayrımını hesapla.
- [ ] **B02.3** Quantity step-floor, adverse tick rounding ve fee-inclusive bütçe kontrolünü yaz.
- [ ] **B02.4** Maintenance kademesi ve long/short liquidation adayını hesapla; adayın kendi kademe aralığına düştüğünü doğrula.
- [ ] **B02.5** Funding AVAILABLE_FIRST ve kısmi kapanış releasedB hesaplarını yaz; boundary sırası state’i henüz Simulation’da uygulanacak.
- [ ] **B02.6** Negative residual/insurance katkısı hesap sonucunu kaybetmeden temsil et; normal exit ile liquidation fee türünü ayır.
- [ ] **B02.7** Backend F01–F15 ve kademe örneğini bağımsız test girdilerine çevir; normal ve sınır durum sonuçlarını paylaş.

**Deneme/kabul:** E=50000,q=.2,L=10,başlangıç=10000 için A=8995; long 50500 kapanışında A=10089.95. Kaldıraç değişip q/E/X aynıysa brüt PnL değişmez. Kademe sınırı, sıfır/negatif değer, invalid string ve rounding testleri var. Kaynak belgedeki tüm altın fixture’lar uygulamada doğrulanır; iki örnek yeterli değildir.

**Açıklama sorusu:** “Likidasyon fiyatı neden son işlem fiyatından ve gerçekleşen çıkış fiyatından ayrı?”
**Devir:** B03’e saf hesap API’si ve fixture sonuçları; AI/React bu hesapları kopyalamayacak. **İlk iş:** Yalnız B02.1, ardından tek PnL fonksiyonu.

## B03 — PostgreSQL, ledger ve kalıcı session

**Hedef:** Saf hesabı gerçek transaction içine yerleştirmek. Öğreneceklerin Flyway, repository, constraint, çift taraflı posting, row lock ve outbox temeli.

**Dosyalar:** Simulation migration’ları, Session/WalletAccount/JournalTransaction/Posting, repository’ler, CreateSession use case, Clock port, inbox/outbox/idempotency tabloları. DB başına ayrı kullanıcı/izin; model_db AI’ye aittir.

- [ ] **B03.1** Yerel PostgreSQL bağlantısını sen kur; Flyway migration ve ddl-auto=validate uygula. docker komutu/volume etkisi açıklansın.
- [ ] **B03.2** Session ve available/reserved/isolated/system hesap modellerini çiz; owner_id testte sabit subject olabilir, public auth bypass oluşturma.
- [ ] **B03.3** Başlangıç sanal sermaye journal’ını yaz; posting toplamı sıfır olsun. Bakiye snapshot’ı ve journal tek transaction.
- [ ] **B03.4** BusinessKey unique ile duplicate finans hareketini önle; exception rollback’i gözlemle.
- [ ] **B03.5** Session row lock ve version alanlarını yaz; iki eşzamanlı test cash’i iki kere tüketemesin.
- [ ] **B03.6** Inbox/outbox ve pending_engine_inputs için migration/sözleşme hazırla; Kafka publish B06’da bağlanacak.
- [ ] **B03.7** Wallet’ı ledger’den yeniden kurup snapshot ile karşılaştır; restart sonrası session kaybolmasın.

**Deneme/kabul:** Gerçek PostgreSQL entegrasyon testi; H2 ile lock davranışı kanıtlanmış sayılmaz. Duplicate journal tek etki; transaction ortasında hata olursa kısmi bakiye yok. Finans satırı silerek reset yapılmaz. Başlangıç session use case testi dışında public güvensiz para endpoint’i açılmaz.

**Açıklama sorusu:** “Redis lock neden tek başına iki kere para düşülmesini engelleyen güvence olamaz?”
**Devir:** B04/B05’e kalıcı session/ledger ve rollback kanıtı. **İlk iş:** Tek migration ile users tablosu değil, Simulation session/account sınırı.

## B04 — Identity, JWT ve kaynak sahipliği

**Hedef:** Spring Security’yi adım adım tekrar etmek; yazan kullanıcı yalnız kendi session’ına erişsin.

**Dosyalar:** identity-service migration, Register/Login/Refresh use case, DTO/controller, password encoder adapter, token signer/JWKS, security config; Simulation resource-server/ownership katmanı.

- [ ] **B04.1** User/refresh schema; normalized email unique, status/role. Kayıt DTO’sunda rol seçimi yok.
- [ ] **B04.2** Register validation/hash/insert ve duplicate email davranışını yaz; şifreyi loglama.
- [ ] **B04.3** Login ve token üretimini yaz; issuer/audience/expiry/kid kavramlarını test et.
- [ ] **B04.4** Refresh cookie+DB hash/rotation/family revoke ve logout akışını yaz; CSRF/Origin’ı açıkça uygula.
- [ ] **B04.5** Her servis için doğrulanmış subject→owner sorgusu; X-User-Id/body userId’ye güvenme.
- [ ] **B04.6** USER/TRAINER/ADMIN ve MODEL_PUBLISH authority politikasını oluştur; controlled dev seed’i ayrı tut.
- [ ] **B04.7** Login limiter portunu hazırla; Redis adapter B08’de tamamlanana kadar sınırlı local dev/test adapter kullan, sınırsız login açma.

**Deneme/kabul:** Yanlış parola, duplicate email yarışı, expired/sahte audience JWT, refresh replay, logout ve cross-user erişim testleri. Auth/session DTO fixture’ları F02’ye verilir. Kısa token TTL ve logout sınırı açıklanır; access token bir anda her yerde iptal olmuş sayılmaz.

**Açıklama sorusu:** “JWT’yi decode etmek ile doğrulamak arasındaki fark ne?”
**Devir:** F02 gerçek auth bağlantısını yapabilir. **İlk iş:** Tek RegisterRequest ve validation testini elle yazmak.

## B05 — Önizleme, onay ve manuel sanal işlem

**Hedef:** İlk tamamlanmış backend dikey akış: session→preview→submit→fill→manuel close→ledger. Market bu adımda etiketli FixtureAdapter’dır; gerçek provider B06.

**Dosyalar:** ExecutionPlan/Approval/Command/Order/Fill/Position, canonical serializer, PlanHash, PreviewOrder/SubmitOrder/ClosePosition, controller DTO, idempotency repository, market snapshot port.

- [ ] **B05.1** README 7.2 request/response şemalarını tamamla; submit yalnız planId/revision/planHash. API’de ikinci alternatif body açma.
- [ ] **B05.2** ManualPreview’dan q/margin/fee/risk üret; nullable manuel stop/hedef için profil gereksinimini uygula.
- [ ] **B05.3** Immutable plan revision/hash/TTL/generation kaydını yaz; canonical byte fixture ile hash kararlı olsun.
- [ ] **B05.4** Session lock altında ownership+expiry+idempotency+unique plan execution; approval audit ve command aynı transaction, commit sonrası 202.
- [ ] **B05.5** Worker snapshot’ı yeniden doğrulasın; market full-fill-or-reject ve LIMIT reserve/NEW/cancel akışını yaz.
- [ ] **B05.6** Tam manuel close ve generation invalidation; fill, journal, position, outbox aynı transaction. Çıkış talebini sonucu bilinmeden CLOSED yapma.
- [ ] **B05.7** GET command/order/position/wallet/ledger ile sonucu sorgula; timeout+aynı key testini çalıştır.

**Deneme/kabul:** Aynı plan iki emir açmaz; key başka body ile 409; fiyat/tavan değişince yeniden onay; ücret yetersizliğinde bakiye kaybı yok. Fixture giriş ve kapanış B02 altın rakamlarıyla tutarlı. LIMIT kabulü position oluşturmaz. B07 tamamlanmadan uygulama eksiksiz likidasyon sistemi diye sunulmaz.

**Açıklama sorusu:** “202 cevabı geldiğinde hangi kayıt kesin, hangi sonuç henüz kesin değil?”
**Devir:** K1; F03–F05 gerçek API’leri kullanabilir. **İlk iş:** Yalnız preview request/response ve bir fixture hesabı.

## B06 — Dinamik piyasa servisi, Kafka ve veri önbelleği

**Hedef:** Mikroservis iletişimini önce fixture, sonra public provider ile öğrenmek. Öğreneceklerin producer/consumer group/partition, outbox/inbox, source freshness, catalog ve backfill.

**Dosyalar:** MarketSource/FixtureAdapter/BinanceAdapter, Instrument/Candle/Coverage/Gap, Kafka configuration/listener/publisher, MarketSnapshot cache adapter, export job, Simulation durable ingress/session fan-out.

- [ ] **B06.1** Tek fixture MarkUpdated olayını produce et; consumer kapat/aç deneyiyle offset’i gör. Farklı servisler ayrı group.
- [ ] **B06.2** Outbox publish ve consumer inbox/DB commit sırasını uygula; duplicate event tek effect. Session fan-out ara crash testini kur.
- [ ] **B06.3** Dinamik instrument catalog, canonical id ve capability/reason’ları yaz; yeni varlık eklemek enum değişikliği gerektirmesin.
- [ ] **B06.4** Candle TRADE/MARK, finalized/availableAt, gap ve coverage backfill; aynı eksik range tek job.
- [ ] **B06.5** Resmi provider şemalarını uygulama gününde doğrula; REST, mark/book stream, reconnect, rate limit ve epoch dedupe adapter’larını sırayla yaz.
- [ ] **B06.6** Redis market snapshot TTL + source time; stale veriyi yeni timestamp’le canlandırma. Redis kapalı senaryoyu dene.
- [ ] **B06.7** Hot/warm/cold abonelik önceliği ve delist; UI bağlantısı kapansa açık pozisyon feed’i sürsün.
- [ ] **B06.8** Immutable dataset export job/ref/hash API’sini tamamla; A02’ye aynı formatta fixture ve gerçek küçük export ver.

**Deneme/kabul:** Consumer restart/repeat, same-symbol farklı venue, yeni eligible instrument, bozuk payload, kapalı olmayan mum, stale/gap ve 429 testleri. Gerçek ağ smoke testi ayrı tarihli sonuç; internet engeli olduğunda fixture PASS, gerçek entegrasyon BLOCKED yazılır. Kafka retention ledger arşivi yerine geçmez.

**Açıklama sorusu:** “Market mark ve quote ayrı topic’lerdeyse olayların tek bir global sırası var mı?”
**Devir:** A02 veri contract; B07 veri/gap olayları; F03/F06 piyasa snapshot. **İlk iş:** Kafka’ya tek fixture event, tüm Binance adapter’ı değil.

## B07 — Risk yaşam döngüsü, funding ve koruyucu çıkışlar

**Hedef:** B05 dikey akışını finans şartnamesinin bütün durumlarına genişletmek.

**Dosyalar:** FundingSettlement/BoundarySnapshot, MaintenanceCheck, LiquidationHandler, BracketTrigger, DueAction, GenerationGuard, RiskPolicyEvaluator; ilgili transaction ve yarış testleri.

- [ ] **B07.1** Mark risk kontrolünü UI’dan bağımsız işlet; maintenance ve B02 liquidation fonksiyonlarını uygula.
- [ ] **B07.2** Funding boundary qty snapshot + kesin rate geç gelmesi + AVAILABLE_FIRST ledger; unique(position,time).
- [ ] **B07.3** SL/TP mark trigger ve kardeş exit iptali; same step liquidation önceliği.
- [ ] **B07.4** Kısmi manuel close, ReleasedB, kalan bracket ve son dust; negatif partial residual politikasını şartnameye göre uygula.
- [ ] **B07.5** LIQUIDATING→LIQUIDATED ve quote yoksa pending; normal gap/forced gap sigorta karşı kaydı, A’yı sessiz tüketme yok.
- [ ] **B07.6** REEVALUATE/CLOSE_AT_DEADLINE due queue, first fill+ufuk, restart/double timer; eski generation re-entry reddi.
- [ ] **B07.7** Günlük kayıp/toplam notional/leverage/giriş sınırı; halt yeni girişleri durdursun, risk çıkışlarını durdurmasın. Close-all ayrı komut.

**Deneme/kabul:** Manual close/stop/liq yarışı tek q kapanışı; funding iki kez tek hareket; mark-last ayrımı; quote yokken sahte fill yok; deadline iki kez tek komut; eski proposal generation ile giriş reddi. Risk servisi Reporting kapanınca çalışır. Quantity ve ledger invariant’ları bütün yollar için sağlanır.

**Açıklama sorusu:** “Stop fiyatını gördük diye aynı fiyattan fill olduğumuzu neden söyleyemeyiz?”
**Devir:** F05 durum/exit reason fixture’ları; B08 complete ledger events. **İlk iş:** Tek mark maintenance check ve bir liquidation testi.

## B08 — Reporting, Redis, gateway ve SSE

**Hedef:** Yazma otoritesini bozmadan okunabilir uygulama API’si oluşturmak.

**Dosyalar:** Reporting inbox/projections/checkpoint/metrics/export/notification, Gateway routes/security/rate limits, SSE publisher/ring buffer, summary cache; local compose profilleri.

- [ ] **B08.1** LedgerTransactionPosted tek finans projection kaynağı; PositionClosed timeline olsun, kâr iki kez toplanmasın.
- [ ] **B08.2** Summary/equity/trade/funding-fee ayrımı, null metric durumları ve projectionAsOf/lag alanlarını yaz.
- [ ] **B08.3** Projection rebuild API export/checkpoint; duplicate/missing sequence davranışı; finans event’inde pause/alarm.
- [ ] **B08.4** Redis summary cache/key/TTL/invalidation ve identity limiter adapter; DB fallback ve quota fallback’i açık test et.
- [ ] **B08.5** Gateway auth/market/sim/strategy/reports route; internal route kapalı, CORS, request limits ve safe logging.
- [ ] **B08.6** Market/session SSE, Last-Event-ID/resync.required, heartbeat ve Bearer proxy; session ownership.
- [ ] **B08.7** Bildirim read ve authorized CSV export; formül injection önlemi. Sağlık/metric temelini görünür yap.

**Deneme/kabul:** Reporting kapat→işlem→aç→tek projection. Redis down bakiyeyi bozmaz. SSE gap sonrası doğru resync. Gateway emir POST’unu kör tekrar etmez. Summary currentEquity örneklemesi/asOf açık. Cross-user stream/export reddi.

**Açıklama sorusu:** “Dashboard neden hafif gecikebilir ama order risk hesabı aynı gecikmiş bakiyeye dayanamaz?”
**Devir:** K2, F06/F08; API gateway base path ve streaming fixture. **İlk iş:** Tek ledger event’ini tek reporting satırına işlemek.

## B09 — Strategy, piyasa tarama ve AI bağlantısı

**Hedef:** Model sonucunu kullanıcı onayına sunulan plana çevirmek; AI’ye emir yetkisi vermemek.

**Dosyalar:** ScanJob/AnalysisJob/Proposal, ModelClient port+fake/internal HTTP adapter, PolicyArtifact/validator, bundle pin, lease/cancel worker, proposal outbox.

- [ ] **B09.1** Analysis DTO/status ve PredictionResult schema’yı A01/A05 ile aynı fixture’dan doğrula.
- [ ] **B09.2** Fake modelle QUEUED→RUNNING→SUCCEEDED LONG/SHORT/WAIT ve teknik FAILED ayrımını yaz; fake açık etiketli.
- [ ] **B09.3** AsOf/coverage→WAITING_DATA→tek backfill; bundle pin ve feature schema gate.
- [ ] **B09.4** Sürümlü numeric policy ile edge/volatility/SL/TP/leverage niyeti; miktarı Simulation’a bırak.
- [ ] **B09.5** AI_PROPOSAL preview ve exact onay hattı; expired/generation/revision testleri.
- [ ] **B09.6** Scan universe snapshot/cursor/coverage/fairness/cancel; partial sonucu tüm piyasa sanma.
- [ ] **B09.7** Açıklama timeout/template; taze olmayan önerinin TTL’sini metin beklerken uzatma; A05 gerçek internal client ile contract test.

**Deneme/kabul:** Scan/analysis başlatmak order sayısını değiştirmez; WAIT proposal yaratmaz; model unavailable teknik hata; score’dan para hesabı çıkmaz. Feature/model versions tek istekte karışmaz. Development candidate serving’in ACTIVE model kalite onayı olmadığı kaydedilir; A09 production promotion kapısı korunur.

**Açıklama sorusu:** “Model LONG dediğinde neden Simulation yine de planı reddedebilir?”
**Devir:** K3; F07 AnalysisView/ProposalView; A05 uyumluluk raporu. **İlk iş:** Tek fake WAIT response ve endpoint status testi.

## B10 — Eğitim yönetimi facade’ı

**Hedef:** Eğitim işlemlerini normal trading akışından ayırarak yetkili erişim sağlamak; PDF parse bu servisin işi değildir.

**Dosyalar:** TrainingFacade, TrainingProvider port, multipart streaming limits, authority guard, progress projection, training API DTO’ları; fake provider integration fixtures.

- [ ] **B10.1** README 7.4 endpoint’lerini source/example/dataset/run/model gruplarına eşleştir; A06–A09 owner’larını not et.
- [ ] **B10.2** USER 403; TRAINER ingest/train; MODEL_PUBLISH promotion/rollback kontrolünü uygula.
- [ ] **B10.3** Bounded upload/import isteğini internal AI’ye taşı; durable job sonrası 202; uzun parse bekleme yok.
- [ ] **B10.4** Review/freeze/train/cancel/detail façade; idempotency scope ve error mapping aynı olsun.
- [ ] **B10.5** training.jobs.v1 projection; status ve stage’i birbirine karıştırma; actual detail AI otoritesinden.
- [ ] **B10.6** Promotion expectedAliasVersion ve audit; eski planın model/evidence geçmişini değiştirme.
- [ ] **B10.7** Eğitim servisi kapalıyken hazır model/manual trade etkilenmesin; gerçek provider bağlantısı hazır oldukça fake’in yerine tak.

**Deneme/kabul:** Büyük/bozuk body route limit, cross-user source erişimi, unauthorized publish, duplicate request, cancel ve job failure. Trading request’inde PDF alanı yok. Spring PDF extraction veya model fit yapmaz. Fake eğitim sonucu gerçek eğitim diye raporlanmaz.

**Açıklama sorusu:** “Dosya yüklemede 202 aldık; bu neden model eğitildi anlamına gelmez?”
**Devir:** F09 training API; A06–A09 status/permission geri bildirimi. **İlk iş:** Yalnız USER reddi ve fake source job endpoint’i.

## B11 — Tarihsel replay ve model değerlendirme köprüsü

**Hedef:** Tek Java finans çekirdeğini geçmiş veri ve AI eval için yeniden kullanmak; yeni Python PnL motoru yazmamak.

**Dosyalar:** VirtualClock, ReplayJob, DatasetManifestReader, PolicyIntentReader, EngineInputReplay, FidelityReport, internal replay-evaluations endpoint/artifact export.

- [ ] **B11.1** Clock injection, seed ve immutable dataset/policy/rule hash’lerini run’a bağla; wall clock bağımlılıklarını çıkar.
- [ ] **B11.2** Önce sentetik recorded input journal’ını aynı sırada tekrar uygula; aynı ledger sonucu.
- [ ] **B11.3** Timestamp tie policy: funding boundary/settlement/mark/SLTP/quote/entry/candle düzenini şartnameye göre uygula.
- [ ] **B11.4** RECORDED_TICKS/PAIRED_OHLC/TRADE_ONLY kalite ayrımı; same bar SL+TP, missing mark ve lookahead testleri.
- [ ] **B11.5** Policy intent artifact→finance engine ve bounded async job/cancel; replay başka session’a emir açamaz.
- [ ] **B11.6** AI internal eval request→202 job→result artifact/metrics; A09’a fixture ver. Historical dataset+intent önce sentetik olabilir.
- [ ] **B11.7** Authorized result/CSV, net/gross/cost/insurance ve ambiguousBarCount; frontend replay API’siyle aynı sonuç anlamı.

**Deneme/kabul:** Aynı input/hash/seed aynı sonuç; kapalı mum t sinyali aynı mumun en iyi fiyatından fill olmaz. Eksik mark’ta kesin liquidation iddiası yok. Cancel yarım sonucu başarı diye yayınlamaz. B11 A09’u beklemeden synthetic fixture ile tamamlanabilir; karşılıklı gerçek bağlantı B12’de.

**Açıklama sorusu:** “OHLC mumunda hem stop hem hedef görüldüyse niye sonucu kesin bilemeyiz?”
**Devir:** K5 için A09 finance eval artifact; F08 replay durumları. **İlk iş:** Tek sanal saat ve iki tick’in deterministik sırası.

## B12 — Entegrasyon, dayanıklılık ve backend teslimi

**Hedef:** Önceki görevlerin birlikte çalıştığını kanıtlamak; test sayısı şişirmek değil kritik riskleri kapatmak.

**Dosyalar:** compose profiles, dependency locks, `.env.example`, meaningful integration/contract tests, restore/operations notları, CI pipeline, progress kabul kaydı.

- [ ] **B12.1** B01–B11 checklist ve fixture/gerçek entegrasyon borçlarını tek listede gözden geçir; eksik olanı bitmeden DONE yazma.
- [ ] **B12.2** Auth→session→manual/AI plan→preview→submit→close→ledger→report uçtan uca, F10 ile doğrula.
- [ ] **B12.3** Kafka/outbox crash, duplicate funding/submit, DB transaction rollback, Redis down, provider stale, AI unavailable yarışlarını çalıştır.
- [ ] **B12.4** A10 ile PDF store/training worker kapalı test; inference hazırken sanal işlemler sürsün. Training facade ile gerçek küçük iş yürüsün.
- [ ] **B12.5** Migration/backup restore, ledger rebuild, projection rebuild, dependency locks ve offline-demo’yu temiz ortamda dene.
- [ ] **B12.6** Consumer lag, engine latency, queue age, memory ve data quality ölç; hedef ile ölçülmüş sonucu ayrı yaz.
- [ ] **B12.7** Contract version, commit, geçen/kalınan testler ve çalıştırma sırasını kayıt altına al; secrets paylaşma.

**Deneme/kabul:** F01–F15 uygulama testleri, sıfır ledger imbalance, çift parasal etki yok, cross-user erişim yok, stale veriyle uydurma fill yok. Public provider erişimi yoksa o test açık sınırlama olarak kalır. Java/React/AI uçtan uca bağlanmadıysa “tüm sistem tamamlandı” denmez.

**Açıklama sorusu:** “Bir işlem yanlış görünürse sırasıyla hangi requestId, plan, command, event ve ledger kayıtlarını izlersin?”
**Devir:** K6 kanıtları. **İlk iş:** Sadece entegrasyon borç listesi; bütün projeyi baştan yazma yok.

## Kaldığın yer kaydı

Her çalışma sonunda şu alanları doldur: görev/alt adım, durum, dosyalar, kendi çalıştırdığın komut, gördüğün sonuç, açıklayamadığın nokta, contract sürümü, başka paketi bekleyen iş, sonraki tek adım. Örnek durum `B05.3 IN_PROGRESS`; test görmeden `B05 DONE` yazılmaz. Ortak kontrol noktası şablonu çalışma kılavuzundadır.

Bu rehberi alan asistanın ilk cevabı tüm backend kodu olmamalıdır. Yeni başlangıçta yalnız **B01.1**; devamda kanıtlanmış son alt görevden sonraki adım.
