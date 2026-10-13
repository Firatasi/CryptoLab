# CryptoLab — Backend paketi uygulama şartnamesi

Sürüm 3.0 · Bu belge [ana proje sözleşmesi](00-ANA-PROJE.md) ile birlikte uygulanır. Frontend: [02-FRONTEND.md](02-FRONTEND.md); AI: [03-AI.md](03-AI.md).

Backend Java/Spring Boot mikroservis paketidir. Sanal hesap ve finans doğruluğunun sahibi bu pakettir. Bu belge içinde açıklanan testler kodlamada uygulanacak kabul ölçütleridir; henüz çalışan uygulama testi yapıldığı iddia edilmez.

## 1. Repo ve modüller

Önerilen monorepo kökleri `backend/`, `frontend/`, `ai/`, `contracts/`, `infra/`. Ayrı repolar seçilirse aynı sözleşme sürümü paketlenir; endpoint’ler farklılaştırılmaz.

Backend Maven aggregate modülleri:
- `finance-core`: saf Java, BigDecimal, clock bağımsız hesaplar; JPA/Kafka/HTTP bağımlılığı yok.
- `identity-service`: kayıt, kimlik ve oturum.
- `market-data-service`: provider adapter, katalog, coverage ve veri yayımı.
- `simulation-service`: hesap, risk, emir, ledger, pozisyon ve replay.
- `strategy-service`: scan/analysis job, AI istemcisi, policy, proposal ve training facade.
- `reporting-service`: Kafka read model, metrik, bildirim ve export.
- `api-gateway`: dış erişim ve SSE proxy.
- `test-support`: ortak sentetik fixture/schema yükleyici; production entity paylaşımı yok.

Her iş servisinde `domain`, `application`, `infrastructure`, `api` paketleri. Domain invariants controller’a veya Kafka listener’a konmaz. Constructor injection, explicit transaction, DTO/domain ayrımı, global exception handler ve Flyway kullanılır. Servisler birbirlerinin entity/repository sınıflarını import etmez; shared DTO jar üzerinden tüm domain’i birbirine kilitleme. Schema’dan üretilmiş istemci/tip paylaşımı uygundur.

## 2. Servis sınırları ve DB sahipliği

Yerelde tek PostgreSQL instance içinde identity_db, market_db, simulation_db, strategy_db, reporting_db, model_db ayrı veritabanları ve ayrı kullanıcılar olabilir. Her servis yalnız kendi DB’sine bağlanır. Model DB AI paketinin mülkiyetindedir; Spring doğrudan okumaz. Cross-DB join/foreign key yoktur.

Kafka event’leri asenkron bilgi dağıtır; kullanıcı onayının, bakiyenin ve ledger’in atomik güncellenmesini dağıtmaz. Bu sebeple wallet/order/risk aynı Simulation transaction’ında kalır. Redis hiçbir zaman finans doğruluk kaynağı veya tek kilit mekanizması değildir.

## 3. Identity

### Tablolar
`users(id UUID,normalized_email UNIQUE,display_name,password_hash,status,created_at,version)`; `user_roles(user_id,role)`; `refresh_sessions(id,user_id,token_hash UNIQUE,family_id,expires_at,revoked_at,replaced_by,version)`; `security_audit(id,actor_id,action,result,trace_id,created_at)`.

Self-register USER üretir. TRAINER ve MODEL_PUBLISH authority yalnız kontrollü yönetim işlemiyle verilir. Parola encoder standart güvenilir kütüphaneden; düz parola/token loglanmaz. Access JWT kısa TTL, refresh token random ve DB’de hash; refresh cookie HttpOnly, uygun SameSite/Secure/Path. Geliştirme HTTP farkı yalnız dev profile’dadır.

### Akışlar
Register: normalize→validate→hash→insert; aynı email yarışında unique constraint 409. Login: rate limiter→password verify→refresh hash kaydı→access issue. Refresh transaction: token satırını kilitle→expiry/revocation kontrol→rotation→eski token revoke. Eski refresh tekrar kullanılırsa family iptal. Logout refresh family iptal eder; mevcut access token süresi bitmeden otomatik her yerde silinmiş gibi davranılmaz.

Her servis issuer, audience, signature, expiry doğrular. JWT decode etmek yeterli değildir. Ownership sorgusu `resource.id AND owner_id=subject` ile uygulanır. Gateway doğrulamasına güvenip downstream kontrolünü kaldırma. Body userId/role veya X-User-Id header’ı yetki oluşturmaz.

Redis login rate limiter TTL taşır; çöküşte sınırlı yerel fallback, sınırsız login yok. Identity için yapay Kafka olayı zorunlu değildir. Kullanıcı kaydı sanal bakiyeyi otomatik değiştirmez; session creation ayrı use case’dir.

Testler: email yarış, yanlış parola, sahte issuer/audience, expired JWT, refresh replay, CSRF/Origin, USER→TRAINER erişim reddi, kullanıcılar arası session erişim reddi.

## 4. Market Data

### Provider sınırı
MarketSource port: `listInstruments`, `loadCandles`, `loadMarkHistory`, `loadFundingHistory`, `connectMarks`, `connectQuotes`. FixtureAdapter ve BinanceAdapter aynı normalize DTO’yu üretir. REST/WS URL ve rate-limit konfigürasyonu tek adapter’da tutulur. Diğer servis Binance JSON alanlarını bilmez.

Uygulama başlangıcında resmi Binance piyasa dokümanından `exchangeInfo`, `premiumIndex`, `klines`, `markPriceKlines`, `fundingRate`, `fundingInfo`, server-time ve ilgili stream adresleri doğrulanır. Sabit blog limitleri kullanılmaz. Hesaba özel komisyon/kademe gerçekmiş gibi anahtarsız üretilmez; sürümlü EDUCATIONAL/FROZEN_REFERENCE profil kullanılır. `pricePrecision` yerine tickSize, quantity precision yerine stepSize kontrol edilir. `maintMarginPercent` tam bracket tablosu yerine kullanılmaz.

### Şema ve indeksler
- instruments: canonical id, exchange/symbol/market/contract identity, base/quote/settlement, status, onboardAt, observedAt.
- instrument_versions: instrument_id, version, raw metadata/filters, checksum, observedAt, effectiveAt nullable.
- candles: instrument_id, interval, price_kind, open_time, close_time, available_at, OHLC/volume, finalized. Unique `(instrument_id,interval,price_kind,open_time)`.
- funding_rates: instrument_id, settlement_time, rate, settlement_mark, source_id. Unique `(instrument_id,settlement_time)`.
- data_gaps: instrument, stream, start/end, reason, resolution_status.
- coverage_ranges: instrument, priceKind, interval, covered_from/to, completeness.
- rule_profiles: id, version, immutable JSON, source_type, checksum, fetchedAt.
- export_jobs ve recording_manifests: snapshot range, artifact refs/hash, source/quality, status.

