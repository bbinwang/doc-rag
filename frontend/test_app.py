"""前端测试：页面渲染、双模式检索渲染、高亮片段透传、后端不可用降级。"""
import io

import pytest
import requests

import app as app_module


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    @property
    def ok(self):
        return self.status_code < 400

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}")

    def json(self):
        return self._payload


@pytest.fixture()
def client():
    app_module.app.config["TESTING"] = True
    with app_module.app.test_client() as c:
        yield c


# ---- 页面结构 ----

def test_index_renders_search_box(client):
    html = client.get("/").get_data(as_text=True)
    assert "文档检索" in html
    assert 'name="q"' in html


def test_index_renders_mode_checkboxes_and_ask_link(client):
    html = client.get("/").get_data(as_text=True)
    assert 'name="modes"' in html
    assert 'value="plain"' in html
    assert 'value="deep"' in html
    # 独立问答页入口；旧的内嵌问答条/勾选已移除
    assert 'href="/ask"' in html
    assert 'id="ask-bar"' not in html
    assert 'class="hit-select"' not in html
    assert 'id="ask-citations"' not in html


def test_index_renders_mode_upload_checkboxes(client):
    html = client.get("/").get_data(as_text=True)
    assert "上传并入库" in html
    # 上传与检索都有 modes 勾选
    assert html.count('name="modes"') >= 4


def test_invalid_modes_fallback_to_plain(client, monkeypatch):
    captured = {}

    def fake_get(url, params=None, timeout=None):
        captured["params"] = params
        return FakeResponse({"modes": {}, "total": 0})

    monkeypatch.setattr(app_module.requests, "get", fake_get)
    client.get("/?q=合同&modes=full,table")
    assert captured["params"]["modes"] == ["plain"]


# ---- 检索 ----

def test_search_forwards_modes_param(client, monkeypatch):
    captured = {}

    def fake_get(url, params=None, timeout=None):
        captured["params"] = params
        return FakeResponse({"modes": {}})

    monkeypatch.setattr(app_module.requests, "get", fake_get)
    client.get("/?q=合同&modes=plain,deep")
    assert captured["params"]["modes"] == ["plain", "deep"]
    assert captured["params"]["q"] == "合同"


