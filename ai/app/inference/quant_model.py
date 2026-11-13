"""Serving-safe multi-horizon quantitative inference model (quant-horizon-v4 + v3 compat).

No scikit-learn, pickle, torch, or training imports permitted in serving.
All GBDT regression & Triple-Barrier classification trees execute in pure Python stdlib.
"""
from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
import json
import math
from typing import Any, Sequence

from app.features.quant import (
    QUANT_FEATURE_NAMES,
    QUANT_FEATURE_NAMES_V3,
    QUANT_SCHEMA_HASH,
    QUANT_SCHEMA_HASH_V3,
)


def _clip(val: float, low: float, high: float) -> float:
    return max(low, min(high, val))


def _sigmoid(z: float) -> float:
    if z >= 35.0:
        return 1.0
    if z <= -35.0:
        return 0.0
    return 1.0 / (1.0 + math.exp(-z))


def _eval_tree(nodes: Sequence[Sequence[Any]], x: Sequence[float]) -> float:
    """Evaluates a single exported HistGradientBoosting node list in pure Python."""
    idx = 0
    steps = 0
    n_nodes = len(nodes)
    while steps < n_nodes:
        node = nodes[idx]
        # Node tuple layout: [value, feature_idx, threshold, left, is_leaf, right, missing_go_left]
        if node[4]:
            return float(node[0])
        val = x[int(node[1])]
        go_left = bool(node[6]) if math.isnan(val) else (val <= float(node[2]))
        idx = int(node[3]) if go_left else int(node[5])
        steps += 1
    raise ValueError("INVALID_ARTIFACT")


def eval_gbdt_raw(ensemble: dict[str, Any] | None, x: Sequence[float]) -> float:
    """Evaluates raw additive tree margin (base + sum of tree leaf outputs)."""
    if not ensemble:
        return 0.0
    score = float(ensemble.get("base", 0.0))
    for tree_nodes in ensemble.get("trees", ()):
        score += _eval_tree(tree_nodes, x)
    if not math.isfinite(score):
        raise ValueError("NON_FINITE_OUTPUT")
    return score


def eval_gbdt_prob(ensemble: dict[str, Any] | None, x: Sequence[float]) -> float:
    """Evaluates GBDT logit + optional validation-fitted Platt scaling [slope, intercept]."""
    if not ensemble:
        return 0.5
    raw_logit = eval_gbdt_raw(ensemble, x)
    platt = ensemble.get("platt")
    if isinstance(platt, (list, tuple)) and len(platt) == 2:
        calibrated_logit = float(platt[0]) * raw_logit + float(platt[1])
    else:
        calibrated_logit = raw_logit
    return _clip(_sigmoid(calibrated_logit), 0.01, 0.99)


def _validate_gbdt_ensemble(ensemble: Any, n_features: int) -> None:
    if not isinstance(ensemble, dict):
        raise ValueError("INVALID_ARTIFACT")
    base = ensemble.get("base")
    trees = ensemble.get("trees")
    if not isinstance(base, (int, float)) or not math.isfinite(base) or not isinstance(trees, list) or not trees:
        raise ValueError("INVALID_ARTIFACT")
    for nodes in trees:
        if not isinstance(nodes, list) or not nodes:
            raise ValueError("INVALID_ARTIFACT")
        for nd in nodes:
            if not isinstance(nd, list) or len(nd) != 7:
                raise ValueError("INVALID_ARTIFACT")
            val, f_idx, thresh, left, is_leaf, right, _ = nd
            if not math.isfinite(float(val)) or not math.isfinite(float(thresh)):
                raise ValueError("INVALID_ARTIFACT")
            if not is_leaf:
                if not (0 <= int(f_idx) < n_features):
                    raise ValueError("INVALID_ARTIFACT")
                if not (0 <= int(left) < len(nodes)) or not (0 <= int(right) < len(nodes)):
                    raise ValueError("INVALID_ARTIFACT")