Mum aralığı ve priceKind indeks kapsamına dahildir. Mark ve trade aynı anahtarda birbirini ezmez. Tick/quote arşivi compressed dosyada saklanabilir; dosyaların content hash ve range manifesti DB’de kalır.

### Başlatma ve dinamik katalog
Saat farkını ölç→metadata snapshot→diff→listed/changed/delisted event→rule/capability hesapla→stream aboneliği→kapalı mum backfill→FRESH gate. Yeni varlık için code enum eklenmez. Re-list farklı kontrat ise farklı kimliktir. Delist geçmiş kayıtları silmez; yeni entry kapatılır, bekleyen entry iptal edilir. Geçerli settlement verisi yokken açık pozisyona uydurma fiyat atanmaz.

### Abonelik bütçesi
Hot açık pozisyon/pending exit, warm tarama/feature, cold katalog/demand history. Aktif kullanıcının tarayıcıyı kapatması pozisyon mark takibini kapatmaz. Rate limit nedeniyle stream kısıtlanınca yeni analiz/giriş önce kısılır. Aktif risk takibi karşılanamıyorsa yeni girişler durur ve gap kaydedilir.

Backfill aralığı immutable job’dır; aynı missing range için single-flight. Sayfalama sınırlarındaki tekrar unique ile temizlenir; kapanmamış mum final training’e girmez. Başka borsadan history boşluğu sessiz tamamlanmaz. Günlük/haftalık UTC kapanışları gerçekten tamamlanmadan kullanılamaz.

Funding: nextFundingTime yalnız scheduler ipucudur. Her varlık için sabit 8 saat varsayılmaz. Boundary qty snapshot Simulation’da; kesin oran sonra gelirse business key ile settlement. Rate gecikmesi PENDING ve quality reason olarak görünür.

### Redis ve hata
Cache örnekleri `market:mark:{instrumentId}`, `market:quote:{instrumentId}`, `market:catalog:{version}`. Örnek TTL 5s/2s/300s; payload source/receive time taşır. Eski DB quote’a yeni timestamp basmak yasak. 429 Retry-After, bounded jitter retry, 5xx ve ban durumu ayrı; sağlıksız loop ile provider bombardımanı yok.

Market stream event’leri ayrı topic’lerde toplam sıraya sahip değildir. Connection epoch, source sequence/updateId, sourceTime ve payload hash dedupe içindir. Aynı timestamp farklı fiyatları yalnız timestamp key’iyle silme. Malformed payload quarantine + gap; son geçerli fiyat “yeni fiyat” olarak yeniden yayımlanmaz.

## 5. Simulation domain’i

### Tablolar
| Tablo | Temel alan ve kısıt |
|---|---|
| simulation_sessions | id,owner_id,mode,initial_deposit,rule_profile_id,risk_policy_id,status,engine_sequence,version |
| wallet_accounts | session_id,kind AVAILABLE/RESERVED/ISOLATED/SYSTEM,position_id?,balance; unique hesap kimliği |
| execution_plans | plan_id,revision,canonical_json,hash,position_generation,valid_until,state |
| approvals | id,owner_id,plan_id,revision,hash,consumed_command_id; unique(plan_id,revision) |
| commands | id,session_id,type,payload,status,reason,created_at |
| orders | id,session_id,instrument_id,side,type,q,status,reserved_amount,position_id?,reduce_only,version |
| fills | order_id,q,price,fee,quote_id,execution_model_version,engine_sequence,time |
| positions | id,session_id,instrument_id,side,q,E,original_margin,initial_margin_remaining,state,generation,version,deadline_at |
| funding_settlements | position_id,time,q_at_boundary,rate,mark,amount,from_A,from_B; unique(position_id,time) |
| journal_transactions | id,business_key UNIQUE,effective_at,recorded_at,reason |
| postings | transaction_id,account_id,amount,currency; transaction toplamı sıfır |
| due_actions | position_id,generation,deadline_at,kind,status; unique(position_id,generation,kind) |
| engine_input_journal | session_id,accepted_sequence,input_type,source_ref,payload/hash |
| inbox / outbox | consumer+event_id unique / immutable event + publishing state |
| idempotency_records | subject,route,scope,key,request_hash,result_ref unique |
| instrument_generations | session_id,instrument_id,generation; close invalidation otoritesi |

Isolated cash B’nin otoritesi wallet_accounts’tır; position cache’i varsa aynı transaction’da güncellenir. Aktif position `(session_id,instrument_id)` partial unique. Aynı kontratta ölçekleyerek ikinci giriş bu sürümde yok; aynı yönde artırma veya ters giriş de reddedilir. Yeni giriş önce mevcut position kapanınca, yeni onayla yapılır. Çok farklı varlıkta açık pozisyon sayısı risk politikasıdır, katalog limiti değildir.

### Transaction akışı
1. Controller şema ve kimliği doğrular. Request’te fiyat/fee/PnL kabul edilmez; yalnız planId/revision/hash alınır.
2. Session row lock; idempotency kaydı kontrolü; plan sahibi/session/generation/expiry/state, güncel data ve risk kontrol edilir.
3. Approval audit + command + idempotency kaydı atomik yazılır, plan consumed olur; commit sonra 202.
4. Session writer komutu alır, güncel mark/quote ve bütçeyle yeniden değerlendirir. Bu arada fiyat değişmişse REVALIDATION_REQUIRED ile reject; quantity artırarak onayı telafi etmez.
5. Market fill veya limit reserve, ledger/fill/position/bracket/outbox tek transaction. Ücret/margin yetersizse hiçbir yarım bakiye hareketi olmaz.
6. REST/SSE status güncellenir. Async command kabulü pozisyon açıldığı anlamına gelmez.

Kritik finans adımında dış HTTP/AI çağrısı yapılmaz. Gerekli veriler önce alınır ve sürümlü snapshot olarak gate’den geçer. Session row lock yerelde yeterlidir; yatay ölçek için sahiplik/partition tasarımı geliştirilse bile DB unique ve transaction korunur.

### Limit reserve
A→H, fill’de H→B ve H→fee, kalan H→A; cancel/reject H→A. Frozen quantity ve maliyet toleransını aşan fill yok. Yeni özsermaye H’yi içerir fakat H funding için harcanabilir değildir. Cancel/fill aynı kilitte yalnız tek terminal geçiş üretir. Reserve bekleyen emir boyunca kalıcıdır, Redis TTL’siyle silinmez.

### Kullanıcı kapanışı ve bracket
close isteği idempotent ve version-aware’dır. positionGeneration artırılır; eski planlar Simulation’da geçersizleşir. Yeni close order reduce-only ve quantity≤kalan q. Kullanıcı tam kapatmak isterken otomatik ters position açılmaz. Eski proposal olayı geç gelse bile generation gate engeller.

Bracket değişikliği kullanıcı eylemidir; expectedVersion, fiyat yönü ve risk kontrolüyle atomik uygulanır. Değişiklik audit edilir. Stop/target çıkışında kardeş emir iptal edilir; kısmi user close sonrasında bracket remaining quantity’ye iner. Funding/SL/TP/manual close/liquidation yarışını aynı position/session kilidi çözer.