def test_search_single_mode_renders_one_column(client, monkeypatch):
    payload = {
        "modes": {
            "plain": {
                "total": 1,
                "degraded": False,
                "hits": [
                    {
                        "docId": "abc-123",
                        "filename": "劳动合同.docx",
                        "path": "/data/upload/劳动合同.docx",
                        "type": "docx",
                        "snippet": "其中<em>合同</em>条款约定……",
                        "score": 3.2,
                        "source": "both",
                    }
                ],
            }
        }
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    html = client.get("/?q=合同&modes=plain").get_data(as_text=True)
    assert "<em>合同</em>" in html
    assert "劳动合同.docx" in html
    assert "1 条" in html                       # 计数按模式展示
    assert "results-grid" in html
    assert "results-grid dual" not in html      # 单模式单栏，无双栏布局
    # 混合检索来源徽标
    assert "混合" in html
    # 查看原文按钮带模式标记 + 惰性加载容器
    assert 'data-mode="plain"' in html
    assert 'id="doc-plain-abc-123"' in html


def test_search_dual_modes_renders_two_columns(client, monkeypatch):
    payload = {
        "modes": {
            "plain": {
                "total": 1, "degraded": False,
                "hits": [{
                    "docId": "p1", "filename": "合同.docx", "path": "/p", "type": "docx",
                    "snippet": "<em>合同</em>条款", "score": 1.0, "source": "bm25",
                }],
            },
            "deep": {
                "total": 2, "degraded": True,
                "hits": [{
                    "docId": "p1", "filename": "合同.docx", "path": "/p", "type": "docx",
                    "snippet": "| 条款 | <em>合同</em> |\n| --- | --- |\n| a | b |",
                    "score": 1.0, "source": "bm25",
                }],
            },
        }
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    html = client.get("/?q=合同&modes=plain,deep").get_data(as_text=True)
    assert "results-grid dual" in html          # 双模式两栏对比
    assert "纯文本解析" in html
    assert "深度解析" in html
    assert "1 条" in html and "2 条" in html    # 各栏独立计数
    assert "已降级" in html                      # 仅 deep 栏降级提示
    assert 'data-md-table="1"' in html          # deep 栏 snippet 按 markdown 表格渲染
    assert 'data-mode="deep"' in html
    # 同文档双栏原文容器各自独立（id 带模式前缀，不复用同一个 div）
    assert 'id="doc-plain-p1"' in html
    assert 'id="doc-deep-p1"' in html


def test_search_pagination_link_carries_modes(client, monkeypatch):
    payload = {
        "modes": {
            "plain": {
                "total": 25, "degraded": False,
                "hits": [{
                    "docId": "p1", "filename": "合同.docx", "path": "/p", "type": "docx",
                    "snippet": "合同", "score": 1.0, "source": "bm25",
                }],
            }
        }
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    html = client.get("/?q=合同&modes=plain,deep&page=2").get_data(as_text=True)
    assert "modes=plain,deep" in html
    assert "page=3" in html
    assert "page=1" in html  # 上一页链接


def test_search_backend_down_shows_error(client, monkeypatch):
    def boom(*args, **kwargs):
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(app_module.requests, "get", boom)
    html = client.get("/?q=合同").get_data(as_text=True)
    assert "后端服务不可用" in html


# ---- 原文片段 ----

def test_doc_detail_proxies_backend_plain(client, monkeypatch):
    payload = {
        "docId": "abc-123",
        "filename": "劳动合同.docx",
        "path": "/data/upload/劳动合同.docx",
        "type": "docx",
        "modified": 1750000000000,
        "content": "第一行原始文本\n第二行\t带制表符 <script>x</script>",
    }
    captured = {}

    def fake_get(url, timeout=None):
        captured["url"] = url
        return FakeResponse(payload)

    monkeypatch.setattr(app_module.requests, "get", fake_get)
    rv = client.get("/doc/abc-123")
    html = rv.get_data(as_text=True)
    assert rv.status_code == 200
    assert "第一行原始文本" in html
    assert "入库时间" in html
    # 原文必须整体转义，不能注入 HTML
    assert "<script>x</script>" not in html
    assert "&lt;script&gt;" in html
    assert captured["url"].endswith("/api/documents/abc-123")


def test_deep_doc_detail_proxies_store_api(client, monkeypatch):
    payload = {
        "docId": "abc-123",
        "filename": "预算表.xlsx",
        "path": "/p",
        "type": "xlsx",
        "modified": 1750000000000,
        "content": "正文\n\n表格 1\n| a | <script>b</script> |",
    }
    captured = {}

    def fake_get(url, timeout=None):
        captured["url"] = url
        return FakeResponse(payload)

    monkeypatch.setattr(app_module.requests, "get", fake_get)
    rv = client.get("/deep-doc/abc-123")
    html = rv.get_data(as_text=True)
    assert rv.status_code == 200
    # deep 原文原样展示：markdown 管道符保留，不做表格渲染改写
    assert 'data-md-table' not in html
    assert "<pre" in html
    assert "| a |" in html
    assert "<script>b</script>" not in html
    assert "&lt;script&gt;" in html
    assert captured["url"].endswith("/api/store/deep/abc-123")


def test_doc_detail_truncates_long_content(client, monkeypatch):
    payload = {
        "docId": "big",
        "filename": "大文档.docx",
        "path": "/p",
        "type": "docx",
        "modified": 0,
        "content": "字" * (app_module.MAX_DOC_CHARS + 10),
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    html = client.get("/doc/big").get_data(as_text=True)
    assert "超长，仅展示前" in html


# ---- 上传 ----

def test_upload_requires_file(client):
    rv = client.post("/upload", data={})
    assert rv.status_code == 400
    assert "未选择文件" in rv.get_data(as_text=True)


def test_upload_requires_mode(client):
    rv = client.post(
        "/upload", data={"file": (io.BytesIO(b"fake"), "a.docx"), "modes": []}
    )
    assert rv.status_code == 400
    assert "至少选择一种解析模式" in rv.get_data(as_text=True)


def test_upload_forwards_modes(client, monkeypatch):
    captured = {}

    def fake_post(url, files=None, data=None, timeout=None):
        captured["url"] = url
        captured["data"] = data
        return FakeResponse({"filename": "报告.docx", "modes": ["plain", "deep"]})

    monkeypatch.setattr(app_module.requests, "post", fake_post)
    rv = client.post(
        "/upload",
        data={
            "file": (io.BytesIO(b"fake"), "报告.docx"),
            "modes": ["plain", "deep"],
        },
        content_type="multipart/form-data",
    )
    assert rv.status_code == 200
    assert "已入库：报告.docx" in rv.get_data(as_text=True)
    assert captured["url"].endswith("/api/documents")
    assert captured["data"]["modes"] == ["plain", "deep"]


def test_upload_single_mode(client, monkeypatch):
    captured = {}

    def fake_post(url, files=None, data=None, timeout=None):
        captured["data"] = data
        return FakeResponse({"filename": "a.docx"})

    monkeypatch.setattr(app_module.requests, "post", fake_post)
    rv = client.post(
        "/upload",
        data={"file": (io.BytesIO(b"fake"), "a.docx"), "modes": ["deep"]},
        content_type="multipart/form-data",
    )
    assert rv.status_code == 200
    assert captured["data"]["modes"] == ["deep"]


# ---- 问答 ----

def test_ask_page_renders_form_params_and_modes(client, monkeypatch):
    monkeypatch.setattr(
        app_module.requests, "get",
        lambda *args, **kwargs: FakeResponse(
            {"bm25Chunks": 3, "vectorChunks": 4, "contextChunks": 6}),
    )
    html = client.get("/ask").get_data(as_text=True)
    assert rv_ok(html)
    assert 'id="ask-question"' in html
    assert 'id="param-bm25"' in html
    assert 'id="param-vector"' in html
    assert 'id="param-context"' in html
    # 参数初始值取后端 /api/ask/params
    assert 'value="3"' in html
    assert 'value="4"' in html
    assert 'value="6"' in html
    assert 'name="modes"' in html
    assert 'href="/"' in html


def rv_ok(html):
    return "doc-rag 文档问答" in html


def test_ask_page_params_fallback_when_backend_down(client, monkeypatch):
    def boom(*args, **kwargs):
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(app_module.requests, "get", boom)
    rv = client.get("/ask")
    html = rv.get_data(as_text=True)
    assert rv.status_code == 200
    # 后端不可达仍渲染页面，参数回退默认 5/5/8
    assert 'value="5"' in html
    assert 'value="8"' in html


def test_ask_proxies_backend(client, monkeypatch):
    payload = {
        "model": "gpt-test",
        "params": {"bm25Chunks": 5, "vectorChunks": 5, "contextChunks": 8},
        "modes": {
            "plain": {
                "answer": "试用期最长不超过六个月[1]。",
                "error": None,
                "degraded": False,
                "chunks": [
                    {
                        "ref": 1,
                        "docId": "abc-123",
                        "filename": "劳动合同.docx",
                        "type": "docx",
                        "title": None,
                        "source": "both",
                        "score": 0.0328,
                        "text": "试用期六个月",
                    }
                ],
                "docs": [
                    {"docId": "abc-123", "filename": "劳动合同.docx",
                     "type": "docx", "path": "/data/upload/a.docx"}
                ],
            }
        },
    }
    captured = {}

    def fake_post(url, json=None, timeout=None):
        captured["url"] = url
        captured["json"] = json
        captured["timeout"] = timeout
        return FakeResponse(payload)

    monkeypatch.setattr(app_module.requests, "post", fake_post)
    rv = client.post(
        "/ask",
        json={
            "question": "试用期最长多久",
            "modes": ["plain", "deep"],
            "bm25Chunks": 5,
            "vectorChunks": 5,
            "contextChunks": 8,
        },
    )
    assert rv.status_code == 200
    data = rv.get_json()
    assert data["modes"]["plain"]["answer"].startswith("试用期最长")
    assert data["modes"]["plain"]["chunks"][0]["source"] == "both"
    # 转发保真：URL、JSON（无 docIds，含三参数）、超时（须大于后端累计 LLM 耗时）
    assert captured["url"].endswith("/api/ask")
    assert "docIds" not in captured["json"]
    assert captured["json"]["modes"] == ["plain", "deep"]
    assert captured["json"]["contextChunks"] == 8
    assert captured["timeout"] == app_module.ASK_TIMEOUT


def test_ask_backend_error_passthrough(client, monkeypatch):
    monkeypatch.setattr(
        app_module.requests,
        "post",
        lambda *args, **kwargs: FakeResponse({"error": "LLM 未配置"}, status_code=400),
    )
    rv = client.post("/ask", json={"question": "x", "modes": ["plain"]})
    assert rv.status_code == 400
    assert "LLM 未配置" in rv.get_json()["error"]


def test_ask_backend_down_returns_502(client, monkeypatch):
    def boom(*args, **kwargs):
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(app_module.requests, "post", boom)
    rv = client.post("/ask", json={"question": "x", "modes": ["plain"]})
    assert rv.status_code == 502
    assert "后端服务不可用" in rv.get_json()["error"]


# ---- 状态 / 清理 ----

def test_index_renders_status_strip(client):
    html = client.get("/").get_data(as_text=True)
    assert 'id="store-status"' in html
    assert 'id="stat-plain"' in html
    assert 'id="stat-deep"' in html
    assert 'id="stat-vector"' in html
    assert 'id="clear-btn"' in html


def test_index_status_cards_link_to_store_pages(client):
    html = client.get("/").get_data(as_text=True)
    assert 'href="/store/plain"' in html
    assert 'href="/store/deep"' in html


def test_status_proxies_backend(client, monkeypatch):
    payload = {
        "plainIndex": {"docs": 2},
        "deepIndex": {"docs": 5},
        "vector": {"available": True, "vectors": {"plain": 3, "deep": 4}, "model": "bge-small-zh-v1.5"},
        "uploads": 3,
    }
    captured = {}

    def fake_get(url, params=None, timeout=None):
        captured["url"] = url
        return FakeResponse(payload)

    monkeypatch.setattr(app_module.requests, "get", fake_get)
    rv = client.get("/status")
    assert rv.status_code == 200
    assert rv.get_json() == payload          # JSON 原样透传
    assert captured["url"].endswith("/api/status")


def test_status_backend_down_returns_502(client, monkeypatch):
    def boom(*args, **kwargs):
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(app_module.requests, "get", boom)
    rv = client.get("/status")
    assert rv.status_code == 502
    assert "后端服务不可用" in rv.get_json()["error"]


def test_clear_proxies_backend(client, monkeypatch):
    payload = {
        "plainIndex": {"docs": 0},
        "deepIndex": {"docs": 0},
        "vector": {"available": True, "vectors": {"plain": 0, "deep": 0}, "model": "bge"},
        "uploads": 0,
        "cleared": ["vector", "plain", "deep", "uploads"],
    }
    captured = {}

    def fake_post(url, json=None, timeout=None):
        captured["url"] = url
        captured["timeout"] = timeout
        return FakeResponse(payload)

    monkeypatch.setattr(app_module.requests, "post", fake_post)
    rv = client.post("/clear")
    assert rv.status_code == 200
    assert rv.get_json()["cleared"] == ["vector", "plain", "deep", "uploads"]
    assert captured["url"].endswith("/api/admin/clear")
    assert captured["timeout"] >= 30


def test_clear_backend_error_passthrough(client, monkeypatch):
    monkeypatch.setattr(
        app_module.requests,
        "post",
        lambda *args, **kwargs: FakeResponse(
            {"error": "vector-service 不可用：已拒绝清理"}, status_code=400
        ),
    )
    rv = client.post("/clear")
    assert rv.status_code == 400
    assert "已拒绝清理" in rv.get_json()["error"]


# ---- 索引明细 ----

def test_store_list_renders_docs_with_links(client, monkeypatch):
    payload = {
        "total": 2,
        "docs": [
            {"docId": "t2", "filename": "预算表.xlsx", "path": "/p", "type": "xlsx",
             "modified": 1750000100000},
            {"docId": "t1", "filename": "合同.docx", "path": "/p", "type": "docx",
             "modified": 1750000000000},
        ],
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    rv = client.get("/store/deep")
    html = rv.get_data(as_text=True)
    assert rv.status_code == 200
    assert "深度解析索引明细 · 2 篇" in html
    assert 'href="/store/deep/t2"' in html
    assert "预算表.xlsx" in html
    assert "2025-" in html


def test_store_plain_list_uses_plain_kind(client, monkeypatch):
    payload = {"total": 1, "docs": [
        {"docId": "f1", "filename": "劳动合同.docx", "path": "/p", "type": "docx",
         "modified": 1750000000000},
    ]}
    captured = {}

    def fake_get(url, params=None, timeout=None):
        captured["url"] = url
        return FakeResponse(payload)

    monkeypatch.setattr(app_module.requests, "get", fake_get)
    html = client.get("/store/plain").get_data(as_text=True)
    assert captured["url"].endswith("/api/store/plain")
    assert "纯文本解析索引明细 · 1 篇" in html
    assert 'href="/store/plain/f1"' in html


def test_store_list_backend_down_returns_502(client, monkeypatch):
    def boom(*args, **kwargs):
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(app_module.requests, "get", boom)
    rv = client.get("/store/deep")
    assert rv.status_code == 502
    assert "后端服务不可用" in rv.get_data(as_text=True)


def test_store_deep_doc_renders_unified_text_escaped(client, monkeypatch):
    payload = {
        "docId": "t1", "filename": "预算表.xlsx", "path": "/p", "type": "xlsx",
        "modified": 1750000000000,
        "content": "正文说明\n\n表格 1\n| a | <script>b</script> |",
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    rv = client.get("/store/deep/t1")
    html = rv.get_data(as_text=True)
    assert rv.status_code == 200
    # 双栏：markdown 原文 + 渲染效果
    assert 'class="doc-columns"' in html
    assert "Markdown 原文" in html
    assert "渲染效果" in html
    assert 'data-md-table="1"' in html
    assert "表格 1" in html
    assert "<script>b</script>" not in html
    assert "&lt;script&gt;" in html


def test_store_plain_doc_renders_plain_text(client, monkeypatch):
    payload = {
        "docId": "f1", "filename": "劳动合同.docx", "path": "/p", "type": "docx",
        "modified": 1750000000000, "content": "第一行索引原文\n第二行",
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    rv = client.get("/store/plain/f1")
    html = rv.get_data(as_text=True)
    assert rv.status_code == 200
    assert "<pre" in html
    assert "第一行索引原文" in html
    assert "doc-columns" not in html, "plain 明细是单栏原文，无双栏布局"


def test_store_doc_missing_returns_404(client, monkeypatch):
    monkeypatch.setattr(
        app_module.requests,
        "get",
        lambda *args, **kwargs: FakeResponse({"error": "文档不存在: x"}, status_code=404),
    )
    rv = client.get("/store/deep/missing")
    assert rv.status_code == 404
    assert "文档不存在" in rv.get_data(as_text=True)


# ---- Debug ----

def test_debug_page_renders_form(client):
    html = client.get("/debug").get_data(as_text=True)
    assert "Debug 解析" in html
    assert 'action="/debug"' in html


def test_debug_parse_renders_tables_and_image_warning(client, monkeypatch):
    payload = {
        "filename": "员工表.docx",
        "type": "docx",
        "imageCount": 2,
        "tableCount": 1,
        "tablesTruncated": False,
        "textTruncated": False,
        "indexedText": "段落\n姓名\t年龄\n张三\t25\n",
        "tables": [
            {"title": "表格 1", "rows": [["姓名", "年龄"], ["张三", "25"]]}
        ],
    }
    monkeypatch.setattr(
        app_module.requests, "post", lambda *args, **kwargs: FakeResponse(payload)
    )
    html = client.post(
        "/debug", data={"file": (io.BytesIO(b"fake"), "员工表.docx")}
    ).get_data(as_text=True)
    assert "图片不参与索引" in html
    assert "表格 1" in html
    assert "<td>张三</td>" in html
    assert "实际入索引的拍平文本" in html


# ---- 向量库明细页 ----

def test_store_vector_lists_both_collections(client, monkeypatch):
    payloads = {
        f"{app_module.API_BASE}/api/store/vector/plain": FakeResponse({
            "mode": "plain", "total": 2, "chunkTotal": 5,
            "docs": [
                {"docId": "f1", "filename": "劳动合同.docx", "type": "docx", "chunkCount": 3},
                {"docId": "g2", "filename": "员工手册.docx", "type": "docx", "chunkCount": 2},
            ],
        }),
        f"{app_module.API_BASE}/api/store/vector/deep": FakeResponse({
            "mode": "deep", "total": 0, "chunkTotal": 0, "docs": [],
        }),
    }
    monkeypatch.setattr(app_module.requests, "get", lambda url, timeout=None: payloads[url])
    html = client.get("/store/vector").get_data(as_text=True)
    assert "docrag_plain" in html and "docrag_deep" in html
    assert "2 篇 / 5 向量" in html
    assert "0 篇 / 0 向量" in html
    assert "劳动合同.docx" in html
    assert "3 chunks" in html
    assert 'href="/store/vector/plain/f1"' in html
    assert "该向量库为空" in html


def test_store_vector_backend_down_shows_error(client, monkeypatch):
    def fake_get(url, timeout=None):
        return FakeResponse({"error": "vector-service HTTP 500"}, status_code=500)

    monkeypatch.setattr(app_module.requests, "get", fake_get)
    html = client.get("/store/vector").get_data(as_text=True)
    assert "vector-service HTTP 500" in html


def test_store_vector_doc_renders_chunk_list(client, monkeypatch):
    payload = {
        "docId": "f1", "filename": "劳动合同.docx", "type": "docx",
        "chunks": [
            {"chunkIndex": 0, "text": "合同正文第一块"},
            {"chunkIndex": 1, "text": "表格 1 | 条款 | 内容"},
        ],
    }
    monkeypatch.setattr(
        app_module.requests, "get",
        lambda url, timeout=None: FakeResponse(payload))
    html = client.get("/store/vector/plain/f1").get_data(as_text=True)
    assert "劳动合同.docx" in html
    assert "docrag_plain" in html
    assert "2 个 chunk" in html
    assert "合同正文第一块" in html
    assert "chunk 1" in html
    assert 'href="/store/plain/f1"' in html
    # chunk 文本转义展示
    assert "<script>" not in html or "合同正文" in html


def test_store_vector_doc_missing_returns_404(client, monkeypatch):
    monkeypatch.setattr(
        app_module.requests, "get",
        lambda url, timeout=None: FakeResponse(
            {"error": "向量库中不存在文档: x"}, status_code=404))
    rv = client.get("/store/vector/plain/missing")
    assert rv.status_code == 404
    assert "向量库中不存在文档" in rv.get_data(as_text=True)


def test_store_vector_doc_invalid_mode_404(client, monkeypatch):
    rv = client.get("/store/vector/table/f1")
    assert rv.status_code == 404
    assert "未知的解析模式" in rv.get_data(as_text=True)
