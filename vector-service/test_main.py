"""vector-service 双 collection 契约测试。

用桩替换 SentenceTransformer（不加载真实模型），chroma 用临时目录，
保证测试确定性且不触碰 data/chroma/ 真实向量库。
"""
import os
import sys
import tempfile
import types

import numpy as np

# 在导入 main 之前打桩：临时 chroma 目录 + 假编码模型
_TMP_CHROMA = tempfile.mkdtemp(prefix="docrag-test-chroma-")
os.environ["DOCRAG_CHROMA_DIR"] = _TMP_CHROMA

_fake_st = types.ModuleType("sentence_transformers")


class _FakeModel:
    """确定性伪编码：按文本字符哈希生成 8 维归一化向量，不同文本向量不同。"""

    def __init__(self, name):
        pass

    def encode(self, texts, **kw):
        single = isinstance(texts, str)
        if single:
            texts = [texts]
        vecs = []
        for t in texts:
            v = [0.0] * 8
            for i, ch in enumerate(t):
                v[(ord(ch) + i) % 8] += 1.0
            norm = sum(x * x for x in v) ** 0.5 or 1.0
            vecs.append([x / norm for x in v])
        return np.array(vecs[0]) if single else np.array(vecs)


_fake_st.SentenceTransformer = _FakeModel
sys.modules["sentence_transformers"] = _fake_st

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402

client = TestClient(main.app)


def _doc(doc_id, mode):
    return {
        "docId": doc_id,
        "filename": f"{doc_id}.docx",
        "type": "docx",
        "mode": mode,
        "chunks": [f"{doc_id}-{mode}-块一", f"{doc_id}-{mode}-块二"],
    }


def _counts():
    return client.get("/health").json()["vectors"]


def setup_function():
    client.request("DELETE", "/documents")


def test_health_reports_per_mode_counts():
    data = client.get("/health").json()
    assert data["status"] == "ok"
    assert set(data["vectors"].keys()) == {"plain", "deep"}


def test_upsert_and_query_mode_isolation():
    r = client.post("/documents", json=_doc("d1", "plain"))
    assert r.status_code == 200 and r.json() == {"chunkCount": 2}
    assert _counts() == {"plain": 2, "deep": 0}

    # plain 库能召回，deep 库为空
    hits_plain = client.post("/query", json={"text": "d1-plain-块一", "topK": 5, "mode": "plain"}).json()["hits"]
    hits_deep = client.post("/query", json={"text": "d1-plain-块一", "topK": 5, "mode": "deep"}).json()["hits"]
    assert hits_plain and hits_plain[0]["docId"] == "d1"
    assert hits_deep == []

    # deep 库单独入库后互不影响
    client.post("/documents", json=_doc("d1", "deep"))
    assert _counts() == {"plain": 2, "deep": 2}


def test_upsert_invalid_mode_rejected():
    body = _doc("d1", "plain")
    body["mode"] = "full"
    assert client.post("/documents", json=body).status_code == 422
    assert client.post("/query", json={"text": "x", "mode": "table"}).status_code == 422


def test_delete_doc_default_all_modes():
    client.post("/documents", json=_doc("d1", "plain"))
    client.post("/documents", json=_doc("d1", "deep"))
    client.post("/documents", json=_doc("d2", "deep"))
    r = client.delete("/documents/d1")
    assert r.status_code == 200
    assert _counts() == {"plain": 0, "deep": 2}
    # 不存在的 docId 删除是幂等 no-op
    assert client.delete("/documents/missing").status_code == 200


def test_clear_single_mode_keeps_other():
    client.post("/documents", json=_doc("d1", "plain"))
    client.post("/documents", json=_doc("d1", "deep"))
    r = client.delete("/documents", params={"mode": "plain"})
    assert r.status_code == 200
    assert _counts() == {"plain": 0, "deep": 2}
    # 清过的 collection 句柄已重建，可继续写入
    client.post("/documents", json=_doc("d2", "plain"))
    assert _counts()["plain"] == 2


def test_clear_all_and_invalid_mode():
    client.post("/documents", json=_doc("d1", "plain"))
    client.post("/documents", json=_doc("d1", "deep"))
    client.delete("/documents")
    assert _counts() == {"plain": 0, "deep": 0}
    assert client.delete("/documents", params={"mode": "table"}).status_code == 400


