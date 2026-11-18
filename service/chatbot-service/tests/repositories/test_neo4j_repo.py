"""BOT-03, BOT-04: Neo4jRepository.search_with_relations voi vector store + Neo4j session gia lap."""
from unittest.mock import MagicMock

import pytest

from app.repositories.neo4j_repo import Neo4jRepository


class FakeSession:
    """Gia lap neo4j session: dung duoc trong 'with driver.session() as session'."""

    def __init__(self, records):
        self.records = records
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def run(self, cypher, **params):
        self.calls.append((cypher, params))
        return self.records


def make_repo(vector_store) -> Neo4jRepository:
    """Tao repository KHONG ket noi Neo4j that (bo qua __init__)."""
    repo = Neo4jRepository.__new__(Neo4jRepository)
    repo.vector_store = vector_store
    return repo


def test_bot_03_search_with_relations_should_return_text_and_formatted_neighbors():
    doc = MagicMock()
    doc.page_content = "Sau rang"
    session = FakeSession([
        {"rel_type": "TREATED_BY", "neighbor_text": "Tram rang"},
        {"rel_type": "CAUSE_BY", "neighbor_text": "Vi khuan"},
    ])
    vector_store = MagicMock()
    vector_store.similarity_search.return_value = [doc]
    vector_store._driver.session.return_value = session
    repo = make_repo(vector_store)

    result = repo.search_with_relations("sau rang", ["TREATED_BY", "CAUSE_BY"], limit=2)

    assert result == [{
        "text": "Sau rang",
        "neighbors": ["[TREATED_BY] Tram rang", "[CAUSE_BY] Vi khuan"],
    }]
    vector_store.similarity_search.assert_called_once_with("sau rang", k=2)
    assert session.calls[0][1]["allowed_rels"] == ["TREATED_BY", "CAUSE_BY"]


def _store_that_raises():
    store = MagicMock()
    store.similarity_search.side_effect = RuntimeError("vector search failed")
    return store


@pytest.mark.parametrize(
    "vector_store",
    [None, _store_that_raises()],
    ids=["vector_store_missing", "vector_search_raises"],
)
def test_bot_04_search_with_relations_should_return_empty_list_when_vector_store_missing_or_search_fails(vector_store):
    repo = make_repo(vector_store)

    assert repo.search_with_relations("sau rang", ["TREATED_BY"], limit=3) == []
