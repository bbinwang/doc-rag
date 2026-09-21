# 任务交接：corpus_complex 复杂语料评测（512 最小 chunk）

> 2026-09-19 goal 任务已完成。本文档是续接上下文：结果、产物位置、关键发现、下一步候选工作。
> 结果详解见 `eval/results/eval-20260919-071349.md`（本文不重复归因细节）。

## 1. 任务定义与完成状态

**Goal**（已达成）：从业界 RAG 评测数据集获取中文 docx/xlsx/pdf 各 5 篇 → 清空全部倒排/向量库 → 以 512 最小 chunk 重新入库 → 跑 eval 出报告。

| 项 | 状态 |
|---|---|
| 语料 15 篇（3 类各 5） | ✅ `eval/corpus_complex/`（docx-corpus + MetaGLM/FinGLM） |
| 清库重建 + 512 chunk 入库 | ✅ plain 9326 / deep 797 chunks，双倒排 15×2 |
| 题集 25 题（9 类题型全覆盖） | ✅ `eval/dataset_complex.json`，金答案逐一从入库文本核对 |
| eval 运行 + 报告 | ✅ `eval/results/eval-20260919-071349.{json,html,md}` |
| 网盘备份 | ✅ `/apps/bdpan/doc-rag/eval-20260919/`（BaiduPCS-Go，qqiqidai 账号；语料+题集+报告 20 件） |

## 2. 结果一句话

**plain 13/25（52%）、deep 12/25（48%）**；小文档散文/slides 类 19/21 稳定，失败全部集中在依赖大 xlsx 的表格题与跨文档题；全部失败均为诚实拒答（零编造），引用契约无一失效。

## 3. 关键发现（改进的靶子，按预期收益排序）

1. **deep 模式超大表硬失败（0-chunk）**：xlsx 整 sheet 一块（7 万–140 万字符）超 `context-char-budget=6000`，`AskService` 贪心截断后剩 0 chunk → 占位拒答、不调 LLM。改法：原子表块超预算时按行二级切分（表头重复）。
2. **BM25 路 doc 池挤占**：`ModeSearcher#recallChunks` 的 doc 池 clamp(bm25Chunks,5,20)=5 篇，公司名类查询被大年报词频占满，大表目标行进不了池（对应 `代办列表.md` TODO-1 的方案 A：chunk 级倒排可根治）。
3. **PDF 碎片行噪声**：PDFBox 对年报窄列/竖排页（承诺函等）抽出每行 5–6 字碎片，512 合并块词面重叠极高，overlap 排序霸榜。改法：入库预处理对连续短行做跨行合并。
4. 偶发：GLM 内容安全过滤对含时政表述文档 400（F1/deep 1 例）；`/api/store/deep/{docId}` 出现过一次瞬时 404 后自愈未复现。

## 4. 代码与工作区状态（续接前必读）

- **512 最小 chunk 尚未提交**：工作树未提交改动含 `Chunker.java`（MIN_CHUNK_CHARS=512 贪心合并）、`ChunkerTest.java`（10 用例）、`AskServiceTest.java`（2 用例适配）、`CLAUDE.md`（IngestService 条目）——`mvn test` 111 全绿，后端已用新 jar 运行中。
- **检索单元测试基线**：双模式 round-trip 等测试均已过；改检索逻辑后先 `cd backend && mvn test`。
- 后端 :8080 运行中（从 `backend/` cwd 启动，512 jar）；vector-service :8081 用户自管；前端 :3100。
- 评测语料已在库：复测直接 `frontend/.venv/bin/python eval/run_eval.py --dataset eval/dataset_complex.json --corpus eval/corpus_complex --output eval/results`（staging 幂等跳过）。**坑**：SOURCES.md 不能放进 `corpus_complex/` 目录，staging 会当语料上传而 400（现放在 `eval/corpus_complex_SOURCES.md`）。

## 5. 下次继续的候选工作

1. （收益最大）deep 超预算表格按行二级切块 → 直接挽回 5 题的 deep 侧；改 `Chunker.chunkKeepingTables` 或 `AskService` 截断逻辑，同步改 CLAUDE.md「表格原子保留」表述与 ChunkerTest。
2. BM25 路 chunk 级倒排（`代办列表.md` TODO-1 方案 A）→ 挽回 plain 侧 7 题的 doc 池问题；schema 变更需先改 CLAUDE.md §4/§5 与 docs/api.md。
3. PDF 短行合并预处理（Parser 层）→ 压噪声块。
4. 题集微调：E2 字面判分增加裸词 any_of（`system` 等不带 `<|…|>` 的写法），消除评分假阴性。
5. 改动后复测对比本次基线（52%/48%），回归关注：小文档题不降、C1 聚合题保持。

## 6. 相关文件索引

| 内容 | 路径 |
|---|---|
| 语料（15 篇） | `eval/corpus_complex/` |
| 语料来源与许可 | `eval/corpus_complex_SOURCES.md` |
| 题集 | `eval/dataset_complex.json` |
| 机器报告 / 可视化 / 人读报告 | `eval/results/eval-20260919-071349.{json,html,md}` |
| 网盘备份 | `/apps/bdpan/doc-rag/eval-20260919/` |
| 记忆（跨会话） | auto-memory: `docrag-corpus-complex-eval-findings` |
