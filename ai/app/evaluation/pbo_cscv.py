"""A09+: Backtest Aşırı Uyum Olasılığı (PBO / CSCV), Sızıntı Bilgi Formu (Model Info Sheet)
ve Maliyet Sonrası Referans Strateji Karşılaştırması.

Kaynaklar:
- Bailey, Borwein, López de Prado, Zhu (2015): "The Probability of Backtest Overfitting" (S02)
- Kapoor & Narayanan (2022): "Leakage and the Reproducibility Crisis in ML-based Science" (S25)
- Fang et al. (2022): "Cryptocurrency Trading: A Comprehensive Survey" (S01)
"""
from itertools import combinations
import math
from typing import Any, Sequence
import numpy as np


def _sharpe_or_mean(series: np.ndarray) -> float:
    """Alt-örneklem performans metriği (standart sapma > 0 ise Sharpe, aksi halde ortalama)."""
    if len(series) == 0:
        return 0.0
    mean = float(np.mean(series))
    std = float(np.std(series, ddof=1)) if len(series) > 1 else 0.0
    if std <= 1e-12:
        return mean
    return mean / std


def compute_pbo_cscv(
    performance_matrix: Sequence[Sequence[float]],
    n_partitions: int = 4,
) -> dict[str, Any]:
    """Bailey et al. (2015) Algorithm 2.3 (CSCV) ile Probability of Backtest Overfitting (PBO) hesaplar.

    Parametreler:
        performance_matrix: (T x N) boyutlu matris; T zaman adımı, N denenen strateji/model sayısı.
        n_partitions: Çift sayı S (alt-matris bölünme sayısı, ör. 4, 8, 16).
    """
    m = np.asarray(performance_matrix, dtype=np.float64)
    if m.ndim != 2:
        raise ValueError("MATRIX_MUST_BE_2D")
    t_steps, n_trials = m.shape
    if n_trials < 2:
        raise ValueError("AT_LEAST_TWO_TRIALS_REQUIRED")
    if n_partitions < 2 or n_partitions % 2 != 0:
        raise ValueError("PARTITIONS_MUST_BE_EVEN_AT_LEAST_2")
    if t_steps < n_partitions or t_steps % n_partitions != 0:
        raise ValueError("TIME_STEPS_MUST_BE_DIVISIBLE_BY_PARTITIONS")
    if not np.all(np.isfinite(m)):
        raise ValueError("NON_FINITE_PERFORMANCE_MATRIX")

    submatrices = np.split(m, n_partitions, axis=0)
    half_s = n_partitions // 2
    all_indices = tuple(range(n_partitions))

    logits: list[float] = []
    is_best_scores: list[float] = []
    oos_Chosen_scores: list[float] = []

    for is_idx in combinations(all_indices, half_s):
        is_set = set(is_idx)
        oos_idx = [i for i in all_indices if i not in is_set]

        j_train = np.concatenate([submatrices[i] for i in is_idx], axis=0)
        j_test = np.concatenate([submatrices[i] for i in oos_idx], axis=0)

        r_is = np.array([_sharpe_or_mean(j_train[:, col]) for col in range(n_trials)], dtype=np.float64)
        r_oos = np.array([_sharpe_or_mean(j_test[:, col]) for col in range(n_trials)], dtype=np.float64)

        best_n = int(np.argmax(r_is))
        is_best_scores.append(float(r_is[best_n]))
        oos_Chosen_scores.append(float(r_oos[best_n]))

        # OOS sıralaması: 1 (en kötü) .. N (en iyi)
        order = np.argsort(np.argsort(r_oos, kind="mergesort"), kind="mergesort") + 1
        oos_rank = float(order[best_n])
        omega_c = oos_rank / (n_trials + 1.0)
        logit_c = math.log(omega_c / (1.0 - omega_c))
        logits.append(logit_c)

    total_combos = len(logits)
    overfit_count = sum(1 for l in logits if l <= 0.0)
    pbo = overfit_count / total_combos
    prob_oos_loss = sum(1 for s in oos_Chosen_scores if s < 0.0) / total_combos

    # Performans bozulması (IS -> OOS regresyon eğimi)
    if len(is_best_scores) > 1 and np.std(is_best_scores) > 1e-12:
        slope = float(np.polyfit(is_best_scores, oos_Chosen_scores, 1)[0])
    else:
        slope = 0.0

    return {
        "nTrials": n_trials,
        "nTimeSteps": t_steps,
        "nPartitions": n_partitions,
        "nCombinations": total_combos,
        "pbo": pbo,
        "probabilityOfLossOOS": prob_oos_loss,
        "performanceDegradationSlope": slope,
        "meanLogit": float(np.mean(logits)),
        "isOverfit": pbo > 0.50,
        "passesStrictGate": pbo <= 0.05,
    }


