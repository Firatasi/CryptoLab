"""A08/A10: Token loss maskesi ve prompt saflığı kontrolleri."""
import pytest
from app.explanations.prompt import encode_example, render_prompt


class CharacterTokenizer:
    eos_token_id = 0

    def encode(self, s: str, add_special_tokens: bool = False) -> list[int]:
        return [ord(c) for c in s]


def test_target_only_loss():
    """Prompt token'larının -100 ile maskelendiğini ve yalnızca target'a loss hesaplandığını doğrular."""
    r = encode_example(CharacterTokenizer(), "input\n", "target", 100)
    assert r["labels"][:6] == [-100] * 6
    assert r["labels"][6:] == [ord(c) for c in "target"] + [0]


def test_no_silent_truncation():
    """Hedef max_tokens bütçesine sığmıyorsa sessiz kırpma yerine hata üretilmelidir."""
    with pytest.raises(ValueError):
        encode_example(CharacterTokenizer(), "input", "target", 4)


def test_prompt_no_source():
    """Prompt çıktısının yalnızca doğrulanmış girdileri içerdiğini, kaynak metin veya kanıt taşımadığını doğrular."""
    p = render_prompt({}, {"action": "WAIT"})
    assert "sourceSpans" not in p
    assert "retrievedEvidence" not in p

