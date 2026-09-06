"""doc-rag 前端：搜索页面渲染 + 后端 API 转发（上传 / 检索 / 问答 / 原文查看）。仅做展示与转发，不承担业务逻辑。"""
import os
from datetime import datetime

import requests
from flask import Flask, render_template, request

API_BASE = os.environ.get("DOCRAG_API", "http://127.0.0.1:8080")
PAGE_SIZE = 10
MAX_DOC_CHARS = 200_000  # 原文展示截断（完整文本仍在索引中）
ASK_TIMEOUT = 180  # 双模式串行两次 LLM 调用，大于后端累计耗时，避免前端先断
MODES = ("plain", "deep")  # 解析模式，与后端 Mode 枚举一致
MODE_LABELS = {"plain": "纯文本解析", "deep": "深度解析"}
# 问答三参数初始值（后端不可达时的页面回退值；正常应取 GET /api/ask/params 反映 yml 配置）
ASK_PARAM_DEFAULTS = {"bm25Chunks": 5, "vectorChunks": 5, "contextChunks": 8}

app = Flask(__name__)


def _render(q="", page=1, size=PAGE_SIZE, results=None, error=None, uploaded=None,
            upload_error=None, modes=("plain",), modes_str="plain", has_next=False):
    return render_template(
        "index.html",
        q=q, page=page, size=size,
        results=results, error=error,
        uploaded=uploaded, upload_error=upload_error,
        modes=list(modes), modes_str=modes_str, has_next=has_next,
        mode_labels=MODE_LABELS,
    )


def _backend_error(resp, fallback):
    try:
        return resp.json().get("error", f"{fallback}（HTTP {resp.status_code}）")
    except ValueError:
        return f"{fallback}（HTTP {resp.status_code}）"


def _parse_modes(args):
    """解析 modes 参数（repeated 或逗号串），非法值丢弃；为空回退 plain"""
    raw = args.getlist("modes")
    values = []
    for item in raw:
        for part in str(item).split(","):
            part = part.strip()
            if part in MODES and part not in values:
                values.append(part)
    return values or ["plain"]


@app.route("/debug", methods=["GET", "POST"])
def debug():
    if request.method == "GET":
        return render_template("debug.html")

    f = request.files.get("file")
    if f is None or not f.filename:
        return render_template("debug.html", error="未选择文件"), 400

    try:
        resp = requests.post(
            f"{API_BASE}/api/debug/parse",
            files={"file": (f.filename, f.stream, f.mimetype)},
            timeout=120,
        )
        if resp.ok:
            return render_template("debug.html", result=resp.json())
        return render_template("debug.html", error=_backend_error(resp, "解析失败")), 502
    except requests.RequestException as exc:
        return render_template("debug.html", error=f"后端服务不可用：{exc}"), 502


@app.route("/")
def index():
    q = (request.args.get("q") or "").strip()
    page = max(1, request.args.get("page", 1, type=int) or 1)
    modes = _parse_modes(request.args)
    modes_str = ",".join(modes)

    results = None
    error = None
    has_next = False
    if q:
        try:
            resp = requests.get(
                f"{API_BASE}/api/search",
                params={"q": q, "page": page, "size": PAGE_SIZE, "modes": modes},
                timeout=10,
            )
            resp.raise_for_status()
            results = resp.json()
            # 两栏共享页码：任一模式结果超出本页即有下一页
            for m in modes:
                r = (results.get("modes") or {}).get(m) or {}
                if page * PAGE_SIZE < (r.get("total") or 0):
                    has_next = True
        except requests.RequestException as exc:
            error = f"后端服务不可用：{exc}"

    return _render(q=q, page=page, results=results, error=error, has_next=has_next,
                   modes=modes, modes_str=modes_str)


@app.route("/upload", methods=["POST"])
def upload():
    f = request.files.get("file")
    if f is None or not f.filename:
        return _render(upload_error="未选择文件"), 400

    modes = [m for m in request.form.getlist("modes") if m in MODES]
    if not modes:
        return _render(upload_error="请至少选择一种解析模式"), 400

    uploaded = None
    upload_error = None
    try:
        resp = requests.post(
            f"{API_BASE}/api/documents",
            files={"file": (f.filename, f.stream, f.mimetype)},
            data={"modes": modes},
            timeout=120,
        )
        if resp.ok:
            uploaded = resp.json().get("filename", f.filename)
        else:
            upload_error = _backend_error(resp, "上传失败")
    except requests.RequestException as exc:
        upload_error = f"后端服务不可用：{exc}"

    status = 200 if uploaded else 502
    return _render(uploaded=uploaded, upload_error=upload_error,
                   modes=modes, modes_str=",".join(modes)), status


