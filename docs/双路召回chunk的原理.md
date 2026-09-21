# 双路召回 chunk 的原理 —— source=both 的真实语义

> 本文回答：召回结果里 `source=both` 是不是「两个候选 chunk 里选了一个」？同一文档的 chunk 是怎么被两条路各自选出、又怎么被识别为同一个的？
> 实现：`backend/src/main/java/com/docrag/searcher/ModeSearcher.java#recallChunks()`；链路全景见《[检索与召回](检索与召回.md)》§3，RRF 公式细节见《[RRF融合与K常数](RRF融合与K常数.md)》。

## 0. 一句话纠偏

**`both` 不是「二选一」，而是同一个 chunk 恰好被两条路同时召回。** 它没有「选择」动作，只有「识别 + 合并打分」：两条路各自独立产出 chunk 列表，融合阶段发现某个 chunk 在两边都出现了，就标 `both` 并把两路的排名贡献相加。

## 1. 两条路各自怎么产出 chunk

| 路 | chunk 从哪来 | 选哪些 |
|---|---|---|
| BM25 路 | Lucene 索引是 doc 粒度的（一文一 Document）。问题词项先做**判别词过滤**（df=0 丢弃、df>60% 全库文档数的通用词剔除、全部剔除回退原始词项）并赋 IDF 权重，用判别词布尔查询召回 pool=clamp(bm25Chunks, 5, 20) 篇（含 content 全文），再**查询时按与入库完全一致的策略重新切块**（`chunkForMode()`：plain=`Chunker.chunk`（min 512 合并）、deep=`chunkKeepingTables(256)`） | 每 chunk 计「判别词去重覆盖 IDF 加权和」（`weightedOverlap()`，细粒度分词后每个判别词只计一次覆盖、乘 IDF 权重——长块不能靠重复词刷分），`score>0` 才入围；按 `覆盖和降序、doc 召回名升序` 排序，取前 `bm25Chunks` 个 |
| 向量路 | 向量库里存的就是**入库时切好的同款 chunk**（`IngestService` 用同一套 `Chunker` 调用写入 per-mode collection） | `vectorClient.query(mode, q, vectorChunks)` 直接返回 topK 个 chunk，不做任何词面过滤 |

关键前提：**检索期切块与入库期切块逐字节同源**——两路产出的 chunk 才可能「完全相等」，这是识别同一个 chunk 的基础（详见《[解析与入库](解析与入库.md)》§4.3）。

## 2. both 怎么判定：去重键

融合阶段用 `(docId, chunk 精确文本)` 二元组作 key（实现为 `chunkKey = docId + "\u0000" + text`，NUL 分隔——chunk 文本里可以有空格，但不能有 NUL，天然无歧义）：

- key **既在** BM25 路结果集、**又在**向量路结果集 → 标 `both`；
- 只在一方 → 标 `bm25` / `vector`。

文本差一个字符就是两个不同的 chunk（不会误合并），文档不同也绝不会合并（docId 不同）。反过来，BM25 路查询时重切出的块与向量库存的块必须逐字节一致才认作同一个——这依赖两处调用同一 `Chunker` 策略，改动切块策略需两侧同步。

## 3. both 之后的排序：RRF 两路贡献相加

融合分 = 两路排名贡献之和：

```
score = 1/(RRF_K + bm25路名次) + 1/(RRF_K + 向量路名次)     // RRF_K = 60
```

只中一路的 chunk 只有一项。**两路共识的 chunk 被累加两次，分数天然更高**——所以 `both` 的 chunk 通常排得更靠前，在 `AskService` 按 `contextChunks` / 字符预算贪心装填时更容易存活。这是刻意设计（双路共识 = 更强信号），不是巧合。

融合序在 RRF 分之上还有一层**词锚定分组**：与问题判别词有重叠（BM25 命中或覆盖和>0）的锚定组排在前、组内按 RRF 降序，零重叠的纯向量块沉到组后（不清除）。语义噪声块（如 plain 语料里与问题词面零重叠的行级表格碎片）不再靠 RRF 排名挤占上下文名额；纯改写问题全库零锚定时锚定组为空，向量序原样生效（见《检索与召回》§3）。

## 4. 同一文档的多个 chunk：独立竞争，互不挤占

不同 chunk 文本不同 → key 不同 → 各自独立参与 RRF 排序。同一文档完全可以同时有多个 chunk 进入融合列表，且各自的 `source` 可以不同（有的 both、有的仅 bm25、有的仅 vector）。不存在「每文档只留一个代表」的逻辑。

元数据来源（实现细节）：

- BM25 路的 chunk 自带 path（doc 召回时从索引取出）；`byKey.putIfAbsent` 使 **both chunk 的元数据取 BM25 路先放入的版本**；
- 仅向量命中的文档才回读倒排（`getById`）补 path（有界 ≤ vectorChunks 个 docId）；
- deep 模式的 `title`（「表格 N」）从 chunk 首行识别（`tableTitle()`），两路同源所以 both 也不会重复。

## 5. 对比：检索页 search() 的 both 是文档级的

问答 `recallChunks()` 是 chunk 级；检索页 `search()` 的 `source=both` 则是 **docId 级**——docId 既在 BM25 top50 又在向量 top50。且那条链路没有 chunk 选择语义：

- BM25 命中的文档展示的是 `Highlighter` 截取的 160 字最佳片段（不是 chunk）；
- 向量侧每个 docId 只取排名最靠前的一个 chunk 作代表（`putIfAbsent`）。

两链路粒度与用途的差异见《[检索与召回](检索与召回.md)》§5。