### Risk ve durum
Session `RUNNING | HALTED | COMPLETED`. HALTED yalnız yeni entry’leri durdurur; azaltıcı çıkış, mark risk ve funding sürer. Halt sebebi MANUAL/RISK_LIMIT/DATA_GAP ayrı saklanır. Resume aktif engel varsa 422. close-all farklı açık eylemdir; child command başına status döner.

Günlük kayıp hesabı Simulation’da UTC başlangıç equity ve net flow düzeltmesiyle tutulur; Reporting kapalıyken risk limiti kaybolmaz. Session içinde dış para transferi yok; reset yeni session’dır. Başlangıç eğitim risk örneği maxLeverage=10, maxConcurrentPositions=2; bunlar borsa maksimumu değildir.

## 6. Strategy orkestrasyonu

### Veri modeli
scan_jobs(id,owner,session,horizon,universe_version,cursor,status,counts,created_at); analysis_jobs(id,owner,session,instrument,horizon,as_of,model_bundle_id,feature_snapshot_id,status,error); proposals(id,revision,session,instrument,generation,policy_version,model_bundle_id,plan_intent,valid_until,state); training_job_projection; inbox/outbox.

Scan cursor kararlı katalog sürümünde ilerler. Tier-0 metadata/liquidity/data uygunluğu; Tier-1 hazır sayısal model; Tier-2 seçilmiş adayda açıklama. CPU/LLM bütçesi ürün listesini ilk birkaç coin’e sabitlemez. Cancel scan yeni analiz planlamasını durdurur, açık pozisyonu kapatmaz. Job lease/heartbeat sayesinde restart sonrası kayıp iş tespit edilir.

Analiz: owner/session doğrula→aktif bundle pin→market coverage/asOf kontrol→gerekiyorsa WAITING_DATA ve tek backfill işi→AI prediction→deterministik policy→proposal veya WAIT→açıklama. AI hata verirse FAILED; sahte skor yok. Veri yetersizliğini bildiren prediction quality WAIT’e çevrilir. Explanation sonradan tamamlanabilir; sayı hazırken uzun metin için finans motoru bekletilmez.

### Policy v1
Model predictedLogReturn verir. Strategy sürümlü policy ile long/short/WAIT belirler; maliyet/oynaklık eşiği validation’da seçilmiş artifact’tan gelir. ATR/volatility bazlı stop/target için `stopAtrMultiplier`, `targetRiskReward`, `minNetEdge`, `minDataCoverage` parametreleri policy sürümünde; uydurma evrensel optimum yoktur. Leverage tavanı Simulation risk profilindedir. AI’sız RuleBasedStrategy ayrı baseline olarak işaretlenir; eğitilmiş model adıyla sunulmaz.

AI proposal margin/quantity otoritesi değildir. Simulation risk bütçesine göre boyutlandırır. LLM söyledi diye 100x veya uygunsuz SL uygulanmaz. Öğrenilmiş modelin verdiği açıklama fiyat hesabını değiştirmez. Long/short seçimi yatırım tavsiyesi iddiası değil sanal araştırma çıktısıdır; UI bunu görünür etiketler.

### Eğitim facade
`/strategy/training/**` yetkili kullanıcıyı AI internal training API’sine taşır. PDF’yi Strategy parse etmez; bounded multipart aktarır. Dosya/kullanım metadata’sı AI’ye gider. Bir eğitim isteği uzun HTTP bağlantısı değil durable jobId üretir. Job kimliği ve status projection Kafka ile güncellenir; detay gerektiğinde AI yetkili query’sinden alınır. Eğitim servisinin kesintisi `/strategy/analyses` için hazır model çalışıyorsa engel değildir.

PDF importer/OCR/vector store runtime Strategy bağımlılığı değildir. Ayrı Knowledge mikroservisi yoktur. Gemini veya yerel açıklama adapter’ı AI paketinin sınırında; Spring aynı açıklama sağlayıcısını ikinci kez uygulamaz.

## 7. Reporting

Finans projection yalnız LedgerTransactionPosted ile güncellenir. PositionClosed/OrderFilled metadata ve timeline içindir; aynı kâr iki kez toplanmaz. Equity örnekleri SessionValuationUpdated otoritesinden gelir; mark’tan bağımsız Python/React finans muhasebesi kurulmaz. `projectionAsOf`, `lastEngineSequence`, `lag` her raporda görünür.

Tablolar: session_summaries, trade_summaries, ledger_projection(transaction_id UNIQUE), equity_samples(session,as_of,engine_sequence), notifications(source_event_id UNIQUE), projection_checkpoints, inbox, report_jobs. Owner projection ile API ownership doğrulanır.

Metrikler: net trade=realized−fees+signedFunding−liquidationFees; insurance adjustment ayrı. Win rate yalnız kapanan trade’ler, sıfır trade’de null. Profit factor kayıp toplamı sıfırken “tanımsız” (Infinity JSON yok). Drawdown=(peakEquity−equity)/peakEquity; equity sampling aralığı belirtilir. Margin ROI ile session getiri yüzdesi ayrı. MDD yalnız trade kapanışlarından hesaplanırsa “örneklenen bakiye düşüşü” diye etiketlenir.

Redis summary cache örnek TTL 5–15 sn; user/session/projectionVersion key. Cache invalidation+TTL; eski projection’a yeni asOf basılmaz. Redis yoksa DB. Kafka retention dışı rebuild Simulation audit export + checkpoint ile; başka DB’ye SQL yok. Rebuild yeni projection namespace’inde tamamlanıp atomik geçilir.

Bildirim: AnalysisReady, PositionLiquidated, OrderRejected, DataGap, SessionHalted, DeadlineReached. Yüksek hacimli fiyat tick’i bildirim üretmez. CSV export authorize edilir, formül injection engellenir. PDF kaynak tıklaması veya canlı RAG metrikleri yoktur.

## 8. Gateway, HTTP ve güvenlik

Route: auth→8081, market→8082, sim→8083, strategy (training dahil)→8084, reports→8085. Gateway 8080; AI 8090 internal; React dev 5173. Model training worker port gerektirmez. Public JWKS path Identity’ye gider. `/internal/**` route yok; AI’ye tarayıcıdan CORS açılmaz.

SSE buffering kapalı, heartbeat/idle timeout uyumlu. Bearer fetch stream; token query string’e koyulmaz. POST finans için gateway yeni idempotency key veya kör retry üretmez. Circuit breaker işlem başarılı fallback’i döndürmez. CORS allowlist; secrets response/loglardan temizlenir. Multipart limit yalnız training routes’ta; normal JSON API’ye büyük dosya izni verilmez.

Spring services JWKS verification yapar. İç servis kimliği ayrı secret/mTLS veya kısa süreli service token ile doğrulanır; uygulama başlangıcında biri seçilip test edilir. User JWT’nin her internal endpoint’e sınırsız erişim sağladığı varsayılmaz. Artifact/download path doğrulaması ve owner scope AI’de de uygulanır.

