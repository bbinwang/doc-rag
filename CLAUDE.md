# doc-rag 项目架构定义

> 本文档是项目的架构宪法，所有代码实现必须遵循此文档。如实现与本文冲突，以本文为准；如需变更架构，先修改本文。

## 1. 项目概述

**doc-rag** 是一个本地文档检索与问答系统：Java + Lucene 后端负责 docx / xlsx / pdf 的解析与**双模式索引入库**，检索采用 BM25（可选融合 vector-service 语义召回）；问答为独立页面，全库 chunk 级混合检索（BM25 + 向量 RRF）后**每模式独立调用 OpenAI 兼容 LLM** 生成带引用编号的答案与召回明细；Python Flask Web 前端提供搜索 + 独立问答 + 索引明细查看页面，命中片段高亮展示。

**双解析模式**（系统核心概念，`mode=plain|deep`）：

- **plain（纯文本解析模式）**：Parser 提取纯文本 → plain 倒排索引 + 向量 collection `docrag_plain`（chunk = `Chunker.chunk`）。
- **deep（深度解析模式）**：deepmd 提取统一文本（正文与 markdown 表格按原文顺序拼接）→ deep 倒排索引 + 向量 collection `docrag_deep`（chunk = `Chunker.chunkKeepingTables` 块感知切块，表格含标题行整体保留）。

**两模式结构完全对称**：各自一套 Lucene 倒排（`data/index-plain/`、`data/index-deep/`）+ 各自一个向量 collection，检索与问答均按模式选择（可选 1-2 个，双模式时检索结果分模式返回、前端两栏对比；问答每模式独立调 LLM、两栏各出一份答案）。

**定位边界**：关键词检索（Lucene BM25）为主体，语义召回依赖可选的 vector-service（bge + ChromaDB，不可用时该模式自动降级纯 BM25）；LLM 问答只调 OpenAI 兼容 `chat/completions` 接口，本地不跑模型、不做 embedding 之外的特征工程；不支持扫描件 PDF（无 OCR）；deep 模式的统一文本依赖文档内建表格结构（docx/xlsx），PDF 无结构化表格，deep 模式下 PDF 仅正文纯文本（与 plain 同文）。

### 架构总览

```
浏览器 ── HTTP ── Python Flask 前端 (:3000) ── REST/JSON ── Java Spring Boot 服务 (:8080)
                                                                        │
                                     ┌──────────────┬──────────────┬────┴─────────┬──────────────┐
                                  Parser 模块     deepmd 模块    Indexer 模块  Searcher/Ask 模块
                                  POI/PDFBox     表格→markdown   Lucene 写×2    Lucene 读×2+高亮 / LLM 编排
                                  (plain)        (deep 统一文本)  (per-mode)    (per-mode)
                                                                        │                │
                                                     ┌──────────────────┤                │
                                              data/index-plain/  data/index-deep/        │
                                              （plain 倒排）       （deep 倒排）            │
                                                                        │                │
                            vector-service (:8081，可选) ────────────────┴────────────────┘
                            bge + ChromaDB，双 collection：docrag_plain / docrag_deep
                                                                    OpenAI 兼容 LLM (/chat/completions)
```

## 2. 技术栈选型

| 层 | 选型 | 说明 |
|---|---|---|
| 后端语言/构建 | Java 17、Maven、Spring Boot 3 | REST 服务 |
| 检索引擎 | Apache Lucene 8.11.x | BM25 打分，本地文件索引；锁定 8.x 线（IK Analyzer 8.5.0 的兼容版本） |
| 中文分词 | IK Analyzer 8.5.0（magese 版，`SmartChineseAnalyzer` 备选） | 中文按词切分，英文按空格 |
| docx / xlsx 解析 | Apache POI（XWPFDocument / XSSFWorkbook） | |
| pdf 解析 | Apache PDFBox（PDFTextStripper） | 仅文本型 PDF |
| 前端 | Python 3.10+、Flask、requests、Jinja2、原生 CSS/JS | 服务端渲染，无前端框架 |
| 向量召回（可选） | vector-service（Python, bge + ChromaDB，:8081） | per-mode collection（`docrag_plain`/`docrag_deep`）：plain=纯文本 chunk、deep=统一文本块感知切块；与 BM25 做 RRF 融合；不可用该模式自动降级 |
| LLM 问答 | OpenAI 兼容 `/chat/completions`（java.net.http 直调） | 检索 chunk 拼 prompt 生成答案 + 引用；base-url / api-key / model 走配置 |