@app.route("/ask", methods=["GET", "POST"])
def ask():
    """GET 渲染独立问答页（服务端取后端参数默认值）；POST 转发问答接口，JSON 进出"""
    if request.method == "GET":
        params = dict(ASK_PARAM_DEFAULTS)
        try:
            resp = requests.get(f"{API_BASE}/api/ask/params", timeout=5)
            if resp.ok:
                body = resp.json()
                params = {k: body.get(k, v) for k, v in ASK_PARAM_DEFAULTS.items()}
        except requests.RequestException:
            pass  # 后端不可达也照常渲染回退默认值；提交时后端仍会钳制
        return render_template("ask.html", mode_labels=MODE_LABELS, params=params)

    payload = request.get_json(silent=True) or {}
    try:
        resp = requests.post(f"{API_BASE}/api/ask", json=payload, timeout=ASK_TIMEOUT)
    except requests.RequestException as exc:
        return {"error": f"后端服务不可用：{exc}"}, 502
    if not resp.ok:
        try:
            return resp.json(), resp.status_code if resp.status_code < 500 else 502
        except ValueError:
            return {"error": f"问答失败（HTTP {resp.status_code}）"}, 502
    return resp.json()


@app.route("/status")
def status():
    """转发后端库状态（plain/deep 索引计数 + 向量库可用性 + 上传文件数），状态条 JS 调用"""
    try:
        resp = requests.get(f"{API_BASE}/api/status", timeout=10)
    except requests.RequestException as exc:
        return {"error": f"后端服务不可用：{exc}"}, 502
    if not resp.ok:
        return {"error": _backend_error(resp, "获取状态失败")}, 502
    return resp.json()


@app.route("/clear", methods=["POST"])
def clear():
    """转发一键清理：清空 plain/deep 索引 + 向量库 + 上传原文件，返回最新状态"""
    try:
        resp = requests.post(f"{API_BASE}/api/admin/clear", timeout=60)
    except requests.RequestException as exc:
        return {"error": f"后端服务不可用：{exc}"}, 502
    if not resp.ok:
        try:
            return resp.json(), resp.status_code if resp.status_code < 500 else 502
        except ValueError:
            return {"error": f"清理失败（HTTP {resp.status_code}）"}, 502
    return resp.json()


@app.route("/store/plain")
def store_plain():
    """plain 模式索引明细列表：转发 /api/store/plain，服务端渲染"""
    return _render_store("plain")


@app.route("/store/deep")
def store_deep():
    """deep 模式索引明细列表：转发 /api/store/deep，服务端渲染"""
    return _render_store("deep")


def _render_store(kind):
    docs, error, status = [], None, 200
    try:
        resp = requests.get(f"{API_BASE}/api/store/{kind}", timeout=15)
        if resp.ok:
            data = resp.json()
            items = data.get("docs", [])
            docs = [
                {
                    "docId": d["docId"],
                    "filename": d["filename"],
                    "type": d["type"],
                    "modified": datetime.fromtimestamp(d.get("modified", 0) / 1000).strftime("%Y-%m-%d %H:%M"),
                }
                for d in items
            ]
        else:
            error = _backend_error(resp, "获取明细列表失败")
            status = resp.status_code if resp.status_code < 500 else 502
    except requests.RequestException as exc:
        error = f"后端服务不可用：{exc}"
        status = 502
    return render_template("store_list.html", kind=kind, mode_labels=MODE_LABELS,
                           total=len(docs), docs=docs, error=error), status


@app.route("/store/plain/<doc_id>")
def store_plain_doc(doc_id):
    """plain 模式明细：整页原文（复用 /api/documents/{id}）"""
    return _render_store_doc("plain", f"{API_BASE}/api/documents/{doc_id}")


@app.route("/store/deep/<doc_id>")
def store_deep_doc(doc_id):
    """deep 模式明细：整页统一文本（markdown 表格由 main.js 渲染）"""
    return _render_store_doc("deep", f"{API_BASE}/api/store/deep/{doc_id}")


