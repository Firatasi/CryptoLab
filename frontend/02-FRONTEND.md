# CryptoLab — Frontend paketi uygulama şartnamesi

Sürüm 3.0 · Ortak protokol: [00-ANA-PROJE.md](00-ANA-PROJE.md). Finans otoritesi: [01-BACKEND.md](01-BACKEND.md). Eğitim akışı: [03-AI.md](03-AI.md).

Frontend React + TypeScript + Vite uygulamasıdır. Kullanıcıya açık uygulama sanal işlem uygulamasıdır. Eğitim yönetimi yalnız yetkili kullanıcı bölümüdür. İşlem ekranında PDF yükleme, doküman seçme, kaynak arama veya RAG yoktur. Borsaya emir gönderme veya gerçek hesap bağlama sayfası yoktur.

## 1. Teknik düzen

`src/app` router, provider ve uygulama layout; `src/features/auth`, `sessions`, `market`, `trading`, `strategy`, `reports`, `replay`, `training`; `src/shared/api`, `contracts`, `ui`, `formatting`, `stream`.

Her feature altında `api`, `components`, `hooks`, `pages`, `types` gerektiği kadar oluşturulur. Bir ekranı gereksiz onlarca küçük dosyaya bölmek öğrenme hedefi değildir. Finans formülleri shared utils içine kopyalanmaz. Backend entity’si değil OpenAPI’den türetilmiş DTO kullanılır.

Başlangıçta state/props/useState/fetch ile küçük ekran; ardından server state için TanStack Query adapter’ı seçilebilir. Bu şartname query library’nin sürümüne bağımlı değildir. TypeScript strict, ESLint, formatter, unit/component test ve production build kapısı vardır. Paket ve lock dosyası sürümü uygulama başlangıcında kaydedilir.

## 2. Görsel dil

Açık ve doğal palet: sayfa #F5F7FA, kart #FFFFFF, ana metin #172B3A, ikincil #64748B, vurgu #2563EB, olumlu #167D6A, olumsuz #C2414B, uyarı #B7791F. Sayısal kolonlarda tabular-nums. Kazanç/kayıp yalnız renk değil işaret ve metinle gösterilir. LONG/SHORT yazıları korunur, Türkçe kısa açıklama eklenebilir.

Desktop: sol navigasyon; üstte session/bağlantı; ana alanda piyasa-grafik, sağda emir formu; altta pozisyon/emir sekmeleri. Mobil: fiyat/grafik, işlem, pozisyonlar ayrı sekmeler. Kritik onay alanları dar ekranda gizlenmez. Hover olmadan erişilemeyen risk bilgisi bırakılmaz.

Her sayfanın loading, empty, error, stale ve permission-denied tasarımı bulunur. Beklerken sahte fiyat veya sahte başarılı işlem animasyonu yoktur. Kullanıcıya “Canlı veriyle simülasyon · Sanal USDT” görünür; teknik traceId yalnız hata ayrıntısında bulunur.

## 3. Route haritası

| Route | Rol | Ekran / temel API |
|---|---|---|
| /register | anonim | POST auth/register |
| /login | anonim | POST auth/login |
| /sessions | USER+ | GET/POST sim/sessions |
| /market | USER+ | Dinamik market/instruments |
| /trade/:sessionId | session sahibi | Grafik, manuel işlem, wallet, positions/orders |
| /analysis/:sessionId | session sahibi | strategy/analyses, proposal |
| /scan/:sessionId | session sahibi | strategy/scans ve adaylar |
| /history/:sessionId | session sahibi | Emir/fill/ledger/funding timeline |
| /reports/:sessionId | session sahibi | reports summary/equity/trades |
| /replay | USER+ | İzinli dataset ve sim/replays |
| /training/sources | TRAINER/ADMIN | Eğitim PDF kaynakları |
| /training/datasets | TRAINER/ADMIN | Örnek review ve dataset freeze |
| /training/runs | TRAINER/ADMIN | Eğitim başlat/izle/iptal |
| /training/models | TRAINER/ADMIN | Model kartı; publish ayrı authority |
| /settings | USER+ | Dil, timezone ve görünüm |

Route guard yalnız UX’tir; backend de yetki kontrol eder. Yetkisiz eğitim route’una doğrudan URL ile girildiğinde form render edilmez, API 403 ele alınır. USER navigasyonunda training menüsü görünmez. Her kullanıcı yalnız kendi session/report/export’unu görür.

## 4. Auth ve API istemcisi

