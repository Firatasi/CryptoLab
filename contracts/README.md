# CryptoLab sözleşmeleri

## Sürümler

- Contract sürümü: 1.0.0
- Şartname sürümü: 3.0

Contract sürümü, bu sözleşme paketinin sürümüdür.
LedgerTransactionPosted içindeki schemaVersion: 2 ise
ilgili olayın şema sürümüdür. Bu iki sürüm farklı amaçlarla kullanılır.

## Başlangıç sözleşmeleri

| Sözleşme | Şema dosyası |
|---|---|
| SubmitOrder | http/submit-order.schema.json |
| MarketSnapshot | http/market-snapshot.schema.json |
| PredictionResult | internal-ai/prediction-result.schema.json |
| LedgerTransactionPosted | events/ledger-transaction-posted.schema.json |

Örnek veriler fixtures klasöründedir.
Bu veriler gerçek piyasa veya gerçek model çıktısı değildir.

## Veri kuralları

- Finansal Decimal değerler JSON string olarak taşınır.
- PredictionResult içindeki istatistiksel tahminler,
  kendi şemasında tanımlandığı gibi JSON number olarak taşınır.
- Kimlikler, şemada belirtilen alanlarda UUID biçimindedir.
- Zamanlar UTC olarak üretilir.
- date-time format kontrolü tek başına UTC kullanımını zorunlu kılmaz.
- Enum alanları yalnızca şemada tanımlanan değerleri kabul eder.
- SubmitOrder isteği tanımlanmamış alanları reddeder.
- Olay şemaları ek alanlara izin verir; mevcut zorunlu alanlar korunur.
- Şema doğrulaması; sahiplik, plan geçerliliği veya tüm iş kurallarının
  doğrulandığı anlamına gelmez.

## Sonraki şemaların görev sahipleri

- Auth: B04
- Simulation: B05 / B07
- Market: B06
- Reporting / SSE: B08
- Account: B08A
- Strategy: B09
- Training: B10
- Replay: B11

## Sözleşme değişikliği kuralı

Her değişiklikte aşağıdakiler kaydedilir:

1. Değişikliğin gerekçesi.
2. Etkilenen üretici ve tüketiciler.
3. Gerekiyorsa geçiş planı.
4. Güncellenen fixture ve doğrulama testleri.
5. Semantik sürümleme kararı.

## Doğrulama

CryptoLab proje kökünde:

```powershell
.\.contracts-venv\Scripts\python.exe contracts/check_contracts.py