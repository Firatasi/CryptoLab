# CryptoLab — Frontend için sıralı elle kodlama görevleri

Plan 1.0 · Şartname 3.0 · 10 ana görev. **Bütün görevler TODO.**

[Frontend şartnamesi](02-FRONTEND.md), [ana sözleşme](../README.md) ve [ortak çalışma kılavuzu](../CALISMA-KILAVUZU.md) ile uygulanır. Rehber yeni ekran/kontrat icat etmez; mevcut React paketini öğrenerek yazma sırasıdır.

## Frontend sohbetine başlangıç talimatı

```text
Bu sohbet CryptoLab frontend öğrenme sohbetimdir. React/TypeScript kodunu,
component testlerini ve yapılandırmayı ben elle yazacağım. README.md,
02-FRONTEND.md ve FRONTEND-GOREVLER.md esas alınacak.
Ben açıkça istemedikçe dosya oluşturma/değiştirme veya komut çalıştırma.
Hazır bütün ekranı verme. Her mesajda tek alt adım: kavram, küçük görev,
beklenen görünüm/davranış, test ve paylaşacağım çıktı.
Önce denememi incele; props/state/effect/API ayrımını Türkçe açıkla.
İpucu→pseudocode→küçük örnek sırasıyla yardım et; tam çözüm açık isteğimle.
Backend veya AI kodunu bu sohbette değiştirme. Endpoint/enum/body'yi değiştirmek
istersen contract değişiklik notu hazırla; kendi alternatif API'ni uydurma.
Mock ile gerçek bağlantıyı ayrı kaydet. PDF yalnız TRAINER eğitim alanında,
trading ekranında PDF/RAG ve gerçek bot yok. Yeni başlangıç F01.1'dir.
```

Sohbet ilk kez açılıyorsa ortam ve önceki kontrol noktası kısa doğrulanır. Her alt adımın sonucunda dosya veya ekran/test çıktısı paylaşılır. Asistan uygulamayı görmeden tasarım kusursuz, build başarılı veya görev bitti diyemez.

## Harita

| Görev | Hedef | Sıralı ön koşul | Gerçek backend bağlantısı |
|---|---|---|---|
| F01 | React/TS ve statik bileşenler | Yok; DTO için B01 | Gerekmiyor |
| F02 | API client/auth | F01 | B04; geçici Vite proxy veya B08 gateway |
| F03 | Session/market/chart | F02 | B05/B06 |
| F04 | Manuel preview/submit | F03 | B05 |
| F05 | Pozisyon/close/bracket | F04 | B07 |
| F06 | SSE/cache/reconnect | F05 | B08 |
| F07 | AI analiz/tarama | F06 | B09+A05 |
| F08 | Rapor/history/replay | F07 | B08 rapor, B11 replay |
| F09 | Eğitim yönetimi | F08 | B10+A06–A09 |
| F10 | E2E/erişilebilirlik/teslim | F09 | B12+A10 ile ortak |

Bekleyen API için ortak fixture üzerinden mock yapılabilir. Bu durumda IMPLEMENTED_WITH_FIXTURE/INTEGRATION_PENDING kaydı düşülür; gerçek bağlantı kurulmadan o entegrasyon DONE değildir. Paket içi sırayı koruyarak ekranı mock’la geliştirebilir, F10’da tüm gerçek bağlantı borçlarını kapatabilirsin.

## F01 — React temeli, TypeScript ve uygulama çatısı

**Öğrenme:** Component, JSX, props, state ve tip. Amaç ilk günden state yönetim kütüphanesi ezberlemek değil, veri akışını görmek.

**Elle yazılacaklar:** frontend package/tsconfig, `src/app`, `shared/ui`, `features/market/components/PriceCard`, layout ve basit stylesheet. Vite scaffold kullanmayı sen seçersen komutu sen çalıştırırsın; asistan repo oluşturmaya başlamaz.