`apiClient` base URL `/api/v1`; same-origin gateway/Vite proxy. Access token yalnız memory; refresh cookie HttpOnly. Token localStorage’a kalıcı yazılmaz. Sayfa yenilemede refresh→me→router restore. 401’de eşzamanlı çağrılar tek refresh promise’i paylaşır; en fazla bir replay denemesi. Finans mutation’ı ancak aynı idempotency key/body korunarak yeniden gönderilebilir; yeni emir üretme yoktur.

Login başarısızlığında genel hata, alan validation ayrı. Logout local memory/cache/stream temizler; başka kullanıcı girişinde önceki session cache’i taşınmaz. Refresh isteğinin CSRF/Origin kuralları backend ile eşleşir. CORS sorununu çözmek için browser güvenliğini devre dışı bırakma.

ApiError ortak `{code,message,traceId,retryable,details}`. 400 alan hatası; 401 giriş; 403 erişim; 409 stale version/plan; 422 domain; 429 bekleme; 503 veri/servis. Her 409’a otomatik retry yoktur. Hata toast’ı tek başına yeterli değildir; formun ilgili yerinde kalıcı gerekçe gösterilir.

## 5. Ortak TypeScript anlamları

Kodlamada `contracts/openapi` kaynak alınarak tipler üretilir. Aşağıdaki tipler anlatım için sözleşme özetidir; ikinci bağımsız API standardı değildir.

```typescript
type DecimalString = string;
type HorizonSeconds = 60 | 900 | 3600 | 86400 | 604800;
type SessionMode = 'LIVE_PAPER' | 'HISTORICAL_REPLAY';
type SelectionMode = 'USER' | 'AI_SCAN';
type PlanMode = 'MANUAL' | 'AI_PLAN';
type Side = 'LONG' | 'SHORT';
type ExpiryPolicy = 'REEVALUATE' | 'CLOSE_AT_DEADLINE';
type SubmitOrderRequest = {
  planId: string;
  revision: number;
  planHash: string;
};
type AnalysisRequest = {
  sessionId: string;
  instrumentId: string;
  horizonSeconds: HorizonSeconds;
};
```

Instrument listesi string enum değildir. InstrumentId opaque string, symbol display. Para string’inin JS Number’a dönüşümü yalnız chart/display için ve sınırları bilerek yapılır. Finans bakiye toplamı, funding veya liquidation frontend’de hesaplanıp gerçekmiş gibi gösterilmez. PnL ve quantity server response’tan gelir.

### Form decimal parser

Text input’ta raw değer korunur. Türkçe ondalık virgülü normalize edilir; binlik ayraç otomatik tahmin edilmez. Önerilen arayüz: “Ondalık için virgül veya nokta kullanın; binlik ayraç girmeyin.” `1000,50`→`1000.50`; `1.000,50`, boş, bilimsel yazım ve iki ayraçlı değer açık validation ister. Eksi işareti margin/price için kabul edilmez. Server step/tick kontrolü nihai otoritedir.

Rakam gösterimi Intl formatıyla yapılabilir; gönderilen canonical string gösterimden geri parse edilmez. Yuvarlanan ekrandaki değer submit body’sine dönüşmez. Kopyalama düğmesi ham doğru değeri kopyalayabilir.

## 6. Session ekranı

Yeni session formu: başlangıç sanal sermaye, erişilebilir immutable rule profile ve risk policy. Örnek eğitim default’u 10.000 USDT; kullanıcı onaylar. POST /sim/sessions Idempotency-Key ile; 201’den sonra detail açılır. Reset eski ledger’i silmez; yeni session oluşturur.

Liste alanları ad/id kısa gösterim, başlangıç, mod, durum, rule profile ve tarih. `RUNNING`, `HALTED`, `COMPLETED` ayrı. Halt “yeni girişler durdu” demektir, pozisyon kapandı demek değildir. “Yeni girişleri durdur” ve “Tüm pozisyonları kapat” ayrı buton ve ayrı açıklamadır.

## 7. Piyasa ve dinamik varlık seçimi

SymbolSelector API cursor pagination + debounced search kullanır. Query cache key: exchange/filter/query/cursor. Search pending sırasında önceki sonuç yeni sorgunun sonucu gibi gösterilmez. Aynı symbol farklı venue satırlarında exchange ve contract etiketi gösterilir.

Kolonlar: symbol/venue, kontrat, last/mark, data quality, analiz uygunluğu, sanal işlem uygunluğu. Uygun olmayan varlık katalogdan yok olmaz; reason açık gösterilir. Yeni listelenen varlık için frontend deploy/enum değişikliği gerekmez.