## 9. Kafka çalışma protokolü

Topic/payload ana belgede normatiftir. Consumer group her iş tüketicisi için ayrı (`simulation-market-v2`, `strategy-market-v2`, `reporting-simulation-v2` gibi). Aynı group’taki worker’lar işi paylaşır; Reporting ve Simulation aynı group’a konmaz.

Outbox publish sonrası crash aynı event’i tekrar üretebilir. Consumer business update+inbox(eventId)+outbox transaction’ı atomic; offset sonrasında. Finans business unique ayrıca gerekir: funding(position,time), journal businessKey, command idempotency. DLQ’a atıp finans sırasını bozma; kısa bounded retry sonra partition pause/alarm. Strategy açıklama gibi kritik sıra gerektirmeyen işler backoff+retry/DLT kullanabilir.

Market event’leri farklı partition/topic’ten gelir. Simulation kabul journal’ı olay sırasını kaydeder; restart replay journal sırasına uyar. Her session için motor sırası monotondur. Raw market ingress dedupe tek seferde tüm session’ların işlendiği anlamına gelmez: kalıcı `market_inputs` ve session bazlı `pending_engine_inputs(session_id,input_id)` unique fan-out önerilir. Offset, input ve ilgili session work kayıtları commit edilince ilerler. Worker her session işini kendi transaction’ında uygular; crash ara session’ların işini kaybettirmez.

Replay tarihsel clock başka namespace’tedir; fixture fiyatlarını gerçek market topic’ine publish etme. Broker retention iş audit’inin yerine geçmez. Başlangıç 1 KRaft broker/replication 1 yalnız geliştirme; yüksek erişilebilirlik iddiası yok. Market 2–3, finans başlangıç 1 partition birer ölçülecek config’tir.

## 10. Redis görevleri

| Alan | Key boyutları | Redis yoksa |
|---|---|---|
| Market snapshot | instrumentId,streamKind | Kaynak/cache durumu açık; eski veri fresh olmaz |
| Rapor | owner,session,projectionVersion | PostgreSQL query |
| Analysis kısa cache | modelBundle,featureSnapshot,horizon,policy | Yeniden hesap; tazelik gate korunur |
| Rate limit | subject,route,window | Sınırlı fallback veya ilgili pahalı işlemi reddet |

Private kullanıcı çıktısı ortak cache key’ine yazılmaz. TTL yetki kontrolünün yerine geçmez. Model artifact/ledger/passwort/refresh token Redis’e truth olarak taşınmaz.

## 11. Ortam, çalışma ve operasyon

`infra/compose.yaml` profilleri: foundation, streaming, paper, ai-inference, training, offline-demo. DB/Kafka/Redis state volume; model-artifacts ayrı read-only inference mount; training-sources yalnız worker’a mount. API readiness dependencies ve market quality farklı göstergelerdir. Provider kopması restart loop sebebi olmamalıdır.

Env: DB_URL/USER/PASSWORD, KAFKA_BOOTSTRAP_SERVERS, REDIS_URL, JWT_ISSUER/AUDIENCE/JWKS_URL, MARKET_SOURCE=BINANCE|FIXTURE, EXCHANGE_REST_BASE, EXCHANGE_MARKET_WS_BASE, EXCHANGE_PUBLIC_WS_BASE, RULE_PROFILE_ID, MODEL_API_URL, INTERNAL_SERVICE_SECRET, DATA_MAX_AGE_* ve job/retention limitleri. Secret `.env.example` içinde gerçek değer olmaz. Binance trading credential env’i yoktur. Gemini key kullanılırsa AI container secret’ıdır, VITE_ değişkeni değildir.

Liveness process; readiness DB/broker bağlantısı; tradingEnabled veri/risk gate. Metrikler market_event_age_ms, consumer_lag, pending_engine_input_age, outbox_age, engine_queue_depth, ledger_imbalance_total, duplicate_events_total, analysis_latency, projection_lag. Prometheus isimleri uygulamada tutarlı kilitlenir.

Backup: DB dump, rule/model/dataset manifestleri, artifact hash’leri; restore sonrası migrations→cursor→inbox→market gap→risk revalidation. Ledger silinmez. Tick retention disk bütçesine bağlı; tam piyasa verisini sonsuza kadar tutma iddiası yok. Loglar trace/session/event id içerir, raw secrets veya eğitim PDF içerikleri içermez.

## 12. Öğrenme ve uygulama işleri

B01 Money/Quantity value object ve long/short PnL; B02 fee/margin/liq; B03 ledger + DB constraint; B04 session/order REST; B05 register/login; B06 Kafka fixture producer/consumer ve duplicate; B07 Market adapter/cache; B08 outbox/report projection; B09 immutable plan hash/onay; B10 AI fake→gerçek inference; B11 scanner/deadline; B12 training facade; B13 replay ve hata toparlama.

Her adımda küçük çalışan sonuç üret. Controller→use case→domain→repository zincirini açıklayabilmeden bütün modülleri AI ile tek seferde kopyalama. Kafka ilk alıştırması consumer kapat→5 olay üret→aç→offset devamını göster; ikinci alıştırma aynı event’i iki kere gönder→tek ledger etkisini göster.

## 13. Kod teslim kapıları

Saf unit/property: aritmetik, tier, rounding, ledger sum, deterministic replay. Integration: PostgreSQL gerçek constraint/row lock, Kafka redelivery/outbox crash, Redis down, Testcontainers. Contract: ana JSON schema, HTTP request/response, Kafka payload ve AI mock contract. E2E: kayıt→session→preview→submit→fill→kısmi/tam close→rapor.

Yarışlar: aynı bakiye iki giriş, cancel/fill, manual close/SL/liq, duplicate funding, expired plan, model switch, deadline double fire, session fan-out worker crash. Sonuç her durumda tek doğru ledger olmalı. Testler internete veya değişken fiyatlara bağımlı değildir; adapter smoke testi ayrı ve sonucu tarihli kaydedilir.

Aşağıdaki finans eki bu sürümde de normatiftir. API isimleri ana sözleşmeden alınır; ekteki örnek oranlar yalnız eğitim fixture’ıdır.

## 14. Finans motoru — korunmuş matematik ve muhasebe

Bu bölüm uygulamanın finans referansıdır. Formüller lineer USDT isolated one-way modeline aittir. Maintenance yaklaşımını referans alan aşağıdaki muhasebe ve fill politikaları projenin açık tasarım seçimleridir. Üretimde hesap bazlı birebir sonuç iddiası yoktur.

### 1. Değişkenler ve sayı modeli
s ∈ {+1 long, -1 short}; q>0 baz miktar; E>0 ağırlıklı giriş; M>0 mark; X>0 çıkış; L≥1 kaldıraç; I başlangıç teminatı; B mevcut isolated cash; A kullanılabilir cash; H bekleyen emirlere ayrılan reserve; r maintenance oranı; c maintenance amount.
Java BigDecimal; decimal string'den oluştur. double'dan oluşturma. İç hesap için DECIMAL128; DB NUMERIC(38,18); para mutabakatında profil cashScale=8. Rounding HALF_EVEN; quantity step için FLOOR; fiyatın yürütme yönüne göre adverse tick rounding. Ara sonuçları UI basamaklarına yuvarlama. Kalan kuruş/dust son kapanışa atanır.

