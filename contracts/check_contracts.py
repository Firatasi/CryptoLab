import json
from decimal import Decimal
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


# Dosya yollarını terminalin bulunduğu yere değil,
# bu Python dosyasının konumuna göre hesaplıyoruz.
ROOT = Path(__file__).resolve().parent


def main():
    # ---------------------------------------------------------
    # 1. SubmitOrder sözleşmesi
    # ---------------------------------------------------------

    # Şema kuralları ile örnek isteği ayrı dosyalardan okuyoruz.
    schema = json.loads(
        (ROOT / "http" / "submit-order.schema.json").read_text(
            encoding="utf-8"
        )
    )
    fixture = json.loads(
        (ROOT / "fixtures" / "submit-order.json").read_text(
            encoding="utf-8"
        )
    )

    # Önce şemanın kendisinin geçerli olduğunu kontrol ediyoruz.
    Draft202012Validator.check_schema(schema)

    # UUID ve tarih gibi format kontrollerini etkinleştirir.
    # Tarih kontrolü için requirements.txt içindeki format ekleri gereklidir.
    validator = Draft202012Validator(
        schema,
        format_checker=FormatChecker()
    )

    validator.validate(fixture)
    print("PASS: Geçerli submit örneği kabul edildi.")

    invalid_cases = [
        ("Fazladan quantity alanı", {"quantity": "1"}),
        ("Sıfır revision", {"revision": 0}),
        ("Bozuk UUID", {"planId": "bozuk"}),
    ]

    for label, change in invalid_cases:
        # Orijinal örneği değiştirmeden hatalı bir kopya oluşturuyoruz.
        invalid_fixture = {**fixture, **change}
        errors = list(validator.iter_errors(invalid_fixture))

        # Hatalı veri kabul edilirse test başarısızdır.
        if not errors:
            raise AssertionError(
                f"FAIL: {label} reddedilmeliydi ama kabul edildi."
            )

        print(f"PASS: {label} reddedildi.")

    print("Submit sözleşmesi: 1 pozitif ve 3 negatif kontrol geçti.")

    # ---------------------------------------------------------
    # 2. MarketSnapshot sözleşmesi
    # ---------------------------------------------------------

    market_schema = json.loads(
        (ROOT / "http" / "market-snapshot.schema.json").read_text(
            encoding="utf-8"
        )
    )
    market_fixture = json.loads(
        (ROOT / "fixtures" / "market-snapshot.json").read_text(
            encoding="utf-8"
        )
    )

    Draft202012Validator.check_schema(market_schema)
    market_validator = Draft202012Validator(
        market_schema,
        format_checker=FormatChecker()
    )

    market_validator.validate(market_fixture)
    print("PASS: Geçerli MarketSnapshot örneği kabul edildi.")

    # Decimal string, tarih ve enum kurallarını sınar.
    market_invalid_cases = [
        ("Fiyatın JSON number gönderilmesi", {"markPrice": 50000}),
        ("Bozuk tarih", {"sourceTime": "bozuk"}),
        ("Tanımsız kalite değeri", {"quality": "SUPER_FRESH"}),
    ]

    for label, change in market_invalid_cases:
        invalid_fixture = {**market_fixture, **change}

        if not list(market_validator.iter_errors(invalid_fixture)):
            raise AssertionError(
                f"FAIL: {label} reddedilmeliydi ama kabul edildi."
            )

        print(f"PASS: {label} reddedildi.")

    # ---------------------------------------------------------
    # 3. PredictionResult sözleşmesi
    # ---------------------------------------------------------

    prediction_schema = json.loads(
        (ROOT / "internal-ai" / "prediction-result.schema.json").read_text(
            encoding="utf-8"
        )
    )
    prediction_fixture = json.loads(
        (ROOT / "fixtures" / "prediction-result.json").read_text(
            encoding="utf-8"
        )
    )

    Draft202012Validator.check_schema(prediction_schema)
    prediction_validator = Draft202012Validator(
        prediction_schema,
        format_checker=FormatChecker()
    )

    # Veri yetersizken tahminlerin null olması geçerli bir sonuçtur.
    prediction_validator.validate(prediction_fixture)
    print("PASS: Veri yetersiz PredictionResult örneği kabul edildi.")

    # READY dalını da geçerli bir örnekle sınarız.
    # Bu sayılar gerçek model çıktısı değil, test değerleridir.
    ready_fixture = {
        **prediction_fixture,
        "quality": "READY",
        "reasonCodes": [],
        "predictedLogReturn": 0.01,
        "volatilityEstimate": 0.02,
    }

    prediction_validator.validate(ready_fixture)
    print("PASS: READY PredictionResult örneği kabul edildi.")

    prediction_invalid_cases = [
        (
            "READY durumunda null tahmin",
            {**ready_fixture, "predictedLogReturn": None},
        ),
        (
            "Veri yetersizken sayısal tahmin",
            {**prediction_fixture, "predictedLogReturn": 0.01},
        ),
        (
            "Negatif volatilite",
            {**ready_fixture, "volatilityEstimate": -0.02},
        ),
        (
            "Desteklenmeyen tahmin ufku",
            {**ready_fixture, "horizonSeconds": 120},
        ),
    ]

    for label, invalid_fixture in prediction_invalid_cases:
        if not list(prediction_validator.iter_errors(invalid_fixture)):
            raise AssertionError(
                f"FAIL: {label} reddedilmeliydi ama kabul edildi."
            )

        print(f"PASS: {label} reddedildi.")

    # ---------------------------------------------------------
    # 4. LedgerTransactionPosted sözleşmesi
    # ---------------------------------------------------------

    # Bu bölüm yukarıdaki for döngüsünün dışındadır.
    # Bütün PredictionResult kontrollerinden sonra bir kez çalışır.
    ledger_schema = json.loads(
        (ROOT / "events" / "ledger-transaction-posted.schema.json").read_text(
            encoding="utf-8"
        )
    )
    ledger_fixture = json.loads(
        (ROOT / "fixtures" / "ledger-transaction-posted.json").read_text(
            encoding="utf-8"
        )
    )

    Draft202012Validator.check_schema(ledger_schema)
    ledger_validator = Draft202012Validator(
        ledger_schema,
        format_checker=FormatChecker()
    )

    ledger_validator.validate(ledger_fixture)
    print("PASS: Geçerli LedgerTransactionPosted örneği kabul edildi.")

    # JSON Schema tutarları toplayamaz.
    # MD'deki tek para birimli fixture'ın muhasebe dengesini
    # Decimal kullanarak ayrıca kontrol ediyoruz.
    postings = ledger_fixture["payload"]["postings"]

    total = sum(
        (Decimal(posting["amount"]) for posting in postings),
        Decimal("0")
    )

    if total != Decimal("0"):
        raise AssertionError(
            f"FAIL: Ledger örneğinin posting toplamı sıfır olmalıydı: {total}"
        )

    print("PASS: Ledger örneğinin posting toplamı sıfır.")

    # Bu satıra ancak önceki kontroller hata vermeden geçerse ulaşılır.
    print("Dört sözleşmenin mevcut kontrolleri tamamlandı.")


# Dosya doğrudan çalıştırıldığında testleri başlatır.
if __name__ == "__main__":
    main()