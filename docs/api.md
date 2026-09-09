# API 契约（唯一权威源）

> 本文是 doc-rag 全部 REST 端点的**字段级契约唯一权威源**——`CLAUDE.md` §5 只保留端点清单，`README.md` 只保留链接，改接口**先改本文**。
> 实现：`backend/src/main/java/com/docrag/api/`（DocumentController、SearchController、AskController、StatusController、StoreController、DebugController）。

## 0. 通用约定

- **Base URL**：`http://127.0.0.1:8080`（Java Spring Boot，`server.port`）。
- **请求/响应**均为 JSON（上传与 debug 为 `multipart/form-data`）；字段名大小写敏感。
- **错误统一形状**：非 2xx 一律返回 `{"error": "<信息>"}`，由 `GlobalExceptionHandler` 兜底：

| 状态码 | 触发条件 |
|---|---|
| 400 | 解析失败（损坏/加密/扫描件 PDF）、参数非法（modes 空或非法值、question 空、LLM 未配置、清理时 vector-service 不可达） |
| 404 | docId 不存在（原文/明细/向量库查询） |
| 413 | 上传超过 `max-file-size`（50MB，请求总体 55MB） |
| 500 | IO 及未预期异常；仅当全部选中模式 LLM 都失败时 `/api/ask` 才 500 |

- **`modes` 参数两种传法**（`mode/Modes.java`）：逗号串 `modes=plain,deep` 或 repeated `modes=plain&modes=deep`，保序去重；可选值 `plain` / `deep`，空集合或非法值 → 400。
- **上传落盘命名**：`data/upload/{docId}_{filename}`（filename 已做路径清洗）；解析失败时先删落盘文件再抛错。
- **`snippet` 是 HTML 片段**：原文已 HTML 转义、命中词以 `<em>` 包裹，前端 `|safe` 直接渲染。

---

## 1. documents — 上传与文档

### POST /api/documents — 上传并入库

- **请求**：`multipart/form-data`，字段 `file`（必填，空文件 400）+ `modes`（可选，默认 `plain,deep`）
- **响应**：

```json
{
  "docId": "uuid",
  "filename": "合同.docx",
  "type": "docx",
  "modes": ["plain", "deep"],
  "chunkCount": {"plain": 12, "deep": 5},
  "tableCount": 3
}
```

- 语义：文件落盘 → 按选中模式解析（plain 纯文本 / deep 统一文本）→ per-mode 切块 → 写库（plain 倒排+向量、deep 倒排+向量，只写选中模式）→ 任一失败**逆序回滚**全部已写库后报错。
- `chunkCount` 为各模式向量 chunk 数；`tableCount` 仅选中 deep 时返回（统一文本中 markdown 表格数），未选 deep 时**省略字段**。

### GET /api/documents/{docId} — 取 plain 索引原文

- **响应**：`{docId, filename, path, type, modified, content}`——`content` 即 plain 模式写入倒排的纯文本；`modified` 为毫秒时间戳。不存在 404。
- deep 统一文本走 `GET /api/store/deep/{docId}`。

### DELETE /api/documents/{docId} — 级联删除

- **响应**：`{"deleted": "<docId>"}`
- 语义：先删双 collection 向量、再删两个模式倒排（幂等）；**上传原文件保留**。

---

## 2. search — 检索

### GET /api/search?q=关键词&page=1&size=10&modes=plain,deep

| 参数 | 默认 | 说明 |
|---|---|---|
| `q` | 必填 | 检索词；空白时每模式返回 `{total: 0, hits: [], degraded: false}`（不报错） |
| `page` | 1 | 从 1 起（<1 修正为 1）；对每模式独立生效，双模式两栏共享同一页码 |
| `size` | 10 | 钳制 [1, 50] |
| `modes` | `plain` | 逗号串或 repeated |

- **响应**（统一嵌套形状，单双模式同构，key 顺序 = 请求 modes 顺序）：

```json
{
  "modes": {
    "plain": {
      "total": 12,
      "degraded": false,
      "hits": [
        {"docId": "uuid", "filename": "xx.docx", "path": "data/upload/xx.docx", "type": "docx",
         "snippet": "…其中<em>合同条款</em>约定…", "score": 3.42, "source": "both"}
      ]
    },
    "deep": {"total": 8, "degraded": true, "hits": [ ... ]}
  }
}
```

- `total`：该模式融合去重后的命中文档总数（分页前）；`degraded`：**per-mode** 降级标记（该模式向量服务不可用即 true，另一模式不受影响）。
- `hit.score`：RRF 融合分（非 BM25 原始分，与余弦相似度不可比）；`source`：`both` / `bm25` / `vector`。

---

## 3. ask — 问答

### POST /api/ask — 全库 chunk 级混合检索 → 每模式独立 LLM

- **请求**：

```json
{"question": "试用期最长多久？", "modes": ["plain", "deep"], "bm25Chunks": 5, "vectorChunks": 5, "contextChunks": 8}
```

