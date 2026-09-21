# eval/ 问答效果验证

衡量 plain / deep 双模式问答效果：固定语料 + 21 题验证集（query + 正确总结答案）+ 两层判分评测 runner。
设计动机与结果解读见 [docs/效果验证.md](../docs/效果验证.md)；机制全貌与使用指南见 [docs/评测机制与使用.md](../docs/评测机制与使用.md)。

## 组成

| 路径 | 说明 |
|---|---|
| `corpus/` | 5 份固定评测语料（员工手册/行政管理制度 docx、预算表/价格表 xlsx、服务合同 pdf），由 `backend/src/test/java/com/docrag/evalcorpus/EvalCorpusTest.java` 生成、入 git |
| `dataset.json` | 验证集：21 题 × 9 类题型，每题含 question、golden（正确总结答案）、可机判要点（keypoints 组内 any_of）、禁含项、拒答预期 |
| `run_eval.py` | 评测 runner（用 `frontend/.venv` 运行，仅依赖 requests + 标准库） |
| `results/` | 评测产物（gitignore）：`eval-<时间戳>.json` 全量原始 + `eval-<时间戳>.html` 人可读报告（表格 + 颜色，plain/deep 对比、失败原因可展开） |

## 前置条件

1. 后端已启动（`http://127.0.0.1:8080`，须从 `backend/` 目录启动，见 docs/运维与启动.md）；
2. 建议 vector-service 也启动——语义改写类（F 组）依赖向量召回，不可用时按 `degraded` 标注解读；
3. LLM 可达（`application.yml` 中 `docrag.llm.active` 选中的 provider）；`--judge` 时 runner 直接复用同一配置（缺省取 active provider 的默认值，环境变量 `DOCRAG_LLM_BASE_URL / DOCRAG_LLM_API_KEY / DOCRAG_LLM_MODEL` 可整体覆盖）。

## 运行

```bash
# 全量（双模式 + LLM 裁判，约 40 次 LLM 调用）
frontend/.venv/bin/python eval/run_eval.py --judge

# 子集 / 单模式 / 按题型
frontend/.venv/bin/python eval/run_eval.py --filter A1,F1 --modes deep
frontend/.venv/bin/python eval/run_eval.py --filter-type 表格聚合统计
```

- 首次运行自动按 filename 增量上传 `corpus/` 语料（已在两个索引中的跳过，不产生重复 docId；**绝不清理已有文档**）；
- 退出码：L1 全过 `0`、有失败 `1`、后端不可达 `2`；
- 重复运行安全（staging 幂等）；完整回归用 `cd backend && mvn test`、`cd frontend && pytest`。

## 重新生成语料

改了语料生成器（或想重建 `corpus/`）：

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
cd backend && mvn test -Dtest=EvalCorpusTest
```

注意：**语料内容与 `dataset.json` 的 golden 答案强耦合**，改语料必须同步改题集。
PDF 依赖系统中文字体（macOS：`/System/Library/Fonts/Supplemental/Arial Unicode.ttf` 等），缺失时生成器直接 fail（不做英文回退，回退会破坏金答案）。

## --reset（危险）

`--reset` 会在评测前调 `POST /api/admin/clear` 全量清空：双倒排索引 + 双向量 collection + **全部上传原文件（含非评测语料的用户文档，不可恢复）**。需交互输入 `CLEAR` 确认（脚本场景用 `--reset --yes`）。

仅在这类场景使用：索引里存在与语料同名但内容不同的旧文件（staging 按 filename 跳过上传会评到旧内容）、或想要绝对干净的评测基线。

## 判分摘要

- **L1（默认，确定性）**：关键点组命中（判分前两侧过 normalize：全角转半角、去千分位逗号、去空白）+ 禁含项（防幻觉）+ 引用校验（答案 `[n]` 必须映射回真实召回 chunks，实质答案须至少一个引用）+ 拒答题话术校验；
- **L2（`--judge`，可选）**：LLM-as-judge 按 golden 打 0/1/2 分 + 理由；拒答题（I 组）不送裁判。
