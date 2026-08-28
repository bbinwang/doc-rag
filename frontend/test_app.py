"""前端测试：页面渲染、高亮片段透传、后端不可用降级。"""
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


def test_index_renders_search_box(client):
    html = client.get("/").get_data(as_text=True)
    assert "文档检索" in html
    assert 'name="q"' in html


def test_search_renders_highlighted_snippet(client, monkeypatch):
    payload = {
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
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    html = client.get("/?q=合同").get_data(as_text=True)
    assert "<em>合同</em>" in html
    assert "劳动合同.docx" in html
    assert "共 1 条结果" in html
    # 混合检索来源徽标
    assert "混合" in html
    assert "已降级" not in html
    # 查看原文按钮 + 惰性加载容器
    assert 'data-doc="abc-123"' in html
    assert 'id="doc-abc-123"' in html


def test_search_degraded_shows_warning(client, monkeypatch):
    payload = {
        "total": 1,
        "degraded": True,
        "hits": [
            {
                "docId": "abc-123",
                "filename": "劳动合同.docx",
                "path": "/p",
                "type": "docx",
                "snippet": "<em>合同</em>",
                "score": 1.0,
                "source": "bm25",
            }
        ],
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    html = client.get("/?q=合同").get_data(as_text=True)
    assert "已降级为纯关键词检索" in html
    assert "关键词" in html


def test_doc_detail_proxies_backend(client, monkeypatch):
    payload = {
        "docId": "abc-123",
        "filename": "劳动合同.docx",
        "path": "/data/upload/劳动合同.docx",
        "type": "docx",
        "modified": 1750000000000,
        "content": "第一行原始文本\n第二行\t带制表符 <script>x</script>",
    }
    monkeypatch.setattr(
        app_module.requests, "get", lambda *args, **kwargs: FakeResponse(payload)
    )
    rv = client.get("/doc/abc-123")
    html = rv.get_data(as_text=True)
    assert rv.status_code == 200
    assert "第一行原始文本" in html
    assert "入库时间" in html
    # 原文必须整体转义，不能注入 HTML
    assert "<script>x</script>" not in html
    assert "&lt;script&gt;" in html


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
    assert len(payload["content"]) > app_module.MAX_DOC_CHARS


def test_search_backend_down_shows_error(client, monkeypatch):
    def boom(*args, **kwargs):
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(app_module.requests, "get", boom)
    html = client.get("/?q=合同").get_data(as_text=True)
    assert "后端服务不可用" in html


def test_upload_requires_file(client):
    rv = client.post("/upload", data={})
    assert rv.status_code == 400
    assert "未选择文件" in rv.get_data(as_text=True)


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
    # 注意：此版本 Werkzeug 中 (bytes, filename) 会被归入 form，需用 (BytesIO, filename) 才是文件字段
    html = client.post(
        "/debug", data={"file": (io.BytesIO(b"fake"), "员工表.docx")}
    ).get_data(as_text=True)
    assert "图片不参与索引" in html          # 图片计数警告
    assert "表格 1" in html                   # 结构化表格标题
    assert "<td>张三</td>" in html            # 表格单元格
    assert "姓名\t年龄" in html or "姓名&#9;年龄" in html  # 拍平索引文本（tab 转义两种形式）
    assert "实际入索引的拍平文本" in html      # 对比区块标题
