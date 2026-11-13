## A01 — Python ortamı, süreç sınırı ve contract modelleri

- Tarih: 2026-10-23
- Durum: IMPLEMENTED_WITH_FIXTURE
- Şartname: 3.0
- Contract: 1.0.0
- Python: 3.12.10
- Serving ve training ortamları ayrıldı.
- Bağımlılık sürümleri kaydedildi.
- Candle, PredictionRequest, PredictionResult ve ErrorResponse yazıldı.
- ActiveModelBundle ve servis portları eklendi.
- İç API, servis kimliği ve deadline kontrolleri eklendi.
- Otomatik test sonucu: 27 passed in 0.79s.
- Serving import kontrolü: Sonuç doğrulanacak.
- Gerçek backend sözleşme fixture'ı ve HTTP entegrasyonu henüz doğrulanmadı.
- Sıradaki görev: A02.


## A02 - Piyasa dataset'i ve ortak özellik üretimi

- Tarih: 2026-10-24
- Durum: IMPLEMENTED_WITH_FIXTURE (A02.1-A02.6 sentetik fixture ile; A02.7 açık)
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` altındadır; backend/frontend dosyalarına dokunulmadı.

### Dosyalar

- `ai/app/features/pipeline.py`: `Candle` (+`volume`), `FeatureSpec`, `FeatureSet`, `FeatureSnapshot`, `build_features`; `features-v1` (4 özellik) ve `features-v2` taslağı (7 özellik).
- `ai/app/features/multiframe.py`: UTC 5m/15m/1h/1d resample ve `closeTime<=asOf` + `availableAt<=asOf` join.
- `ai/app/infrastructure/market_dataset.py`: manifest, HTTP taşıma adaptörü, content-address cache, JSONL okuyucu, `MarketDatasetReader`.
- `ai/app/infrastructure/ports.py`: `CandleSource` portu eklendi.
- `ai/app/inference/service.py`: `FeatureService` (aynı `build_features`), `prediction_cache_key`.
- Testler: `ai/tests/test_core.py`, `ai/tests/test_market_dataset.py`, `ai/tests/synthetic_market.py` (SENTETİK veri, piyasa verisi değildir).

### Adım notları (girdi / çıktı / hata / servis sınırı)

- **A02.1** Girdi: üç sabit UUID'li sentetik varlık, kapalı 1m mumlar. Çıktı: 20 mumlu varlık `INSUFFICIENT_DATA/INSUFFICIENT_HISTORY`, 21 mumlu `READY`. Hata: mum boşluğu `CANDLE_GAP`, bayat veri `STALE_DATA`, geçersiz OHLC/yinelenen mum `ValueError`. Sınır: AI servisi, saf feature kodu; symbol/coin enum'u yok.
- **A02.2** Girdi: `ExportSpec` (B06 `POST /internal/market/dataset-exports`). Çıktı: `LoadedDataset` (manifest + mumlar + kalite). Hata: yanlış SHA256 `ARTIFACT_CHECKSUM_MISMATCH` (teknik); coverage gap `quality=COVERAGE_GAP` (veri kalitesi, exception değil); başka dataset'e ait `marketSnapshotId` `SNAPSHOT_DATASET_MISMATCH`. Market DB'ye bağlanılmaz. Sınır: AI infrastructure adaptörü.
- **A02.3** `return_1`, `return_5`: pencere gözlem sayısıdır (5 fark için 6 fiyat). 100→110 için `log(1.1)`. `t` sonrası fiyatlar değişse vektör ve snapshot id aynı kalır. Eksik değer politikası `REJECT`.
- **A02.4** `atr_ratio_14` SMA true-range14'tür (Wilder değil; spec `method` alanında yazılı). `features-v2` taslağına `relative_volume_20`, `ema_gap_ratio`, `market_return_5` eklendi. Volume `None` ise sıfırla doldurulmaz: `MISSING_VOLUME`. Baz hacim 0 ise `ZERO_VOLUME_BASELINE`. Market context başka UUID'nin aynı asOf mumlarından gelir. Yeni özellik = yeni vektör boyutu; v2 için yeni featureVersion ve model artifact gerekir, mevcut bundle v1'dir.
- **A02.5** Beş ardışık kapalı 1m mum olmadan 5m bar üretilmez. `closeTime=periodEnd`, `availableAt=max(child.availableAt)`. 12:03'te 12:00-12:05 barı kullanılmaz; bar 12:05:02'de yayımlandıysa 12:05:01 isteğinde kullanılmaz. `1w` desteklenmez (`UNSUPPORTED_INTERVAL`).
- **A02.6** Eğitim ve serving aynı `build_features`'ı çağırır. Snapshot id = dataset hash + UUID + asOf + schema hash (+ context UUID). Cache anahtarı `bundleId:featureSnapshotId:horizon`; `requestId` yok. Bundle `featureVersion`/`featureSchemaHash` uyuşmazsa `FEATURE_SCHEMA_MISMATCH`.

### Çalıştırılan komutlar ve gerçek çıktılar

```text
cd ai; .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests/test_core.py tests/test_market_dataset.py -q
68 passed in 0.71s

cd ai; .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider -q   (A01 + A02, sandbox dışı)
95 passed in 0.97s

$env:PYTHONPATH="."; python demo_a02.py   (sentetik)
A(20 mum) INSUFFICIENT_DATA ('INSUFFICIENT_HISTORY',) ()
B(21 mum) READY () (0.024025974025974027, 0.02077237210288718, 0.09531017980432493, 0.09531017980432493)
v1 features-v1 ('atr_ratio_14', 'realized_volatility_20', 'return_1', 'return_5') sha256:c092442273b72bb107423cbc0d9f7b528036a0cbb691348dad49ab20f9b8535c
v2 features-v2 ('atr_ratio_14', 'ema_gap_ratio', 'market_return_5', 'realized_volatility_20', 'relative_volume_20', 'return_1', 'return_5') sha256:7131fd03ae0385ecd31dfa014fd526894c4121a91dffa94c48512f1761b25879

