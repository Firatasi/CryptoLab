"""A06.7: Serving modüllerinin izole olduğunu ve training/sources kütüphanelerini import etmediğini doğrular."""
import sys


def test_serving_does_not_import_training_or_sources():
    """Serving için gerekli modüller yüklendiğinde training, sources veya pypdf'in yüklenmediğini kontrol eder."""
    # Modülleri temizle
    to_delete = [
        k for k in list(sys.modules.keys())
        if k.startswith(("app.", "pypdf", "torch", "transformers", "peft"))
    ]
    for k in to_delete:
        del sys.modules[k]

    # Yalnız serving bileşenlerini import et
    import app.api.model_api
    import app.contracts.model
    import app.features.pipeline
    import app.inference.numeric
    import app.inference.service
    import app.registry.loader

    # Yüklenmemesi gereken modüllerin olmadığını doğrula
    assert "pypdf" not in sys.modules, "pypdf should not be loaded in serving context"
    assert "app.sources.pdf_extract" not in sys.modules, "pdf_extract should not be loaded in serving context"
    assert "app.training.numeric" not in sys.modules, "training.numeric should not be loaded in serving context"
    assert "torch" not in sys.modules, "torch should not be loaded in serving context"

