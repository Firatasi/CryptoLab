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