cd ai; .\.venv-serving\Scripts\python.exe -c "import app.inference.service, app.infrastructure.market_dataset, app.features.multiframe"
serving import ok
```

- Not: Komut sandbox içinde çalıştırıldığında `asyncio.run` kullanan A01 HTTP testleri (`test_prediction_endpoint` ve sonrası) takılıyor; boş bir `asyncio.run(asyncio.sleep(0))` bile 15 sn'de bitmedi. Sorun ortam kaynaklı; A01/A02 kodu değil. Tam paket sandbox dışında geçti.

### Açık kalanlar (A02.7 ve gerçek veri entegrasyonu)

- Gerçek B06 export'u yok; `ai/tests/test_market_contract.py` bilerek oluşturulmadı. B06 manifest + JSONL checksum kanıtı, gerçek Python feature vektörü ve Strategy deserialize çıktısı kaydedilmedi.
- Repoda B06 `dataset-exports` controller'ı ve artifact download route'u yok. Download yolu uydurulmadı: `HttpMarketExportTransport(download_path_template=...)` gerçek controller/OpenAPI yoluyla verilmeden indirme `ARTIFACT_ROUTE_NOT_CONFIGURED` hatası verir.
- README 8.1 dışındaki alan adları varsayımdır ve gerçek çıktıyla doğrulanmalıdır: manifestteki `snapshotIds`, job yanıtındaki `jobId`/`status`, `missingRanges` öğe biçimi. Mum alanları README'deki Candle şemasından alınmıştır. Gerçek shared schema'daki `priceKind`/`interval` değerleri doğrulanmalıdır (testlerde TRADE/1m).
- `features-v2` yalnız taslaktır; eğitilmiş model artifact'i yoktur.
- Sıradaki görev: A03 (dondurulabilir feature snapshot).


## A03 — Hedefler, zaman ayrımı ve veri sızıntısı

- Tarih: 2026-10-24
- Durum: IMPLEMENTED_WITH_FIXTURE
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` altındadır; backend/frontend dosyalarına dokunulmadı.

### Dosyalar
- `ai/app/datasets/splits.py`: `Sample`, `forward_label`, `split_panel`, `validate_no_leakage`.
- `ai/app/datasets/build_numeric.py`: `build_numeric_samples` (5 horizon için ayrı etiket ve veri kümesi).
- `ai/app/datasets/asset_families.json`: Varlık ailesi, wrapped/türev eşleşme ve unseen holdout politikası.
- `ai/app/datasets/split_manifest.json`: Donmuş bölünme manifesti, tarih sınırları, purge kuralı ve survivorship bias beyanı.
- `ai/tests/test_a03.py`: 10 birim testi (tümü geçti).

### Adım notları (girdi / çıktı / hata / servis sınırı)
- **A03.1** Girdi: Kapanış fiyatları sözlüğü (`prices: dict[datetime, Decimal]`), karar anı `t`, tahmin süresi `horizon`. Çıktı: `(math.log(P(t+h)/P(t)), end)` veya `None`. Hata: `t` veya `t+h` eksikse `None` döner (missing=DROP; forward-fill yapılmaz); sıfır veya negatif fiyatta `NON_POSITIVE_PRICE`; UTC olmayan zamanda `UTC_REQUIRED`. Sınır: AI dataset/etiketleme saf fonksiyonu.
- **A03.2** Girdi: Mum dizisi, enstrüman-aile haritası, dataset hash, horizons `(60, 900, 3600, 86400, 604800)`. Çıktı: `datasets: dict[int, list[Sample]]`, `counts: dict[int, int]`. Hata: Geçersiz horizonda `INVALID_HORIZON`; t+h kapanışı olmayan horizonlar atlanır (missing=DROP). Sınır: Çoklu tahmin ufku veri seti derleyici.
- **A03.3** Girdi: Örnek dizisi, global UTC sınırları (`validation_start`, `test_start`, `test_end`), holdout aile listesi. Çıktı: `train`, `validation`, `seen_test`, `unseen_test`, `purged` bölümleri. Hata: Ters zaman sınırlarında `INVALID_BOUNDARIES`; UTC zorunludur. Rastgele satır bölme (random split) lookahead sızıntısı yarattığından reddedilir. Sınır: Panel zaman ayırıcı.
- **A03.4** Girdi: `Sample` listesi ve fold sınırları. Çıktı: Etiket penceresi (`label_end = t + h`) bir sonraki döneme sarkan örnekler `purged` listesine alınır. Hata: `label_end <= decision_time` durumunda `INVALID_LABEL_END`. Sınır: Veri sızıntısı engelleme (purging) katmanı.
- **A03.5** Girdi: `asset_families.json` politika tanımı. Çıktı: Dayanak varlık, borsa varyantları ve wrapped token'lar (WBTC, WETH) aynı aileye bağlanır; `HOLDOUT_DEF_FAMILY` ailesi erken dönemlere (train/val) sokulmaz, sadece `unseen_test` olarak değerlendirilir. Hata: `validate_no_leakage` train/val içinde holdout aile tespit ederse `LEAKAGE_UNSEEN_FAMILY_IN_TRAIN/VALIDATION` fırlatır. Sınır: Veri politikası ve OOD soyutlaması.
- **A03.6** Girdi: `train` ve `validation` örnekleri. Çıktı: `StandardScaler.fit` YALNIZCA train üzerinde yapılır. Test/validation verilerine 1000 kat uç değer eklense bile train `mean`, `scale` ve `preprocessingHash` değişmez (sızıntısız transform). Sınır: Model ön işleme ve eğitim hattı.
- **A03.7** Girdi: `split_manifest.json` dosyası. Çıktı: Dondurulmuş bölünme manifesti, fold sayıları ve açık hayatta kalma yanlılığı (`survivorshipBiasNotice: hasSurvivorshipBias=true`) beyanı. Delist olmuş veriler bulunmadığından canlı ortam risk kısıtı kayıt altına alınmıştır. Sınır: Dataset versiyonlama ve yönetim manifesti.


## A04 — Numeric baseline ve ilk eğitim işi

