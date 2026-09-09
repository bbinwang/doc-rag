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
├── CLAUDE.md               # 本文件（架构宪法）
├── docs/                   # 分域文档：api.md（API 契约唯一权威源）+ 五篇域文档 + adr/ 决策记录 + archive/ 归档（索引见 docs/README.md）
├── backend/                # Java Maven 工程（Spring Boot；包结构见 §4，配置集中 application.yml）
├── frontend/               # Python Flask（app.py 路由 + templates/ + static/）
├── vector-service/         # bge + ChromaDB 语义召回（:8081 可选，main.py 单文件 + models/ 本地模型快照）
├── eval/                   # 问答效果验证（纯工具不参与服务运行）：corpus/ 固定语料 + dataset.json 验证集 + run_eval.py 评测 runner（见 docs/效果验证.md）
└── data/                   # upload/ 上传原文；index-plain/、index-deep/ 倒排与 chroma/ 向量均 gitignore、可删重建
```

## 4. 模块设计（backend + frontend）

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

### ask（LLM 问答，独立页面）
- `LlmClient`：OpenAI 兼容 `POST {base-url}/chat/completions`（java.net.http），非流式；多 provider 配置 `docrag.llm.providers.<name>.*`（base-url / api-key / model / temperature / timeout），`docrag.llm.active`（env `DOCRAG_LLM_ACTIVE`）选出唯一启用 provider——yml 维护 `glm`（智谱，默认启用）与 `mac-uni`（内网，保留不启用）两个，每 provider 参数支持环境变量 `DOCRAG_LLM_<NAME>_*` 覆盖；active 未配置或其 api-key 为空时问答接口返回 400。
- `AskService` 编排（全库检索，不选文档）：三参数钳制（请求覆盖 → 配置默认 `docrag.ask.*` → clamp [1,20]，`effectiveParams` 单点）→ 逐模式独立：`ModeSearcher.recallChunks` 混合召回 → 按 `contextChunks` + `contextCharBudget`（每模式）贪心截断 → 拼编号 prompt（资料头行 `[n] filename`；deep 表格块自带「表格 N」标题行；system：仅依据资料作答、引用标 [n]、资料不足须明说）→ **该模式独立调一次 LLM**（双模式=两次调用）→ 组装 `AskModeResult`。
- **三参数**（`docrag.ask.*`，前端问答页可按次覆盖、响应 `params` 回显生效值）：`bm25-chunks`（倒排路 chunk 上限，默认 5）、`vector-chunks`（向量路 chunk 上限，默认 5）、`context-chunks`（重排后送 LLM 的 chunk 上限，默认 8）；另有 `context-char-budget`（每模式字符预算，默认 6000，不开放请求覆盖）。
- 失败语义：某模式 0 chunk → 占位提示不调 LLM；LLM 失败**按模式隔离**（失败模式 `answer=null + error`，chunks 照常返回），全部模式失败才整体 500；向量不可用 per-mode `degraded=true`。
- `GET /api/ask/params` 暴露三参数默认值（前端问答页初始值，改 yml 即时生效）。响应字段见 `docs/api.md`。

### vector（语义召回 HTTP 客户端）
- `VectorClient`：vector-service 七端点的 per-mode 客户端封装（服务端端点表见 `docs/vector-service.md` §3）。
- 写路径失败上抛（入库由 IngestService 回滚保证各库一致）；读路径失败由 `ModeSearcher` 该模式降级纯 BM25。

### debug（解析诊断）
- `DebugParseService`：**纯解析不入库、不落盘**，返回结构化明细用于定位解析问题；仅 docx/xlsx，PDF「暂未实现」。
- 关键约束：`indexedText` 字段直接调用生产 `DocxParser`/`XlsxParser`，保证对比的就是真实写索引的 content；图片仅计数（不入索引、无 OCR）。

### frontend（Flask 前端，纯展示与转发）
- 页面：`/` 搜索（双模式两栏对比）、`/ask` 独立问答（双栏 + 三参数）、`/debug` 解析对比、`/store/plain|deep` 索引明细、`/store/vector` 向量库明细、`/doc/<docId>` 原文片段（结果卡片惰性加载）；状态条数据源 `GET /status`，卡片点击进对应明细页。
- 只做渲染与转发（`/upload`、`/ask`、`/status`、`/clear` 等转发后端 API），不承担业务逻辑；高亮 snippet `|safe` 直接渲染，deep 的 markdown 表格由 `md-table.js` 渲染为 HTML table（否则回退纯文本）；后端不可达时问答页参数用内置默认回退；转发层后端不可达/5xx 归一为 502，非法 modes 页面级回退 plain（400 语义只在后端 API 层）。

## 5. API 契约

端点清单如下；**字段级契约（请求/响应 JSON、错误码、语义细节）唯一权威源为 [`docs/api.md`](docs/api.md)，改接口先改它**，本文不重复字段表。

| 端点 | 一句话语义 |
|---|---|
| `POST /api/documents` | 上传入库（multipart：`file` + `modes`），任一库失败逆序回滚；响应含 per-mode `chunkCount` 与（选 deep 时）`tableCount` |
| `GET /api/documents/{docId}` | 取 plain 索引原文（不存在 404） |
| `DELETE /api/documents/{docId}` | 级联删除双倒排 + 双 collection（幂等；上传原文件保留） |
| `GET /api/search` | 检索：`q`/`page`/`size`/`modes`，嵌套 `modes` 响应形状（单双模式同构，key 顺序=请求顺序） |
| `POST /api/ask` | 问答：`question`/`modes`/三参数，每模式独立调一次 LLM，`chunks[].ref` ↔ 答案 `[n]` |
| `GET /api/ask/params` | 三参数默认值（前端问答页初始值） |
| `GET /api/status` | 各库状态（双倒排 count、vector stats、上传文件数） |
| `POST /api/admin/clear` | 一键全清（vector 不可用 400 拒绝；含删除全部上传原文件，全量清空语义） |
| `GET /api/store/plain`、`GET /api/store/deep`、`GET /api/store/deep/{docId}` | 倒排明细列表 / deep 统一文本明细（plain 明细复用 `/api/documents/{docId}`） |
| `GET /api/store/vector/{mode}`、`GET /api/store/vector/{mode}/{docId}` | 向量库明细（透传 vector-service） |
| `POST /api/debug/parse` | Debug 解析（不入库、不落盘，仅 docx/xlsx） |

横切约定（详见 docs/api.md §0）：错误统一 `{"error": "..."}`（解析/参数 400、不存在 404、超上传限 413、未预期 500）；`modes` 支持逗号串与 repeated 两种传法（保序去重，空/非法 400）；`snippet` 为后端已 HTML 转义并保留 `<em>` 的片段，前端 `|safe` 直接渲染。

## 6. 构建与运行

- 本机 JDK 为 brew openjdk@17：启动后端前需 `export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"`。
- **相对路径坑**：`application.yml` 的 `../data/*` 按进程 cwd 解析——只有从 `backend/` 启动才正确，从其它目录启动必须显式传绝对路径。
- Python 一律用 `frontend/.venv`。
- 测试：`cd backend && mvn test`（parser/deepmd、双模式 round-trip、IngestService 回滚、AskService LLM 打桩）；`cd frontend && pytest`（页面路由与 API 转发）。
- 启动命令与顺序、端口、配置全量清单、LLM 环境变量、数据目录重建见 `docs/运维与启动.md`（README 快速开始同源，不再重复）。

## 7. 开发约定

- 文档体系：**API 字段契约唯一权威源是 `docs/api.md`（改接口先改它，本文件 §5 与 README 只留清单/链接）**；分域设计文档（解析入库/检索召回/问答/vector-service/运维）与 ADR 决策记录在 `docs/`（索引见 `docs/README.md`）；架构级变更先改本文件。
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