- [ ] **F01.1** Node/paket yöneticisi sürümünü sen kontrol et; TypeScript strict ve development komutlarını beraber anlamlandır.
- [ ] **F01.2** Tek PriceCard component’ini props ile yaz; last ve mark farklı alan olsun. Para değeri başlangıçta string.
- [ ] **F01.3** Loading/empty/error/stale için örnek prop değiştir; aynı component farklı durumları göstermeli.
- [ ] **F01.4** Layout/navigasyon ve açık paleti uygula; mobil sıralamayı kaba ama kullanılır yap.
- [ ] **F01.5** Yerel seçili sekme/varlık state’iyle props-state farkını dene; finans sonucu hesaplama.
- [ ] **F01.6** B01 contract fixture’ını tipli olarak göster; ilk component testi, typecheck ve build çıktısını paylaş.

**Kabul:** Mark/last karışmıyor, sabit coin enum yok, yanlış prop tipi TypeScript’te görülüyor, loading durumunda sahte fiyat yok. Bu aşama API bağlantısı değildir.
**Anlama sorusu:** “Fiyat API’den gelince state’in sahibi kim; sekme seçiminin sahibi kim?”
**Devir:** Kullanılan contract sürümü ve UI fixture; B/F dosyaları karışmaz. **İlk küçük iş:** Yalnız F01.1 ve tek component taslağı.

## F02 — API istemcisi, route ve kimlik

**Öğrenme:** Async request, hata, form, route guard, access/refresh ayrımı.

**Dosyalar:** `shared/api/client`, auth API/hooks/forms, router/ProtectedRoute, AuthProvider; contract tipleri. Auth state’i her feature’da ikinci kez tutulmaz.

- [ ] **F02.1** Ortak ApiError tipini ve client base path `/api/v1` oluştur; fetch hata/success durumlarını ayır.
- [ ] **F02.2** Register formu ve alan validation; rol seçtirme, plain password loglama.
- [ ] **F02.3** Login ve memory access token; HttpOnly refresh cookie browser tarafından taşınsın, JS cookie içeriğini okumaya çalışmasın.
- [ ] **F02.4** Sayfa yenilemede refresh→me→route restore; eşzamanlı 401 için single-flight refresh.
- [ ] **F02.5** Logout cache/stream/auth temizliği; önceki kullanıcı verisi yeni kullanıcıda görünmesin.
- [ ] **F02.6** 400/401/403/409/429/503 UI davranışını yaz; bütün hatalara kör retry yok. B04 gerçek endpoint’ine bağla.

**Kabul:** Wrong password, expired token, refresh failure, protected deep link ve farklı kullanıcıya geçiş testleri. Token URL/localStorage’a yazılmıyor. Route guard backend yetkisinin yerine geçmiyor. B08 yoksa kontrollü dev proxy; ayrı sahte public auth endpoint’i icat edilmez.
**Anlama sorusu:** “HttpOnly cookie’yi neden React state’e alıp saklamıyoruz?”
**Devir:** B04’e request/response ve hata farkları; contract sapmasını sessiz düzeltme. **İlk iş:** Sadece client error dönüşümü ve bir login mock’u.

## F03 — Session, dinamik piyasa ve grafik

**Öğrenme:** Liste/key, arama/pagination, sunucu durumu, formlar ve grafik verisi.

**Dosyalar:** sessions API/form/list, market InstrumentSelector/MarketSummary/CandleChart; ilgili route’lar ve query anahtarları.

