# doc-rag · 本地文档关键词检索系统

纯本地部署的文档关键词检索系统，支持 **docx / xlsx / pdf** 三种格式，基于 Lucene BM25 算法检索，可选开启本地语义检索（bge embedding + ChromaDB）。

## 架构

```
浏览器 ── HTTP ── Python Flask 前端 (:3000) ── REST/JSON ── Java Spring Boot 服务 (:8080)
                                                                        │
                                                          ┌─────────────┼─────────────┐
                                                       Parser 模块   Indexer 模块   Searcher 模块
                                                       POI/PDFBox    Lucene 写     Lucene 读+高亮
                                                                        │
                                                          Vector Client ──► vector-service (:8081)
                                                                        │
                                                                    data/index/ 索引目录
                                                                    data/chroma/ 向量库
```

| 层 | 技术 | 说明 |
|---|---|---|
| 后端 | Java 17、Spring Boot 3、Maven | REST 服务 (:8080) |
| 检索引擎 | Apache Lucene 8.11 | BM25 打分，本地文件索引 |
| 中文分词 | IK Analyzer 8.5.0 | 中文按词切分 |
| 文档解析 | Apache POI (docx/xlsx)、PDFBox (pdf) | 提取纯文本 |
| 向量检索（可选）| Python FastAPI、bge-small-zh-v1.5、ChromaDB | 语义检索 (:8081) |
| 前端 | Python Flask、Jinja2、原生 CSS/JS | 服务端渲染 (:3000) |

## 快速开始

### 前置条件

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | Java 后端 |
| Maven | 3.6+ | 构建工具 |
| Python | 3.10+ | 前端 + 向量服务 |

```bash
# 如系统未设置 JAVA_HOME，先指定（macOS Homebrew 路径）
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
```

### 1. 启动后端（Java Spring Boot）

```bash
cd backend
mvn spring-boot:run
```

后端默认监听 **`:8080`**，索引目录为 `../data/index/`，上传目录为 `../data/upload/`（相对 `backend/` 工作目录）。

### 2. 启动前端（Flask）

```bash
cd frontend
pip install -r requirements.txt
flask run --host 0.0.0.0 --port 3000
```

前端默认监听 **`:3000`**，打开浏览器访问 `http://localhost:3000`。

### 3. [可选] 启动向量服务（语义检索）

```bash
cd vector-service
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8081
```

向量服务使用 **bge-small-zh-v1.5** 模型本地编码，首次启动会自动下载模型（约 130MB）。模型下载完成后会在运行时离线工作，数据不出机。

向量服务默认监听 **`:8081`**。如不需要语义检索，跳过此步即可——后端会自动降级为纯 BM25 关键词检索。

## API 文档

### POST `/api/documents` — 上传并入库

- **请求**: `multipart/form-data`，字段 `file`
- **响应**:
```json
{
  "docId": "550e8400-e29b-41d4-a716-446655440000",
  "filename": "合同.docx",
  "type": "docx",
  "chunkCount": 5
}
```

### GET `/api/search?q=关键词&page=1&size=10` — 检索

- **响应**:
```json
{
  "total": 12,
  "hits": [
    {
      "docId": "uuid",
      "filename": "合同.docx",
      "path": "data/upload/...",
      "type": "docx",
      "snippet": "其中<em>合同条款</em>约定……",
      "score": 3.42,
      "source": "both"
    }
  ],
  "degraded": false
}
```

`source`: `bm25`（仅关键词）/ `vector`（仅语义）/ `both`（混合召回 RRF 融合）。

### GET `/api/documents/{docId}` — 取索引原文

- **响应**:
```json
{
  "docId": "uuid",
  "filename": "合同.docx",
  "path": "data/upload/...",
  "type": "docx",
  "modified": 1750000000000,
  "content": "写入索引的完整纯文本"
}
```

### DELETE `/api/documents/{docId}` — 删除

- 从索引和向量库中删除该文档（上传原文保留）