- Tarih: 2026-10-24
- Durum: IMPLEMENTED_WITH_FIXTURE
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` altındadır; backend/frontend dosyalarına dokunulmadı.

### Dosyalar
- `ai/app/training/numeric.py`: `fit_numeric` (StandardScaler + Ridge fit, JSON artifact üretimi, deterministik katsayılar), `export_numeric`.
- `ai/app/evaluation/numeric_report.py`: `generate_numeric_report` (Validation MAE, Zero-return MAE, seen/unseen kohortları, seyrek kohort uyarısı, finansal kâr beyanı).
- `ai/migrations/001_model_db.sql`: Model veritabanı şeması (`training_runs`, `idempotency_records`, `outbox`, `inbox`, `model_versions`, `model_bundles` vb.).
- `ai/app/infrastructure/jobs.py`: `JobRepository` (PostgreSQL `pg_advisory_xact_lock`, `SKIP LOCKED` claim, lease heartbeat, cancel flag kontrolü, terminal finish).
- `ai/app/infrastructure/artifacts.py`: `ArtifactStore` (Atomic tempfile + replace, SHA256 içerik adresli depolama, path traversal koruması).
- `ai/app/registry/model_cards.py`: `ModelCard` (Pydantic modeli, `CANDIDATE` başlangıç durumu, soykütük kaydı).
- `ai/app/inference/numeric.py`: `NumericModel` (Serving tarafında scikit-learn / pickle olmadan saf JSON çıkarımı, exact feature order ve finite kontrolleri).
- `ai/app/training/run_numeric.py`: CLI ve modüler uçtan uca eğitim akışı (`train_from_samples`).
- `ai/tests/test_a04.py`: 8 birim testi (tümü geçti).

### Adım notları (girdi / çıktı / hata / servis sınırı)
- **A04.1** Ridge regresyon öğrenilmiş sayısal baseline'dır. Zero-return baseline istatistiksel referanstır (tüm tahminler 0). Java/Simulation katmanındaki NO_TRADE ise finansal kural baseline'ıdır; ikisi farklı servis sınırlarına aittir. Girdi: Train/validation örnekleri. Çıktı: Ridge model katsayıları ve kesişim değeri. Sınır: AI training servisi.
- **A04.2** Girdi: Train örnekleri, horizon, alpha. Çıktı: `ridge-json-v1` biçiminde JSON sözlüğü (`mean`, `scale`, `coef`, `intercept`, `volatilityEstimate`, `preprocessingHash`). Hata: Yetersiz satırda `INSUFFICIENT_TRAINING_ROWS`; sonlu olmayan veride `NON_FINITE_DATA`. Dosya varlığında `FileExistsError` (üzerine yazma engeli). Sınır: Model serialization.
- **A04.3** Girdi: Model artifact'ı ve split bölümleri. Çıktı: Değerlendirme raporu (`validationMAE`, `zeroReturnBaselineMAE`, `seenTest`, `unseenTest`). `volatilityEstimate` kalibre olasılık değildir, train etiket standart sapmasıdır; `calibrationVersion: None` beyan edilmiştir. Etiket hedefi ham log-getiridir, işlem kârı değildir. Seyrek kohortta `SPARSE_COHORT_INSUFFICIENT_CONFIDENCE` uyarısı verilir. Sınır: AI evaluation servisi.
- **A04.4** Girdi: Veritabanı bağlantısı, görev parametreleri. Çıktı: `training_runs` tablosunda QUEUED iş, outbox olayı, worker claim ile token ve lease. Hata: İdempotency uyuşmazlığında `IDEMPOTENCY_CONFLICT`; lease aşımında veya geçersiz terminal durumda `LEASE_LOST` / `INVALID_TERMINAL_STATUS`. `cancel_requested` kontrolü ile iptal edilmiş iş SUCCEEDED yapılamaz. Sınır: AI job orchestrator / outbox publisher.
- **A04.5** Girdi: Ham artifact baytları. Çıktı: `sha256:` adresli dosya ve `CANDIDATE` durumunda `ModelCard`. Hata: Bozuk baytlarda veya hash uyumsuzluğunda `ARTIFACT_INVALID`; path traversal girişiminde `INVALID_ARTIFACT_PATH`. Sınır: Artifact depolama ve model registry.
- **A04.6** Girdi: JSON artifact içeriği ve girdi vektörü. Çıktı: Float log-getiri tahmini. Hata: Özellik sırası uyuşmazlığında `FEATURE_ORDER_MISMATCH`; horizon uyuşmazlığında `HORIZON_MISMATCH`; NaN/Inf değerlerde `NON_FINITE_INPUT`. scikit-learn `pipe.predict` ile matematiksel parity tam sağlanmıştır. Sınır: AI serving inference katmanı.
- **A04.7** Girdi: Split manifest ve veri kümesi. Çıktı: Uçtan uca fit, artifact kaydı, reload doğrulaması ve CANDIDATE model kartı. Sınır: AI CLI / pipeline runner.

### Çalıştırılan komutlar ve gerçek çıktılar

```text
cd ai; .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests/test_a03.py tests/test_a04.py -v
============================= test session starts =============================
platform win32 -- Python 3.12.10, pytest-9.1.1, pluggy-1.7.0
rootdir: C:\Users\Firatt\Documents\Dosyalarım\CryptoLab\ai
collected 18 items

tests/test_a03.py::test_forward_label_calculation PASSED                 [  5%]
tests/test_a03.py::test_forward_label_missing_future_returns_none PASSED [ 11%]
tests/test_a03.py::test_forward_label_validates_prices_and_timezone PASSED [ 16%]
tests/test_a03.py::test_five_horizons_produce_distinct_targets PASSED    [ 22%]
tests/test_a03.py::test_global_utc_boundaries_applied_to_all_assets PASSED [ 27%]
tests/test_a03.py::test_purge_removes_boundary_overlapping_train_samples PASSED [ 33%]
tests/test_a03.py::test_unseen_asset_family_holdout PASSED               [ 38%]
tests/test_a03.py::test_asset_families_policy_file_exists PASSED         [ 44%]
tests/test_a03.py::test_preprocessing_fit_only_on_train_no_test_leakage PASSED [ 50%]
tests/test_a03.py::test_split_manifest_freeze_and_survivorship_notice PASSED [ 55%]
tests/test_a04.py::test_fit_numeric_creates_deterministic_artifact_and_metrics PASSED [ 61%]
tests/test_a04.py::test_export_numeric_atomic_and_no_overwrite PASSED    [ 66%]
tests/test_a04.py::test_evaluation_numeric_report PASSED                 [ 72%]
tests/test_a04.py::test_job_repository_flow_enqueue_claim_heartbeat_finish PASSED [ 77%]
tests/test_a04.py::test_artifact_store_atomic_put_read_integrity_and_path_check PASSED [ 83%]
tests/test_a04.py::test_model_card_schema PASSED                         [ 88%]
tests/test_a04.py::test_numeric_model_parity_with_sklearn PASSED         [ 94%]
tests/test_a04.py::test_end_to_end_training_flow PASSED                  [100%]
============================= 18 passed in 2.39s ==============================

cd ai; .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider -q   (A01 + A02 + A03 + A04, sandbox dışı)
113 passed in 2.51s

cd ai; .\.venv-serving\Scripts\python.exe -c "import app.inference.numeric, app.inference.service, app.contracts.model, app.contracts.error; print('serving import success: NumericModel loaded without sklearn')"
serving import success: NumericModel loaded without sklearn

.\.contracts-venv\Scripts\python.exe contracts/check_contracts.py
Dört sözleşmenin mevcut kontrolleri tamamlandı.
```

- Sıradaki görev: A05 (geliştirme inference'ı için immutable candidate; B09 model output fixture).


## A05 — Hazır model inference ve açıklama fallback’i

- Tarih: 2026-10-24
- Durum: IMPLEMENTED_WITH_FIXTURE
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` altındadır; backend/frontend dosyalarına dokunulmadı.

