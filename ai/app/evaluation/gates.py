"""A09: Eşikler validation'da önceden dondurulur; test raporu eşik seçmez.
Hard leakage, schema, ve kalite kapıları.
"""
import math
from typing import Any


def publish_gate(report: dict[str, Any], thresholds: dict[str, Any]) -> dict[str, Any]:
    """Model değerlendirme raporunu dondurulmuş kalite ve güvenlik eşiklerine göre sınar."""
    failures = []
    maximums = thresholds.get("maximum", {})
    minimums = thresholds.get("minimum", {})
    numeric_keys = set(maximums.keys()) | set(minimums.keys())

    for name in numeric_keys:
        value = report.get(name)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            failures.append(name)

    if failures:
        return {"passed": False, "failures": failures}

    # Sıfır hata zorunlu güvenlik ve doğruluk alanları
    for name in ("schemaErrors", "nonFiniteOutputs", "knownLeakage", "policyContradictions"):
        if report.get(name) != 0:
            failures.append(name)

    # Doğrulanması zorunlu boolean kapılar
    for name in ("artifactVerified", "inputParity", "authorizationTestsPassed", "javaReplayVerified"):
        if report.get(name) is not True:
            failures.append(name)

    # Maksimum limitler
    for name, maximum in maximums.items():
        if name not in report or report[name] > maximum:
            failures.append(name)

    # Minimum limitler
    for name, minimum in minimums.items():
        if name not in report or report[name] < minimum:
            failures.append(name)

    return {"passed": not failures, "failures": failures}