def build_leakage_info_sheet(
    *,
    has_held_out_test_set: bool,
    train_only_preprocessing: bool,
    train_only_feature_selection: bool,
    duplicate_free_across_splits: bool,
    no_illegitimate_proxy_features: bool,
    strict_temporal_ordering_and_purge: bool,
    family_grouped_independence: bool,
    survivorship_bias_documented: bool,
) -> dict[str, Any]:
    """Kapoor & Narayanan (2022) [L1.1 - L3.3] sızıntı taksonomisi denetim formunu üretir."""
    checks = {
        "L1.1_hasHeldOutTestSet": bool(has_held_out_test_set),
        "L1.2_trainOnlyPreprocessing": bool(train_only_preprocessing),
        "L1.3_trainOnlyFeatureSelection": bool(train_only_feature_selection),
        "L1.4_duplicateFreeAcrossSplits": bool(duplicate_free_across_splits),
        "L2_noIllegitimateProxyFeatures": bool(no_illegitimate_proxy_features),
        "L3.1_strictTemporalOrderingAndPurge": bool(strict_temporal_ordering_and_purge),
        "L3.2_familyGroupedIndependence": bool(family_grouped_independence),
        "L3.3_survivorshipBiasDocumented": bool(survivorship_bias_documented),
    }
    failed = [k for k, passed in checks.items() if not passed]
    return {
        "taxonomyReference": "Kapoor & Narayanan (2022) arXiv:2207.07048v1",
        "checks": checks,
        "passedAll": len(failed) == 0,
        "failedChecks": failed,
    }


def compare_with_baselines_after_costs(
    gross_returns: Sequence[float],
    positions: Sequence[int],
    *,
    fee_bps: float = 5.0,
    slippage_bps: float = 3.0,
    funding_bps_per_step: float = 1.0,
) -> dict[str, Any]:
    """Komisyon, slippage ve funding düşüldükten sonra stratejiyi basit referanslarla karşılaştırır.

    positions: Her adımda {-1 (SHORT), 0 (WAIT), +1 (LONG)}
    gross_returns: Her adımda varlığın gerçekleşmiş ham getirisi
    """
    if len(gross_returns) != len(positions) or len(gross_returns) == 0:
        raise ValueError("MISMATCHED_OR_EMPTY_SERIES")

    r = np.asarray(gross_returns, dtype=np.float64)
    pos = np.asarray(positions, dtype=np.int64)
    if not np.all(np.isin(pos, [-1, 0, 1])):
        raise ValueError("INVALID_POSITION_VALUES")

    cost_per_turnover = (fee_bps + slippage_bps) / 10000.0
    funding_rate = funding_bps_per_step / 10000.0

    # İşlem dönüşümü (turnover): pozisyon değişimi |pos_t - pos_{t-1}|
    prev_pos = np.concatenate(([0], pos[:-1]))
    turnover = np.abs(pos - prev_pos)

    strategy_gross = pos * r
    total_fees_and_slippage = turnover * cost_per_turnover
    total_funding = np.abs(pos) * funding_rate
    strategy_net = strategy_gross - total_fees_and_slippage - total_funding

    # Referans 1: NO_TRADE (Sıfır işlem baseline'ı)
    no_trade_net = np.zeros_like(r)

    # Referans 2: BUY_AND_HOLD (Başta 1 kez alım + dönem boyu funding)
    bh_turnover = np.zeros_like(r)
    bh_turnover[0] = 1.0
    buy_and_hold_net = r - (bh_turnover * cost_per_turnover) - funding_rate

    return {
        "steps": len(r),
        "totalTrades": int(np.sum(turnover > 0)),
        "grossExpectancyPerStep": float(np.mean(strategy_gross)),
        "netExpectancyPerStep": float(np.mean(strategy_net)),
        "totalCostDrag": float(np.sum(total_fees_and_slippage + total_funding)),
        "noTradeBaselineNet": float(np.mean(no_trade_net)),
        "buyAndHoldBaselineNet": float(np.mean(buy_and_hold_net)),
        "beatsNoTradeAfterCosts": bool(float(np.mean(strategy_net)) > 0.0),
        "beatsBuyAndHoldAfterCosts": bool(float(np.mean(strategy_net)) > float(np.mean(buy_and_hold_net))),
    }