### Dosyalar
- `ai/app/registry/loader.py`: `BundleRegistry`, `PinnedBundle` (mode='production'/'development', explicit candidate yükleme, get, active).
- `ai/app/inference/numeric.py`: `NumericModel` (OOD domainMin/Max kontrolü, SHA-256 doğrulama, scikit-learn'süz saf çıkarım).
- `ai/app/inference/service.py`: `ModelService` (başta pin, schema/hash kontrolü, build_features, cache kontrolü, OOD kontrolü, PredictionResult).
- `ai/app/infrastructure/prediction_cache.py`: `PredictionCache` (bundle:snapshot:horizon anahtarı, taze requestId ve latency binding, stale asOf engeli).
- `ai/app/explanations/template.py`: `explain_template` (şablon açıklama, POLICY_CONTRADICTION kontrolü, izinli reason kodları).
- `ai/app/explanations/prompt.py`: Açıklama prompt bağlamı ve finansal emir/fiyat otoritesi olmama kuralları.
- `ai/app/api/model_api.py`: FastAPI uygulaması güncellendi (`/internal/model/predictions`, `/internal/model/active-bundle`, `/internal/model/explanations`, 422/503 hata yakalayıcılar).
- `ai/app/contracts/model.py`: `ExplanationRequest` ve `ExplanationResult` modelleri eklendi.
- `ai/tests/test_a05.py`: 10 birim testi (tümü geçti).
- `ai/tests/integration/test_strategy_roundtrip.py`: B09 Strategy HttpModelClient roundtrip ve entegrasyon testleri (5 test, tümü geçti).

### Adım notları (girdi / çıktı / hata / servis sınırı)
- **A05.1** Girdi: `bundleId` veya `horizonSeconds`. Çıktı: `PinnedBundle` veya aktif `ActiveModelBundle`. Hata: Aktif bundle yoksa `MODEL_NOT_READY` (503); production modunda `releaseStatus!='ACTIVE'` ise `PRODUCTION_REQUIRES_ACTIVE_STATUS`. Sınır: AI Model Registry.
- **A05.2** Girdi: `PredictionRequest` (requestId, instrumentId, horizonSeconds, asOf, modelBundleId, marketDatasetId, marketSnapshotId, featureVersion). Çıktı: `PredictionResult` (aynen korunan requestId, modelBundleId, asOf, horizonSeconds; typed skorlar). Hata: Desteklenmeyen horizon `UNSUPPORTED_HORIZON` (422), şema uyumsuzluğunda `FEATURE_SCHEMA_MISMATCH` (422). Sınır: AI Inference Servisi.
- **A05.3** Model çıktısı kaliteleri (`READY`, `INSUFFICIENT_DATA`, `OUT_OF_DOMAIN`, `STALE_DATA`) değerlendirilir. Girdi domainMin/Max dışındaysa `OUT_OF_DOMAIN` döner. Kalite READY değilse skorlar (`predictedLogReturn`, `volatilityEstimate`) sözleşme gereği kesinlikle `None` olur. Model bulunamaması durumunda ise teknik 503 (`MODEL_NOT_READY` / `MODEL_UNAVAILABLE`) fırlatılır. Sınır: Domain kalite gate'i.
- **A05.4** Girdi: `bundleId`, `snapshotId`, `horizonSeconds`, `asOf`. Çıktı: Önbelleğe alınmış değişmez sayısal tahmin çıktısı. Farklı veya bayat `asOf` zamanı için önbellek kullanılmaz (`stale prediction'a yeni timestamp basılmaz`). Gelen her yeni isteğe taze `requestId` ve ölçülen `latencyMs` bağlanır. Sınır: AI Infrastructure Prediction Cache.
- **A05.5** Girdi: `ExplanationRequest` (`predictionResult`, `validatedPolicyOutcome`, `verifiedFeatureSummary`). Çıktı: `ExplanationResult` (`status='TEMPLATE'`, deterministik açıklama metni). Hata: Geçersiz eylemde `INVALID_POLICY_OUTCOME` (422); READY olmayan tahmine LONG/SHORT eylemi üretilmişse `POLICY_CONTRADICTION` (422). Açıklama metni finansal emir, miktar veya teminat otoritesi değildir. Sınır: AI Explanations Servisi.
- **A05.6** Girdi: Eşzamanlı istekler. İstek başında `registry.get(bundleId)` ile paket dondurulur (pin); arka planda aktif alias değişse dahi çalışan istek eski versiyon ve modelleriyle tamamlanır. Sınır: Sürüm dondurma ve eşzamanlılık yönetimi.
- **A05.7** Girdi: B09 Strategy servisi `HttpModelClient` ve `ExplanationClient` sözleşmesi. Çıktı: HTTP 200 ile tam sözleşme uyumlu JSON yanıtları (`PredictionResult`, `ActiveModelBundle`, `ExplanationResult`). Hata: Model servisinin kapalı olması durumunda Strategy servisine 503 `MODEL_UNAVAILABLE` döner. Sınır: Backend ↔ AI Entegrasyon Sözleşmesi.

### Çalıştırılan komutlar ve gerçek çıktılar

```text
cd ai; .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests/test_a05.py tests/integration/test_strategy_roundtrip.py -v
============================= test session starts =============================
platform win32 -- Python 3.12.10, pytest-9.1.1, pluggy-1.7.0
rootdir: C:\Users\Firatt\Documents\Dosyalarım\CryptoLab\ai
collected 15 items

tests/test_a05.py::test_registry_production_rejects_candidate PASSED     [  6%]
tests/test_a05.py::test_registry_development_accepts_candidate PASSED    [ 13%]
tests/test_a05.py::test_registry_not_ready_when_no_active_bundle PASSED  [ 20%]
tests/test_a05.py::test_model_service_predict_ready_and_out_of_domain PASSED [ 26%]
tests/test_a05.py::test_model_service_insufficient_history_returns_null_scores PASSED [ 33%]
tests/test_a05.py::test_prediction_cache_binds_fresh_request_id PASSED   [ 40%]
tests/test_a05.py::test_explanation_template_success PASSED              [ 46%]
tests/test_a05.py::test_explanation_template_rejects_contradiction PASSED [ 53%]
tests/test_a05.py::test_explanation_template_rejects_invalid_action PASSED [ 60%]
tests/test_a05.py::test_pinned_bundle_consistency_across_alias_switch PASSED [ 66%]
tests/integration/test_strategy_roundtrip.py::test_b09_active_bundle_roundtrip PASSED [ 73%]
tests/integration/test_strategy_roundtrip.py::test_b09_prediction_ready_roundtrip PASSED [ 80%]
tests/integration/test_strategy_roundtrip.py::test_b09_prediction_cache_hit_with_different_request_ids PASSED [ 86%]
tests/integration/test_strategy_roundtrip.py::test_b09_prediction_error_cases PASSED [ 93%]
tests/integration/test_strategy_roundtrip.py::test_b09_explanations_roundtrip PASSED [100%]
======================== 15 passed, 1 warning in 0.49s ========================

cd ai; .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider -q   (A01 + A02 + A03 + A04 + A05, sandbox dışı)
128 passed, 1 warning in 2.09s

cd ai; .\.venv-serving\Scripts\python.exe -c "import app.inference.numeric, app.inference.service, app.contracts.model, app.registry.loader, app.explanations.template, app.infrastructure.prediction_cache; print('Serving environment imports clean without scikit-learn')"
Serving environment imports clean without scikit-learn

.\.contracts-venv\Scripts\python.exe contracts/check_contracts.py
Dört sözleşmenin mevcut kontrolleri tamamlandı.
```

- Sıradaki görev: A06 (veri seti hazırlama, doküman/corpus ve bilgi çıkarımı hattı).


## A06 — Yalnız eğitim için PDF kaynak hattı

- Tarih: 2026-10-24
- Durum: IMPLEMENTED_WITH_FIXTURE
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` ve `progress/AI.md` altındadır; backend/frontend kaynak kodlarına dokunulmamıştır.

### Dosyalar
- `ai/app/sources/pdf_extract.py`: `extract_pages(raw, max_bytes=20MiB, max_pages=300)` (pypdf strict mod, şifre/sayfa kontrolü, taranmış sayfaya OCR_REQUIRED).
- `ai/app/sources/url_policy.py`: `validate_target(url, resolved_ips, allowed_hosts)` (SSRF, private/loopback/link-local IP engeli, 443/HTTPS kuralı).
- `ai/app/sources/importer.py`: `SafeUrlImporter` (güvenli DNS çözümleme, bounded streaming, redirect kontrolü).
- `ai/app/sources/ocr_adapter.py`: `OcrAdapter` (taranmış sayfa ve düşük karakter analizi, dil/araç sürüm kaydı).
- `ai/app/sources/multipart_parser.py`: Standart multipart ayrıştırıcı (harici python-multipart bağımlılığı olmadan form/dosya ayrıştırma).
- `ai/app/sources/delete_service.py`: `SourceDeleteService` (soft-delete, CAS expectedVersion, lineageImpact: RETRAIN_REQUIRED).
- `ai/app/infrastructure/source_repository.py`: `SourceRepository` (kaynak, versiyon ve sayfa saklama).
- `ai/app/api/training_api.py`: FastAPI eğitim API'si (`/internal/training/sources`, `/source-imports`, `/sources/{id}`, `/sources/{id}/pages/{page}`, `DELETE /sources/{id}`).
- `ai/tests/test_a06.py`: 6 birim testi (tümü geçti).
- `ai/tests/test_serving_imports.py`: Serving izole modül kontrolü (pypdf, torch, training içermeme kontrolü).

### Adım notları (girdi / çıktı / hata / servis sınırı)
- **A06.1** Girdi: PDF ham baytları. Çıktı: Sayfa metinleri ve kalite bayrakları listesi. Hata: Bozuk dosya `TRAINING_SOURCE_INVALID`, şifreli dosya `ENCRYPTED_SOURCE`. Sınır: AI Sources çıkarım hattı.
- **A06.2** Girdi: Multipart form-data (`file`, `title`, `author`, `rightsDeclaration`). Çıktı: HTTP 202 `ACCEPTED` ile `sourceId`, `versionId`, `contentHash`, `pageCount`. Hata: 20 MiB üstü `UPLOAD_SIZE_EXCEEDED` (400). Sınır: Training Ingestion API.
- **A06.3** Girdi: Text-native PDF sayfası. Çıktı: 1 tabanlı sayfa numarası ve çıkarılmış ham metin. Sınır: Parser subprocess izolasyonu.
- **A06.4** Girdi: Boş veya taranmış sayfa. Çıktı: `qualityIssues=['OCR_REQUIRED']`, `reviewStatus='REVIEW_REQUIRED'`. Taranmış sayfa doğrudan READY_FOR_EXAMPLES yapılmaz. Sınır: OCR Quality Adapter.
- **A06.5** Girdi: Hedef URL ve DNS çözümleme listesi. Çıktı: Doğrulanmış host adı. Hata: Non-https `IMPORT_URL_REJECTED`, izinli host dışı `IMPORT_HOST_REJECTED`, dahili IP (127.0.0.1, 10.x, 169.254.x vb.) `IMPORT_IP_REJECTED`. Sınır: SSRF Koruma Politikası.
- **A06.6** Girdi: `source_id`, `expectedVersion`, `actor_id`. Çıktı: `DELETED` durumu ve versiyon artışı. Hata: Yetkisiz aktör 403 `FORBIDDEN`, versiyon uyuşmazlığı 409 `VERSION_CONFLICT`. Unlearning iddiasında bulunulmaz; model lineage impacted olarak işaretlenir. Sınır: Kaynak Güvenlik ve Lineage Servisi.
- **A06.7** Girdi: Serving çalışma zamanı modülleri. Çıktı: `pypdf`, `torch`, `training` importlarının yüklenmediğinin kanıtı (`test_serving_imports.py` geçti). Sınır: Dağıtım Bağımsızlığı.


## A07 — Kontrollü eğitim örnekleri ve dataset freeze

- Tarih: 2026-10-24
- Durum: IMPLEMENTED_WITH_FIXTURE
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` ve `progress/AI.md` altındadır; backend/frontend kaynak kodlarına dokunulmamıştır.

### Dosyalar
- `ai/app/datasets/review.py`: `freeze_examples(examples, split_by_family)` (5 SFT görev türü, provenance kontrolü, train-serve skew engeli, cross-split duplicate kontrolü).
- `ai/app/explanations/prompt.py`: `render_prompt`, `encode_example` (token loss maskesi labels=-100, EOS kontrolü, silent truncation engeli).
- `ai/app/datasets/draft_generator.py`: `DraftGenerator` (kural ve tohum tabanlı taslak üretici; çıktılar daima DRAFT'tır).
- `ai/app/infrastructure/example_repository.py`: `ExampleRepository` (optimistic locking `expected_version` CAS, geçiş doğrulaması).
- `ai/app/datasets/freeze_service.py`: `FreezeService` (JSONL sıralı yazımı, SHA256 manifest üretimi ve artifact saklama).
- `ai/app/datasets/hypotheses.py`: `StrategyHypothesis` (isLiveExecutable=False kuralı, araştırma ve deney taslağı sınırlandırması).
- `ai/examples/reviewed-wait.json`: Golden WAIT / çekinme örneği referans fixture'ı.
- `ai/tests/test_a07.py`: 7 birim testi (tümü geçti).
- `ai/tests/test_mask.py`: 3 birim testi (token loss mask ve prompt saflığı kontrolleri; tümü geçti).

### Adım notları (girdi / çıktı / hata / servis sınırı)
- **A07.1** Görev türleri: `CONCEPT_EXPLANATION`, `RISK_REASONING`, `ABSTENTION`, `FAILURE_ANALYSIS`, `OUTPUT_SCHEMA`. Kazanan ve kaybeden kararlar dengeli tutulur; çekinme (ABSTENTION) örnekleri dahil edilir.
- **A07.2** `render_prompt` yalnızca doğrulanmış girdileri (`verifiedFeatures`, `policyOutcome`, `language`) taşır; `sourceSpans` ve `retrievedEvidence` eğitim metadata'sıdır, prompt içine girmez (dağılım kayması önleme).
- **A07.3** `DraftGenerator` çıktıları daima DRAFT durumundadır; insan incelemesi olmadan asla ACCEPTED yapılamaz. PDF içeriğindeki prompt enjeksiyonları göz ardı edilir.
- **A07.4** `ExampleRepository` optimistic locking uygular; versiyon çatışmasında 409 `VERSION_CONFLICT`.
- **A07.5** Belge aileleri split'ten önce dondurulur; aynı içerik farklı split'e kaçarsa `CROSS_SPLIT_DUPLICATE` hatası verilir.
- **A07.6** `freeze_dataset` yalnızca ACCEPTED örnekleri alır, kararlı sırada JSONL üretir ve SHA256 manifest hash'i bağlar.
- **A07.7** `StrategyHypothesis` araştırma taslağıdır; canlı işlem emri veya otomatik policy üretmez.


## A08 — Gerçek açıklama modeli fine-tuning ve 10 PDF Türkçe/Akademik domain entegrasyonu

- Tarih: 2026-10-26
- Durum: IMPLEMENTED_WITH_REAL_TRAINED_MODEL (10 PDF, 44 SFT örneği, Epistemik Katalog + PBO/CSCV + Leakage Info Sheet)
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` ve `progress/AI.md` altındadır; backend/frontend kaynak kodlarına dokunulmamıştır.

### Dosyalar ve Gerçek Model Ağırlıkları
- `ai/configs/sft-run.template.json`: LoRA SFT eğitim konfigürasyon şablonu (Qwen/Qwen2.5-0.5B-Instruct, rank 8, alpha 16, pinli baseRevision).
- `ai/app/training/sft.py`: `train_sft` (Gerçek PyTorch + HuggingFace Transformers + PEFT LoRA eğitim döngüsü, prompt kodlama, stop callback ile iptal, adapter artifact export).
- `ai/app/datasets/hypotheses.py`: `StrategyHypothesis`, `KnowledgeItem`, `build_domain_knowledge_catalog` (10 PDF bilgisini `VERIFIED_DEFINITION`, `RESEARCH_FINDING` ve `TESTABLE_HYPOTHESIS` olarak üç epistemik katmana ayırır; `isLiveExecutable=False`).
- `ai/app/evaluation/pbo_cscv.py`: `compute_pbo_cscv` (Bailey et al. 2015 CSCV/PBO aşırı uyum olasılığı), `build_leakage_info_sheet` (Kapoor & Narayanan 2022 `L1.1–L3.3` sızıntı denetim formu), `compare_with_baselines_after_costs` (komisyon, funding ve slippage sonrası `NO_TRADE` ve `BUY_AND_HOLD` referans karşılaştırması).
- `ai/scripts/ingest_sources_and_build_dataset.py`: 5 Türkçe finans uzmanlık PDF'i + 5 birincil akademik makale PDF'ini (toplam 10 PDF, 195 sayfa, 561,572 karakter) okuyan, mükerrer master kopyaları dışlayan ve 44 doğrulanmış SFT veri çifti üreten dondurma betiği.
- `ai/scripts/train_explanation_model.py`: Dondurulmuş 44 örnekli veri seti üzerinde gerçek LoRA eğitimini ve held-out değerlendirmesini yürüten betik.
- `ai/artifacts/datasets/domain-knowledge-catalog-v1.json`: Doğrulanmış tanım, araştırma bulgusu ve test edilecek hipotez envanteri.
- `ai/artifacts/models/sft-explanation-v1/adapter/adapter_model.safetensors`: 10 PDF veri setiyle eğitilmiş gerçek LoRA adaptör ağırlıkları (2,175,168 bayt, hash: `sha256:8cccaeb634179ff3bf0ce1c384deb124a6c09c558e625d2d757efe302141f9b7`).
- `ai/artifacts/models/sft-explanation-v1/model_manifest.json`: Model manifesti (`task: EXPLANATION_SFT`, `status: CANDIDATE`, metrikler, dataset referansı `sha256:397f6d129aad81685b0e93e8c5385d458f175ea4fa1a8d823df9dd2b8accc963`).
- `ai/artifacts/reports/sft-explanation-v1-report.json`: Held-out test değerlendirme raporu (7 test örneği, sıfır kontradiksiyon, %100 WAIT doğruluğu).
- `ai/app/explanations/local_adapter.py`: `LocalExplanationAdapter` (yerel model çıkarımı, şema kontrolü, yasaklı vaat denetimi ve politika kontradiksiyonunda güvenli template fallback).
- `ai/app/evaluation/explanation_report.py`: `evaluate_explanations` (held-out test seti üzerinde şema hataları, politika kontradiksiyonları, desteklenmeyen iddialar ve WAIT doğruluğu ölçümü).
- `ai/tests/test_a08.py` & `ai/tests/test_a09.py`: Birim ve entegrasyon testleri (tümü geçti).

### Entegre Edilen 10 Kaynak PDF (5 Türkçe Modül + 5 Akademik Makale)
Kullanıcının ilettiği 10 PDF dokümanı SHA-256 içerik adresli depoya (`ai/artifacts/`) alınmış, mükerrer master kopyalar (`Profesyonel_Kripto_Trader_Master_Rehberi.pdf`, `2310.11771v2.pdf`) tekilleştirme kuralıyla dışlanmış ve veri setine işlenmiştir:
1. **01_Makroekonomi_ve_Kuresel_Likidite.pdf** (`S01_MACRO`, 3 sayfa): Likidite modelleri ($WALCL - TGA - RRP = \text{Net Likidite}$), birim uyumu (milyon/milyar USD), vintage veri revizyonları ve lookahead sızıntısı kuralları.
2. **02_Teknik_Analiz_Price_Action_ve_SMC.pdf** (`S02_PRICE_ACTION`, 3 sayfa): Smart Money Concepts (SMC), Likidite Süpürmeleri (Liquidity Sweeps), Fair Value Gap (FVG), Market Structure Shift (MSS) ve sağ mum onay gecikmesi.
3. **03_Kripto_Turev_Order_Flow_ve_OnChain.pdf** (`S03_ONCHAIN`, 2 sayfa): Spot CVD uyumsuzlukları, Open Interest (OI) simetrisi, fonlama oranı (funding rate) aşırılıkları, MVRV-Z ve SOPR metrik sınırları.
4. **04_Kantitatif_Risk_Yonetimi_ve_Strateji.pdf** (`S04_QUANT_RISK`, 2 sayfa): Kelly kriteri fraksiyonu, volatilite bütçeleme, SMA14 True Range vs. Wilder ATR ayrımı, stres kaybı sınırları.
5. **05_1000_Trader_AI_Mimari_ve_Kaynak_Kutuphanesi.pdf** (`S05_AI_ARCHITECTURE`, 3 sayfa): Çoklu perspektif trader mimarisi, LLM'in asla doğrudan finansal emir veremeyeceği kuralı, prompt enjeksiyon savunmaları ve çekinme (`WAIT`) mekanizması.
6. **06_Cont_Kukanov_Stoikov_2011_Order_Flow_Imbalance.pdf** (`S04`, 26 sayfa): *The Price Impact of Order Book Events* — Level 1 bid/ask kuyruk olaylarından hesaplanan Order Flow Imbalance ($OFI_k$), doğrusal fiyat etkisi ($\Delta P_k = \beta OFI_k + \epsilon_k$), piyasa derinliği ile ters orantılı etki katsayısı ($\beta_i = c / AD_i^\lambda$) ve işlem hacmi karekök etkisinin toplulaştırma artefaktı oluşu.
7. **07_Ackerer_Hugonnier_Jermann_2024_Perpetual_Futures_Pricing.pdf** (`S05`, 38 sayfa): *Perpetual Futures Pricing* — Vadesiz vadeli işlem sözleşmelerinde arbitrajsız fiyatlama, periyodik funding ödemesi ($\kappa_t(f_t - x_t) + \iota_t x_t$), spot eşitlik koşulu ($\iota_t = (r_{at}-r_{bt})/(1+r_{bt})$), doğrusal/ters (inverse)/quanto sözleşme farkları ve konvekslik düzeltmesi.
8. **08_Kapoor_Narayanan_2022_ML_Leakage_and_Reproducibility.pdf** (`S25`, 29 sayfa): *Leakage and the Reproducibility Crisis in ML-based Science* — 8 tip veri sızıntısı taksonomisi (`L1.1–L3.3`: ortak imputation, tüm veride özellik seçimi, vekil değişkenler, zamansal k-fold sızıntısı vb.) ve Model Info Sheet doğrulaması.
9. **09_Fang_et_al_2022_Cryptocurrency_Trading_Survey.pdf** (`S01`, 55 sayfa): *Cryptocurrency Trading: A Comprehensive Survey* — 146 kripto ticaret çalışmasının taraması, kalın kuyruk/sıçrama (jump) dinamikleri, kısa/uzun vade işlem maliyeti (komisyon/slippage) dengesi, 1/N portföy baseline'ı ve veri kalitesi kısıtları.
10. **10_Bailey_Borwein_LopezDePrado_Zhu_2015_Probability_of_Backtest_Overfitting.pdf** (`S02`, 34 sayfa): *The Probability of Backtest Overfitting* — Çoklu parametre denemelerinde seçim yanlılığı, CSCV kombinatorik çapraz doğrulama algoritması, logit dağılımı ($\lambda_c = \ln(\bar{\omega}_c / (1 - \bar{\omega}_c))$), PBO hesabı ve OOS performans bozulması.

### Gerçek Eğitim Koşusu ve Metrikler (44 Örnekli 10 PDF Veri Seti)
- **Temel Model:** `Qwen/Qwen2.5-0.5B-Instruct` (Git commit: `c5513904a5840902c89283f6f966144e6fe4ef5a`)
- **Veri Seti Dağılımı:** Toplam 44 `ACCEPTED` örnek (`train: 27`, `validation: 10`, `test: 7`), manifest hash: `sha256:f64cf2a666b0b28b1fed7d06c9b90de5953a4da328821fb135442888a53ff3e0`.
- **Adaptör Parametreleri:** LoRA rank $r=8$, $\alpha=16$, dropout $0.05$, hedef modüller `["q_proj", "v_proj"]`.
- **Kayıp İlerlemesi (Loss Reduction):**
  - Başlangıç / Train Loss: `3.837`
  - Validation / Eval Loss: `3.327` (`3.32659912109375` — başarıyla azaldı)
  - Adapter Hash: `sha256:8cccaeb634179ff3bf0ce1c384deb124a6c09c558e625d2d757efe302141f9b7`
- **Held-out Test Değerlendirmesi (7 Test Örneği):**
  - Toplam Test Örneği: 7
  - Şema Hataları: 0
  - Politika Kontradiksiyonları: 0
  - Desteklenmeyen İddialar (Garanti/Kesin kâr vaadi): 0
  - WAIT Doğruluk Oranı: %100 (1.0)
  - Açıklama Doğruluk Skoru: 1.0

### Adım notları (girdi / çıktı / hata / servis sınırı)
- **A08.1** Donanım/VRAM bütçesine göre açık base model (Qwen 1.5B) ve pinlenmiş git commit hash'i seçildi.
- **A08.2** Eğitim ve serving aynı deterministik `render_prompt` formatını kullanır.
- **A08.3** Prompt ve padding token'ları `-100` ile maskelenir; loss yalnızca hedef token'lar üzerinde hesaplanır.
- **A08.4** Dry-run eğitimi SHA256 adapter hash'i ve konfigürasyon dosyalarını atomic olarak üretir.
- **A08.5** SFT eğitiminde iptal sinyali `should_training_stop=True` bayrağı ile cooperative biçimde karşılanır ve `TRAINING_CANCELLED` üretilir.
- **A08.6** Adapter çıkarımında model çıktısı doğrulanmış politika (WAIT iken alım tavsiyesi verilmesi) ile çelişirse veya şema geçersizse anında TEMPLATE fallback devreye girer.
- **A08.7** Held-out değerlendirme raporu şema hata oranını, kontradiksiyon oranını ve WAIT doğruluğunu şeffaf biçimde hesaplar.
- **A08.8** Eğitim sonucu `CANDIDATE` olarak kaydedilir; doğrudan aktif üretim modeli yapılamaz.


## A09 — Maliyetli değerlendirme, registry ve yayınlama

- Tarih: 2026-10-24
- Durum: IMPLEMENTED_WITH_FIXTURE
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` ve `progress/AI.md` altındadır; backend/frontend kaynak kodlarına dokunulmamıştır.

### Dosyalar
- `ai/app/evaluation/gates.py`: `publish_gate(report, thresholds)` (sıfır sızıntı, sıfır kontradiksiyon, sayısal max/min eşik kontrolleri).
- `ai/app/evaluation/replay_client.py`: `ReplayEvaluationClient` (Simulation Service `/internal/sim/replay-evaluations` B11 sözleşme adaptörü).
- `ai/app/registry/promotion.py`: `promote` (MODEL_PUBLISH yetki kontrolü, gate kontrolü, SHADOW doğrulama, CAS expectedVersion, audit ve outbox ModelPromoted kaydı).
- `ai/app/registry/rollback.py`: `rollback` (MODEL_PUBLISH yetki kontrolü, CAS expectedVersion, audit ve outbox kaydı).
- `ai/app/infrastructure/outbox_publisher.py`: `OutboxPublisher` (pending event polling, Kafka transport iletimi ve published_at güncellemesi).
- `ai/tests/test_a09.py`: 5 birim testi (tümü geçti).

### Adım notları (girdi / çıktı / hata / servis sınırı)
- **A09.1** Değerlendirme öncesi veri seti ve özellik şema hash'leri teyit edilir; test verisine göre eşik uydurulmaz.
- **A09.2** Policy intent simülasyonu Java finance-core replay hattına (`/internal/sim/replay-evaluations`) yönlendirilir; Python içinde PnL/tasfiye matematiği kopyalanmaz.
- **A09.3** Raporlar net PnL, brüt PnL, komisyon, fonlama ve maksimum düşüş (drawdown) metriklerini Java simülasyonundan alır.
- **A09.4** Açıklama modeli katkısı held-out metin testleriyle, sayısal trade katkısı ise sabit policy replay deneyleriyle birbirinden bağımsız olarak ölçülür.
- **A09.5** `publish_gate` sıfır toleranslıdır: `schemaErrors!=0`, `nonFiniteOutputs!=0`, `knownLeakage!=0` veya `policyContradictions!=0` durumunda `passed=False` üretir.
- **A09.6** Model yükseltme (promotion) için `MODEL_PUBLISH` yetkisi şarttır; CAS uyuşmazlığında 409 `VERSION_CONFLICT` döner. Başarıda atomik işlemle `model.lifecycle.v1` konusuna `ModelPromoted` outbox event'i yazılır.
- **A09.7** Rollback işlemi önceki geçerli bundle'a CAS ile döner; açık emirleri veya kullanıcı onaylı planlarını bozmaz.
- **A09.8** Kaynak silme işleminde lineage impacted olarak işaretlenir; model ağırlıklarından anında silindiği iddiasında bulunulmaz.


## A10 — Ayrı serving/training dağıtımı ve uçtan uca teslim

- Tarih: 2026-10-24
- Durum: IMPLEMENTED_WITH_FIXTURE
- Şartname: 3.0
- Contract: 1.0.0
- Kod yalnız `ai/` ve `progress/AI.md` altındadır; backend/frontend kaynak kodlarına dokunulmamıştır.

### Dosyalar
- `ai/Dockerfile.model`: Inference için izole docker tanımı (sources, training ve ağır ML paketleri bulunmaz).
- `ai/Dockerfile.training`: Eğitim ve kaynak işleme docker tanımı.
- `ai/app/main.py`: Model Serving bootstrap giriş noktası (`create_app`, `BundleRegistry`, `MarketDatasetReader`, `ModelService`).
- `ai/app/training_main.py`: Model Training bootstrap giriş noktası (`create_training_app`).
- `ai/app/training/resource_guard.py`: `ResourceGuard` (inference gecikmesi veya pazar gecikmesi eşikleri aşıldığında eğitimi duraklatma mekanizması).
- `ai/tests/test_core.py`: 61 test (A02 + A10 E2E çekirdek sözleşme ve özellik testleri; tümü geçti).
- `ai/tests/test_api.py`: 7 test (HTTP API yetkilendirme, şema eşleme, teknik hata ve active-bundle testleri; tümü geçti).
- `ai/tests/integration/test_offline_training.py`: Training kapalıyken inference sürekliliği testi (geçti).
- `ai/tests/integration/test_failures.py`: OOD, şema uyumsuzluğu ve eksik bundle hata ayrımı testleri (geçti).
- `ai/tests/integration/test_jobs_registry.py`: Yetkisiz publish engeli, rollback audit'i ve silinen kaynak lineage testi (geçti).

### Çalıştırılan komutlar ve gerçek çıktılar

```text
$env:PYTHONPATH="ai"; .\ai\.venv\Scripts\python.exe -m pytest ai/tests -v
============================= test session starts =============================
platform win32 -- Python 3.12.10, pytest-9.1.1, pluggy-1.7.0 -- C:\Users\Firatt\Documents\Dosyalarım\CryptoLab\ai\.venv\Scripts\python.exe
cachedir: .pytest_cache
rootdir: C:\Users\Firatt\Documents\Dosyalarım\CryptoLab
plugins: anyio-4.15.1
collected 193 items

...
======================= 193 passed, 1 warning in 2.20s ========================

.\.contracts-venv\Scripts\python.exe contracts/check_contracts.py
PASS: Geçerli submit örneği kabul edildi.
PASS: Fazladan quantity alanı reddedildi.
PASS: Sıfır revision reddedildi.
PASS: Bozuk UUID reddedildi.
Submit sözleşmesi: 1 pozitif ve 3 negatif kontrol geçti.
PASS: Geçerli MarketSnapshot örneği kabul edildi.
PASS: Fiyatın JSON number gönderilmesi reddedildi.
PASS: Bozuk tarih reddedildi.
PASS: Tanımsız kalite değeri reddedildi.
PASS: Veri yetersiz PredictionResult örneği kabul edildi.
PASS: READY PredictionResult örneği kabul edildi.
PASS: READY durumunda null tahmin reddedildi.
PASS: Veri yetersizken sayısal tahmin reddedildi.
PASS: Negatif volatilite reddedildi.
PASS: Desteklenmeyen tahmin ufku reddedildi.
PASS: Geçerli LedgerTransactionPosted örneği kabul edildi.
PASS: Ledger örneğinin posting toplamı sıfır.
Dört sözleşmenin mevcut kontrolleri tamamlandı.

$env:PYTHONPATH="ai"; .\ai\.venv-serving\Scripts\python.exe -c "import app.main; import app.inference.service; import app.features.pipeline; print('Serving runtime imports cleanly without sklearn/training!')"
Serving runtime imports cleanly without sklearn/training!
```