- [ ] **F03.1** Session list/create formu: sanal başlangıç sermaye, rule/risk policy; reset yeni session açsın.
- [ ] **F03.2** Dinamik instrument API’sini cursor ile listele; symbol yerine instrumentId key kullan.
- [ ] **F03.3** Debounced search ve eski response yarışı; aynı symbol farklı venue ayrı satır.
- [ ] **F03.4** Capability booleans/reasonCodes ve WARMING/STALE görüntüsü; işlem uygun değil diye varlığı yok etme.
- [ ] **F03.5** Candle grafiği için kaynak/interval/gap etiketleri; mark, last, bid/ask ayrı kutular. Chart kütüphanesi seçimini lisans ve sürümle kaydet.
- [ ] **F03.6** Grafik timeframe’ini hedef işlem süresinden ayır; loading/error/empty ve B05/B06 gerçek sorgu bağlantısı.

**Kabul:** Yeni fixture instrument deploy gerektirmeden görünüyor. Katalogdaki ikinci sayfa erişilir. Missing candle sahte düz çizgiyle “tam veri” yapılmaz. 1h chart seçmek otomatik horizon değişikliği yaratmaz. Yanlış session owner erişimi 403/404 olarak ele alınır.
**Anlama sorusu:** “Mum aralığı ile bir saatlik işlem hedefi niye farklı alanlar?”
**Devir:** B06 coverage/query sorunları; F04 seçili session/instrument. **İlk iş:** Tek session list component’i, sonra instrument listesi.

## F04 — Manuel emir formu ve kesin onay

**Öğrenme:** Controlled form, decimal string, state machine, mutation/idempotency ve optimistic UI sınırı.

**Dosyalar:** OrderTicket, DecimalInput, ExecutionPlanCard, SubmitOrder hook, CommandStatus; schema’dan gelen request/response types.

- [ ] **F04.1** Raw decimal input parser’ı yaz; `1000,50` normalize, belirsiz binlik ayraç reddi. Quantity/fee’yi Number ile kendin hesaplama.
- [ ] **F04.2** MarginBudget/leverage/side/market-limit/horizon/expiry alanları; LIMIT fiyatı koşullu. Manuel SL/TP null ise profil kuralları uygulanır.
- [ ] **F04.3** Önizleme isteği ve server plan kartı: q/notional/margin/fee/SLTP/liq estimate/tolerans/TTL.
- [ ] **F04.4** Form değişince eski preview/onay temizlensin; kısa TTL sayaç server doğruluğunun yerine geçmesin.
- [ ] **F04.5** Submit yalnız planId/revision/planHash; kullanıcı eylemi başına tek Idempotency-Key, timeout’ta aynı key.
- [ ] **F04.6** 202→command/order GET; ACCEPTED/NEW/FILLED/REJECTED ayrımı. Position’ı başarılı response gelmeden optimistic açma.
- [ ] **F04.7** REVALIDATION_REQUIRED için yeni plan göster, eski onayı otomatik kullanma; gerçek B05 roundtrip testi.

**Kabul:** Double click tek submit anlamı; timeout yeni emir yaratmaz; bakiye 1000 ve margin 1000 örneğinde fee warning backend’den; quantity extra field hiç gönderilmez. Model sınırlı fill davranışı UI’da korunur.
**Anlama sorusu:** “Onay ekranında gördüğün miktarı submit body’sine tekrar yazmak neden gereksiz ve tehlikeli?”
**Devir:** B05’le canonical contract ve status fixture; F05 order/position query. **İlk iş:** Yalnız DecimalInput ve üç parser denemesi.

## F05 — Pozisyon, kısmi kapanış ve risk durumu

**Öğrenme:** Kaynak version’ı, pending state, mutation sonrası refetch, kullanıcı eylemi ile gerçekleşme farkı.

**Dosyalar:** PositionsTable, ClosePositionForm, BracketEditor, OpenOrdersTable, SessionControls; position/order/detail hooks.