Trade grafiği last/trade mum; mark, bid/ask ayrı etiket. Timeframe grafik seçimi analiz horizon’unu otomatik değiştirmez. Candle open/current overlay kullanılabilir ama “kapanmış” etiketi yanlış verilemez. Missing range grafikte boşluk veya işaret taşır, sahte düz fiyatla doldurulmaz.

## 8. Manuel emir formu

Alanlar: instrumentId, side, MARKET/LIMIT, marginBudget, leverage, limitPrice (LIMIT ise), stopLoss/takeProfit (profil gereksinimine göre), horizonSeconds, expiryPolicy. Bakiye ve risk limitleri Simulation query’sinden; Reporting gecikmiş rakamı emir bütçesi değildir.

“1.000 USDT teminat” etiketi açık, altında server preview notional. “Tüm kullanılabilir” işlemi backend fee-inclusive sizing/preview üzerinden; frontend margin=available atayıp ücreti unutmaz. Bu kolaylık endpoint desteklenmeden sahte tam bakiye düğmesi yapılmaz; ilk sürüm elle bütçe girilebilir.

Seçim/parametre değiştiğinde mevcut preview invalid edilir. Debounce hesap önizlemesine uygulanabilir; submit’e kendiliğinden basılmaz. MARKET formunda limit gönderilmez; LIMIT’te limit zorunlu. Profil izin vermediği kaldıraç disabled; bu UI sınırı backend testinin yerine geçmez.

### Onay state machine

`EDITING → PREVIEW_LOADING → PREVIEW_READY → SUBMITTING → ACCEPTED_PENDING → RESULT`.

Önizleme POST /sim/order-previews, source=MANUAL. Önizleme kartında varlık/venue, yön, q, notional, teminat, fee, leverage, SL/TP, yaklaşık liquidation, hedef süre, süre sonu davranışı, fiyat aralığı ve validUntil bulunur. “Sanal long aç / Sanal short aç” explicit submit eylemidir.

Submit yalnız `{planId,revision,planHash}` ve tek Idempotency-Key. Request başlarken key üretilir ve response bilinmezken korunur. Buton disable yalnız UX. 202 alınırsa command status GET ve session SSE takip edilir; pozisyonu optimistic ekleme. Command SUCCEEDED olsa bile limit NEW kalabilir. FILLED geldikten sonra gerçek response pozisyon gösterilir.

Timeout: “İşlem sonucu sorgulanıyor”; aynı key ile kontrollü tekrar/status. Yeni order göndermek için yeni key üretme. 409 REVALIDATION_REQUIRED: eski onay kaldırılır, kullanıcı yeni preview’ı görür. Otomatik yeni onay yoktur. Plan ttl sayaç sunucu zaman farkı kullanır; server expiry otoritedir.

## 9. AI analiz ekranı

Kullanıcı varlık ve hedef süre seçer, analiz ister. PDF, kitap, koleksiyon, eğitim veri yolu veya model dosyası alanı yoktur. Model sürümü bilgi olarak gösterilebilir; normal kullanıcının aktif modeli rastgele değiştirmesi yoktur.

Durumlar: QUEUED sıra; WAITING_DATA veri hazırlanıyor; RUNNING analiz; SUCCEEDED+LONG/SHORT öneri; SUCCEEDED+WAIT işlem önerisi yok; FAILED teknik hata; CANCELLED; EXPIRED. WAIT kartında gerekçe ve veri zamanı; “İşlem aç” yok. FAILED/MODEL_UNAVAILABLE, AI’nın piyasa görüşüymüş gibi WAIT diye gizlenmez.

Numeric result/explanation ayrı: numeric hazır, explanation PENDING ise metin alanı bekler; version ve snapshot aynı kalır. Açıklama TEMPLATE ise “Veriye dayalı şablon açıklama” etiketi; model eğitimliymiş gibi sunulmaz. Kitap/sayfa alıntı chip’i yoktur. Model eğitim kaynağı metadata’sı TRAINER model kartındadır, işlem kanıtı olarak gösterilmez.

AI önerisi accept doğrudan order oluşturmaz. POST /sim/order-previews source=AI_PROPOSAL → aynı onay kartı → kullanıcı submit. Değer değiştirmek manuel plan düzenlemesine geçer; AI proposal ID’si köken için tutulabilir. Model olasılığı kesin kazanma yüzdesi diye etiketlenmez.

## 10. Tarama ekranı

