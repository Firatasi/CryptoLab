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
    "QUANT_MODEL_V3": "Çok zaman dilimli kantitatif tahmin modeli uygulandı.",
    "HTF_TREND_CONFIRMED": "Üst zaman dilimi trendi işlem yönünü destekliyor.",
    "HTF_COUNTER_TREND": "Üst zaman dilimi trendi karşı yönde; kaldıraç ihtiyatlı tutuldu.",
    "VOLUME_CONFIRMED": "Hacim ortalaması kırılımı/ivmeyi teyit etti.",
    "CHOPPY_REGIME": "Piyasa yatay ve verimsiz (choppy) görünümde.",
    "MEAN_REVERSION_EXHAUSTION": "Aşırı alım/satım yorgunluğu sinyali saptandı.",
    "HIGH_FUNDING_CAUTION": "Fonlama oranı (funding) yoğunluğu nedeniyle risk baskılandı.",
    "VOLATILITY_REGIME_UNSAFE": "Aşırı oynaklık rejimi saptandı.",
    "TREND_MOMENTUM_ALIGNED": "Trend ve momentum göstergeleri aynı yönde hizalandı.",
    "LOW_CONFIDENCE": "Sinyal güven skoru maliyet sonrası eşik altında kaldı.",
    "EDGE_BELOW_COST": "Beklenen getiri komisyon ve kayma maliyetinin altında.",
    "INSUFFICIENT_EDGE": "Maliyet sonrası beklenen net avantaj yetersiz.",
}

_REGIME_TR = {
    "TREND_UP": "Yükseliş Trendi",
    "TREND_DOWN": "Düşüş Trendi",
    "CHOPPY": "Yatay / Gürültülü",
    "HIGH_VOL_STRESS": "Yüksek Oynaklık Stresi",
    "MEAN_REVERSION": "Ortalamaya Dönüş",
}


def build_template_explanation(action: str, reason_codes: list[str], feature_summary: dict | None = None) -> str:
    """Doğrulanmış eylem ve gerekçe kodlarından şablon açıklama metni üretir."""
    explained_parts = [REASONS.get(x, x) for x in reason_codes if x]
    reasons_text = " ".join(explained_parts)
    metrics_text = _format_metrics_summary(feature_summary)
    prefix = f"{action} kararı verildi."
    segments = [prefix]
    if metrics_text:
        segments.append(metrics_text)
    if reasons_text:
        segments.append(reasons_text)
    segments.append("İşlem miktarı ve teminat risk motoru tarafından hesaplanır; bu metin bağlayıcı değildir.")
    return " ".join(segments)


def _format_metrics_summary(summary: dict | None, outcome: dict | None = None) -> str:
    if not summary and not outcome:
        return ""
    s = summary or {}
    o = outcome or {}
    parts: list[str] = []
    if s.get("interval"):
        htf = s.get("contextInterval")
        parts.append(f"Zaman dilimi: {s['interval']}" + (f" (üst teyit: {htf})" if htf and htf != s["interval"] else ""))
    if s.get("regime"):
        parts.append(f"Rejim: {_REGIME_TR.get(str(s['regime']), str(s['regime']))}")
    if isinstance(s.get("atrPct"), (int, float)):
        parts.append(f"ATR Oynaklık: %{float(s['atrPct']) * 100:.2f}")
    if isinstance(s.get("rsi14"), (int, float)):
        parts.append(f"RSI(14): {float(s['rsi14']):.1f}")
    if isinstance(s.get("signalConfidence"), (int, float)):
        parts.append(f"Sinyal Güveni: %{float(s['signalConfidence']) * 100:.0f}")
    if isinstance(s.get("winProbabilityEstimate"), (int, float)):
        parts.append(f"Kalibre Yön Olasılığı: ~%{float(s['winProbabilityEstimate']) * 100:.1f}")
    if o.get("suggestedLeverage"):
        risk_lbl = {"LOW": "Düşük", "MEDIUM": "Orta", "HIGH": "Yüksek"}.get(str(o.get("riskLevel", "")), "")
        risk_part = f" ({risk_lbl} Risk)" if risk_lbl else ""
        parts.append(f"Optimize Kaldıraç: {o['suggestedLeverage']}x{risk_part}")
    if not parts:
        return ""
    return " | ".join(parts) + "."


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
    merged_summary = dict(request.predictionResult.observedFeatureSummary or {})
    merged_summary.update(request.verifiedFeatureSummary or {})
    metrics_text = _format_metrics_summary(merged_summary, outcome)

    segments = [f"{action} sonucu oluştu."]
    if metrics_text:
        segments.append(metrics_text)
    if reasons_text:
        segments.append(reasons_text)
    segments.append("İşlem miktarı ve teminat onay ekranında risk motoruyla hesaplanır.")
    text = " ".join(segments)

    return ExplanationResult(
        status="TEMPLATE",
        explanationModelVersion=None,
        text=text,
        reasonCodes=reasons,
        latencyMs=0,
    )