### 2. Büyüklük ve açılış
Kullanıcı marginBudget girer. Önizleme qRaw = marginBudget × L / referenceEntry; q = floorToStep(qRaw).
Nentry=q×E; I=Nentry/L; feeOpen=Nentry×takerRate (market için).
İşlemin gerektirdiği kullanılabilir tutar I+feeOpen; qRaw hesabından kalan marginBudget farkı kullanılabilir kalır. “Tüm bakiyeyi kullan” çözümü fee'yi dışlamaz: q üst sınırı yaklaşık A/[E×(1/L+feeRate)] olup step ve slippage ile yeniden kontrol edilir.
Açılış tek DB transaction: A azalır I+feeOpen; position B=I; fee gider hesabına kaydolur. Fee ayrıca B'den düşülmez. Nakit varlık satın alımı yapılmaz; türev marj modeli kullanılır, Nentry cüzdandan komple çekilmez.

### 3. PnL ve özsermaye
U=s×q×(M-E).
Equity = A+H+ΣB+ΣU. Reserved H harcanabilir değildir ama özsermayeye dahildir.
Tam kapanış brüt PnL R=s×q×(X-E).
feeClose=q×X×feeRate.
Pozisyonun normal kapanışında residual=B+R-feeClose hesaplanır. residual≥0 ise A += residual; B sıfırlanır. Quote gap/spread yüzünden residual<0 çıkarsa A eksiye itilmez veya serbest isolated-dışı cash kaybına zorlanmaz: aşağıdaki açık insolvency karşı hesabı uygulanır ve exitReason=INSOLVENT_EXIT kaydedilir. Normal exit fee korunur; ayrıca liquidation fee eklenmez. Open fee açılışta zaten ödenmiştir. TradeNet = ΣrealizedPnL - ΣtradeFees + ΣsignedFunding - ΣliquidationFees; badDebtAdjustment varsa ayrıca gösterilir.
Net hesap performansı: finalEquity-initialDeposit-netExternalTransfers. Paper reset yeni session açar; eski kaydı silmez.
Teminat ROI için payda ilk ayrılan I; equity getirisi için session başlangıç özsermayesi. İkisini tek “kazanç %” etiketiyle birleştirme.

### 4. Maintenance ve risk kontrolü
NotionalMark=q×M. Profil kademesinden r,c seç. MM=max(0,NotionalMark×r-c).
PositionEquity=B+U. Eşik PositionEquity≤MM olduğunda liquidation süreci başlar. available A isolated pozisyonu otomatik kurtarmaz. Auto-add margin kapalı.
RiskRatio=MM/PositionEquity, equity>0 iken; equity≤0 iken kritik. UI bunun proje oranı olduğunu söyler. Her kabul edilmiş mark ve funding sonrası kontrol yapılır; UI refresh hızı risk kontrol hızını sınırlamaz.

### 5. Tahmini likidasyon fiyatının türetilmesi
B+s q(P-E)=q P r-c eşitliğini çöz:
- Long: Pliq=(qE-B-c)/(q(1-r)).
- Short: Pliq=(qE+B+c)/(q(1+r)).
Bu, fixed B ve seçilen kademe altında, tetikleme eşiğidir; masraflı tasfiye fill fiyatı değildir. Açılış ücreti A'dan ödendiği için burada tekrar çıkarılmaz. B'yi azaltan funding veya manuel margin değişimi varsa yeni B kullanılır.
Her kademede aday P hesapla; qP o kademenin [floor,cap) aralığında ise geçerli. Son kademede üst sınır dahil/sonsuz politikası profil dosyasında. Pozitif aday yoksa, örneğin tam teminatlı long, “pozitif fiyat aralığında eşik yok” döndür; negatif fiyat gösterme. Tick'e yuvarlanan eşik yalnızca gösterimdir; motor eşitsizliği tam sayılarla değerlendirir.
Risk profil değişikliği frozen session'da yok; yeni profile geçiş yeni oturum gerektirir. Bu, borsanın canlı kural değiştirebilmesine göre bilinçli sapmadır.

### 6. Funding muhasebesi
Settlement için signedFunding = -s×qAtSettlement×settlementMark×rate.
Ödeme (negatif): önce A'dan min(A,abs(funding)) düş; kalan tutar ilgili pozisyonun B'sinden düş. Tahsilat (pozitif): A'ya ekle. Bu policy seçilmiş simülasyon muhasebesidir; profil adı AVAILABLE_FIRST. Bekleyen emir reserve H ödeme kaynağı değildir; yetmeyen kalan B'ye gider.
Aynı timestamp'te birden çok pozisyon ödeme yaparsa instrumentId alfabetik sırası proje tie-break kuralıdır; session kilidi altında uygula. Bu dağıtım B ve likidasyon sonucunu etkileyebilir; sıra kaydedilir.
B düştükten sonra mark ile likidasyon tekrar hesaplanır. FundingRateHistory'deki kesinleşmiş kayıt kullanılır; stream'deki tahmini oran yalnızca önizlemedir.
Unique(sessionId,positionId,fundingTime) ile tek settlement. Tam sınır anı için proje tanımı: openedAt < fundingTime ve closedAt ≥ fundingTime olan açık miktar borç/alacak kapsamındadır. Replay olay sırası bu kuralla tutarlı olmalı. Geç gelen kesin oran için boundary quantity snapshot saklanır; eski pozisyon artık kapalıysa realized adjustment yazılır ve etkilenen çalışma “gecikmiş settlement” olarak işaretlenir. Geçmişte olması gereken likidasyonu sessizce uydurma; yeniden replay ayrı run üretir.

### 7. Kısmi kapanış
qClose≤qOpen. f=qClose/qBefore. ReleasedB=Bbefore×f, son kapanışta tüm bakiye; realized=s×qClose×(X-E).
Partial residual=ReleasedB+realized-feeClose negatifse kısmi fill uygulanmaz; emir tüm kalan pozisyon için zorunlu risk kapamasına çevrilir ve güncel quote ile tüm residual hesaplanır. Bunun yaklaşık model davranışı kaydedilir. Normal pozitif partial residual için A += residual. B -= ReleasedB; q -= qClose. E değişmez. Başlangıç I'nin kalan pozisyona atfedilen kısmı aynı oranda azalır; lifetime trade ROI orijinal I üzerinden raporlanır. SL/TP miktarı kalan q'ya çekilir. Funding geçmişini yeniden oranlayarak ledger'i değiştirme; rapor atfı ayrı hesaplanır.