Girdi: session, horizon, kullanıcı filtreleri (venue/contract/likidite kriterleri). Scan işi `totalKnown,evaluatedCount,skippedCount,coverage,nextCursor,status` döndürür. Aday kartında instrument, action/score meaning, dataAsOf, model version, reason. Skipped sayısı başarısız kullanıcı işlemi değildir; yeterli veri yok gibi nedenler açılabilir.

“Taramayı başlat” order çağırmaz. “Taramayı durdur” açık position kapatmaz. Tüm katalog bir anda değerlendirilemediyse PARTIAL coverage dürüst görünür. UI tek yüklenen sayfayı bütün piyasa diye etiketlemez. Aday seçimi ister manuel formu doldurur, ister analiz detayına gider; otomatik submit yoktur.

## 11. Pozisyonlar ve açık emirler

Pozisyon tablosu: symbol/venue, yön, q, E, mark/asOf, margin, unrealized PnL, maintenance, yaklaşık liquidation, SL/TP, hedef deadline, state, data quality. Aynı kontratta one-way tek aktif position kuralı formda açıklanır.

“Kapat” tam kalan miktar; “Kısmi kapat” input quantity. expectedVersion server response’tan gönderilir. 202 sonrası closeRequested görünür, quantity=0 yapılmaz. Stale quote yüzünden EXIT_PENDING_DATA ise kullanıcıya bekleme nedeni gösterilir. İptal ve kapama ayrı kaynaklardır; entry limit iptal etmek açık pozisyonu kapatmaz.

SL/TP düzenlemesi version conflict’te yeniden detail çekip kullanıcıya güncel değer gösterir. Deadline REEVALUATE pozisyonu otomatik kapatmaz; “hedef süre doldu” bildirimi. CLOSE_AT_DEADLINE önceden seçilmiş azaltıcı çıkış; gerçekleşme gelmeden kapandı etiketi yoktur.

V1 full-fill-or-reject modelidir. Kısmi kullanıcı kapanışı position q’yu azaltır ama close order FILLED olur. UI desteklenmeyen PARTIALLY_FILLED state’i eklemez.

## 12. SSE, polling ve state tutarlılığı

Market stream seçili instrument’a göre açılır. Session stream owner session’a göre; değiştirilince AbortController eski stream’i kapatır. Unmount cleanup zorunlu. Ağ reconnect jitter/backoff; auth expiry single-flight refresh; document visibility değişikliği kritik backend risk takibini etkilemez.

Market görüntü yenileme 250–500 ms örnek throttle olabilir; backend tüm kabul edilmiş mark’ları işler. Session stream’in changedResources alanı query invalidation tetikler. sequence gap→snapshot refetch; eski sequence discard. Infinite replay varsayımı yoktur. Last-Event-ID ring’de bulunamazsa resync.required uygulanır.

Server state query cache, UI state local, stream latest cache olarak ayrılır. Wallet için dört bağımsız state kopyası yoktur. SSE yoksa bounded polling fallback; `ConnectionStatus` bunu belirtir. Büyük grafiğe gelen tick bütün component ağacını render etmemelidir; selector/memo ancak ölçüme göre kullanılır.

## 13. Rapor ve tarihsel test

Raporlar gross/net, ücret/funding/tasfiye ücreti/insurance adjustment ayrı; margin ROI ve session return ayrı. projectionAsOf ve lag görünür. Zero trade, no-loss profit factor ve eksik equity örnekleri doğru null/etiketle gösterilir. CSV oluşturma async job; yalnız owner download path’i.

Replay formunda izinli dataset/profile/policy/period/seed seçilir. Bu araştırma run’ı normal session order formuyla karışmaz. Sonuç RECORDED_TICKS/PAIRED_OHLC/TRADE_ONLY ve EXACT_WITHIN_MODEL/DEGRADED/INDETERMINATE gibi belgelenmiş fidelity bilgisini taşır. Mum içi sıra bilinmiyorsa kesin kazanç grafiği gibi sunma; ambiguousBarCount göster.

## 14. Eğitim yönetimi — normal işlemden ayrı

### Kaynak ekranı
Sadece TRAINER: yerel PDF veya izinli URL, başlık, yazar/tarih/kullanım açıklaması. Async source job status ve stage ayrı gösterilir: örneğin RUNNING/EXTRACTING, RUNNING/REVIEW_REQUIRED, SUCCEEDED/READY_FOR_EXAMPLES; başarısızlık job status=FAILED. PDF eklenince toast “Eğitim kaynağı hazırlandı”; “Model eğitildi” denmez. Parse/OCR kalite uyarısı, sayfa önizlemesi, problemli içerik dışlama. Kaynaklar inference çağrısına eklenmez.

