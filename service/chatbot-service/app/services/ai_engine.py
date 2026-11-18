import re
import unicodedata

import torch
from transformers import (
    AutoTokenizer, 
    AutoModelForTokenClassification, 
    pipeline
)
from app.core.config import settings


# ----------------------------------------------------------------------------
# Heuristic fallback: dung khi KHONG load duoc model Intent / NER
# (vi du chua co thu muc app/models). Khi model da load thi KHONG dung toi phan nay.
# ----------------------------------------------------------------------------
def preprocess_text(text: str) -> str:
    """Chuan hoa de so khop tu khoa: chu thuong + bo dau tieng Viet.
    Moi ky tu giu nguyen 1 ky tu (do dai khong doi) nen vi tri start/end van khop voi cau goc."""
    out = []
    for ch in text or "":
        low = ch.lower()
        if len(low) != 1:
            low = ch
        base = "".join(c for c in unicodedata.normalize("NFD", low) if not unicodedata.combining(c))
        if base == "đ":
            base = "d"
        out.append(base if len(base) == 1 else low)
    return "".join(out)


HEURISTIC_INTENT_KEYWORDS = [
    ("INTENT_CAUSE", ["nguyen nhan", "tai sao", "vi sao", "gay ra", "do dau"]),
    ("INTENT_DIAGNOSIS", ["trieu chung", "dau hieu", "bieu hien", "bi benh gi", "benh gi"]),
    ("INTENT_ADVICE", ["phong ngua", "ngan ngua", "nen lam gi", "loi khuyen", "cham soc"]),
    ("INTENT_TREATMENT", ["cach chua", "dieu tri", "chua tri", "chua", "tri lieu", "khac phuc"]),
]
HEURISTIC_INTENT_SCORE = 0.55
HEURISTIC_DEFAULT_SCORE = 0.2

# (cum tu da bo dau, loai thuc the)
HEURISTIC_ENTITY_TERMS = [
    ("sau rang", "DISEASE"),
    ("hoi mieng", "SYMPTOM"),
    ("dau rang", "SYMPTOM"),
    ("e buot rang", "SYMPTOM"),
    ("chay mau chan rang", "SYMPTOM"),
    ("viem loi", "DISEASE"),
    ("viem nuou", "DISEASE"),
    ("viem tuy", "DISEASE"),
    ("rang khon", "DISEASE"),
    ("cao voi rang", "TREATMENT"),
    ("tram rang", "TREATMENT"),
    ("nho rang", "TREATMENT"),
    ("nieng rang", "TREATMENT"),
    ("boc rang su", "TREATMENT"),
    ("tay trang rang", "TREATMENT"),
]


def _contains_phrase(folded: str, phrase: str) -> bool:
    return re.search(r"(?<!\w)" + re.escape(phrase) + r"(?!\w)", folded) is not None


class AIEngine:
    def __init__(self):
        print(f"Đang khởi tạo AI Engine trên thiết bị: {settings.DEVICE}...")

        print(f"   - Loading Intent model từ: {settings.INTENT_MODEL_PATH}")
        try:
            self.intent_classifier = pipeline(
                "text-classification", 
                model=settings.INTENT_MODEL_PATH, 
                tokenizer=settings.INTENT_MODEL_PATH,
                device=0 if settings.DEVICE == "cuda" else -1
            )
        except Exception as e:
            print(f"⚠️ Lỗi load Intent Model: {e}")
            self.intent_classifier = None

        print(f"   - Loading NER model từ: {settings.NER_MODEL_PATH}")
        try:
            self.ner_tokenizer = AutoTokenizer.from_pretrained(settings.NER_MODEL_PATH)
            self.ner_model = AutoModelForTokenClassification.from_pretrained(settings.NER_MODEL_PATH)
            self.ner_model.to(settings.DEVICE)
            self.ner_model.eval()

            self.id2label = self.ner_model.config.id2label
        except Exception as e:
            print(f"⚠️ Lỗi load NER Model: {e}")
            self.ner_model = None

    def _heuristic_intent(self, text: str) -> dict:
        folded = preprocess_text(text)
        for label, keywords in HEURISTIC_INTENT_KEYWORDS:
            if any(_contains_phrase(folded, kw) for kw in keywords):
                return {"label": label, "score": HEURISTIC_INTENT_SCORE}
        return {"label": "DEFAULT", "score": HEURISTIC_DEFAULT_SCORE}

    def _heuristic_ner(self, text: str) -> list:
        folded = preprocess_text(text)
        taken = [False] * len(folded)
        found = []
        # cum dai truoc de khong bi cum ngan chiem cho
        for phrase, ent_type in sorted(HEURISTIC_ENTITY_TERMS, key=lambda x: -len(x[0])):
            for m in re.finditer(r"(?<!\w)" + re.escape(phrase) + r"(?!\w)", folded):
                start, end = m.start(), m.end()
                if any(taken[start:end]):
                    continue
                for i in range(start, end):
                    taken[i] = True
                found.append({"type": ent_type, "text": text[start:end], "start": start, "end": end})
        return sorted(found, key=lambda e: e["start"])

    def predict_intent(self, text: str) -> dict:
        if not self.intent_classifier:
            return self._heuristic_intent(text)

        result = self.intent_classifier(text)[0]
        return result

    def predict_ner(self, text: str) -> list:
        if not self.ner_model:
            return self._heuristic_ner(text)

        inputs = self.ner_tokenizer(
            text, 
            return_offsets_mapping=True, 
            truncation=True, 
            return_tensors="pt"
        ).to(settings.DEVICE)
        
        offset_mapping = inputs.pop("offset_mapping")[0].cpu().numpy()
        
        with torch.no_grad():
            outputs = self.ner_model(**inputs)
        
        logits = outputs.logits
        predictions = torch.argmax(logits, dim=2)[0].cpu().numpy()

        entities = []
        current_entity = None

        for idx, pred in enumerate(predictions):
            label_name = self.id2label[pred]
            start, end = offset_mapping[idx]
            
            if start == end: continue

            if label_name.startswith("B-"):
                if current_entity:
                    entities.append(current_entity)
                current_entity = {
                    "type": label_name[2:],
                    "text": text[start:end],
                    "start": int(start),
                    "end": int(end)
                }
            elif label_name.startswith("I-") and current_entity:
                if label_name[2:] == current_entity["type"]:
                    current_entity["end"] = int(end)
                    current_entity["text"] = text[current_entity["start"]:current_entity["end"]]
            else:
                if current_entity:
                    entities.append(current_entity)
                    current_entity = None
        
        if current_entity:
            entities.append(current_entity)

        return entities