### 8. Tasfiye: seçilmiş eğitim modeli
Likidasyon algılanınca kullanıcı kapanışıyla aynı positionVersion kilidi altında state OPEN→LIQUIDATING. Bekleyen kardeş çıkışlar iptal edilir. Taze bid/ask varsa reduce-only market modeline göre X; veri yoksa LIQUIDATING bekler, eski fiyatla fill üretilmez.
Kademeli tasfiye ve ADL bu sürümde yok; kalan miktarın tamamı kapanır. liquidFee=q×X×liquidationFeeRate; normal close fee ile çift kesinti yok. liquidationFeeRate örneklerde 0, diğer eğitim profillerinde açıkça yapılandırılır; gerçek oran iddiası yapılmaz.
residual=B+R-liquidFee.
- residual≥0: A'ya residual döner.
- residual<0: A'dan fazladan kesilmez; simulatedInsuranceContribution=-residual ayrı sistem karşı hesabına yazılır, kullanıcı B sıfırlanır.
Aynı explicit insolvency counterpart normal kapanışın negatif residual durumunda da uygulanır; yalnızca ücret türü normal close fee olarak kalır. Bu sigorta muhasebesi Binance sigorta davranışının kopyası değildir. Ham ekonomik kayıp, kullanıcı net kaybı ve sistem katkısı raporda ayrılır. Bakiyeyi max(0,x) yapıp farkı kaybetmek yasak.

### 9. Ledger sözleşmesi
Her hareket bir journalTransactionId altında dengeli iki taraflı transferdir. Basit signed posting modeli: ΣpostingAmount=0, currency=USDT. Kullanıcı cash hesaplarına artı kullanıcıya giriş, eksi çıkıştır; karşı hesaplar SIM_FUNDING_POOL, SIM_PNL_POOL, SIM_FEES, SIM_INSURANCE veya INITIAL_CAPITAL.
Örnek: A→B teminat aktarımı (-1000 A,+1000 B). Fee (-5 A,+5 SIM_FEES). Kâr (+100 A,-100 SIM_PNL_POOL). Başlangıç sermayesi (+10000 A,-10000 INITIAL_CAPITAL).
Ledger silinmez; düzeltme reversal+replacement ile yapılır. Her post businessKey ile unique olur. Wallet snapshot transaction içinde güncellenir ve ledger toplamlarıyla mutabakat yapılır.

### 10. Risk limitleri
Eğitim profilinde maxLeverage=10, maxConcurrentPositions=2; instrument eligibility katalog capability ve kullanıcı risk policy üzerinden; bu Binance maksimumu değildir. Kullanıcının seçtiği limits profile altında daha düşük olabilir. AI plan boyutlandırmasında qRisk≈riskBudget/(abs(E-SL)+unitFees+unitSlippage). Son miktar min(qRisk,qMargin,qNotionalCap) değerinin step-floor sonucudur. Stop gap nedeniyle max kaybı garanti etmez. Minimum miktarın altı WAIT/reject; risk bütçesini aşmak için minimum lota yuvarlama yok.

### 11. Kural profilinin zorunlu alanları
profileId, version, sourceType EDUCATIONAL/FROZEN_REFERENCE, effectiveAt, fetchedAt, instrumentIds/capabilityScope, tick/step/minNotional, brackets, maker/taker/liquidationFeeRate, fundingDebitPolicy, maxLeverage, executionModelVersion, dataGapPolicy, checksum. Bracket bilinmiyorsa “güncel Binance uyumu” rozeti verilmez. `exchangeInfo.maintMarginPercent` maintenance tablosu yerine kullanılmaz.

## 15. Fill, veri tazeliği ve replay politikası

### Fiyat işlevleri
Mark yalnızca değerleme/risk ve seçilmiş stop tetikleme içindir. Bid/ask market emir gerçekleşmesinin girdisidir. Last/trade mumları grafik ve strateji girdisidir. Spot veri ile perpetual pozisyon değerleme yapılmaz. Piyasa adapter referansları bu belgenin kaynak bölümündedir; yürütme kuralları proje modelidir.

### MARKET_V1
Küçük emir/top-of-book modeli. Market BUY: ask×(1+slippageBps/10000), tick'e yukarı. SELL: bid×(1-slippageBps/10000), tick'e aşağı. Negatif veya aşırı slippage yapılandırması reddedilir. Ücret notional üzerinden taker. SlippageBps sabit eğitim parametresi veya kaydedilmiş fonksiyon sürümüdür; bps=1, yüzde 0,01 demektir.
q, karşı taraf görünen miktarının en fazla configuredParticipation oranında olmalı; MVP oran 0,10 proje varsayımı. Bu hacim yoksa PARTIAL_FILL üretmek yerine MVP emri LIQUIDITY_LIMIT ile reddeder. Daha sonra depth modeli eklenebilir. Kapanışta bu limit LIQUIDATING pozisyonu sonsuza dek tutmamalı: ayrı LIQUIDATION_V1 tüm kalan miktarı adverse slippage ile kapatır ve liquidityApproximation=true işaretler.
Quote saklanırken bid≤ask, fiyat>0, eventTime, receivedAt, sequence doğrulanır. Fill kaydı kullanılan quoteId ve priceModelVersion'ı taşır. Gizli likidite veya gerçek queue priority bilinmez.

### LIMIT_V1
GTC only. Emir sisteme kabul edildiği anda marketable ise taker modeline gider. Değilse reserve oluşturup NEW bekler. Fiyat sadece grafikte değdi diye fill yok. Yeni taze quote'ta BUY için ask≤limit; SELL için bid≥limit. Fill fiyatı model adverse fiyatıyla limit sınırını geçmemeli; sınırı geçiyorsa beklet. Küçük-emir hacim limiti uygulanır.
Bekleyen limitin gerçek maker olup olmayacağı simülatör tarafından bilinmez. V1 tüm fill'leri konservatif taker fee ile ücretlendirir. makerRate profil alanı geleceğe ayrılmıştır; UI yanlış “maker tasarrufu” göstermez. Limit fill queue-fidelity=APPROXIMATE.
Reserve=limit üzerinden initial margin + fee + tanımlı risk tamponu. Fill sırasında yetersizse yeniden doğrula ve reject; reserved kısmı serbest bırak. Kalan reserve otomatik iade. Cancel ve fill yarışı position/session version ile seri çözülür.

### SL/TP
MARK_PRICE tetikleme sabittir. Long stop M≤stop; long take profit M≥target. Short stop M≥stop; short target M≤target. Tetikleme sonrası reduce-only market exit yaratılır. Stop fiyatı fill fiyatı değildir. SL ve TP aynı pozisyonun kardeş emirleri: biri fill olduğunda diğeri atomik iptal. Pozisyon hiç açılmamışsa attached bracket etkin değildir.
Aynı kabul edilmiş engine adımında liquidation ve SL koşulları birlikte doğruysa liquidation öncelikli. Bu açık proje kuralı Binance mesaj sırasını temsil etmez. Eşzamanlı farklı veri akışlarının sıralamasını sonradan borsa gerçeği olarak sunma.