def test_query_hit_shape_matches_java_contract():
    """Java VectorClient 解析的字段：docId/filename/type/chunk/similarity，按相似度降序"""
    client.post("/documents", json=_doc("d1", "plain"))
    r = client.post("/query", json={"text": "d1-plain-块一", "topK": 5, "mode": "plain"})
    hits = r.json()["hits"]
    assert hits, "同文本应能召回自身"
    for h in hits:
        assert set(h.keys()) == {"docId", "filename", "type", "chunk", "similarity"}
        assert h["filename"] == "d1.docx" and h["type"] == "docx"
        assert -1.0 <= h["similarity"] <= 1.0
    sims = [h["similarity"] for h in hits]
    assert sims == sorted(sims, reverse=True), "命中须按相似度降序"
    # 空白查询不调模型直接返回空
    assert client.post("/query", json={"text": "  ", "topK": 5, "mode": "plain"}).json()["hits"] == []


def test_upsert_idempotent_no_stale_chunks():
    """同 docId 重复入库覆盖旧 chunk：计数不翻倍、旧文本不可再召回"""
    client.post("/documents", json=_doc("d1", "plain"))
    assert _counts()["plain"] == 2
    # 第二次入库换成不同 chunk
    body = _doc("d1", "plain")
    body["chunks"] = ["全新的内容块"]
    assert client.post("/documents", json=body).json() == {"chunkCount": 1}
    assert _counts()["plain"] == 1, "upsert 应覆盖而非追加"
    hits = client.post("/query", json={"text": "全新的内容块", "topK": 5, "mode": "plain"}).json()["hits"]
    assert hits and all(h["chunk"] == "全新的内容块" for h in hits), \
        "命中的只能是新 chunk，旧 chunk 必须已被删除: " + str(hits)


def test_topk_limits_results():
    for i in range(3):
        body = _doc(f"d{i}", "deep")
        assert client.post("/documents", json=body).status_code == 200
    hits = client.post("/query", json={"text": "d0-deep-块一", "topK": 2, "mode": "deep"}).json()["hits"]
    assert len(hits) == 2


def test_list_documents_per_mode_aggregates_by_doc():
    client.post("/documents", json=_doc("d1", "plain"))
    client.post("/documents", json=_doc("d1", "deep"))
    client.post("/documents", json=_doc("d2", "plain"))
    r = client.get("/documents", params={"mode": "plain"})
    assert r.status_code == 200
    docs = r.json()["docs"]
    assert docs == [
        {"docId": "d1", "filename": "d1.docx", "type": "docx", "chunkCount": 2},
        {"docId": "d2", "filename": "d2.docx", "type": "docx", "chunkCount": 2},
    ]
    assert client.get("/documents", params={"mode": "deep"}).json()["docs"] \
        == [{"docId": "d1", "filename": "d1.docx", "type": "docx", "chunkCount": 2}]
    # 空库 / 非法 mode
    client.delete("/documents", params={"mode": "deep"})
    assert client.get("/documents", params={"mode": "deep"}).json()["docs"] == []
    assert client.get("/documents", params={"mode": "all"}).status_code == 400
    assert client.get("/documents", params={"mode": "table"}).status_code == 400


def test_get_document_chunks_ordered_404_on_missing():
    client.post("/documents", json=_doc("d1", "plain"))
    r = client.get("/documents/d1", params={"mode": "plain"})
    assert r.status_code == 200
    data = r.json()
    assert data["docId"] == "d1" and data["filename"] == "d1.docx" and data["type"] == "docx"
    assert [c["chunkIndex"] for c in data["chunks"]] == [0, 1]
    assert data["chunks"][1]["text"] == "d1-plain-块二"
    # 其他模式无此文档 → 404；删除后 → 404；非法 mode → 400
    assert client.get("/documents/d1", params={"mode": "deep"}).status_code == 404
    client.delete("/documents/d1")
    assert client.get("/documents/d1", params={"mode": "plain"}).status_code == 404
    assert client.get("/documents/d1", params={"mode": "all"}).status_code == 400