def _validate_linear_head(head: Any, n_features: int) -> None:
    if not isinstance(head, dict):
        raise ValueError("INVALID_ARTIFACT")
    for field in ("mean", "scale", "coef"):
        vec = head.get(field)
        if not isinstance(vec, list) or len(vec) != n_features or not all(isinstance(v, (int, float)) and math.isfinite(v) for v in vec):
            raise ValueError("INVALID_ARTIFACT")
    if any(float(v) <= 0.0 for v in head["scale"]):
        raise ValueError("INVALID_ARTIFACT")
    inter = head.get("intercept", 0.0)
    if not isinstance(inter, (int, float)) or not math.isfinite(float(inter)):
        raise ValueError("INVALID_ARTIFACT")


def _eval_linear(
    x: Sequence[float],
    mean: tuple[float, ...],
    scale: tuple[float, ...],
    coef: tuple[float, ...],
    intercept: float,
) -> float:
    acc = intercept
    for xi, mu, sc, wi in zip(x, mean, scale, coef):
        acc += ((xi - mu) / sc) * wi
    if not math.isfinite(acc):
        raise ValueError("NON_FINITE_OUTPUT")
    return acc


@dataclass(frozen=True)
class QuantHorizonModel:
    format: str
    feature_names: tuple[str, ...]
    feature_schema_hash: str
    preprocessing_hash: str
    horizon_seconds: int
    mean: tuple[float, ...]
    scale: tuple[float, ...]
    coef: tuple[float, ...]
    domain_min: tuple[float, ...]
    domain_max: tuple[float, ...]
    intercept: float
    volatility_estimate: float
    residual_std: float
    calibration_version: str
    gbdt_edge: dict[str, Any] | None
    gbdt_barrier_diff: dict[str, Any] | None
    gbdt_long_win: dict[str, Any] | None
    gbdt_short_win: dict[str, Any] | None
    logit_mean: tuple[float, ...]
    logit_scale: tuple[float, ...]
    logit_coef: tuple[float, ...]
    logit_intercept: float | None
    edge_std: float
    barrier_std: float
    linear_std: float
    ensemble_weights: tuple[float, float, float]
    ensemble_blend: float
    tp_r_multiple: float
    fee_r_cost: float
    a: dict[str, Any]

    def __init__(self, raw: bytes | str | dict[str, Any], expected_hash: str | None = None) -> None:
        if isinstance(raw, dict):
            a = raw
        else:
            if isinstance(raw, str):
                raw = raw.encode("utf-8")
            if expected_hash is not None:
                computed = "sha256:" + sha256(raw).hexdigest()
                if computed != expected_hash:
                    raise ValueError("ARTIFACT_HASH_MISMATCH")
            a = json.loads(raw)

        fmt = a.get("format")
        if fmt == "quant-horizon-v3":
            expected_names = list(QUANT_FEATURE_NAMES_V3)
            expected_schema_hash = QUANT_SCHEMA_HASH_V3
        elif fmt == "quant-horizon-v4":
            expected_names = list(QUANT_FEATURE_NAMES)
            expected_schema_hash = QUANT_SCHEMA_HASH
        else:
            raise ValueError("FEATURE_SCHEMA_MISMATCH")

        if a.get("featureNames") != expected_names:
            raise ValueError("FEATURE_SCHEMA_MISMATCH")
        if a.get("featureSchemaHash") != expected_schema_hash:
            raise ValueError("FEATURE_SCHEMA_MISMATCH")

        expected_len = len(expected_names)
        for field in ("mean", "scale", "coef", "domainMin", "domainMax"):
            if (
                field not in a
                or len(a[field]) != expected_len
                or not all(isinstance(v, (int, float)) and math.isfinite(v) for v in a[field])
            ):
                raise ValueError("INVALID_ARTIFACT")

        if (
            any(v <= 0 for v in a["scale"])
            or not math.isfinite(a["intercept"])
            or not math.isfinite(a["volatilityEstimate"])
            or a["volatilityEstimate"] < 0
        ):
            raise ValueError("INVALID_ARTIFACT")

        gbdt_edge = a.get("gbdtEdge")
        gbdt_barrier_diff = a.get("gbdtBarrierDiff")
        gbdt_long_win = a.get("gbdtLongWin")
        gbdt_short_win = a.get("gbdtShortWin")
        linear_logit = a.get("linearLogit")
        if fmt == "quant-horizon-v4":
            _validate_gbdt_ensemble(gbdt_edge, expected_len)
            if gbdt_barrier_diff is not None:
                _validate_gbdt_ensemble(gbdt_barrier_diff, expected_len)
            else:
                _validate_gbdt_ensemble(gbdt_long_win, expected_len)
                _validate_gbdt_ensemble(gbdt_short_win, expected_len)
            if linear_logit is not None:
                _validate_linear_head(linear_logit, expected_len)

        scales_cfg = a.get("ensembleScales") or {}
        edge_std = max(float(scales_cfg.get("edgeStd", 1.0)), 1e-4)
        barrier_std = max(float(scales_cfg.get("barrierStd", 1.0)), 1e-4)
        linear_std = max(float(scales_cfg.get("linearStd", 1.0)), 1e-4)
        raw_weights = a.get("ensembleWeights")
        if isinstance(raw_weights, (list, tuple)) and len(raw_weights) == 3:
            w_pair = (float(raw_weights[0]), float(raw_weights[1]), float(raw_weights[2]))
            w_sum = sum(max(0.0, w) for w in w_pair)
            ens_weights = tuple(max(0.0, w) / w_sum for w in w_pair) if w_sum > 1e-6 else (0.35, 0.35, 0.30)
        else:
            ens_weights = (0.35, 0.35, 0.30)

        logit_mean = tuple(float(v) for v in linear_logit["mean"]) if linear_logit else ()
        logit_scale = tuple(float(v) for v in linear_logit["scale"]) if linear_logit else ()
        logit_coef = tuple(float(v) for v in linear_logit["coef"]) if linear_logit else ()
        logit_intercept = float(linear_logit.get("intercept", 0.0)) if linear_logit else None

        barrier_cfg = a.get("barrierConfig") or {}
        tp_atr = float(barrier_cfg.get("tpAtr", 3.0))
        sl_atr = max(float(barrier_cfg.get("slAtr", 1.5)), 1e-6)
        tp_r = _clip(tp_atr / sl_atr, 1.0, 5.0)
        fee_r = _clip(float(barrier_cfg.get("feeR", 0.08)), 0.0, 1.0)
        blend = _clip(float(a.get("ensembleBlend", 0.80 if fmt == "quant-horizon-v4" else 0.0)), 0.0, 1.0)

        res_std = float(a.get("residualStd", max(float(a["volatilityEstimate"]), 1e-4)))
        if not math.isfinite(res_std) or res_std <= 0:
            res_std = max(float(a["volatilityEstimate"]), 1e-4)

        object.__setattr__(self, "a", a)
        object.__setattr__(self, "format", str(fmt))
        object.__setattr__(self, "feature_names", tuple(a["featureNames"]))
        object.__setattr__(self, "feature_schema_hash", str(a["featureSchemaHash"]))
        object.__setattr__(self, "preprocessing_hash", str(a.get("preprocessingHash", "")))
        object.__setattr__(self, "horizon_seconds", int(a["horizonSeconds"]))
        object.__setattr__(self, "mean", tuple(float(x) for x in a["mean"]))
        object.__setattr__(self, "scale", tuple(float(x) for x in a["scale"]))
        object.__setattr__(self, "coef", tuple(float(x) for x in a["coef"]))
        object.__setattr__(self, "domain_min", tuple(float(x) for x in a["domainMin"]))
        object.__setattr__(self, "domain_max", tuple(float(x) for x in a["domainMax"]))
        object.__setattr__(self, "intercept", float(a["intercept"]))
        object.__setattr__(self, "volatility_estimate", float(a["volatilityEstimate"]))
        object.__setattr__(self, "residual_std", res_std)
        object.__setattr__(
            self,
            "calibration_version",
            str(a.get("calibrationVersion", "walkforward-gbdt-triple-barrier-v4" if fmt == "quant-horizon-v4" else "walkforward-ridge-quant-v3")),
        )
        object.__setattr__(self, "gbdt_edge", gbdt_edge)
        object.__setattr__(self, "gbdt_barrier_diff", gbdt_barrier_diff)
        object.__setattr__(self, "gbdt_long_win", gbdt_long_win)
        object.__setattr__(self, "gbdt_short_win", gbdt_short_win)
        object.__setattr__(self, "logit_mean", logit_mean)
        object.__setattr__(self, "logit_scale", logit_scale)
        object.__setattr__(self, "logit_coef", logit_coef)
        object.__setattr__(self, "logit_intercept", logit_intercept)
        object.__setattr__(self, "edge_std", edge_std)
        object.__setattr__(self, "barrier_std", barrier_std)
        object.__setattr__(self, "linear_std", linear_std)
        object.__setattr__(self, "ensemble_weights", ens_weights)
        object.__setattr__(self, "ensemble_blend", blend)
        object.__setattr__(self, "tp_r_multiple", tp_r)
        object.__setattr__(self, "fee_r_cost", fee_r)

    def _in_domain(self, x: Sequence[float]) -> bool:
        if len(x) != len(self.feature_names):
            return False
        for xi, lo, hi in zip(x, self.domain_min, self.domain_max):
            if not math.isfinite(xi) or xi < lo or xi > hi:
                return False
        return True

    def predict_quant(
        self,
        x: Sequence[float],
        horizon_seconds: int,
        horizon_vol: float,
        summary: dict[str, Any],
    ) -> dict[str, Any] | None:
        if horizon_seconds != self.horizon_seconds:
            raise ValueError("INVALID_HORIZON")
        if not self._in_domain(x):
            return None

        # 1. Trained linear Ridge inference on standardized causal features
        raw_ridge = self.intercept
        for xi, mu, sc, wi in zip(x, self.mean, self.scale, self.coef):
            raw_ridge += ((xi - mu) / sc) * wi

        if not math.isfinite(raw_ridge):
            raise ValueError("NON_FINITE_OUTPUT")

        # Named feature lookups for transparent regime & alignment scoring
        idx = {name: i for i, name in enumerate(self.feature_names)}
        ema_trend_z = x[idx["ema_trend_z"]]
        ema_macro_z = x[idx["ema_macro_z"]]
        rsi_centered = x[idx["rsi_14_centered"]]
        macd_hist_atr = x[idx["macd_hist_atr"]]
        bb_z = x[idx["bb_zscore_20"]]
        eff_ratio = x[idx["efficiency_ratio_14"]]
        vol_regime = x[idx["vol_regime_ratio"]]
        volume_z = x[idx["volume_z_20"]]
        htf_trend_z = x[idx["htf_trend_z"]]
        funding_bps = x[idx["funding_rate_bps"]]
        atr_pct = float(summary.get("atrPct", 0.0))
        dyn_vol = max(float(horizon_vol), atr_pct, 0.0005)

        trend_composite = 0.40 * ema_trend_z + 0.25 * ema_macro_z + 0.35 * htf_trend_z
        momentum_composite = 0.50 * macd_hist_atr + 0.30 * rsi_centered - 0.20 * _clip(bb_z - 2.0, 0.0, 2.0)

        if self.format == "quant-horizon-v4" and self.gbdt_edge is not None:
            return self._predict_v4_gbdt(
                x=x,
                idx=idx,
                raw_ridge=raw_ridge,
                dyn_vol=dyn_vol,
                atr_pct=atr_pct,
                trend_composite=trend_composite,
                ema_trend_z=ema_trend_z,
                rsi_centered=rsi_centered,
                bb_z=bb_z,
                eff_ratio=eff_ratio,
                vol_regime=vol_regime,
                volume_z=volume_z,
                htf_trend_z=htf_trend_z,
                funding_bps=funding_bps,
            )

        ridge_norm = raw_ridge / max(self.volatility_estimate, 1e-4)
        composite_dir = (
            0.55 * _clip(ridge_norm, -2.5, 2.5)
            + 0.30 * _clip(trend_composite, -2.0, 2.0)
            + 0.15 * _clip(momentum_composite, -2.0, 2.0)
        )
        direction = 1.0 if composite_dir >= 0 else -1.0
        reason_codes, conviction, htf_agrees, is_choppy, extreme_stress = self._score_regime_and_conviction(
            direction=direction,
            base_conviction=0.46 + 0.18 * min(abs(composite_dir), 1.8) + 0.16 * eff_ratio,
            ema_trend_z=ema_trend_z,
            rsi_centered=rsi_centered,
            bb_z=bb_z,
            eff_ratio=eff_ratio,
            vol_regime=vol_regime,
            atr_pct=atr_pct,
            volume_z=volume_z,
            htf_trend_z=htf_trend_z,
            funding_bps=funding_bps,
            model_tag="QUANT_MODEL_V3",
        )
        signal_confidence = _clip(conviction, 0.20, 0.94)

        if signal_confidence < 0.22 or extreme_stress:
            predicted_log_return = direction * min(abs(raw_ridge) * 0.25, dyn_vol * 0.15)
            win_prob = 0.49
            if "LOW_CONFIDENCE" not in reason_codes:
                reason_codes.append("LOW_CONFIDENCE")
        else:
            if signal_confidence < 0.48 and "LOW_CONFIDENCE" not in reason_codes:
                reason_codes.append("LOW_CONFIDENCE")
            edge_gain = 0.38 + 1.00 * _clip((signal_confidence - 0.22) / 0.72, 0.0, 1.0)
            if is_choppy:
                edge_gain *= 0.85
            if htf_agrees:
                edge_gain *= 1.12
            if volume_z > 0.25:
                edge_gain *= 1.06
            min_floor = dyn_vol * (0.42 if is_choppy else 0.50)
            predicted_mag = _clip(dyn_vol * edge_gain, max(abs(raw_ridge) * 0.5, min_floor), dyn_vol * 2.2)
            if (raw_ridge >= 0 and direction > 0) or (raw_ridge < 0 and direction < 0):
                predicted_mag = max(predicted_mag, min(abs(raw_ridge) * 1.1, dyn_vol * 2.0))
            predicted_log_return = direction * predicted_mag
            win_prob = _clip(0.50 + (signal_confidence - 0.45) * 0.34, 0.48, 0.66)

        if not math.isfinite(predicted_log_return) or not math.isfinite(dyn_vol):
            raise ValueError("NON_FINITE_OUTPUT")

        return {
            "predictedLogReturn": float(predicted_log_return),
            "volatilityEstimate": float(dyn_vol),
            "calibrationVersion": self.calibration_version,
            "reasonCodes": list(dict.fromkeys(reason_codes)),
            "summaryExtras": {
                "rawRidgeReturn": round(raw_ridge, 6),
                "compositeSignal": round(composite_dir, 4),
                "signalConfidence": round(signal_confidence, 4),
                "winProbabilityEstimate": round(win_prob, 4),
            },
        }

    def _score_regime_and_conviction(
        self,
        *,
        direction: float,
        base_conviction: float,
        ema_trend_z: float,
        rsi_centered: float,
        bb_z: float,
        eff_ratio: float,
        vol_regime: float,
        atr_pct: float,
        volume_z: float,
        htf_trend_z: float,
        funding_bps: float,
        model_tag: str,
        extra_chop_escape: float = 0.0,
    ) -> tuple[list[str], float, bool, bool, bool]:
        reason_codes: list[str] = [model_tag]
        htf_agrees = (direction > 0 and htf_trend_z > 0.25) or (direction < 0 and htf_trend_z < -0.25)
        htf_conflicts = (direction > 0 and htf_trend_z < -0.75) or (direction < 0 and htf_trend_z > 0.75)
        is_choppy = eff_ratio < 0.18 and abs(ema_trend_z) < 0.50 and abs(bb_z) < 1.80 and extra_chop_escape < 0.65
        extreme_stress = vol_regime > 2.85 and atr_pct > 0.065

        exhausted_long = direction > 0 and rsi_centered > 1.35 and bb_z > 2.2
        exhausted_short = direction < 0 and rsi_centered < -1.35 and bb_z < -2.2
        crowded_funding = (direction > 0 and funding_bps > 4.5) or (direction < 0 and funding_bps < -4.5)

        conviction = base_conviction
        if htf_agrees:
            conviction += 0.08
            reason_codes.append("HTF_TREND_CONFIRMED")
        elif htf_conflicts:
            conviction -= 0.11
            reason_codes.append("HTF_COUNTER_TREND")
        if volume_z > 0.35:
            conviction += 0.04
            reason_codes.append("VOLUME_CONFIRMED")
        if is_choppy:
            conviction -= 0.15
            reason_codes.append("CHOPPY_REGIME")
        if exhausted_long or exhausted_short:
            conviction -= 0.12
            reason_codes.append("MEAN_REVERSION_EXHAUSTION")
        if crowded_funding:
            conviction -= 0.05
            reason_codes.append("HIGH_FUNDING_CAUTION")
        if extreme_stress:
            conviction -= 0.24
            reason_codes.append("VOLATILITY_REGIME_UNSAFE")
        if abs(ema_trend_z) >= 0.45 and eff_ratio >= 0.26:
            reason_codes.append("TREND_MOMENTUM_ALIGNED")
        return reason_codes, conviction, htf_agrees, is_choppy, extreme_stress

    def _predict_v4_gbdt(
        self,
        *,
        x: Sequence[float],
        idx: dict[str, int],
        raw_ridge: float,
        dyn_vol: float,
        atr_pct: float,
        trend_composite: float,
        ema_trend_z: float,
        rsi_centered: float,
        bb_z: float,
        eff_ratio: float,
        vol_regime: float,
        volume_z: float,
        htf_trend_z: float,
        funding_bps: float,
    ) -> dict[str, Any]:
        """Pure-Python GBDT + Triple-Barrier symmetric ensemble inference."""
        raw_gbdt_z = eval_gbdt_raw(self.gbdt_edge, x)
        gbdt_ret = _clip(raw_gbdt_z * dyn_vol, -dyn_vol * 2.5, dyn_vol * 2.5)
        blended_ret = self.ensemble_blend * gbdt_ret + (1.0 - self.ensemble_blend) * raw_ridge

        taker_imbal_z = x[idx["taker_imbalance_z"]]
        cum_delta_z = x[idx["cum_delta_z"]]
        sweep_score = x[idx["liquidity_sweep_score"]]
        range_comp_z = x[idx["range_compression_z"]]

        if self.gbdt_barrier_diff is not None:
            raw_bar_diff = eval_gbdt_raw(self.gbdt_barrier_diff, x)
            if self.logit_intercept is not None:
                raw_lin = _eval_linear(x, self.logit_mean, self.logit_scale, self.logit_coef, self.logit_intercept)
            else:
                raw_lin = raw_ridge
            z_edge = raw_gbdt_z / self.edge_std
            z_bar = raw_bar_diff / self.barrier_std
            z_lin = raw_lin / self.linear_std
            w_e, w_b, w_l = self.ensemble_weights
            combined_dir = (
                w_e * _clip(z_edge, -3.0, 3.0)
                + w_b * _clip(z_bar, -3.0, 3.0)
                + w_l * _clip(z_lin, -3.0, 3.0)
            )
            direction = 1.0 if combined_dir >= 0.0 else -1.0
            agree_count = (
                (1 if (z_edge * direction) > 0.02 else 0)
                + (1 if (z_bar * direction) > 0.02 else 0)
                + (1 if (z_lin * direction) > 0.02 else 0)
            )
            oppose_count = (
                (1 if (z_edge * direction) < -0.05 else 0)
                + (1 if (z_bar * direction) < -0.05 else 0)
                + (1 if (z_lin * direction) < -0.05 else 0)
            )
            models_agree = (agree_count >= 2) and (oppose_count == 0)
            abs_dir = abs(combined_dir)
            if models_agree and abs_dir >= 0.42:
                base_conv = (
                    0.535
                    + 0.14 * _clip((abs_dir - 0.42) / 1.08, 0.0, 1.0)
                    + (0.02 if agree_count == 3 else 0.0)
                    + 0.04 * _clip(eff_ratio, 0.0, 1.0)
                )
            elif models_agree:
                base_conv = 0.42 + 0.08 * _clip(abs_dir / 0.42, 0.0, 1.0) + 0.03 * _clip(eff_ratio, 0.0, 1.0)
            else:
                base_conv = 0.32 + 0.10 * _clip(abs_dir, 0.0, 1.0)
        else:
            raw_bar_diff = 0.0
            agree_count = 2
            p_long_raw = eval_gbdt_prob(self.gbdt_long_win, x)
            p_short_raw = eval_gbdt_prob(self.gbdt_short_win, x)
            ev_long = p_long_raw * self.tp_r_multiple - (1.0 - p_long_raw) - self.fee_r_cost
            ev_short = p_short_raw * self.tp_r_multiple - (1.0 - p_short_raw) - self.fee_r_cost
            edge_z = blended_ret / dyn_vol
            barrier_z = (p_long_raw - p_short_raw) / 0.08
            combined_dir = (
                0.46 * _clip(edge_z, -2.5, 2.5)
                + 0.44 * _clip(barrier_z, -2.5, 2.5)
                + 0.10 * _clip(trend_composite, -2.0, 2.0)
            )
            direction = 1.0 if combined_dir >= 0.0 else -1.0
            models_agree = (edge_z * barrier_z) > 0.0
            selected_ev_legacy = ev_long if direction > 0 else ev_short
            base_conv = (
                0.44
                + 0.38 * _clip(selected_ev_legacy, -0.30, 0.45)
                + 0.10 * _clip(abs(combined_dir), 0.0, 1.5)
                + (0.05 if models_agree else -0.06)
                + 0.08 * _clip(eff_ratio, 0.0, 1.0)
            )

        flow_signal = (0.45 * taker_imbal_z + 0.35 * cum_delta_z + 0.50 * sweep_score) * direction
        flow_confirms = flow_signal > 0.45
        flow_conflicts = flow_signal < -0.95

        reason_codes, conviction, htf_agrees, is_choppy, extreme_stress = self._score_regime_and_conviction(
            direction=direction,
            base_conviction=base_conv,
            ema_trend_z=ema_trend_z,
            rsi_centered=rsi_centered,
            bb_z=bb_z,
            eff_ratio=eff_ratio,
            vol_regime=vol_regime,
            atr_pct=atr_pct,
            volume_z=volume_z,
            htf_trend_z=htf_trend_z,
            funding_bps=funding_bps,
            model_tag="QUANT_MODEL_V4",
            extra_chop_escape=abs(sweep_score),
        )
        if flow_confirms:
            conviction += 0.05
            reason_codes.append("ORDER_FLOW_CONFIRMED")
        elif flow_conflicts:
            conviction -= 0.07
            reason_codes.append("ORDER_FLOW_DIVERGENCE")
        if abs(sweep_score) >= 0.65 and (sweep_score * direction) > 0:
            conviction += 0.04
            reason_codes.append("LIQUIDITY_SWEEP_ALIGNED")
        if range_comp_z < -0.55 and abs(combined_dir) > 0.45:
            reason_codes.append("SQUEEZE_BREAKOUT_SETUP")

        min_conf = 0.18 if extreme_stress else 0.24
        signal_confidence = _clip(conviction, min_conf, 0.94)
        if self.gbdt_barrier_diff is not None and (not models_agree or abs(combined_dir) < 0.42):
            signal_confidence = min(signal_confidence, 0.49)

        if self.gbdt_barrier_diff is not None:
            win_prob = _clip(
                0.43
                + 0.08 * _clip((abs(combined_dir) - 0.30) / 1.20, 0.0, 1.0)
                + (0.04 if models_agree else -0.04)
                + (0.025 if htf_agrees else 0.0)
                + (0.025 if flow_confirms else 0.0)
                - (0.05 if is_choppy else 0.0),
                0.36,
                0.68,
            )
            p_long = win_prob if direction > 0 else _clip(1.0 - win_prob - 0.08, 0.24, 0.60)
            p_short = win_prob if direction < 0 else _clip(1.0 - win_prob - 0.08, 0.24, 0.60)
            selected_ev = win_prob * self.tp_r_multiple - (1.0 - win_prob) - self.fee_r_cost

            if signal_confidence < 0.22 or extreme_stress:
                predicted_log_return = direction * min(abs(blended_ret) * 0.20, dyn_vol * 0.10)
                if "LOW_CONFIDENCE" not in reason_codes:
                    reason_codes.append("LOW_CONFIDENCE")
            elif signal_confidence < 0.52 or is_choppy:
                # Below selective LOW/MEDIUM gate: calibrated moderate edge so HIGH risk remains responsive without inflating MAE
                chop_scale = 0.24 if is_choppy else (0.24 + 0.14 * _clip((signal_confidence - 0.22) / 0.30, 0.0, 1.0))
                predicted_mag = _clip(max(abs(blended_ret) * 0.35, dyn_vol * chop_scale), dyn_vol * 0.22, dyn_vol * 0.45)
                predicted_log_return = direction * predicted_mag
                if signal_confidence < 0.50 and "LOW_CONFIDENCE" not in reason_codes:
                    reason_codes.append("LOW_CONFIDENCE")
            else:
                strength = _clip((signal_confidence - 0.50) / 0.35, 0.0, 1.0)
                edge_mult = 0.36 + 0.56 * strength + (0.08 if htf_agrees else 0.0) + (0.06 if flow_confirms else 0.0)
                raw_anchor = abs(raw_gbdt_z * dyn_vol * 0.50) if (raw_gbdt_z * direction) > 0 else 0.0
                predicted_mag = _clip(dyn_vol * edge_mult, max(raw_anchor, dyn_vol * 0.38), dyn_vol * 1.45)
                predicted_log_return = direction * predicted_mag
        else:
            p_long = p_long_raw
            p_short = p_short_raw
            win_prob = _clip(p_long if direction > 0 else p_short, 0.30, 0.78)
            selected_ev = ev_long if direction > 0 else ev_short
            if signal_confidence < 0.22 or extreme_stress:
                predicted_log_return = direction * min(abs(blended_ret) * 0.25, dyn_vol * 0.14)
                if "LOW_CONFIDENCE" not in reason_codes:
                    reason_codes.append("LOW_CONFIDENCE")
            else:
                if selected_ev < 0.0 and "LOW_CONFIDENCE" not in reason_codes and signal_confidence < 0.52:
                    reason_codes.append("LOW_CONFIDENCE")
                prob_sep = _clip(abs(p_long - p_short) / 0.12, 0.0, 1.2)
                edge_gain = 0.36 + 0.95 * _clip((signal_confidence - 0.22) / 0.70, 0.0, 1.0) + 0.18 * prob_sep
                if is_choppy:
                    edge_gain *= 0.80
                if htf_agrees:
                    edge_gain *= 1.10
                if flow_confirms:
                    edge_gain *= 1.08
                min_floor = dyn_vol * (0.40 if is_choppy else 0.48)
                raw_anchor = abs(blended_ret) if (blended_ret * direction) > 0 else 0.0
                predicted_mag = _clip(dyn_vol * edge_gain, max(raw_anchor * 0.85, min_floor), dyn_vol * 2.4)
                predicted_log_return = direction * predicted_mag

        if not math.isfinite(predicted_log_return) or not math.isfinite(dyn_vol):
            raise ValueError("NON_FINITE_OUTPUT")

        return {
            "predictedLogReturn": float(predicted_log_return),
            "volatilityEstimate": float(dyn_vol),
            "calibrationVersion": self.calibration_version,
            "reasonCodes": list(dict.fromkeys(reason_codes)),
            "summaryExtras": {
                "rawRidgeReturn": round(raw_ridge, 6),
                "gbdtZScore": round(raw_gbdt_z, 4),
                "barrierDiffScore": round(raw_bar_diff, 4),
                "modelsAgreeCount": int(agree_count),
                "pLongWin": round(p_long, 4),
                "pShortWin": round(p_short, 4),
                "barrierEvR": round(selected_ev, 4),
                "compositeSignal": round(combined_dir, 4),
                "signalConfidence": round(signal_confidence, 4),
                "winProbabilityEstimate": round(win_prob, 4),
            },
        }