### Veri tazeliği
Proje default'ları: markMaxAgeMs=5000, quoteMaxAgeMs=2000, maxClockSkewMs=1000; donanım/piyasa testiyle değiştir ve versiyonla. Age hem source-time hem receive-time üzerinden incelenir. Aktif pozisyon riskinde tüm kabul edilmiş mark'lar işlenir; UI için yalnızca gösterim throttle edilir.
STALE olduğunda yeni giriş reddedilir, yeni AI planlarının yürütülmesi engellenir. Eski quote ile market kapama yapılmaz. Çıkış isteği EXIT_PENDING_DATA olarak kaydedilebilir; taze quote gelince doldurulur. Veri boşluğunda riskin tam olarak bilinemediği gösterilir.
Sistem yeniden bağlanınca mark düşük seviyeye düşüp geri dönmüş olabilir. Sadece son snapshot “likide olmadın” kanıtı değildir. Gap kaydı aç; elde edilen mark history ile incele. Eksik tick yüzünden kesin sonuç kurulamazsa session fidelity=DEGRADED, replay sonucu INDETERMINATE. Gerekirse daha düşük çözünürlükle ayrı approximate run üret.

### Replay katmanları
| Katman | Girdi | Güven düzeyi |
|---|---|---|
| RECORDED_TICKS | Kendi kaydettiğimiz mark/quote/funding akışı | Gözlenen akış ve model içinde deterministik |
| PAIRED_OHLC | Trade mum + mark mum + funding | Mum içi sırada belirsiz |
| TRADE_ONLY | Yalnızca normal kline | Likidasyon doğrulaması için yetersiz; gösterim/strateji deneyi |

Replay engine aynı saf finance-core'u kullanır; ağ, sistem saati, Math.random yok. Clock injection ve seeded random. Her run dataset hash, time range, model/strategy/rule sürümü, seed ve tie policy taşır.

### OHLC belirsizliği
Bir dakika içinde hem stop hem hedef görüldüyse hangi sırayla olduğu bilinmez. Varsayılan conservative adverse-first kullan, sonucu ambiguousBarCount ile işaretle. Aynı mumda giriş ile likidasyon/çıkış varsa fiyat yolunu biliyormuş gibi kesin kâr raporu verme. İyimser ve kötümser path ile bir sonuç aralığı üretilebilir. Eşik kapanıştan kontrol edilmez: mark low/high eşiği gösterebilir ama geçiş anı bilinmez.
Strateji yalnızca kapanmış mumu t anında görür. t kapanışından çıkan emrin en erken fill'i bir sonraki eligible quote/bar open'dır. Aynı mumun en iyi fiyatından giriş yasak. Kullanılan indikatör warmup verisi sonuç döneminden önce olabilir ama geleceğinden olamaz.
Funding hesabı için settlement anındaki açık miktar gereklidir. OHLC fill ile aynı timestamp belirsizse tie policy ve belirsizlik etiketi korunur.

### Sembol yaşam döngüsü
Market servisi instrument status/snapshot geçmişi tutar. Yeni listelenen varlık dinamik kataloğa girer; veri ve kontrat/rule uygunluğu sağlandıkça analyze/trade capability kazanır, kalıcı coin listesi onayı gerekmez. Delist/TRADING dışı sembolde yeni giriş durur; pending giriş iptal edilir. Açık pozisyon arşivden silinmez. Doğrulanmış settlement price yoksa zorunlu sahte fiyatla kapatılmaz: DATA_UNAVAILABLE ve kullanıcıya uyarı. Eğitim fixture'ında delist senaryosu ayrıca tanımlanabilir.
Backtest evreni bugünkü listeye göre seçilirse survivorship bias oluşur. Evren snapshot'ı ve seçim yöntemi rapora yazılır. Küçük fixture evreniyle başlamak bu gereksinimi basitleştirir; ürünün evrenini sınırlamaz.

### Mutlak sınırlar
Gerçek borsa kuyruğu, hesap özelindeki bracket, latency arbitrage, RPI likiditesi, kısmi liquidation, ADL ve sigorta fonu eşleştirilmez. “Binance referanslı paper simülasyon” doğru ürün adıdır. “Binance ile aynı sonucu kesin verir” yanlış kabul kriteridir.

### Replay eşzamanlı olay politikası
Virtual clock source event time ile ilerler. Aynı timestamp için sıralama: (1) instrument/rule status ve funding boundary quantity snapshot; (2) funding cash settlement, kesin oran mevcutsa; (3) mark update ve liquidation kontrolü; (4) SL/TP trigger; (5) quote update ve bekleyen reduce-only exit; (6) giriş fill; (7) candle-close feature/strategy değerlendirmesi. Kendi kaydedilmiş engine journal replay'inde bu yeniden sıralama yapılmaz: original engineSequence korunur.
Funding boundary'de tam o anda açılan yeni pozisyon henüz adım 6'da olduğundan ücrete girmez; o anda kapanan eski pozisyon adım 1'de açık olduğundan girer. Ayrı bir clock boundary event, rate geç gelse de eligible quantity snapshot'ını üretir. Oran elde değilse pending settlement tutulur ve sonucu etkileyebilecek veri eksikliği işaretlenir.
Source-time akışında eşit zamanlı quote/mark arasında borsa seviyesinde gerçek sıra bilinmeyebilir; bu sıralama reproducibility politikasıdır. Replay'de data age hesapları wall clock değil virtual clock ile yapılır.

## 16. Finans fixture’ları ve sınır testleri

### Referans profil: yalnız eğitim
E=50000 USDT/BTC, q=0.2 BTC, L=10, I=B=1000; başlangıç cash=10000; takerRate=0.0005 (%0,05); r=0.004 (%0,4); c=0; tek kademe [0,∞); liquidationFeeRate=0; slippage=0; bid=ask=referans fiyat. Bunlar güncel Binance fee/bracket iddiası değildir. Testlerde rate ve q decimal string.

### Altın örnekler
| ID | Girdi/işlem | Beklenen sonuç |
|---|---|---|
| F01 | Long aç | notional=10000, openFee=5, A=8995, B=1000, equity=9995 |
| F02 | Mark 50500 | unrealized=100, equity=10095 |
| F03 | Long 50500 tam kapama | gross=100, closeFee=5.05, net=89.95, A=10089.95 |
| F04 | Short 49500 tam kapama | gross=100, closeFee=4.95, net=90.05, A=10090.05 |
| F05 | Pozitif funding 0.0001, mark=50000, long | ödeme=1; A=8994; B=1000; liq aynı |
| F06 | Aynı funding short | tahsilat=1; A=8996; B=1000 |
| F07 | Long A=0.25, funding borcu=1 | A=0, B=999.25; B'ye etki=0.75 |
| F08 | Long threshold | 45180.722891566265... |
| F09 | Short threshold | 54780.876494023904... |
| F10 | Long mark=45000 | U=-1000, positionEquity=0, MM=36; liquidation |
| F11 | Long gap exit=44000 | R=-1200, residual=-200, simulatedInsuranceContribution=200 |
| F12 | F01 sonrası qClose=0.1, X=50500 | releasedB=500, R=50, fee=2.525, A=9542.475, kalan B=500/q=0.1 |
| F13 | F12 sonrası kalan q=0.1, X=49500 | R=-50, fee=2.475, final A=9990; tüm trade fee=10 |
| F14 | Başlangıç A=1000, marginBudget=1000 | fee dahil yetersiz; 1000 margin emri reject |
| F15 | Normal full exit gap=44000, close fee=4.4 | residual=-204.4; A=8995 korunur; insurance=204.4; exitReason=INSOLVENT_EXIT |

