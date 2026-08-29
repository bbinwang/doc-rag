# doc-rag 项目架构定义

> 本文档是项目的架构宪法，所有代码实现必须遵循此文档。如实现与本文冲突，以本文为准；如需变更架构，先修改本文。

## 1. 项目概述

**doc-rag** 是一个本地文档检索与问答系统：Java + Lucene 后端负责 docx / xlsx / pdf 的解析、**双格式索引入库**（全文索引 + 表格 markdown 索引）与 BM25 检索（可选融合 vector-service 语义召回）；检索命中的 chunk/表格片段可送 **OpenAI 兼容 LLM** 生成带引用文档列表的问答；Python Flask Web 前端提供搜索 + 内嵌问答页面，命中片段高亮展示。

**定位边界**：关键词检索（Lucene BM25）为主体，语义召回依赖可选的 vector-service（bge + ChromaDB，不可用时自动降级纯 BM25）；LLM 问答只调 OpenAI 兼容 `chat/completions` 接口，本地不跑模型、不做 embedding 之外的特征工程；不支持扫描件 PDF（无 OCR）；表格 markdown 提取依赖文档内建表格结构（docx/xlsx），PDF 无结构化表格，仅正文片段入表格格式索引。

### 架构总览

```
浏览器 ── HTTP ── Python Flask 前端 (:3000) ── REST/JSON ── Java Spring Boot 服务 (:8080)
                                                                        │
                                     ┌──────────────┬──────────────┬────┴─────────┬──────────────┐
                                  Parser 模块     tablemd 模块   Indexer 模块  Searcher/Ask 模块
                                  POI/PDFBox     表格→markdown   Lucene 写×2    Lucene 读×2+高亮 / LLM 编排
                                                                        │                │
                                                     ┌──────────────────┤                │
                                              data/index/         data/index-table/   OpenAI 兼容 LLM
                                              （全文索引）          （表格 markdown 索引）  (/chat/completions)
                                                                        │
                                                              vector-service (:8081，可选)
                                                              bge + ChromaDB 语义召回
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
| 向量召回（可选） | vector-service（Python, bge + ChromaDB，:8081） | 全文 chunk 语义召回，与 BM25 做 RRF 融合；不可用自动降级 |
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
│       │   ├── api/          # REST Controller（DocumentController, SearchController, DebugController, AskController）
│       │   ├── parser/       # DocumentParser 接口 + Docx/Xlsx/Pdf 三个实现 + ParserRouter（全文纯文本）
│       │   ├── tablemd/      # 表格 markdown 提取：TablemdExtractor + Docx/Xlsx/Pdf 实现 + Router（第二索引格式的片段来源）
│       │   ├── indexer/      # DocumentIndexer（全文索引）+ TableIndexer（表格索引）+ Chunker（切块）
│       │   ├── searcher/     # DocumentSearcher（混合检索）+ TableSearcher（表格索引检索，按 docId 聚合）
│       │   ├── ask/          # 问答：LlmClient（OpenAI 兼容）+ AskService（检索→prompt→LLM→引用）
│       │   ├── vector/       # vector-service HTTP 客户端
│       │   ├── debug/        # Debug 解析服务（表格结构化 + 图片计数，不入库）
│       │   └── config/       # 路径/LLM 配置项 + Lucene 双索引生命周期
│       ├── main/resources/application.yml
│       └── test/java/com/docrag/   # JUnit5 测试
├── frontend/                 # Python Flask 应用
│   ├── app.py                # 路由：页面渲染 + 转发后端 API（含 /ask 转发）
│   ├── templates/index.html  # 搜索 + 内嵌问答页
│   ├── static/               # CSS/JS
│   └── requirements.txt
├── vector-service/           # Python bge + ChromaDB 语义召回服务（:8081，可选）
└── data/
    ├── upload/               # 上传原文存放
    ├── index/                # Lucene 全文索引目录（gitignore，可随时删除重建）
    └── index-table/          # Lucene 表格 markdown 索引目录（gitignore，可随时删除重建）
```

## 4. 后端模块设计