## 3. 目录结构

```
doc-rag/
├── CLAUDE.md
├── backend/                  # Java Maven 工程（Spring Boot）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/docrag/
│       │   ├── DocRagApplication.java
│       │   ├── mode/         # Mode 枚举（plain|deep）+ Modes 解析工具（改名的锚点，独立包避免循环依赖）
│       │   ├── api/          # REST Controller（DocumentController, SearchController, DebugController, AskController, StatusController, StoreController）
│       │   ├── parser/       # DocumentParser 接口 + Docx/Xlsx/Pdf 三个实现 + ParserRouter（plain 模式纯文本）
│       │   ├── deepmd/       # deep 模式统一文本：DeepmdExtractor + Docx/Xlsx/Pdf 实现 + Router（表格→markdown + 统一文本拼接）
│       │   ├── indexer/      # ModeIndexer（per-mode 倒排）+ IngestService（4 库写入编排+回滚）+ Chunker（切块）
│       │   ├── searcher/     # ModeSearcher（per-mode 混合检索：BM25+向量 RRF + 高亮，按 docId 聚合）
│       │   ├── ask/          # 问答：LlmClient（OpenAI 兼容）+ AskService（参数钳制→per-mode 召回截断→prompt→LLM→答案+召回明细）
│       │   ├── vector/       # vector-service HTTP 客户端（per-mode）
│       │   ├── debug/        # Debug 解析服务（表格结构化 + 图片计数，不入库）
│       │   └── config/       # 路径/LLM 配置项 + Lucene 双模式索引生命周期（bean 装配中心）
│       ├── main/resources/application.yml
│       └── test/java/com/docrag/   # JUnit5 测试
├── frontend/                 # Python Flask 应用
│   ├── app.py                # 路由：页面渲染 + 转发后端 API（含 /ask 问答页+转发、/store 明细转发）
│   ├── templates/index.html  # 搜索页（双模式分栏对比）
│   ├── templates/ask.html    # 独立问答页（双模式两栏 + 三参数输入）
│   ├── templates/store_list.html / store_doc.html  # 索引明细列表页 / 明细页（deep 双栏：原文 | 渲染）
│   ├── templates/store_vector.html / store_vector_doc.html  # 向量库明细列表页 / chunk 明细页
│   ├── static/               # CSS/JS（md-table.js 表格渲染、main.js 搜索页、ask.js 问答页）
│   └── requirements.txt
├── vector-service/           # Python bge + ChromaDB 语义召回服务（:8081，可选，双 collection）
└── data/
    ├── upload/               # 上传原文存放
    ├── index-plain/          # Lucene plain 倒排目录（gitignore，可随时删除重建）
    ├── index-deep/           # Lucene deep 倒排目录（gitignore，可随时删除重建）
    └── chroma/               # ChromaDB 持久化（vector-service 管理，gitignore）
```

## 4. 后端模块设计

### mode（模式标识）
- `Mode` 枚举：`PLAIN("plain", "纯文本解析")`、`DEEP("deep", "深度解析")`；`id` 用于 API/配置/目录/URL，`label` 用于前端与提示文案；`Mode.of(id)` 未知值抛异常。
- `Modes` 静态工具：`parse("plain,deep")`（逗号串，保序去重）与 `parseList(List<String>)`（repeated 参数），空集合或非法值抛异常 → API 层转 400。

### parser（plain 模式文档解析）
- 接口：`DocumentParser`，两个方法：`boolean supports(String ext)`、`String parse(InputStream in)`（返回提取的纯文本）。
- 实现：`DocxParser`（XWPFWordExtractor）、`XlsxParser`（逐 Sheet 逐行逐单元格拼接，单元格间制表符、行间换行）、`PdfParser`（PDFTextStripper）。
- `ParserRouter` 按文件扩展名路由到对应实现；不支持的扩展名抛出受检异常，API 层转为 400。