def _render_store_doc(kind, url):
    doc, content, truncated, modified, error, status = None, "", False, "", None, 200
    try:
        resp = requests.get(url, timeout=15)
        if resp.ok:
            doc = resp.json()
            content = doc.get("content") or ""
            truncated = len(content) > MAX_DOC_CHARS
            modified = datetime.fromtimestamp((doc.get("modified") or 0) / 1000).strftime("%Y-%m-%d %H:%M")
        else:
            error = _backend_error(resp, "获取明细失败")
            status = resp.status_code if resp.status_code < 500 else 502
    except requests.RequestException as exc:
        error = f"后端服务不可用：{exc}"
        status = 502
    return render_template(
        "store_doc.html",
        kind=kind, mode_labels=MODE_LABELS, doc=doc,
        content=content[:MAX_DOC_CHARS] if truncated else content,
        truncated=truncated, modified=modified, error=error,
    ), status


@app.route("/store/vector")
def store_vector():
    """向量库明细列表页：双向量 collection 各一节，每篇文档含 chunk 数"""
    collections, error, status = [], None, 200
    for mode in ("plain", "deep"):
        data = {"mode": mode, "label": MODE_LABELS[mode], "total": 0,
                "chunkTotal": 0, "docs": [], "error": None}
        try:
            resp = requests.get(f"{API_BASE}/api/store/vector/{mode}", timeout=15)
            if resp.ok:
                body = resp.json()
                data["total"] = body.get("total", 0)
                data["chunkTotal"] = body.get("chunkTotal", 0)
                data["docs"] = body.get("docs", [])
            else:
                data["error"] = _backend_error(resp, "获取向量库明细失败")
                if resp.status_code >= 500:
                    status = 502
            collections.append(data)
        except requests.RequestException as exc:
            data["error"] = f"后端服务不可用：{exc}"
            status = 502
            collections.append(data)
    return render_template("store_vector.html", collections=collections, error=error), status


@app.route("/store/vector/<mode>/<doc_id>")
def store_vector_doc(mode, doc_id):
    """向量库单文档 chunk 明细页：该文档在该 collection 中的全部 chunk"""
    if mode not in MODE_LABELS:
        return render_template("store_vector_doc.html", mode_labels=MODE_LABELS,
                               mode=mode, doc=None, chunks=[], error="未知的解析模式"), 404
    doc, chunks, error, status = None, [], None, 200
    try:
        resp = requests.get(f"{API_BASE}/api/store/vector/{mode}/{doc_id}", timeout=15)
        if resp.ok:
            body = resp.json()
            doc = body
            chunks = body.get("chunks", [])
        else:
            error = _backend_error(resp, "获取向量 chunk 明细失败")
            status = resp.status_code if resp.status_code < 500 else 502
    except requests.RequestException as exc:
        error = f"后端服务不可用：{exc}"
        status = 502
    return render_template("store_vector_doc.html", mode_labels=MODE_LABELS,
                           mode=mode, doc=doc, chunks=chunks, error=error), status


@app.route("/doc/<doc_id>")
def doc_detail(doc_id):
    """转发后端取 plain 索引原文，返回 HTML 片段（由 main.js 注入结果卡片）"""
    return _doc_fragment(f"{API_BASE}/api/documents/{doc_id}")


@app.route("/deep-doc/<doc_id>")
def deep_doc_detail(doc_id):
    """转发后端取 deep 索引统一文本，返回 HTML 片段（原样展示，保持原始 markdown 格式）"""
    return _doc_fragment(f"{API_BASE}/api/store/deep/{doc_id}")


def _doc_fragment(url):
    try:
        resp = requests.get(url, timeout=15)
    except requests.RequestException as exc:
        return f'<p class="err">后端服务不可用：{exc}</p>', 502
    if not resp.ok:
        return f'<p class="err">{_backend_error(resp, "获取原文失败")}</p>', resp.status_code if resp.status_code < 500 else 502

    doc = resp.json()
    content = doc.get("content") or ""
    truncated = len(content) > MAX_DOC_CHARS
    modified = datetime.fromtimestamp((doc.get("modified") or 0) / 1000).strftime("%Y-%m-%d %H:%M")
    return render_template(
        "doc_detail.html",
        doc=doc,
        content=content[:MAX_DOC_CHARS] if truncated else content,
        truncated=truncated,
        modified=modified,
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=3000)
