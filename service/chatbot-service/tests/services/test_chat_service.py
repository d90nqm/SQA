"""BOT-05, BOT-07: ChatService - dieu phoi luong chat, khong goi Gemini that."""
import asyncio
from unittest.mock import AsyncMock, MagicMock, call

from app.services import chatbot_service
from app.services.chatbot_service import ChatService

TREATMENT_RELATIONS = ["TREATED_BY", "MANAGED_BY", "SUPPORTS_TREATMENT", "REQUIRES_TREATMENT"]


def test_bot_05_process_question_should_execute_thin_runtime_orchestration():
    ai = MagicMock()
    ai.predict_intent.return_value = {"label": "INTENT_TREATMENT", "score": 0.9}
    ai.predict_ner.return_value = [{"type": "DISEASE", "text": "sau rang", "start": 9, "end": 17}]

    doc = {"text": "Sau rang", "neighbors": ["[TREATED_BY] Tram rang"]}
    repo = MagicMock()
    # lan 1 (tim theo thuc the 'sau rang'): rong -> lan 2 (tim ca cau): tra ve 1 doc
    repo.search_with_relations.side_effect = [[], [doc]]

    history = MagicMock()
    history.save_message = AsyncMock()

    service = ChatService(ai_engine=ai, neo4j_repo=repo, history_repo=history)
    service._generate_response_smart = MagicMock(return_value="BOT REPLY")   # khong goi Gemini

    question = "Cach chua sau rang"
    result = asyncio.run(service.process_question("user-1", question))

    assert result == {"reply": "BOT REPLY", "intent": "INTENT_TREATMENT", "entities": ["sau rang"]}
    assert repo.search_with_relations.call_args_list == [
        call("sau rang", TREATMENT_RELATIONS, limit=2),
        call(question, TREATMENT_RELATIONS, limit=3),
    ]
    # context gui sang buoc sinh cau tra loi phai chua doc tim duoc
    args = service._generate_response_smart.call_args.args
    assert args[0] == question and "Sau rang" in args[1] and args[2] == "INTENT_TREATMENT"
    # luu lich su 2 tin: user va bot
    assert history.save_message.await_args_list == [
        call("user-1", "user", question),
        call("user-1", "bot", "BOT REPLY"),
    ]


def test_bot_07_generate_response_should_use_fallback_when_llm_is_disabled(monkeypatch):
    monkeypatch.setattr(chatbot_service, "llm", None)    # khong co LLM
    service = ChatService(ai_engine=MagicMock(), neo4j_repo=MagicMock(), history_repo=MagicMock())

    reply = service._generate_response_smart("toi bi dau rang", "", "DEFAULT")

    assert isinstance(reply, str) and reply.strip()
    assert "triệu chứng" in reply.lower()   # xin them thong tin trieu chung
    assert "bác sĩ" in reply.lower()        # hoac khuyen di kham