### deepmd（deep 模式统一文本：表格→markdown + 统一文本拼接）
- 接口：`DeepmdExtractor`，`boolean supports(String ext)`、`DeepDocument extract(InputStream in)`；`DeepmdRouter` 按扩展名路由，不支持抛受检异常。
- `DeepDocument`：`content`（统一文本）+ `tableCount`（其中 markdown 表格数：docx=表格数 / xlsx=非空 sheet 数 / pdf=0，仅上传响应展示，不入 schema）。
- **统一文本拼接约定**（deepmd 与 `Chunker.chunkKeepingTables` 的共同契约）：块之间一个空行分隔；表格标题行与表格紧连（无空行，保证块感知切块时「标题+表格」整体保留）；markdown 单元格内不含换行（`|`→`\|`、换行→空格），表格内部不可能出现空行，空行是可靠块边界。
- `DocxDeepmdExtractor`：按 body 元素顺序遍历——段落逐行累积，表格先补空行分隔、`表格 N` 标题行（按 body 顺序从 1 递增）+ markdown 表格（首行作表头）、表格后补空行；
- `XlsxDeepmdExtractor`：每非空 sheet = sheet 名标题行 + 完整 markdown 表格（首行作表头，**不分片**），sheet 间空行；
- `PdfDeepmdExtractor`：整体纯文本（PDF 无表格结构，不做启发式猜测），`tableCount=0`（deep 模式下 PDF 统一文本与 plain 纯文本相同）。

### indexer（索引入库，双模式目录）
- **双索引目录**：`data/index-plain/` 与 `data/index-deep/`，各自一个进程内单例 `IndexWriter` + `SearcherManager`（`LuceneConfig` 以 qualifier 区分），可独立删除重建。
- `ModeIndexer`：plain 与 deep 共用的统一倒排写入类（两模式 schema 完全同构，仅 content 来源不同：plain=Parser 纯文本、deep=统一文本），`index(docId, filename, path, type, String content)` 一文一 Document，`id` 直接存 docId 作级联删除键，upsert 按 `id`。提供 `count()` 与 `clearAll()`（`deleteAll` + commit + refresh）。
- `IngestService`：入库编排（从 Controller 抽出）——按选中模式解析（fail-fast，全部解析成功才写库）→ per-mode 切块（plain=`Chunker.chunk`；deep=`Chunker.chunkKeepingTables(content, DEEP_EMBED_MAX_CHARS=256)`）→ 写库顺序 plain 倒排 → deep 倒排 → plain 向量 → deep 向量，`List<Runnable> rollbacks` 逆序回滚（回滚失败 log.warn 不吞原始异常）。
- 索引 schema（plain 与 deep 完全同构，一文一 Document，`id`=docId 为级联删除键）：

| 字段 | 类型 | 是否存储 | 说明 |
|---|---|---|---|
| `id` | StringField | 是 | 即 docId（UUID） |
| `filename` | TextField(IK) | 是 | 文件名，参与检索 |
| `path` | StringField | 是 | 存储路径 |
| `type` | StringField | 是 | docx / xlsx / pdf |
| `modified` | StoredField | 是 | 上传时间戳 |
| `content` | TextField(IK) | 是 | plain=纯文本 / deep=统一文本，存储以供高亮与明细展示 |

### searcher（检索，per-mode 混合）
- `ModeSearcher`：plain 与 deep 共用（原 DocumentSearcher/TableSearcher 合并；deep 也走混合检索）——两条检索路径：
  - `search()`（检索页）：BM25（`MultiFieldQueryParser` 查 `filename`+`content`）与 vector-service 语义召回（`query(mode, q, topK)` 查对应 collection）两路，**docId 级** RRF 融合（RRF_POOL=50、RRF_K=60），向量不可用**该模式**降级纯 BM25（per-mode `degraded=true`）；`Highlighter`+`SimpleFragmenter` 截取最佳片段，命中词 `<em>` 包裹；
  - `recallChunks(q, bm25Chunks, vectorChunks)`（问答）：**chunk 级**混合召回（全库，不限 docIds）——BM25 路全库 doc 召回 pool=clamp(bm25Chunks,5,20) 篇 → 按**与入库一致的切块策略**查询时切块（plain=`Chunker.chunk`、deep=`chunkKeepingTables(256)`，保证与向量库 chunk 逐字节同源）→ 与问题分词重叠度排序取前 bm25Chunks 个；向量路 `query(mode, q, vectorChunks)`；两路按 `(docId, chunk 精确文本)` 去重后 chunk 级 RRF（k=60）融合，仅向量命中的文档回读索引补 path；返回 `ChunkRecall(chunks, degraded)`。
  - 共用：`getById(docId)` 取该模式入库文本；`listAll()` 供明细页。
