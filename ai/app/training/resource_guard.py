"""A10.5: Eğitim sırasında serving ve simülasyon gecikmesini koruyan kaynak bekçisi (Resource Guard)."""
import time
from typing import Callable


class ResourceGuard:
    """Inference gecikmesi veya simülasyon gecikmesi eşikleri aşıldığında eğitimi geçici olarak durduran (pause) bekçi."""

    def __init__(
        self,
        max_inference_latency_ms: float = 150.0,
        max_market_lag_ms: float = 500.0,
        pause_duration_seconds: float = 2.0,
        latency_provider: Callable[[], float] | None = None,
        lag_provider: Callable[[], float] | None = None,
    ):
        self.max_inference_latency_ms = max_inference_latency_ms
        self.max_market_lag_ms = max_market_lag_ms
        self.pause_duration_seconds = pause_duration_seconds
        self.latency_provider = latency_provider or (lambda: 15.0)
        self.lag_provider = lag_provider or (lambda: 20.0)
        self.paused_count = 0

    def check_and_throttle(self) -> bool:
        """Kaynak tüketimini kontrol eder; gerekirse duraklatma (throttle/pause) uygular."""
        curr_latency = self.latency_provider()
        curr_lag = self.lag_provider()

        if curr_latency > self.max_inference_latency_ms or curr_lag > self.max_market_lag_ms:
            self.paused_count += 1
            time.sleep(self.pause_duration_seconds)
            return True

        return False

