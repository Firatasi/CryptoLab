"""A05: Metin, policy kararını değiştirmez. Uydurma fiyat/kitap atfı yok.

- Şablon açıklama yalnızca doğrulanmış politika sonucundan (validatedPolicyOutcome) üretilir.
- Sayısal tahmin READY değilse policy eylemi WAIT olmak zorundadır (POLICY_CONTRADICTION kontrolü).
- Serbest veya tanımsız reason kodları filtrelenir; yalnız izinli sabit metinler yansıtılır.
- Metin fiyat, emir, miktar veya teminat otoritesi değildir.
"""
from app.contracts.model import ExplanationRequest, ExplanationResult

REASONS = {
    "INSUFFICIENT_HISTORY": "Geçmiş veri yeterli değil.",
    "STALE_DATA": "Veri güncel değil.",
    "OUT_OF_DOMAIN": "Girdi modelin ölçülen kapsamı dışında.",
    "NUMERIC_POLICY": "Sayısal politika koşulları değerlendirildi.",
    "CANDLE_GAP": "Piyasa mumlarında eksik aralık tespit edildi.",
    "STALE_CANDLES": "Mum verisi karar anına göre bayat.",
}


def build_template_explanation(action: str, reason_codes: list[str], feature_summary: dict | None = None) -> str:
    """Doğrulanmış eylem ve gerekçe kodlarından şablon açıklama metni üretir."""
    explained_parts = [REASONS.get(x, x) for x in reason_codes if x]
    reasons_text = " ".join(explained_parts)
    if reasons_text:
        return f"{action} kararı verildi. {reasons_text} İşlem miktarı ve teminat risk motoru tarafından hesaplanır; bu metin bağlayıcı değildir."
    return f"{action} kararı verildi. İşlem miktarı ve teminat risk motoru tarafından hesaplanır; bu metin bağlayıcı değildir."


def explain_template(request: ExplanationRequest) -> ExplanationResult:
    outcome = request.validatedPolicyOutcome
    action = outcome.get("action")
    if action not in ("LONG", "SHORT", "WAIT"):
        raise ValueError("INVALID_POLICY_OUTCOME")

    if request.predictionResult.quality != "READY" and action != "WAIT":
        raise ValueError("POLICY_CONTRADICTION")

    reasons = outcome.get("reasonCodes", request.predictionResult.reasonCodes)
    if not isinstance(reasons, list) or not all(isinstance(x, str) for x in reasons):
        raise ValueError("INVALID_REASON_CODES")

    # Serbest reason metni yansıtılmaz: yalnız izinli sabit açıklamalar kullanılır.
    explained_parts = [REASONS[x] for x in reasons if x in REASONS]
    reasons_text = " ".join(explained_parts)

    if reasons_text:
        text = f"{action} sonucu oluştu. {reasons_text} İşlem miktarı ve teminat onay ekranında risk motoruyla hesaplanır."
    else:
        text = f"{action} sonucu oluştu. İşlem miktarı ve teminat onay ekranında risk motoruyla hesaplanır."

    return ExplanationResult(
        status="TEMPLATE",
        explanationModelVersion=None,
        text=text,
        reasonCodes=reasons,
        latencyMs=0,
    )