- **检索期切块=入库期切块**是 chunk 级去重成立的前提（`IngestService` 与 `ModeSearcher.chunkForMode` 同一套 `Chunker` 调用），改动切块策略需两侧同步。
- `source` 枚举：`both` / `bm25` / `vector`（两模式同义，不再有独立 `table` 值）。
- **IK 双分词器策略**：索引侧细粒度（`IKAnalyzer(false)`，多切词保证召回），查询侧智能（`IKAnalyzer(true)`）。高亮时用索引侧分词器重切文本对齐 offset。双模式共用同一对 analyzer 单例 bean。
- snippet 返回 HTML 片段，前端直接渲染（后端对原文做 HTML 转义后保留 `<em>`）。

### ask（LLM 问答，独立页面）
- `LlmClient`：OpenAI 兼容 `POST {base-url}/chat/completions`（java.net.http），非流式；配置 `docrag.llm.*`（base-url / api-key / model / temperature / timeout），api-key 支持环境变量 `DOCRAG_LLM_API_KEY`；未配置时问答接口返回 400。
- `AskService` 编排（全库检索，不选文档）：三参数钳制（请求覆盖 → 配置默认 `docrag.ask.*` → clamp [1,20]，`effectiveParams` 单点）→ 逐模式独立：`ModeSearcher.recallChunks` 混合召回 → 按 `contextChunks` + `contextCharBudget`（每模式）贪心截断 → 拼编号 prompt（资料头行 `[n] filename`；deep 表格块自带「表格 N」标题行；system：仅依据资料作答、引用标 [n]、资料不足须明说）→ **该模式独立调一次 LLM**（双模式=两次调用）→ 组装 `AskModeResult`。
- **三参数**（`docrag.ask.*`，前端问答页可按次覆盖、响应 `params` 回显生效值）：`bm25-chunks`（倒排路 chunk 上限，默认 5）、`vector-chunks`（向量路 chunk 上限，默认 5）、`context-chunks`（重排后送 LLM 的 chunk 上限，默认 8）；另有 `context-char-budget`（每模式字符预算，默认 6000，不开放请求覆盖）。
- 响应：`{model, params, modes: {plain: {answer, error, degraded, chunks[], docs[]}, deep: {...}}}`——`chunks`=实际送入 LLM 的上下文（ref 与答案 [n] 一一对应，含 source/score/title/text 调试字段）；`docs`=chunks 按 docId 去重。
- 失败语义：某模式 0 chunk → 占位提示不调 LLM；LLM 失败**按模式隔离**（失败模式 `answer=null + error`，chunks 照常返回），全部模式失败才整体 500；向量不可用 per-mode `degraded=true`。
- `GET /api/ask/params` 暴露三参数默认值（前端问答页初始值，改 yml 即时生效）。
- snippet 返回 HTML 片段，前端直接渲染（信任后端输出，后端对原文做 HTML 转义后保留 `<em>`）。

### vector（语义召回 HTTP 客户端）
- `VectorClient` 方法面（均带 mode）：`ping()`（可用性）、`stats()`（GET `/health` → `{model, vectors:{plain, deep}}`）、`upsert(mode, docId, filename, type, chunks)`、`query(mode, text, topK)`、`delete(docId, mode)`（mode=all 双删，回滚与级联删除用）、`clearAll(mode)`（mode=all 全清）、`listDocs(mode)`（向量库文档列表，明细页用）、`getDoc(mode, docId)`（单文档 chunk 列表，chunkIndex 升序，不存在返回 null）。
- 写路径失败上抛（入库由 IngestService 回滚保证各库一致）；读路径失败由 `ModeSearcher` 该模式降级纯 BM25。

### debug（解析诊断）
- `DebugParseService`：**纯解析不入库、不落盘**，返回结构化明细用于定位解析问题。
- 关键约束：`indexedText` 字段直接调用生产 `DocxParser`/`XlsxParser`，保证对比的就是真实写索引的 content。
- 明细内容：表格以行列结构返回（docx = `表格 N`，xlsx = sheet 名）；图片**仅计数**（不入索引、无 OCR，页面给出警告）；表格行数截断 100 行、索引文本截断 50k 字符（带 truncated 标志）。
- 第一期支持 docx/xlsx；PDF 返回「暂未实现」。

