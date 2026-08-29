"""doc-rag 前端：搜索页面渲染 + 后端 API 转发（上传 / 检索 / 问答 / 原文查看）。仅做展示与转发，不承担业务逻辑。"""
import os
from datetime import datetime

import requests
from flask import Flask, render_template, request

API_BASE = os.environ.get("DOCRAG_API", "http://127.0.0.1:8080")
PAGE_SIZE = 10
MAX_DOC_CHARS = 200_000  # 原文展示截断（完整文本仍在索引中）
ASK_TIMEOUT = 120  # 大于后端 LLM 超时，避免前端先断

app = Flask(__name__)


def _render(q="", page=1, results=None, error=None, uploaded=None, upload_error=None,
            format="full"):
    return render_template(
        "index.html",
        q=q, page=page, size=PAGE_SIZE,
        results=results, error=error,
        uploaded=uploaded, upload_error=upload_error,
        format=format,
    )


def _backend_error(resp, fallback):
    try:
        return resp.json().get("error", f"{fallback}（HTTP {resp.status_code}）")
    except ValueError:
        return f"{fallback}（HTTP {resp.status_code}）"


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
    fmt = request.args.get("format", "full")
    if fmt not in ("full", "table"):
        fmt = "full"

    results = None
    error = None
    if q:
        try:
            resp = requests.get(
                f"{API_BASE}/api/search",
                params={"q": q, "page": page, "size": PAGE_SIZE, "format": fmt},
                timeout=10,
            )
            resp.raise_for_status()
            results = resp.json()
        except requests.RequestException as exc:
            error = f"后端服务不可用：{exc}"

    return _render(q=q, page=page, results=results, error=error, format=fmt)


@app.route("/upload", methods=["POST"])
def upload():
    f = request.files.get("file")
    if f is None or not f.filename:
        return _render(upload_error="未选择文件"), 400

    uploaded = None
    upload_error = None
    try:
        resp = requests.post(
            f"{API_BASE}/api/documents",
            files={"file": (f.filename, f.stream, f.mimetype)},
            timeout=120,
        )
        if resp.ok:
            uploaded = resp.json().get("filename", f.filename)
        else:
            upload_error = _backend_error(resp, "上传失败")
    except requests.RequestException as exc:
        upload_error = f"后端服务不可用：{exc}"

    status = 200 if uploaded else 502
    return _render(uploaded=uploaded, upload_error=upload_error), status


@app.route("/ask", methods=["POST"])
def ask():
    """转发后端问答接口：{question, docIds, format} → {answer, citations}，JSON 进出"""
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


@app.route("/doc/<doc_id>")
def doc_detail(doc_id):
    """转发后端取索引原文，返回 HTML 片段（由 main.js 注入结果卡片）"""
    try:
        resp = requests.get(f"{API_BASE}/api/documents/{doc_id}", timeout=15)
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
