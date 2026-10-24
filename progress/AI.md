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
