"""
Cau hinh chung cho unit test chatbot-service.

- Gia lap (stub) cac thu vien nang / can ket noi ngoai (torch, transformers, langchain, neo4j,
  motor, google-generativeai) NEU may chua cai chung -> unit test chay nhanh, khong can GPU,
  khong can Neo4j / MongoDB / Gemini that.
- Tat GEMINI_API_KEY de chac chan test KHONG goi Gemini that.
"""
import importlib
import os
import sys
from unittest.mock import MagicMock

os.environ["GEMINI_API_KEY"] = ""          # khong goi Gemini that
os.environ["USE_GPU"] = "false"


def _register_stub(dotted_name: str) -> None:
    """Dang ky module gia (MagicMock) cho 'a.b.c' va gan vao module cha."""
    parts = dotted_name.split(".")
    for i in range(1, len(parts) + 1):
        name = ".".join(parts[:i])
        if name not in sys.modules:
            sys.modules[name] = MagicMock(name=name)
        if i > 1:
            parent = sys.modules[".".join(parts[: i - 1])]
            setattr(parent, parts[i - 1], sys.modules[name])


for _mod in [
    "torch",
    "transformers",
    "langchain_huggingface",
    "langchain_neo4j",
    "motor.motor_asyncio",
    "pymongo",
    "google.generativeai",
]:
    try:
        importlib.import_module(_mod)
    except Exception:
        _register_stub(_mod)