### parser（文档解析）
- 接口：`DocumentParser`，两个方法：`boolean supports(String ext)`、`String parse(InputStream in)`（返回提取的纯文本）。
- 实现：`DocxParser`（XWPFWordExtractor）、`XlsxParser`（逐 Sheet 逐行逐单元格拼接，单元格间制表符、行间换行）、`PdfParser`（PDFTextStripper）。
- `ParserRouter` 按文件扩展名路由到对应实现；不支持的扩展名抛出受检异常，API 层转为 400。

### tablemd（表格 markdown 提取，第二索引格式的片段来源）
- 接口：`TablemdExtractor`，`boolean supports(String ext)`、`List<TableFragment> extract(InputStream in)`；`TablemdRouter` 按扩展名路由，不支持抛受检异常。
- `TableFragment`：`kind`（`table`=表格 markdown / `text`=正文片段）、`title`（表格 N / sheet 名 / 正文）、`content`（markdown 表格或正文文本）。
- `DocxTablemdExtractor`：按 body 元素顺序遍历——段落累积为正文片段（Chunker 切块，上限 512 字符），表格转 markdown（首行作表头）；`XlsxTablemdExtractor`：每 sheet 一个 markdown 片段（首行作表头，超过 60 行分片、后续分片重复表头，标题 `sheet (i/n)`）；`PdfTablemdExtractor`：仅正文片段（PDF 无表格结构，不做启发式猜测）。
- markdown 单元格转义：换行→空格、`|`→`\|`；`MarkdownTable` 工具集中处理。

### indexer（索引入库，双索引目录）
- **双索引目录**：`data/index/`（全文）与 `data/index-table/`（表格 markdown），各自一个进程内单例 `IndexWriter` + `SearcherManager`（`LuceneConfig` 以 qualifier 区分），可独立删除重建。
- `DocumentIndexer`（全文，一个文件 = 一个 Document，`content` 存整篇纯文本，不分块）；`TableIndexer`（表格，一个片段 = 一个 Document，按 `docId` 整体 upsert / 删除）。
- 全文索引 schema：

| 字段 | 类型 | 是否存储 | 说明 |
|---|---|---|---|
| `id` | StringField | 是 | UUID，唯一标识 |
| `filename` | TextField(IK) | 是 | 文件名，参与检索 |
| `path` | StringField | 是 | 存储路径 |
| `type` | StringField | 是 | docx / xlsx / pdf |
| `modified` | StoredField | 是 | 上传时间戳（仅展示） |
| `content` | TextField(IK) | 是 | 解析出的全文，存储以供高亮截取片段 |

- 表格索引 schema（每片段一个 Document）：

| 字段 | 类型 | 是否存储 | 说明 |
|---|---|---|---|
| `id` | StringField | 是 | 片段 UUID |
| `docId` | StringField | 是 | 所属文档 id（检索过滤 / 级联删除的关联键） |
| `filename` | TextField(IK) | 是 | 文件名，参与检索 |
| `path` | StringField | 是 | 存储路径 |
| `type` | StringField | 是 | docx / xlsx / pdf |
| `kind` | StringField | 是 | table / text |
| `title` | TextField(IK) | 是 | 表格 N / sheet 名 / 正文，参与检索 |
| `modified` | StoredField | 是 | 上传时间戳 |
| `content` | TextField(IK) | 是 | markdown 表格或正文片段，存储以供高亮 |

### searcher（检索）
- `DocumentSearcher`（全文格式）：BM25（`MultiFieldQueryParser` 查 `filename`+`content`）与 vector-service 语义召回两路，docId 级 RRF 融合，向量不可用降级纯 BM25（`degraded=true`）；`Highlighter`+`SimpleFragmenter` 截取最佳片段，命中词 `<em>` 包裹；`getById(docId)` 取整篇原文；`topDocsByDocIds` 供问答在选中文件范围内 BM25 召回。
- `TableSearcher`（表格格式）：纯 BM25（查 `filename`+`title`+`content`），**按 docId 聚合**——每个文档取最佳片段做 snippet（整段高亮不切碎，保证 markdown 表格完整）、累计匹配片段数（`matchCount`）；`topFragments(q, docIds, …)` 供问答直接取片段。
- **IK 双分词器策略**：索引侧细粒度（`IKAnalyzer(false)`，多切词保证召回），查询侧智能（`IKAnalyzer(true)`）。高亮时用索引侧分词器重切文本对齐 offset。双索引共用同一对 analyzer。
- snippet 返回 HTML 片段，前端直接渲染（后端对原文做 HTML 转义后保留 `<em>`）。