- `modes` 默认 `["plain"]`；`question` 空 → 400；LLM 未配置（`docrag.llm.active` 空 / 指向不存在的 provider / 其 api-key 空）→ 400。
- 三参数可选（`Integer`，null/缺省 = 配置默认 `docrag.ask.*`：5 / 5 / 8），后端统一钳制 [1, 20]；**每模式独立调一次 LLM**（双模式 = 两次调用）。

- **响应**（嵌套 `modes` 与 `/api/search` 同构，key 顺序 = 请求顺序）：

```json
{
  "model": "gpt-4o-mini",
  "params": {"bm25Chunks": 5, "vectorChunks": 5, "contextChunks": 8},
  "modes": {
    "plain": {
      "answer": "试用期最长不超过六个月[1]。",
      "error": null,
      "degraded": false,
      "chunks": [
        {"ref": 1, "docId": "uuid1", "filename": "xx.docx", "type": "docx",
         "title": null, "source": "both", "score": 0.0328, "text": "试用期…完整块文本"}
      ],
      "docs": [{"docId": "uuid1", "filename": "xx.docx", "type": "docx", "path": "data/upload/xx.docx"}]
    },
    "deep": {"answer": null, "error": "LLM 调用失败: …", "degraded": true, "chunks": [ ... ], "docs": [ ... ]}
  }
}
```

- `params` 回显**实际生效值**（请求覆盖 → 配置默认 → 钳制后）。
- `chunks` = 实际送入该次 LLM 的上下文：`ref` 与答案中 `[n]` 及 prompt 资料编号**一一对应**（禁止返回未送入的 chunk）；`title` 仅 deep 表格块填「表格 N」；`answer` 与 `error` 互斥。
- `docs` = chunks 按 docId 去重（调试用）。
- 失败语义：某模式 0 chunk → 占位提示不调 LLM；LLM 失败**按模式隔离**（该模式 `answer=null + error`，chunks 照常返回，HTTP 200）；仅全部选中模式都失败才 500。

### GET /api/ask/params — 三参数默认值

- **响应**：`{"bm25Chunks": 5, "vectorChunks": 5, "contextChunks": 8}`（读 `docrag.ask.*`，改 yml 即时生效，前端问答页初始值用）。

---

## 4. status / admin — 状态与清理

### GET /api/status — 各库状态

```json
{
  "plainIndex": {"docs": 12},
  "deepIndex":  {"docs": 34},
  "vector":     {"available": true, "model": "bge-small-zh-v1.5", "vectors": {"plain": 1200, "deep": 800}},
  "uploads":    15
}
```

- `vector.available=false` 表示 vector-service 不可达（此时 `model`/`vectors` 为 null）；`uploads` 为 `data/upload/` 常规文件数（不计隐藏文件）。

### POST /api/admin/clear — 一键清理全部库 + 上传原文件

- **响应**：同 `/api/status` 结构，外加 `"cleared": ["vector", "plain", "deep", "uploads"]`。
- 语义：先探测 vector-service（**不可用直接 400 拒绝**，防「清了倒排残留向量」的幽灵命中）→ 清双 collection → `clearAll()` 双倒排（走存活单例 `IndexWriter.deleteAll()`）→ 删除 `data/upload/` 全部常规文件（目录保留，删除失败上抛）。**全量清空语义**：系统回到零状态。

---

## 5. store — 索引/向量库明细

### GET /api/store/plain、GET /api/store/deep — 倒排文档列表

- **响应**：`{"total": n, "docs": [{"docId", "filename", "path", "type", "modified"}]}`
- 按 `modified` 倒序（同毫秒按 docId 字典序）；不含 content，明细走下一端点。

### GET /api/store/deep/{docId} — deep 统一文本明细

- **响应**：`{docId, filename, path, type, modified, content}`——content 为统一文本（正文 + markdown 表格按原文顺序）；不存在 404。plain 明细复用 `GET /api/documents/{docId}`。

### GET /api/store/vector/{mode} — 向量库文档列表（透传 vector-service）

- **响应**：`{"mode": "plain", "total": n, "chunkTotal": m, "docs": [{"docId", "filename", "type", "chunkCount"}]}`（docId 字典序）
- vector-service 不可用 → **500 + error**（明细页不降级）。

### GET /api/store/vector/{mode}/{docId} — 向量库 chunk 明细

- **响应**：`{docId, filename, type, chunks: [{"chunkIndex", "text"}]}`（chunkIndex 升序 = 入库顺序）；向量库中不存在 404。

---

## 6. debug — 解析诊断

### POST /api/debug/parse — Debug 解析（不入库、不落盘）

- **请求**：`multipart/form-data`，字段 `file`（docx/xlsx；PDF 暂不支持）
- **响应**：

```json
{
  "filename": "员工表.docx", "type": "docx",
  "imageCount": 2, "tableCount": 1, "tablesTruncated": false, "textTruncated": false,
  "indexedText": "拍平的索引文本……",
  "tables": [{"title": "表格 1", "rows": [["姓名", "年龄"], ["张三", "25"]]}]
}
```

- `indexedText` 直接调用生产 `DocxParser`/`XlsxParser`，保证对比的就是真实写索引的 content；表格行数截断 100 行、索引文本截断 50k 字符（`tablesTruncated` / `textTruncated` 标记）；图片仅计数（不入索引、无 OCR）。