### api（REST 层）
Spring MVC Controller，统一 JSON 返回；解析/参数错误返回 4xx + `{error: "..."}`。
`StatusController` 提供库状态查询（`GET /api/status`）与一键清理（`POST /api/admin/clear`）：状态聚合两个模式索引的 `count()`、vector-service per-mode `stats()`、上传目录文件数；清理先探测 vector-service 可用性（不可用直接 400 拒绝，避免清了倒排却残留向量造成幽灵命中），再依次清向量库（双 collection）→ 两个模式索引 → 删除 `data/upload/` 全部原文件（**全量清空语义**：系统回到零状态，目录本身保留），成功后返回最新状态。
`StoreController` 提供索引明细：`GET /api/store/plain`、`GET /api/store/deep`（轻量文档列表：docId/filename/path/type/modified，modified 倒序）与 `GET /api/store/deep/{docId}`（统一文本明细，不存在 404）；plain 明细复用 `GET /api/documents/{docId}`。另提供向量库明细（透传 vector-service）：`GET /api/store/vector/{mode}`（该模式 collection 的文档列表，含 chunkCount/chunkTotal）与 `GET /api/store/vector/{mode}/{docId}`（单文档全部 chunk，chunkIndex 升序，不存在 404）。

## 5. API 契约

前后端共同遵守，字段名以下述为准：

### POST /api/documents — 上传并入库
- 请求：`multipart/form-data`，字段 `file` + `modes`（逗号串或 repeated，可选值 `plain,deep`，默认 `plain,deep`）
- 响应：`{"docId": "uuid", "filename": "xx.docx", "type": "docx", "modes": ["plain","deep"], "chunkCount": {"plain": 12, "deep": 5}, "tableCount": 3}`
- 行为：文件落盘 `data/upload/` → 按选中模式解析（plain 纯文本 / deep 统一文本）→ per-mode 切块 → 写库（plain 倒排+plain 向量、deep 倒排+deep 向量，只写选中模式），任一失败回滚全部已写库并报错；`chunkCount` 为各模式向量 chunk 数；`tableCount` 仅 deep 选中时返回（统一文本中 markdown 表格数），未选 deep 时省略该字段

### GET /api/search?q=关键词&page=1&size=10&modes=plain,deep — 检索
- `modes` 默认 `plain`；每模式独立混合检索（BM25 + 该模式向量 collection RRF 融合，向量不可用该模式 `degraded=true`）；响应**统一嵌套形状**（单双模式同构），key 顺序=请求顺序：

```json
{
  "modes": {
    "plain": {
      "total": 12, "degraded": false,
      "hits": [
        {"docId": "uuid", "filename": "xx.docx", "path": "data/upload/xx.docx", "type": "docx",
         "snippet": "…其中<em>合同条款</em>约定…", "score": 3.42, "source": "both"}
      ]
    },
    "deep": {"total": 8, "degraded": true, "hits": [ ... ]}
  }
}
```

- `source`：`both` / `bm25` / `vector`；snippet 中命中词用 `<em>` 包裹；`page` 从 1 开始，对每模式独立生效（双模式共享 `page`，各栏显示各自 total）

### POST /api/ask — 文档问答（全库 chunk 级混合检索 → 每模式独立 LLM → 答案 + 召回明细）
- 请求：`{"question": "试用期最长多久？", "modes": ["plain", "deep"], "bm25Chunks": 5, "vectorChunks": 5, "contextChunks": 8}`
- `modes` 默认 `["plain"]`；三个检索参数可选（缺省=配置默认 `docrag.ask.*`，后端钳制 [1,20]）；**每模式独立调用一次 LLM**（双模式=两次）
- 响应（嵌套 `modes` 与 `/api/search` 同构，key 顺序=请求顺序）：
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
- `chunks` = 实际送入该次 LLM 的上下文，`ref` 与答案中 `[n]` 及 prompt 资料编号一一对应；`title` 仅 deep 表格块填「表格 N」；`docs` = chunks 按 docId 去重（调试用）
- LLM 未配置返回 400；某模式无 chunk 时该模式返回「无法作答」类提示、不调 LLM，另一模式照常；LLM 失败按模式隔离（`answer=null + error`），全部模式失败才 500

### GET /api/ask/params — 问答三参数默认值
- 响应：`{"bm25Chunks": 5, "vectorChunks": 5, "contextChunks": 8}`（读 `docrag.ask.*`，前端问答页初始值用）