### ask（LLM 问答）
- `LlmClient`：OpenAI 兼容 `POST {base-url}/chat/completions`（java.net.http），非流式；配置 `docrag.llm.*`（base-url / api-key / model / temperature / timeout），api-key 支持环境变量 `DOCRAG_LLM_API_KEY`；未配置时问答接口返回 400。
- `AskService` 编排：选中 docIds + 索引格式 → 检索上下文——`full`：BM25 召回选中文档 → `Chunker` 查询时切块 → 按与问题的分词重叠度选 top-K；`table`：直接取表格索引 top 片段（带字符预算）——拼编号 prompt（system：仅依据资料作答、引用标 [n]、资料不足须明说）→ 调 LLM → 返回 `answer + citations`（每个上下文块一条：ref/docId/filename/title/excerpt）。
- 问答检索只用倒排（BM25），不走向量服务；LLM 失败整体报错，不降级编造。
- **IK 双分词器策略**：索引侧细粒度（`IKAnalyzer(false)`，多切词保证召回，否则「合同条款」整词入索引、查「合同」漏召回）；查询侧智能（`IKAnalyzer(true)`，贴近用户输入）。高亮时用索引侧分词器重切文本对齐 offset。
- snippet 返回 HTML 片段，前端直接渲染（信任后端输出，后端对原文做 HTML 转义后保留 `<em>`）。

### debug（解析诊断）
- `DebugParseService`：**纯解析不入库、不落盘**，返回结构化明细用于定位解析问题。
- 关键约束：`indexedText` 字段直接调用生产 `DocxParser`/`XlsxParser`，保证对比的就是真实写索引的 content。
- 明细内容：表格以行列结构返回（docx = `表格 N`，xlsx = sheet 名）；图片**仅计数**（不入索引、无 OCR，页面给出警告）；表格行数截断 100 行、索引文本截断 50k 字符（带 truncated 标志）。
- 第一期支持 docx/xlsx；PDF 返回「暂未实现」。

### api（REST 层）
Spring MVC Controller，统一 JSON 返回；解析/参数错误返回 4xx + `{error: "..."}`。

## 5. API 契约

前后端共同遵守，字段名以下述为准：

### POST /api/documents — 上传并入库
- 请求：`multipart/form-data`，字段 `file`
- 响应：`{"docId": "uuid", "filename": "xx.docx", "type": "docx", "chunkCount": 12, "fragmentCount": 3}`
- 行为：文件落盘 `data/upload/` → 全文解析 + 表格 markdown 提取 → 三库写入（全文索引 / 表格索引 / vector-service），任一失败回滚全部并报错；全文索引一个文档一个 `content` 字段（检索层不分块），表格索引一个片段一个 Document

### GET /api/search?q=关键词&page=1&size=10&format=full|table — 检索
- `format` 默认 `full`：混合检索（BM25 + 向量 RRF 融合，向量不可用降级 `degraded=true`）；`table`：表格 markdown 索引纯 BM25，按 docId 聚合
- 响应：
```json
{
  "total": 12,
  "hits": [
    {"docId": "uuid", "filename": "xx.docx", "path": "data/upload/xx.docx", "type": "docx",
     "snippet": "…其中<em>合同条款</em>约定…", "score": 3.42, "source": "both"}
  ],
  "degraded": false
}
```
- `source`：`both` / `bm25` / `vector` / `table`；`table` 格式额外返回 `matchCount`（该文档命中片段数），snippet 为完整 markdown 片段（含 `<em>` 高亮，管道符已转义）；snippet 中命中词用 `<em>` 包裹；`page` 从 1 开始。

### POST /api/ask — 文档问答（检索 chunk → LLM → 答案 + 引用）
- 请求：`{"question": "试用期最长多久？", "docIds": ["uuid1", "uuid2"], "format": "full|table"}`
- `docIds` 必填（界面上勾选的文档），`format` 默认 `full`，与检索共用同一索引格式
- 响应：
```json
{
  "answer": "试用期最长不超过六个月[1]。",
  "model": "gpt-4o-mini",
  "format": "full",
  "citations": [
    {"ref": 1, "docId": "uuid1", "filename": "xx.docx", "type": "docx", "title": "表格 2", "excerpt": "| 试用期 | 6 个月 |…"}
  ]
}
```
- LLM 未配置返回 400；选中范围内检索不到内容时直接返回「无法作答」类提示，不强行调用 LLM