- [ ] **F05.1** PositionView alanlarını server’dan göster: q,E,mark/asOf,B,PnL,maintenance,liq estimate,data quality.
- [ ] **F05.2** Tam/kısmi close ve expectedVersion; 202’de closeRequested, q=0 değil.
- [ ] **F05.3** EXIT_PENDING_DATA/LIQUIDATING durumlarını ayrı anlat; eski fiyatla kesin kapanış uydurma.
- [ ] **F05.4** Bracket düzenleme, conflict refetch, kalan quantity server truth; JS’de position math yazma.
- [ ] **F05.5** LIMIT iptalini pozisyon kapatmadan ayır; daily halt ve close-all farklı düğmeler olsun.
- [ ] **F05.6** REEVALUATE ve CLOSE_AT_DEADLINE göstergeleri; user close sonrası gelen eski AI response auto-submit olmasın.
- [ ] **F05.7** B07 gerçek durumlarıyla manual/stop/liq timeline ve refetch doğrula.

**Kabul:** 202 close sonrası pozisyon erken silinmiyor. Partial user close ayrı FILLED azaltıcı emir; PARTIALLY_FILLED uydurulmuyor. Halt pozisyonların kapandığı anlamına gelmiyor. Güncel olmayan version kullanıcıya taze bilgi getiriyor.
**Anlama sorusu:** “Kısmi kapanış sonrası stop miktarını neden tarayıcı hesabıyla belirlemiyoruz?”
**Devir:** B07’ye state/event eksikleri; F06 refetch kaynakları. **İlk iş:** Salt okunur tek position satırı.

## F06 — SSE, cache ve bağlantı toparlama

**Öğrenme:** Effect cleanup, AbortController, event sequence ve server-state invalidation.

**Dosyalar:** shared stream parser/reconnect, useMarketStream/useSessionStream, ConnectionStatus; Query cache veya seçilen sunucu state adapter’ı.

- [ ] **F06.1** Bearer header’lı fetch SSE parser; parçalanmış event/frame ve multiline data sınırlarını test et.
- [ ] **F06.2** Component unmount/session/instrument değişince eski stream’i kapat; duplicate listener üretme.
- [ ] **F06.3** Sequence/epoch ve eski event discard; gap/resync.required sonrası REST snapshot.
- [ ] **F06.4** Session changedResources ile ilgili query’leri tazele; bağımsız dört wallet kopyası oluşturma.
- [ ] **F06.5** Reconnect jitter/backoff, token expiry single-flight refresh; URL’de access token yok.
- [ ] **F06.6** UI mark render throttle ile backend risk hızını karıştırma; SSE down bounded polling ve açık etiket.
- [ ] **F06.7** B08 gateway streaming üzerinde buffering/reconnect testi; tab kapatmak backend risk akışını durdurmasın.

**Kabul:** Seçili instrument değişince önceki fiyat geç update yapmıyor; logout sonrası event verisi sızmıyor. Eski sequence wallet’ı geri götürmüyor. Network kes→yeniden bağlan→snapshot tutarlı. Test fetch mock’la başlayabilir, gerçek streaming F10’dan önce doğrulanır.
**Anlama sorusu:** “useEffect cleanup’ını yazmazsak aynı session’a kaç kez dinleyici bağlanabilir?”
**Devir:** B08 gap/auth/proxy sorunları; F07 job polling’den farklı stream protokolü. **İlk iş:** Tek parser fixture ve cleanup testi.

## F07 — AI analiz ve piyasa taraması

**Öğrenme:** Async job, belirsizlik/hata ayrımı, state’e göre eylem izinleri.

**Dosyalar:** AnalysisRequestForm/AnalysisCard/ProposalDetail, ScanForm/ScanCoverage/CandidateList, job polling hooks; mevcut ExecutionPlanCard yeniden kullanılır.