### Veri seti ekranı
Sayfa kaynaklı örneklerde task, input, expectedOutput, source page, reviewer, kalite ve split group. ACCEPT/REJECT/EDIT; toplu onay görmeden rastgele otomatik etiketlemez. Sayısal dataset ekranında coverage, gaps, train/validation/test ve unseen asset family görünür. Freeze sonrası sürüm immutable.

### Eğitim ekranı
Task NUMERIC veya EXPLANATION_SFT; dataset/model revision/config/seed. Eğitim start/cancel idempotent. Progress gerçek stage; epoch yoksa sahte yüzde yok. Failure detail kullanıcıya anlaşılır, log secrets içermez. CPU/RAM/GPU ölçümü varsa gösterilir; tahmini süre ölçülmediyse uydurulmaz.

### Model kartı
CANDIDATE/VALIDATED/SHADOW/ACTIVE/REJECTED/RETIRED; dataset/version/hash, baseline karşılaştırması, unseen/gelecek dönem sonuçları, finance replay ve risk ihlali. Publish yalnız MODEL_PUBLISH; expectedAliasVersion çatışması yeniden onay ister. Rollback eski model bundle’a gider; açık pozisyonları değiştirmez. Eğitim kaynak listesi model lineage’dır; kullanıcı işleminin o kitaba dayalı olduğuna kanıt değildir.

## 15. Bileşen kabul listesi

| Component | Girdi | Kritik davranış |
|---|---|---|
| SessionBadge | SessionView | Canlı veri de olsa sanal para etiketi |
| InstrumentSelector | paged API | Dynamic ID; same symbol venue ayrımı |
| MarketSummary | snapshot | Mark/last/bid/ask ve stale ayrımı |
| CandleChart | finalized bars/gaps | Eksik veri uydurmama |
| OrderTicket | form/profile | Decimal raw input; change invalidates preview |
| ExecutionPlanCard | server plan | Bütün onaylı alanlar; TTL; değişince reset |
| CommandStatus | command/order | 202 ≠ fill; NEW limit ayrımı |
| PositionTable | server snapshot | Pending close ≠ closed |
| AnalysisCard | AnalysisView | WAIT/FAILED/explanation ayrımı |
| ScanCoverage | ScanJob | Kısmi tarama açık |
| ReportSummary | projection | source time/lag ve net/gross |
| TrainingJobPanel | JobView | Gerçek stage; ingestion ≠ training |

## 16. Test planı

Unit: Decimal input, timezone gösterimi, expiry calculation, reason code mapping. Component: mandatory alanlar, preview invalidation, stale disabled entry, WAIT no-submit, role nav, conditional limit alanı. API mock testleri ana OpenAPI fixture’larından gelir; frontend’in kendi farklı DTO’su kullanılmaz.

E2E: register/login/refresh; session; long/short preview ve submit; double click; network timeout+aynı key; limit cancel; partial close; liquidation banner; SSE reconnect; logout→başka user cache izolasyonu; unknown instrument; training 403; PDF’siz USER tam akış; eğitim worker kapalıyken trade UI.

Erişilebilirlik: form label, semantic button, focus trap/restore, klavye navigasyonu, hata live region, kontrast, renkten bağımsız durum. Close ve halt yan yana iken yanlışlıkla tetiklemeyi azaltan açık isimler kullanılır. Test yalnız screenshot snapshot değildir; gönderilen payload/status değişimleri assert edilir.

Build kapısı: typecheck, lint, test, production build ve responsive smoke. Finans fixture’larını JS içinde yeniden üretip ikinci finans motoru kurmak test sayılmaz. Backend contract fixture’larının doğru gösterimini doğrula.

## 17. Öğrenme sırası ve teslim

F01 statik PriceCard/props → F02 useState/form → F03 fetch loading/error → F04 router/auth → F05 manual preview/submit → F06 query invalidation → F07 SSE cleanup → F08 chart/gaps → F09 AI/scan → F10 report/replay → F11 ayrı TRAINER ekranları → F12 E2E.

Her aşamada “bu state sunucudan mı, UI’dan mı, stream’den mi geliyor?” sorusunu cevapla. useEffect türetilebilir her değere eklenmez. API sonucu değiştikçe karmaşık local state senkronizasyonu yerine tek kaynak ve refetch kullan.

Teslim: çalışabilir frontend paketi, lock file, `.env.example` (yalnız public gateway adresi), generated types, fixture mocks, test raporu ve geliştirme komutları. API anahtarı/secret frontend bundle’a giremez. Bu MD kod üretmeye yeterli şartnamedir; kodun yazıldığına dair bir beyan değildir.