### GET /api/documents/{docId} — 取索引原文
- 响应：`{docId, filename, path, type, modified, content}`，content 即写入索引的原始纯文本；不存在返回 404
- 前端 `/doc/<docId>` 转发此接口（HTML 片段），搜索页「查看原文」按钮惰性加载，展示层截断 200k 字符

### DELETE /api/documents/{docId} — 删除
- 行为：按 `id` 删除索引文档（上传原文保留）

### POST /api/debug/parse — Debug 解析（不入库）
- 请求：`multipart/form-data`，字段 `file`（docx/xlsx）
- 响应：`{filename, type, imageCount, tableCount, tablesTruncated, textTruncated, indexedText, tables: [{title, rows: [[...]]}]}`

## 6. 数据流

- **入库**：上传 → 存 `data/upload/` → Parser 提取纯文本 + tablemd 提取表格 markdown 片段 → IK 分词 → 全文索引（`data/index/`，一文一 Document）+ 表格索引（`data/index-table/`，一片段一 Document）+ vector-service 全文 chunk 向量；三库任一失败回滚全部
- **检索**：query + 索引格式 → `full`：BM25 与向量两路召回 → RRF 融合；`table`：表格索引 BM25 按 docId 聚合 → Highlighter 截取片段（`table` 格式整段不切碎）→ JSON 返回 → Flask 用 Jinja2 `|safe` 渲染 snippet，`table` 格式由前端 JS 将 markdown 表格渲染为 HTML table（保留 `<em>` 高亮）
- **问答**：勾选文档 + 问题 + 索引格式 → `/api/ask` → 选中范围内 BM25 召回（`full` 再查询时切块选 top-K / `table` 直接取片段）→ 编号 prompt → OpenAI 兼容 LLM → 答案 + 引用文档列表 → 前端按 docId 汇总展示引用

## 7. 构建与运行

```bash
# 后端（默认 :8080）
# 本机 JDK 为 brew 安装的 openjdk@17，需先设置：
# export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
cd backend && mvn spring-boot:run

# 前端（默认 :3000，监听 0.0.0.0 可局域网访问）
cd frontend && pip install -r requirements.txt && flask run --host 0.0.0.0 --port 3000

# vector-service（可选，:8081；不启动则检索自动降级纯 BM25）
cd vector-service && pip install -r requirements.txt && python main.py

# LLM 问答配置（OpenAI 兼容；不配置则 /api/ask 返回 400 提示）
# export DOCRAG_LLM_BASE_URL="https://api.openai.com/v1"
# export DOCRAG_LLM_API_KEY="sk-..."
# export DOCRAG_LLM_MODEL="gpt-4o-mini"

# 测试
cd backend && mvn test        # JUnit5：parser/tablemd 提取正确性、双索引入库-检索 round-trip、AskService（LLM 打桩）
cd frontend && pytest         # 页面路由、API 转发（含 /ask）
```

## 8. 开发约定

- 后端包命名 `com.docrag.*`，类职责与本文模块划分一一对应，不跨层调用（api → parser/tablemd/indexer/searcher/ask/vector，不反向依赖）。
- 索引目录、上传目录、端口、LLM 配置集中在 `application.yml`，禁止硬编码；密钥走环境变量。
- `IndexWriter` / `SearcherManager` 的生命周期由 `config/` 统一管理；**每个索引目录一个进程内单例 `IndexWriter`**（全文 `data/index/`、表格 `data/index-table/`，qualifier 区分），写后 commit + `maybeRefreshBlocking` 近实时可见。
- 解析异常（损坏文件、加密文件、扫描件 PDF）不得 500，统一 400 + 错误信息；上传三库写入任一失败必须回滚已写库。
- 前端仅做展示与请求转发，不承担业务逻辑；问答编排全在后端；高亮样式集中在 `static/` 的 CSS。
- 问答答案中的引用必须能映射回真实送入 LLM 的上下文（ref 编号与 citations 一一对应），禁止返回未送入的引用。
- `data/index/`、`data/index-table/` 与 `data/upload/` 加入 `.gitignore`。