- [ ] **F07.1** Yalnız session/instrument/horizon ile POST analysis; dosya/PDF/collection alanı yok.
- [ ] **F07.2** QUEUED/WAITING_DATA/RUNNING/SUCCEEDED/FAILED/CANCELLED/EXPIRED ve LONG/SHORT/WAIT göster.
- [ ] **F07.3** Numeric sonucu açıklama PENDING/TEMPLATE/UNAVAILABLE’dan ayrı göster; template eğitimli LLM gibi etiketlenmez.
- [ ] **F07.4** Proposal kabulünde source=AI_PROPOSAL preview→aynı onay kartı→user submit; plan düzenleme MANUAL’a geçsin.
- [ ] **F07.5** Scan coverage/skipped reasons/cursor ve cancel; başlat/stop butonları emir/close çağırmasın.
- [ ] **F07.6** Dört selectionMode/planMode kombinasyonunu form akışında test et; kullanıcı kapamasından sonra geç response yeni işlem açmasın.
- [ ] **F07.7** B09+A05 bağlantısını test et; development bundle/fake etiketi ve gerçek model durumunu ayır.

**Kabul:** WAIT’te order düğmesi yok; FAILED teknik hatayı piyasa görüşü diye göstermiyor. “%95 kesin kazanır” etiketi yok. Her aday tüm pazar tarandı anlamına gelmiyor. Eski proposal yeniden onaylanmıyor.
**Anlama sorusu:** “Analizi başlatmak ile işlemi onaylamak arasında hangi API adımları var?”
**Devir:** K3 ekran kanıtı ve exact payload; B09 status uyumsuzlukları. **İlk iş:** Tek WAIT fixture kartı.

## F08 — Geçmiş, rapor ve tarihsel replay

**Öğrenme:** Read projection, tablo/filter, asOf ve kalite etiketleri, async export/run.

**Dosyalar:** HistoryTimeline, ReportSummary/EquityChart/CostBreakdown, ReplayForm/ReplayResult, ExportJobStatus; rapor/replay API hooks.

- [ ] **F08.1** Order/fill/ledger/funding timeline; brüt net, ücret, tasfiye ve insurance ayrı.
- [ ] **F08.2** Summary projectionAsOf/lag; eski rapor rakamını yeni order bütçesinde kullanma.
- [ ] **F08.3** Win rate/no trade/profit factor null ve margin ROI/session return ayrımı; server oranını yüzde gösterimine dönüştür.
- [ ] **F08.4** Authorized CSV export job; bekleme/hata ve izinli indirme yolu.
- [ ] **F08.5** Replay dataset/profile/policy/seed seçimi, job state ve cancel; LIVE_PAPER ile karıştırma.
- [ ] **F08.6** DataLayer/Fidelity/ambiguousBarCount etiketleri; TRADE_ONLY sonucu kesin liquidation başarısı diye gösterme.
- [ ] **F08.7** B08 rapor ve B11 replay gerçek endpoint testlerini ayrı kaydet; B11 bekleniyorsa mock ekran tamamlanabilir.

**Kabul:** Sonuçlar query’den geliyor; frontend ikinci PnL/equity hesap otoritesi değil. CSV başka user’dan indirilemiyor. Missing data chart’ı yüksek kesinlikte sunulmuyor. 10.000→10.100→9.900 örneğinde server MDD alanı doğru etiketle gösteriliyor.
**Anlama sorusu:** “ProjectionAsOf neden sadece sayfanın açıldığı saat olamaz?”
**Devir:** K5 rapor/replay ekranları; eksik backend metrik açıklamaları. **İlk iş:** Net/gross/fee alanlı tek statik rapor kartı.

## F09 — Ayrı TRAINER eğitim yönetimi

**Öğrenme:** Rol temelli arayüz, uzun job, veri review ve model lifecycle; eğitim eklemek runtime analiz formunu büyütmez.

**Dosyalar:** training SourcesPage/DatasetReviewPage/TrainingRunsPage/ModelCardPage; ayrı route guard ve facade API client.