### GET /api/documents/{docId} — 取 plain 索引原文
- 响应：`{docId, filename, path, type, modified, content}`，content 即 plain 模式写入索引的纯文本；不存在返回 404
- 前端 `/doc/<docId>` 转发此接口（HTML 片段），plain 栏「查看原文」按钮惰性加载，展示层截断 200k 字符

### DELETE /api/documents/{docId} — 删除
- 行为：级联删除两模式倒排与两向量 collection 中该 docId 的全部数据（幂等；上传原文保留）

### POST /api/debug/parse — Debug 解析（不入库）
- 请求：`multipart/form-data`，字段 `file`（docx/xlsx）
- 响应：`{filename, type, imageCount, tableCount, tablesTruncated, textTruncated, indexedText, tables: [{title, rows: [[...]]}]}`

### GET /api/status — 各库状态（前端状态条）
- 响应：
```json
{
  "plainIndex": {"docs": 12},
  "deepIndex":  {"docs": 34},
  "vector":     {"available": true, "model": "bge-small-zh-v1.5", "vectors": {"plain": 1200, "deep": 800}},
  "uploads":    15
}
```
- `vector.available=false` 表示 vector-service 不可达（此时 `vectors`/`model` 为 null）；`uploads` 为 `data/upload/` 文件数

### POST /api/admin/clear — 一键清理全部索引库 + 向量库
- 行为：先探测 vector-service（不可用返回 400，不做部分清理）→ 清空 vector-service 双 collection → `clearAll()` 两个模式索引 → 删除 `data/upload/` 全部原文件（目录保留）→ 返回清理后状态（同 `/api/status` 结构，外加 `cleared: ["vector", "plain", "deep", "uploads"]`）
- 全量清空语义：各库与上传原文件一并清除，系统回到零状态，之后需重新上传文档

### GET /api/store/plain、GET /api/store/deep — 索引明细列表
- 响应：`{"total": n, "docs": [{"docId", "filename", "path", "type", "modified"}]}`
- 按 `modified` 倒序（同毫秒按 docId 字典序）；不含 content，明细走下方端点

### GET /api/store/deep/{docId} — deep 索引统一文本明细
- 响应：`{docId, filename, path, type, modified, content}`，content 为统一文本（正文 + markdown 表格按原文顺序拼接）；不存在返回 404
- plain 明细复用 `GET /api/documents/{docId}`

### GET /api/store/vector/{mode}、GET /api/store/vector/{mode}/{docId} — 向量库明细（透传 vector-service）
- 列表响应：`{"mode": "plain", "total": n, "chunkTotal": m, "docs": [{"docId", "filename", "type", "chunkCount"}]}`（docId 字典序；vector-service 不可用返回 500 + error）
- 明细响应：`{docId, filename, type, chunks: [{"chunkIndex", "text"}]}`（chunkIndex 升序 = 入库顺序）；向量库中不存在返回 404
- vector-service 侧对应端点：`GET /documents?mode=`、`GET /documents/{docId}?mode=`

## 6. 数据流

- **入库**：上传（选 1-2 个模式）→ 存 `data/upload/` → plain：Parser 提取纯文本；deep：deepmd 提取统一文本 → per-mode 切块（plain=`Chunker.chunk`；deep=`chunkKeepingTables`）→ 写库：plain 倒排（`data/index-plain/`）+ `docrag_plain` 向量、deep 倒排（`data/index-deep/`）+ `docrag_deep` 向量；任一失败按已写集合逆序回滚
- **检索**：query + modes → 各模式独立：BM25 与该模式向量两路召回 → RRF 融合 → Highlighter 截取片段 → 嵌套 JSON 返回 → Flask 按模式渲染（双模式两栏对比，`<em>` 高亮 `|safe` 输出，deep 栏 markdown 表格由前端 JS 渲染为 HTML table，否则回退纯文本）
- **问答**：独立问答页（Flask `/ask`）问题 + modes + 三参数 → `POST /api/ask` → 每模式独立：chunk 级混合召回（BM25 路 doc 召回→入库同款切块→overlap 排序 + 向量路，chunk 级 RRF 融合）→ 按 contextChunks/字符预算截断 → 编号 prompt → 该模式独立调 LLM → 答案 + chunks（ref 对应 [n]）+ 去重文档 → 前端两栏渲染（chunks 列表 + deep 表格块渲染）
- **状态/清理**：页面加载时 JS `GET /status` 渲染状态条 → 点「一键清理」confirm 确认（明示将删除全部上传原文件、不可恢复）→ `POST /clear` → 后端拒绝或全量清空（双 collection + 双索引 + upload 原文件）→ 前端用返回的最新状态刷新状态条
- **索引明细**：状态条「纯文本解析」「深度解析」卡片可点击 → 独立明细页（Flask `/store/plain`、`/store/deep` 转发 `/api/store/*`）列全部文档 → 点条目看入库文本（deep 明细页 markdown 表格由 JS 渲染为 HTML table，双栏：markdown 原文 | 渲染效果）
- **向量库明细**：状态条「向量库」卡片可点击 → Flask `/store/vector` 转发 `/api/store/vector/*`，双向量 collection（`docrag_plain` / `docrag_deep`）各一节列全部文档（含 chunk 数）→ 点条目看该文档全部 chunk（chunkIndex 升序）

