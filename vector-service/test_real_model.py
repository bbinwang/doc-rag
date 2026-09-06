"""真实 embedding 模型（bge-small-zh-v1.5，本地 models/ 目录）基本功能测试：

index 构造 chunks 入库 → query 构造一条查询验证召回。
不打桩、用真模型编码，跑一次约数秒（模型加载占大头）。
chroma 仍用临时目录，不触碰 data/chroma/ 真实向量库。
"""
import os
import sys
import tempfile

import pytest

pytest.importorskip("sentence_transformers", reason="未安装 sentence_transformers 时跳过真实模型测试")

_st = sys.modules.get("sentence_transformers")
if "main" in sys.modules or (_st is not None and not hasattr(_st, "__version__")):
    # test_main.py 打桩（假模块无 __version__）后 import main 会被 sys.modules 缓存，
    # 同进程混跑会拿到假模型。真实模型测试须单独运行：python -m pytest test_real_model.py
    pytest.skip("与打桩契约测试共用了进程，真实模型测试需单独运行", allow_module_level=True)

_TMP_CHROMA = tempfile.mkdtemp(prefix="docrag-test-real-chroma-")
os.environ["DOCRAG_CHROMA_DIR"] = _TMP_CHROMA

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402  （导入即加载真实模型，勿打桩）

client = TestClient(main.app)


def test_real_model_index_and_query():
    # 确认用的确实是本地真实模型而非桩
    assert "bge-small-zh" in main.MODEL_NAME

    chunks = [
        "劳动合同期限为三年，其中试用期为三个月。",
        "员工年度绩效奖金与部门考核结果挂钩。",
        "公司提供补充商业医疗保险。"
    ]
    r = client.post("/documents", json={
        "docId": "real-1",
        "filename": "员工手册.docx",
        "type": "docx",
        "mode": "plain",
        "chunks": chunks,
    })
    assert r.status_code == 200 and r.json() == {"chunkCount": 3}
    assert client.get("/health").json()["vectors"]["plain"] >= 3

    # 语义相关查询（无字面重叠于「劳动合同」四字也能靠语义命中）
    hits = client.post("/query", json={
        "text": "试用期多长时间",
        "topK": 3,
        "mode": "plain",
    }).json()["hits"]

    assert hits, "真实模型应能召回"
    assert hits[0]["docId"] == "real-1", f"最相似应为试用期条款: {hits[0]['chunk']}"
    assert hits[0]["chunk"] == chunks[0], f"命中应是第一条 chunk: {hits[0]['chunk']}"
    assert hits[0]["similarity"] > 0.5, f"语义相似度应显著: {hits[0]['similarity']}"
    # 第二条（绩效奖金）与查询语义最远，应排在试用期条款之后
    assert hits[0]["similarity"] > hits[-1]["similarity"]

    # 清理临时库
    assert client.delete("/documents/real-1").status_code == 200
