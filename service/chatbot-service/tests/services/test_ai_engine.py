"""BOT-01, BOT-02, BOT-06: AIEngine chay o che do heuristic (khong co model Intent/NER)."""
from app.services.ai_engine import AIEngine


def make_heuristic_engine() -> AIEngine:
    """Tao AIEngine KHONG load model that (bo qua __init__) -> chay nhanh nhanh, khong can thu muc models."""
    engine = AIEngine.__new__(AIEngine)
    engine.intent_classifier = None
    engine.ner_model = None
    return engine


def test_bot_01_predict_intent_should_use_text_preprocessing_in_heuristic_runtime_path():
    engine = make_heuristic_engine()

    result = engine.predict_intent("Cach Chua Sau Rang")   # chu hoa + khong dau

    assert result["label"] == "INTENT_TREATMENT"
    assert abs(result["score"] - 0.55) < 1e-6
    # cung noi dung nhung viet thuong / viet hoa toan bo phai cho cung ket qua (da duoc tien xu ly)
    assert engine.predict_intent("cach chua sau rang")["label"] == "INTENT_TREATMENT"
    assert engine.predict_intent("CACH CHUA SAU RANG")["label"] == "INTENT_TREATMENT"


def test_bot_02_predict_ner_should_apply_text_preprocessing_in_heuristic_runtime_path():
    engine = make_heuristic_engine()
    text = "Toi bi Hoi Mieng va Dau Rang"

    entities = engine.predict_ner(text)

    found = {e["text"].lower() for e in entities}
    assert "hoi mieng" in found
    assert "dau rang" in found
    for e in entities:
        # moi span start/end phai map nguoc ve dung doan van ban goc
        assert text[e["start"]:e["end"]] == e["text"]


def test_bot_06_predict_intent_should_return_default_for_unknown_question():
    engine = make_heuristic_engine()

    result = engine.predict_intent("hom nay thoi tiet co mua khong")

    assert result["label"] == "DEFAULT"
    assert abs(result["score"] - 0.2) < 1e-6