## 7. 构建与运行

```bash
# 后端（默认 :8080）
# 本机 JDK 为 brew 安装的 openjdk@17，需先设置：
# export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
# 注意：相对路径以进程 cwd 解析，从非 backend/ 目录启动必须显式传绝对路径
cd backend && mvn spring-boot:run

# 前端（默认 :3000，监听 0.0.0.0 可局域网访问；Python 一律用 frontend/.venv）
cd frontend && pip install -r requirements.txt && flask run --host 0.0.0.0 --port 3000

# vector-service（可选，:8081；不启动则各模式检索自动降级纯 BM25）
cd vector-service && pip install -r requirements.txt && python main.py

# LLM 问答配置（OpenAI 兼容；不配置则 /api/ask 返回 400 提示）
# export DOCRAG_LLM_BASE_URL="https://api.openai.com/v1"
# export DOCRAG_LLM_API_KEY="sk-..."
# export DOCRAG_LLM_MODEL="gpt-4o-mini"

# 测试
cd backend && mvn test        # JUnit5：parser/deepmd 提取正确性、双模式索引入库-检索 round-trip（含融合路径）、IngestService 回滚、AskService（LLM 打桩）
cd frontend && pytest         # 页面路由、API 转发（含 /ask、modes）
```

## 8. 开发约定

- 后端包命名 `com.docrag.*`，类职责与本文模块划分一一对应，不跨层调用（api → mode/parser/deepmd/indexer/searcher/ask/vector，不反向依赖）。
- 索引目录、上传目录、端口、LLM/问答参数配置集中在 `application.yml`，禁止硬编码；密钥走环境变量。
- `IndexWriter` / `SearcherManager` 的生命周期由 `config/` 统一管理；**每个模式索引目录一个进程内单例 `IndexWriter`**（plain `data/index-plain/`、deep `data/index-deep/`，qualifier 区分；qualifier 只允许出现在 `LuceneConfig` 一个文件内，`ModeIndexer`/`ModeSearcher` 走 @Bean 工厂产出），写后 commit + `maybeRefreshBlocking` 近实时可见。
- 解析异常（损坏文件、加密文件、扫描件 PDF）不得 500，统一 400 + 错误信息；上传各库写入任一失败必须回滚全部已写库（IngestService 逆序回滚）。
- 前端仅做展示与请求转发，不承担业务逻辑；问答编排全在后端；高亮样式集中在 `static/` 的 CSS。
- 问答答案中的引用必须能映射回真实送入 LLM 的上下文（`chunks[].ref` 编号与答案 `[n]` 一一对应），禁止返回未送入的 chunk。
- 问答 LLM 失败按模式隔离（失败模式 `answer=null + error`、chunks 照常返回供调试），仅全部选中模式都失败才整体 500；不降级编造答案。
- 一键清理必须各库一致：vector-service 不可用时直接 400 拒绝清理（只清倒排会残留向量幽灵命中）；清理用 `IndexWriter.deleteAll()` 走存活的单例 writer，禁止绕过 writer 直接删索引目录文件；清理为全量清空语义——双 collection + 双索引之后同步删除 `data/upload/` 全部原文件（目录保留，删除失败上抛报错不静默）。
- 统一文本的块边界约定（空行分隔、表格标题行紧贴表格）是 deepmd 与 `Chunker.chunkKeepingTables` 的共同契约，改动需两侧同步。
- `data/index-plain/`、`data/index-deep/`、`data/chroma/` 与 `data/upload/` 加入 `.gitignore`。