- [ ] **F09.1** USER menüsünde training yok; direct route yetkisiz; backend 403 doğru ele alınır.
- [ ] **F09.2** PDF/URL eğitim kaynağı formu, rights metadata, upload boyut/hata; SourceJob status ve stage ayrı.
- [ ] **F09.3** Sayfa kalite/review ve training example ACCEPT/REJECT/EDIT; freeze edilmiş sürüm üzerine sessiz yazma yok.
- [ ] **F09.4** Numeric dataset coverage/split; açıklama dataset document family; örnek sayısı ve kaynak lineage göster.
- [ ] **F09.5** NUMERIC/EXPLANATION_SFT run start/cancel; gerçek stage/progress, ölçülmemiş süre uydurma yok.
- [ ] **F09.6** ModelCard CANDIDATE/VALIDATED/SHADOW/ACTIVE; MODEL_PUBLISH ile promotion/rollback; expectedAliasVersion conflict davranışı.
- [ ] **F09.7** B10 ve A06–A09 gerçek facade bağlantıları; upload başarılı ile training/model active başarısını ayır.

**Kabul:** Normal trade/AI ekranı PDF istemiyor. Kaynak hazır toast’ı “model eğitildi” demiyor. Eğitim fail/cancel mevcut active model değiştirmiyor. Private training source başka user’a görünmüyor. SFT yapılmadıysa UI başarılı eğitim örneği diye göstermez.
**Anlama sorusu:** “Dataset freeze, training success ve model ACTIVE durumları neden üç farklı aşama?”
**Devir:** K4/K5 eğitim ekran kanıtı; A09 registry status kontraktı. **İlk iş:** Sadece TRAINER route guard + fake job status.

## F10 — Gerçek entegrasyon, erişilebilirlik ve frontend teslimi

**Hedef:** Mock borçlarını kapatıp kullanıcının gerçek ekran akışını kanıtlamak.

**Dosyalar:** Component/E2E tests, env example, lock, build scripts, ekran/erişilebilirlik kontrol kaydı; uygulama kodunu baştan yeniden yazma yok.

- [ ] **F10.1** F01–F09 durum ve dependency listesi: mock kalan endpoint’ler, gerçek bağlantı kanıtları.
- [ ] **F10.2** B12 ile register→session→manual preview→submit→close→report; aynı akış AI proposal için.
- [ ] **F10.3** Double click/timeout/same-key, 401 refresh, SSE reconnect, 409 plan/version ve stale durumları.
- [ ] **F10.4** PDF hiç yüklememiş USER ile tam kullanım; A10’da training deposu kapalıyken normal analiz ve ekran akışı.
- [ ] **F10.5** Keyboard/focus, form label, mobil görünüm, kontrast ve yalnız renge bağlı olmayan hata/risk anlamı.
- [ ] **F10.6** Logout→başka user cache izolasyonu, eğitim 403, export erişimi; frontend bundle’da secret taraması.
- [ ] **F10.7** Typecheck/lint/test/production build; temiz kurulum ve gerçek gateway base path ile smoke. Sonuçları kendi çalıştırdığın çıktı olarak paylaş.

**Kabul:** Zorunlu gerçek entegrasyon borcu yok; kaldıysa final durumda açık BLOCKED. Başarılı screenshot finans doğruluğu kanıtı sayılmaz; request body, server state ve UI state birlikte kontrol edilir. Uygulama demo sırasında mock’u gerçek diye göstermiyor.
**Anlama sorusu:** “UI’de pozisyon kaybolduysa gerçekten kapandığını hangi backend kaydıyla doğrularsın?”
**Devir:** K6 E2E/erişilebilirlik kanıtları. **İlk iş:** Yalnız mock/gerçek bağlantı borç listesi.

## Her çalışma sonunda

Görev/alt adım, yazdığın component/dosyalar, contract sürümü, gerçek mi mock mu, komut/test çıktısı, açık hata ve sonraki adımı `progress/FRONTEND.md` kaydına sen ekle. Sadece ekran göründü diye auth/finans/async testleri geçmiş sayılmaz. Sohbet değiştirirken ortak kontrol noktasını taşı.