### POST `/api/debug/parse` — Debug 解析（不入库）

- **请求**: `multipart/form-data`，字段 `file`（仅支持 docx/xlsx）
- **响应**:
```json
{
  "filename": "员工表.docx",
  "type": "docx",
  "imageCount": 2,
  "tableCount": 1,
  "tablesTruncated": false,
  "textTruncated": false,
  "indexedText": "拍平的索引文本……",
  "tables": [
    { "title": "表格 1", "rows": [["姓名", "年龄"], ["张三", "25"]] }
  ]
}
```

## 前端页面

| 页面 | 路径 | 说明 |
|---|---|---|
| 搜索页 | `/` | 上传文件、关键词检索、结果高亮 |
| Debug 解析 | `/debug` | 上传文件查看结构化解析结果，对比拍平文本 |
| 原文查看 | `/doc/<docId>` | 从搜索结果卡片「查看原文」按钮惰性加载 |

## 数据流

### 入库流程

```
用户上传 → 落盘 data/upload/ → Parser 提取纯文本
  → Indexer 写入 Lucene 索引 (content 字段)
  → VectorClient 编码 + upsert ChromaDB（如向量服务可用）
```

### 检索流程

```
用户输入关键词
  → IK 分词 → Lucene MultiFieldQueryParser (filename + content)
  → BM25 打分召回 Top-50
  → 并行: VectorClient 语义召回 Top-50（如可用）
  → RRF 融合排序 (k=60)
  → Highlighter 截取最佳片段（<em> 包裹命中词）
  → 分页返回 JSON
```

## 索引字段 Schema

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | StringField | UUID，唯一标识 |
| `filename` | TextField (IK) | 文件名，参与检索 |
| `path` | StringField | 文件存储路径 |
| `type` | StringField | docx / xlsx / pdf |
| `modified` | StoredField | 上传时间戳 |
| `content` | TextField (IK) | 解析全文，存储用于高亮 |

## 配置

所有路径集中在 `backend/src/main/resources/application.yml`：

```yaml
server:
  port: 8080

spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 55MB

docrag:
  upload-dir: ../data/upload
  index-dir: ../data/index
  vector-service-url: http://127.0.0.1:8081
```

## 测试

```bash
# 后端 JUnit5
cd backend && mvn test

# 前端 pytest
cd frontend && pip install -r requirements.txt && pytest
```

## 目录结构

```
doc-rag/
├── backend/                    # Java Maven 工程
│   ├── pom.xml
│   └── src/main/java/com/docrag/
│       ├── api/                # REST Controller
│       ├── parser/             # 文档解析器
│       ├── indexer/            # Lucene 写入
│       ├── searcher/           # Lucene 检索 + 高亮
│       ├── debug/              # Debug 解析服务
│       ├── vector/             # 向量服务 HTTP 客户端
│       └── config/             # 配置项
├── frontend/                   # Python Flask 应用
│   ├── app.py
│   ├── templates/
│   ├── static/
│   └── requirements.txt
├── vector-service/             # 向量服务（可选）
│   ├── main.py
│   └── requirements.txt
├── data/
│   ├── upload/                 # 上传原文
│   ├── index/                  # Lucene 索引
│   └── chroma/                 # ChromaDB 向量库
└── CLAUDE.md                   # 架构宪法
```

## 技术要点

- **IK 双分词器策略**: 索引侧细粒度（`useSmart=false`）保证召回，查询侧智能切分（`useSmart=true`）贴近用户意图。高亮时索引侧分词器重切文本对齐 offset。
- **RRF 融合排序**: 两路召回（BM25 + 语义）各自排序后，按 `score = Σ 1/(k + rank)` 融合，k=60。
- **自动降级**: 向量服务不可用时自动降级为纯 BM25 关键词检索，`degraded=true` 标记。
- **HTML 安全**: 后端对原文做 HTML 转义后保留 `<em>` 高亮标记，前端用 Jinja2 `|safe` 渲染。