F08/09 değerleri yeniden üretilecek Decimal fixture eşikleridir. Eşik yerine konunca B+s q(P-E)=qPr eşitliği sağlanır. Uygulama henüz yazılmadığından bu, Java uygulama testlerinin geçtiği anlamına gelmez.

### Kademe örneği
Eğitim tier1 [0,5000), r=.004,c=0; tier2 [5000,∞), r=.005,c=5. Sınırda her iki MM=20; süreklilik vardır. Likidasyon adayını hesapladığın tier'ın qP aralığında bulunduğunu doğrula. E=50000,q=.2,B=1000 ile long tier2 adayı (10000-1000-5)/(.2×.995)=45201.005025... ve notional=9040.201...; tier2 içindedir.

### Saf unit/property testleri
- LongPnL(E,X,q)=-ShortPnL(E,X,q); E=X için brüt 0 ama net fee kadar eksi.
- Aynı q,E,X için leverage değişimi PnL'yi değiştirmez.
- q step'e bölünebilir; floor sonrası q hiçbir zaman budget'ı aşmaz.
- Daha yüksek B, long liquidation eşiğini düşürür, short eşiğini yükseltir (aynı tier).
- Ledger transaction toplamı sıfır; replay sonucu aynı input'ta aynıdır.
- Kısmi kapanışların toplamı tam kapanışa, aynı fill fiyatları ve yuvarlama politikası altında eşittir.
- NaN/Infinity/negative price/zero quantity stringleri API/domain katmanında reddedilir.

### Integration testleri
PostgreSQL constraint ve row lock gerçek DB ile; H2 yerine Testcontainers. Kafka tekrar teslim ve outbox crash; Redis TTL ve down fallback; provider HTTP fixture ile rate-limit ve malformed JSON. Test internet API'sine veya değişken canlı fiyata bağımlı olmamalı.

### Yarış koşulları
1. Aynı bakiyeyle aynı anda iki emir: reserve sonrası toplam cash negatif olmaz.
2. Cancel ve fill: yalnız bir terminal geçiş.
3. Manual close, stop ve liquidation aynı anda: q bir kez kapanır; tek ledger effect.
4. Funding mesajı iki kez: tek ücret.
5. HTTP timeout ardından aynı idempotency key: yeni pozisyon yok.
6. Outbox publish sonrası crash: reporting tek projection kaydı.
7. AI önerisi onaylanırken fiyat stale: yeniden kontrol reject.

### Veri ve replay testleri
Mark ve trade farklı seviyede: liquidation yalnız mark'a bağlı. Mum içinde hem stop/target: ambiguous flag. Eksik mark dataset: exact fidelity yasak. Yeni listelenme ve delist: yeni giriş engeli. Funding gerçekleşme sınırı: openedAt<t, closedAt≥t. Aynı timestamp'li quote/mark journal sırası kaydedilir. Gelecek mum çıkarıldığında önceki AI/rule önerisi değişmemeli.

### UI uçtan uca
Login→paper session→preview→order→open position→close→ledger. Double click; refresh sonrası restore; API 503; SSE reconnect; stale banner; AI WAIT; local decimal input 1.000,50 karışıklığı. Server rakamları canonical string olarak dönmeli; TR gösterim yalnız presentation.

### Performans deneyi
İlk hedef: 2 sembol, 10 paper session, 1 Hz mark + kayıtlı quote burst. Donanım yazılır. Önce correctness, sonra throughput. P95 engine latency için başlangıç hedefi 500 ms; ölçülmemiş hedef, piyasa garantisi değil. Mark sample interval toplam liquidation gözlem gecikmesine ayrıca eklenir. CPU/RAM/broker lag/DB duration aynı koşulda kaydedilir.

### Final release kapısı
Tüm F01-F15 fixture'ları; sıfır ledger imbalance; duplicate finans etkisi yok; final scope ekranları; offline demo; secret sızıntısı yok; veri/model/rule version kaydı; dependency lock; tekrarlanabilir kurulum. Unit test sayısı başarı metriği değildir; kritik invariant kapsaması önemlidir.

## 17. Uygulama ayrıntıları için tamamlayıcı hükümler

MARKET_V1 girişleri görünür karşı miktarın participation sınırını aşarsa full reject eder. Normal azaltıcı çıkış miktarı bu sınırı aşıyorsa EXIT_PENDING_DATA yerine reason=LIQUIDITY_LIMIT taşıyan NEW exit olarak yeni quote bekler; mark risk kontrolü sürer. LIQUIDATION_V1 kalan miktarın tamamını açık yaklaşık modelle kapatır. Taze quote hiç yoksa zorunlu çıkış da EXIT_PENDING_DATA bekler. Kullanıcıya kapanış garantisi verilmez.

Replay finans intent artifact’ı: `runId,decisionTime,instrumentId,side,orderType,marginBudget,leverage,stopLoss,takeProfit,horizonSeconds,policyVersion,modelBundleId`. Bu artifact yalnız HISTORICAL_REPLAY scope’unda çalıştırılır; normal session submit onayını atlayamaz. Ana API’de ReplayJob ayrı oluşturulur, mode field’ı kullanıcı tarafından çalışan oturumda değiştirilemez.

Budget hesabında manuel input=marginBudget; AI için riskBudget=equity×riskFraction. qRisk yalnız geçerli stop varken hesaplanır. Manual stop yoksa kullanıcı profilinin stop zorunluluk politikası uygulanır; qMargin ve notional limitleri her durumda geçerlidir. Finans çekirdeğine gelen rate/quantity/price değerleri Decimal olarak doğrulanır.

### Referanslar ve doğrulama sınırı

- [Binance piyasa verisi](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Exchange-Information): adapter metadata referansı; endpoint/filter smoke testi uygulamada yapılır.
- [Binance mark price](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Mark-Price): mark ve funding veri yüzeyi.
- [Spring Kafka](https://docs.spring.io/spring-kafka/reference/): consumer/transaction uygulama referansı.
- [Apache Kafka tasarım](https://kafka.apache.org/documentation/): sıra ve teslim modelini uygularken seçilen sürüm dokümanı kilitlenir.

Likidasyon, fill, ücret ve sigorta karşı hesabı burada açık bir simülasyon modelidir. Gerçek borsayla birebir gerçekleşme veya hesap eşleşmesi iddiası yoktur. Bu belgelerdeki referans bağlantılarının varlığı entegrasyon testlerinin geçtiği anlamına gelmez.
