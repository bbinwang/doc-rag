# doc-rag 项目架构定义

> 本文档是项目的架构宪法，所有代码实现必须遵循此文档。如实现与本文冲突，以本文为准；如需变更架构，先修改本文。

## 1. 项目概述

**doc-rag** 是一个本地文档关键词检索系统：Java + Lucene 后端负责 docx / xlsx / pdf 的解析、索引入库与 BM25 检索；Python Flask Web 前端提供搜索页面，检索命中片段高亮展示。

**定位边界**：纯关键词检索（Lucene BM25），不做向量检索 / embedding / LLM 问答；不支持扫描件 PDF（无 OCR）。

### 架构总览

```
浏览器 ── HTTP ── Python Flask 前端 (:3000) ── REST/JSON ── Java Spring Boot 服务 (:8080)
                                                                        │
                                                          ┌─────────────┼─────────────┐
                                                       Parser 模块   Indexer 模块   Searcher 模块
                                                       POI/PDFBox    Lucene 写     Lucene 读+高亮
                                                                        │
                                                                    data/index/ 索引目录
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

## 3. 目录结构

```
doc-rag/
├── CLAUDE.md
├── backend/                  # Java Maven 工程（Spring Boot）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/docrag/
│       │   ├── DocRagApplication.java
│       │   ├── api/          # REST Controller（DocumentController, SearchController）
│       │   ├── parser/       # DocumentParser 接口 + Docx/Xlsx/Pdf 三个实现 + ParserRouter
│       │   ├── indexer/      # IndexWriter 单例封装（DocumentIndexer）
│       │   ├── searcher/     # IndexSearcher + Highlighter 封装（DocumentSearcher）
│       │   ├── debug/        # Debug 解析服务（表格结构化 + 图片计数，不入库）
│       │   └── config/       # 索引路径等配置项
│       ├── main/resources/application.yml
│       └── test/java/com/docrag/   # JUnit5 测试
├── frontend/                 # Python Flask 应用
│   ├── app.py                # 路由：页面渲染 + 转发后端 API
│   ├── templates/index.html  # 搜索页
│   ├── static/               # CSS/JS
│   └── requirements.txt
└── data/
    ├── upload/               # 上传原文存放
    └── index/                # Lucene 索引目录（gitignore，可随时删除重建）
```

## 4. 后端模块设计

### parser（文档解析）
- 接口：`DocumentParser`，两个方法：`boolean supports(String ext)`、`String parse(InputStream in)`（返回提取的纯文本）。
- 实现：`DocxParser`（XWPFWordExtractor）、`XlsxParser`（逐 Sheet 逐行逐单元格拼接，单元格间制表符、行间换行）、`PdfParser`（PDFTextStripper）。
- `ParserRouter` 按文件扩展名路由到对应实现；不支持的扩展名抛出受检异常，API 层转为 400。

### indexer（索引入库）
- `DocumentIndexer` 封装 Lucene `IndexWriter`（**进程内单例**，全链路唯一写入点）。
- 索引字段 schema：

| 字段 | 类型 | 是否存储 | 说明 |
|---|---|---|---|
| `id` | StringField | 是 | UUID，唯一标识 |
| `filename` | TextField(IK) | 是 | 文件名，参与检索 |
| `path` | StringField | 是 | 存储路径 |
| `type` | StringField | 是 | docx / xlsx / pdf |
| `modified` | LongField | 是 | 上传时间戳 |
| `content` | TextField(IK) | 是 | 解析出的全文，存储以供高亮截取片段 |

### searcher（检索）
- `DocumentSearcher`：`MultiFieldQueryParser`（查 `filename` + `content`）→ `IndexSearcher` BM25 召回 → Lucene `Highlighter` + `SimpleFragmenter` 截取最佳片段，命中词以 `<em>` 包裹；`getById(docId)` 按索引取整篇原文。
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
- 响应：`{"docId": "uuid", "filename": "xx.docx", "type": "docx"}`
- 行为：文件落盘 `data/upload/` → 解析提取文本 → 写入索引（单次调用 = 一个文档一个 `content` 字段，不做分块）

### GET /api/search?q=关键词&page=1&size=10 — 检索
- 响应：
```json
{
  "total": 12,
  "hits": [
    {"docId": "uuid", "filename": "xx.docx", "path": "data/upload/xx.docx", "type": "docx",
     "snippet": "…其中<em>合同条款</em>约定…", "score": 3.42}
  ]
}
```
- snippet 中命中词用 `<em>` 包裹；`page` 从 1 开始。

### GET /api/documents/{docId} — 取索引原文
- 响应：`{docId, filename, path, type, modified, content}`，content 即写入索引的原始纯文本；不存在返回 404
- 前端 `/doc/<docId>` 转发此接口（HTML 片段），搜索页「查看原文」按钮惰性加载，展示层截断 200k 字符

### DELETE /api/documents/{docId} — 删除
- 行为：按 `id` 删除索引文档（上传原文保留）

### POST /api/debug/parse — Debug 解析（不入库）
- 请求：`multipart/form-data`，字段 `file`（docx/xlsx）
- 响应：`{filename, type, imageCount, tableCount, tablesTruncated, textTruncated, indexedText, tables: [{title, rows: [[...]]}]}`

## 6. 数据流

- **入库**：上传 → 存 `data/upload/` → Parser 提取纯文本 → IK 分词 → IndexWriter 写入 `data/index/`
- **检索**：query → IK 分词 → BM25 召回打分 → Highlighter 截取最佳片段（`<em>` 包裹命中词）→ JSON 返回 → Flask 用 Jinja2 `|safe` 渲染 snippet，CSS 将 `em` 样式化为黄色背景高亮

## 7. 构建与运行

```bash
# 后端（默认 :8080）
# 本机 JDK 为 brew 安装的 openjdk@17，需先设置：
# export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
cd backend && mvn spring-boot:run

# 前端（默认 :3000，监听 0.0.0.0 可局域网访问）
cd frontend && pip install -r requirements.txt && flask run --host 0.0.0.0 --port 3000

# 测试
cd backend && mvn test        # JUnit5：parser 提取正确性、indexer/searcher 入库-检索 round-trip
cd frontend && pytest         # 页面路由、API 转发
```

## 8. 开发约定

- 后端包命名 `com.docrag.*`，类职责与本文模块划分一一对应，不跨层调用（api → parser/indexer/searcher，不反向依赖）。
- 索引目录、上传目录、端口等路径集中在 `application.yml`，禁止硬编码。
- `IndexWriter` / `IndexSearcher` 的生命周期由 `config/` 统一管理；`IndexWriter` 进程内唯一，提交后关闭 reader 重新打开（`SearcherManager` 可选）。
- 解析异常（损坏文件、加密文件、扫描件 PDF）不得 500，统一 400 + 错误信息。
- 前端仅做展示与请求转发，不承担业务逻辑；高亮样式集中在 `static/` 的 CSS。
- `data/index/` 与 `data/upload/` 加入 `.gitignore`。
