#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""doc-rag 问答效果验证 runner。

端到端评测：按 filename 增量上传 eval/corpus 固定语料 → 逐题调 POST /api/ask
（plain/deep 每模式独立作答）→ 两层判分 → 输出分模式 × 分题型报告。

两层判分：
  L1（默认，确定性）：关键点组命中（组内 any_of）+ 禁含项 + 引用 [n] 映射校验 + 拒答话术；
  L2（--judge，可选）：LLM-as-judge，按 golden 给 0/1/2 分 + 理由（复用后端同一 LLM 配置，
      环境变量 DOCRAG_LLM_BASE_URL / DOCRAG_LLM_API_KEY / DOCRAG_LLM_MODEL 覆盖，
      缺省回退 backend/src/main/resources/application.yml 的默认值）。

安全：绝不默认清理数据；--reset 需交互输入 CLEAR（或 --yes）才会调 POST /api/admin/clear
（该接口全量清空双索引 + 双 collection + 全部上传原文件）。

用法示例：
  frontend/.venv/bin/python eval/run_eval.py --judge
  frontend/.venv/bin/python eval/run_eval.py --filter F1,F2 --modes deep
详见 eval/README.md。
"""

import argparse
import datetime
import json
import os
import re
import sys
import time
from pathlib import Path

import requests

EVAL_DIR = Path(__file__).resolve().parent
DEFAULT_BACKEND = "http://127.0.0.1:8080"
TYPE_ORDER = ["单点事实", "表格数值定位", "表格聚合统计", "表格条件筛选",
              "跨文档综合", "语义改写", "总结归纳", "多点列举", "无答案拒答"]
KNOWN_TYPES = set(TYPE_ORDER)

# 判分前统一 normalize：全角数字/百分号转半角、去千分位逗号、去全部空白
# （应对「1000000 / 100万 / 3,010,000 / １５ 个工作日」等写法差异）
_FW_TRANS = str.maketrans("０１２３４５６７８９％", "0123456789%")
REFUSAL_RE = re.compile(
    r"资料不足|无法.{0,8}(?:作答|找到|确定|回答|提供|获取|判断|得出|计算)"
    r"|未检索到|无相关|没有.{0,6}相关|未能找到|暂无|未提供|未包含|未提及"
)


def normalize(s: str) -> str:
    return re.sub(r"\s+", "", s.translate(_FW_TRANS).replace(",", "").replace("，", ""))


# ---------- dataset ----------

def load_dataset(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    cases = data.get("cases")
    if not isinstance(cases, list) or not cases:
        sys.exit(f"dataset 无效：cases 为空（{path}）")
    ids = set()
    for c in cases:
        for field in ("id", "type", "question", "golden", "expect"):
            if field not in c:
                sys.exit(f"dataset 无效：用例缺字段 {field}（{c.get('id', '?')}）")
        if c["type"] not in KNOWN_TYPES:
            sys.exit(f"dataset 无效：未知题型 {c['type']}（{c['id']}）")
        if c["id"] in ids:
            sys.exit(f"dataset 无效：id 重复 {c['id']}")
        ids.add(c["id"])
    return data


# ---------- LLM（L2 裁判） ----------

def llm_config() -> dict:
    """环境变量优先，缺省回退 application.yml 中 active provider 的 ${VAR:default} 默认值。"""
    defaults = {}
    yml = EVAL_DIR.parent / "backend" / "src" / "main" / "resources" / "application.yml"
    if yml.exists():
        text = yml.read_text(encoding="utf-8")
        # active provider 名（docrag.llm.active: ${DOCRAG_LLM_ACTIVE:glm}）
        am = re.search(r"^\s*active:\s*\$\{[^:}]*:(.*?)\}\s*$", text, re.M)
        active = am.group(1).strip() if am else ""
        if active:
            # providers.<active> 块：从 provider 名行起，到下一个缩进更浅的非空行为止
            bm = re.search(r"^\s+" + re.escape(active) + r":\s*$", text, re.M)
            if bm:
                rest = text[bm.end():]
                stop = re.search(r"^\s{0,6}\S", rest, re.M)
                block = rest[:stop.start()] if stop else rest
                for key in ("base-url", "api-key", "model"):
                    pm = re.search(r"^\s*" + re.escape(key) + r":\s*\$\{[^:}]*:(.*?)\}\s*$", block, re.M)
                    if pm:
                        defaults[key] = pm.group(1).strip()
    return {
        "base_url": os.environ.get("DOCRAG_LLM_BASE_URL", defaults.get("base-url", "")),
        "api_key": os.environ.get("DOCRAG_LLM_API_KEY", defaults.get("api-key", "")),
        "model": os.environ.get("DOCRAG_LLM_MODEL", defaults.get("model", "")),
    }


def llm_chat(cfg: dict, system: str, user: str, timeout: int = 90) -> str:
    r = requests.post(
        f"{cfg['base_url'].rstrip('/')}/chat/completions",
        headers={"Authorization": f"Bearer {cfg['api_key']}"},
        json={
            "model": cfg["model"],
            "temperature": 0,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        },
        timeout=timeout,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


JUDGE_SYSTEM = "你是严格的中文问答质量评审员。只依据「标准答案」判定「待评答案」正确性，不考虑文风与格式。"

JUDGE_USER_TMPL = """【问题】{question}
【标准答案】{golden}
【待评答案】{answer}

评审规则：
1. 待评答案须给出与标准答案一致的实质信息；数值允许 1000000 / 100万 / 一百万 等等价写法；
2. 数值、人名、日期、比例等关键事实错误即不正确；
3. 标准答案要求列举多项时，漏项超过三分之一记部分正确；
4. 待评答案以「资料不足/无法作答」为主要结论、且未给出标准答案关键信息的，记不正确；已给出正确关键信息后再补充不确定性说明的，不影响判分。

只输出 JSON（不要输出其他内容）：
{{"score": 2, "reason": "一句话理由"}}
score：2=正确且完整；1=部分正确（方向对但有遗漏或小瑕疵）；0=错误或不当拒答。"""


def judge_llm(case: dict, answer: str, cfg: dict) -> dict:
    if not answer:
        return {"score": 0, "reason": "答案为空"}
    content = llm_chat(cfg, JUDGE_SYSTEM, JUDGE_USER_TMPL.format(
        question=case["question"], golden=case["golden"], answer=answer))
    content = content.strip()
    m = re.search(r"\{.*\}", content, re.S)  # 容错：剥掉 ```json 围栏或前后闲话
    if not m:
        return {"score": None, "reason": f"裁判输出无法解析: {content[:120]}"}
    try:
        parsed = json.loads(m.group(0))
        score = int(parsed.get("score"))
        if score not in (0, 1, 2):
            raise ValueError(score)
        return {"score": score, "reason": str(parsed.get("reason", ""))[:200]}
    except (ValueError, TypeError) as e:
        return {"score": None, "reason": f"裁判输出无法解析: {e}: {content[:120]}"}


# ---------- L1 确定性判分 ----------

def judge_deterministic(case: dict, mr: dict) -> dict:
    """mr = 单模式结果 {answer, error, degraded, chunks, docs}。"""
    exp = case["expect"]
    answer = mr.get("answer") or ""
    failures = []
    detail = {"hits": None, "cited": 0, "invalid_citations": []}

    if mr.get("error"):
        return {"passed": False, "failures": [f"接口错误: {mr['error']}"], **detail}

    if exp.get("expect_refusal"):
        if not REFUSAL_RE.search(answer):
            failures.append(f"未命中拒答话术（答案: {answer[:60]}…）" if answer else "答案为空且未拒答")
        for f in exp.get("forbidden", []):
            if normalize(f) in normalize(answer):
                failures.append(f"拒答却包含禁含项: {f}")
        return {"passed": not failures, "failures": failures, **detail}

    if not answer.strip():
        return {"passed": False, "failures": ["答案为空"], **detail}

    norm_answer = normalize(answer)

    # 1) 关键点组（组内 any_of），min_hits 可放宽（总结/列举题）
    groups = exp.get("keypoints", [])
    missed = [g for g in groups if not any(normalize(k) in norm_answer for k in g)]
    need = exp.get("min_hits") or len(groups)
    detail["hits"] = f"{len(groups) - len(missed)}/{len(groups)}"
    if groups and len(groups) - len(missed) < need:
        missed_desc = "；".join("|".join(g) for g in missed)
        failures.append(f"关键点命中 {len(groups) - len(missed)}/{need}，缺: {missed_desc}")

    # 2) 禁含项（防幻觉）
    for f in exp.get("forbidden", []):
        if normalize(f) in norm_answer:
            failures.append(f"包含禁含项: {f}")

    # 3) 引用校验：[n] 必须映射回真实召回 chunks；实质答案须至少一个引用
    refs = {c.get("ref") for c in (mr.get("chunks") or [])}
    cited = [int(n) for n in re.findall(r"\[(\d+)\]", answer)]
    detail["cited"] = len(cited)
    detail["invalid_citations"] = [n for n in cited if n not in refs]
    if detail["invalid_citations"]:
        failures.append(f"引用 {detail['invalid_citations']} 未映射到召回 chunks")
    if not cited:
        failures.append("实质答案缺少 [n] 引用")

    return {"passed": not failures, "failures": failures, **detail}


# ---------- 服务交互 ----------

def preflight(backend: str) -> dict:
    try:
        status = requests.get(f"{backend}/api/status", timeout=10).json()
        params = requests.get(f"{backend}/api/ask/params", timeout=10).json()
    except requests.RequestException as e:
        sys.exit(f"后端不可达（{backend}）: {e}\n请按 docs/运维与启动.md 启动后端（须从 backend/ 目录启动）。")
    vector_ok = bool(status.get("vector", {}).get("available"))
    print(f"后端 {backend}  plain 文档数={status.get('plainIndex', {}).get('docs')} "
          f"deep 文档数={status.get('deepIndex', {}).get('docs')} "
          f"uploads={status.get('uploads')}  vector={'available' if vector_ok else 'UNAVAILABLE'}")
    print(f"ask 参数默认值: {json.dumps(params, ensure_ascii=False)}")
    if not vector_ok:
        print("警告: vector-service 不可用，语义改写类（F 组）将大概率失败，结果按 degraded 标注解读")
    return {"status": status, "params": params, "vector_ok": vector_ok}


def maybe_reset(backend: str, auto_yes: bool) -> None:
    status = requests.get(f"{backend}/api/status", timeout=10).json()
    print(f"即将全量清空：plain 文档 {status.get('plainIndex', {}).get('docs')} 篇、"
          f"deep 文档 {status.get('deepIndex', {}).get('docs')} 篇、"
          f"上传原文件 {status.get('uploads')} 个（不可恢复，含非评测语料的用户文档）")
    if not auto_yes:
        typed = input('确认请输入 CLEAR（回车取消）: ').strip()
        if typed != "CLEAR":
            sys.exit("已取消 --reset")
    r = requests.post(f"{backend}/api/admin/clear", timeout=120)
    if r.status_code != 200:
        sys.exit(f"清理失败 HTTP {r.status_code}: {r.text[:200]}")
    print("已全量清空（vector + 双索引 + 上传原文件）")


def stage_corpus(backend: str, corpus_dir: Path) -> None:
    """按 filename 增量上传：已在两个索引中的跳过，避免重复 docId；绝不清理已有文档。"""
    present = {}
    for mode in ("plain", "deep"):
        docs = requests.get(f"{backend}/api/store/{mode}", timeout=30).json().get("docs", [])
        present[mode] = {d["filename"] for d in docs}
    files = sorted(p for p in corpus_dir.iterdir() if p.is_file() and not p.name.startswith("."))
    if not files:
        sys.exit(f"语料目录为空: {corpus_dir}（先运行 mvn test -Dtest=EvalCorpusTest 生成）")
    uploaded = 0
    for f in files:
        missing = [m for m in ("plain", "deep") if f.name not in present[m]]
        if not missing:
            print(f"  语料已就绪: {f.name}")
            continue
        if set(missing) != {"plain", "deep"}:
            print(f"  注意: {f.name} 仅存在于部分索引（{missing} 缺失），重新上传可能在已有模式产生重复")
        with open(f, "rb") as fh:
            r = requests.post(
                f"{backend}/api/documents",
                files={"file": (f.name, fh)},
                data={"modes": "plain,deep"},
                timeout=120,
            )
        if r.status_code != 200:
            sys.exit(f"上传 {f.name} 失败 HTTP {r.status_code}: {r.text[:200]}")
        body = r.json()
        counts = body.get("chunkCount", {})
        print(f"  已上传: {f.name}  chunkCount={counts} tableCount={body.get('tableCount')}")
        for mode, n in counts.items():
            if not n:
                sys.exit(f"上传 {f.name} 的 {mode} 模式 chunkCount=0，语料异常")
        uploaded += 1
    print(f"语料 staging 完成（新上传 {uploaded}/{len(files)}）")


def ask(backend: str, question: str, modes: list, timeout: int) -> dict:
    r = requests.post(
        f"{backend}/api/ask",
        json={"question": question, "modes": modes},
        timeout=timeout,
    )
    r.raise_for_status()
    return r.json()


# ---------- 报告 ----------

def render_report(results: list, modes: list, header: dict, judge_on: bool) -> tuple:
    """返回 (控制台文本, 汇总统计)。"""
    lines = []
    lines.append(f"== 问答效果验证  backend={header['backend']}  modes={','.join(modes)}  "
                 f"judge={'on' if judge_on else 'off'} ==")

    pass_count = {m: 0 for m in modes}
    degraded_count = {m: 0 for m in modes}
    type_stats = {}  # type -> mode -> [passed, total]
    l2_scores = {m: [] for m in modes}
    failures = []

    for i, item in enumerate(results, 1):
        case = item["case"]
        cells = []
        for m in modes:
            mr = item["modes"][m]
            l1 = mr["l1"]
            l2 = mr.get("l2")
            if mr.get("degraded"):
                degraded_count[m] += 1
            if l1["passed"]:
                pass_count[m] += 1
            else:
                failures.append((case, m, mr))
            st = type_stats.setdefault(case["type"], {mm: [0, 0] for mm in modes})[m]
            st[1] += 1
            if l1["passed"]:
                st[0] += 1
            cell = "PASS" if l1["passed"] else f"FAIL({'；'.join(l1['failures'])[:80]})"
            if judge_on and l2 is not None and l2.get("score") is not None:
                cell += f" L2={l2['score']}"
                l2_scores[m].append(l2["score"])
            if mr.get("degraded"):
                cell += " [degraded]"
            cells.append(f"{m} {cell}")
        lines.append(f"[{i:>2}/{len(results)}] {case['id']} {case['type']:<6} {' | '.join(cells)}")

    lines.append("── L1 通过率矩阵（type × mode）──")
    for t in TYPE_ORDER:
        if t not in type_stats:
            continue
        row = "  ".join(f"{m} {type_stats[t][m][0]}/{type_stats[t][m][1]}" for m in modes)
        lines.append(f"  {t:<8} {row}")
    total = len(results)
    lines.append(f"  合计      " + "  ".join(f"{m} {pass_count[m]}/{total}" for m in modes)
                 + f"   (degraded: " + "/".join(f"{m} {degraded_count[m]}" for m in modes) + ")")
    if judge_on:
        lines.append("── L2 裁判均分（0-2）──")
        lines.append("  " + "  ".join(
            f"{m} {sum(l2_scores[m]) / len(l2_scores[m]):.2f}（{len(l2_scores[m])} 题）" if l2_scores[m]
            else f"{m} 无有效评分" for m in modes))

    if failures:
        lines.append("── 失败明细 ──")
        for case, m, mr in failures:
            answer = (mr.get("answer") or mr.get("error") or "")[:200]
            chunks = mr.get("chunks") or []
            top = f"top chunk: [{chunks[0].get('ref')}] {chunks[0].get('filename')} score={chunks[0].get('score')}" if chunks else "无召回 chunks"
            lines.append(f"  {case['id']}/{m}: {'；'.join(mr['l1']['failures'])}")
            lines.append(f"    答案节选: {answer}")
            lines.append(f"    {top}")

    stats = {"pass_count": pass_count, "degraded_count": degraded_count,
             "type_stats": type_stats, "total": total,
             "l2_avg": {m: (sum(v) / len(v) if v else None) for m, v in l2_scores.items()}}
    return "\n".join(lines), stats


# ---------- main ----------

def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="doc-rag 问答效果验证 runner（详见 eval/README.md）")
    ap.add_argument("--backend", default=DEFAULT_BACKEND, help=f"后端地址（默认 {DEFAULT_BACKEND}）")
    ap.add_argument("--dataset", default=str(EVAL_DIR / "dataset.json"), help="题集 JSON 路径")
    ap.add_argument("--corpus", default=str(EVAL_DIR / "corpus"), help="语料目录")
    ap.add_argument("--modes", default="plain,deep", help="参评模式逗号串（默认 plain,deep）")
    ap.add_argument("--filter", default=None, help="按 id 过滤（逗号串，如 A1,F1）")
    ap.add_argument("--filter-type", default=None, help="按题型过滤（子串匹配）")
    ap.add_argument("--judge", action="store_true", help="启用 L2 LLM 裁判（拒答题不送裁判）")
    ap.add_argument("--reset", action="store_true",
                    help="评测前全量清空（危险：清双索引+双向量+全部上传原文件，含用户文档）")
    ap.add_argument("--yes", action="store_true", help="--reset 免交互确认（脚本/CI 用）")
    ap.add_argument("--timeout", type=int, default=180, help="单题 ask 超时秒数（双模式两次 LLM 串行）")
    ap.add_argument("--output", default=str(EVAL_DIR / "results"), help="结果输出目录")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    bad = [m for m in modes if m not in ("plain", "deep")]
    if bad or not modes:
        sys.exit(f"--modes 非法: {bad}（只允许 plain/deep）")

    dataset = load_dataset(Path(args.dataset))
    cases = dataset["cases"]
    if args.filter:
        wanted = {c.strip() for c in args.filter.split(",") if c.strip()}
        unknown = wanted - {c["id"] for c in cases}
        if unknown:
            sys.exit(f"--filter 含未知 id: {sorted(unknown)}")
        cases = [c for c in cases if c["id"] in wanted]
    if args.filter_type:
        cases = [c for c in cases if args.filter_type in c["type"]]
    if not cases:
        sys.exit("过滤后无可用用例")

    pre = preflight(args.backend)
    if args.reset:
        maybe_reset(args.backend, args.yes)
    stage_corpus(args.backend, Path(args.corpus))

    cfg = llm_config() if args.judge else None
    if args.judge:
        if not (cfg["base_url"] and cfg["api_key"] and cfg["model"]):
            print("警告: LLM 配置不完整，L2 裁判将全部失败", file=sys.stderr)
        else:
            try:
                llm_chat(cfg, "你是探针。", "回复 ok")
                print(f"L2 裁判就绪: model={cfg['model']} base={cfg['base_url']}")
            except requests.RequestException as e:
                print(f"警告: LLM 探测失败，L2 将按解析失败记录: {e}", file=sys.stderr)

    print(f"开始评测：{len(cases)} 题 × {len(modes)} 模式（ask timeout={args.timeout}s）")
    results = []
    for case in cases:
        item = {"case": {"id": case["id"], "type": case["type"], "question": case["question"],
                         "golden": case["golden"]}, "modes": {}}
        t0 = time.time()
        try:
            payload = ask(args.backend, case["question"], modes, args.timeout)
        except requests.RequestException as e:
            for m in modes:
                item["modes"][m] = {"answer": None, "error": f"请求失败: {e}",
                                    "degraded": None, "chunks": [], "docs": [],
                                    "l1": {"passed": False, "failures": [f"请求失败: {e}"]}}
            item["elapsed_s"] = round(time.time() - t0, 1)
            results.append(item)
            print(f"  {case['id']} 请求失败: {e}")
            continue
        for m in modes:
            mr = payload.get("modes", {}).get(m)
            if mr is None:
                mr = {"answer": None, "error": "响应缺少该模式结果", "chunks": []}
            l1 = judge_deterministic(case, mr)
            entry = {"answer": mr.get("answer"), "error": mr.get("error"),
                     "degraded": mr.get("degraded"), "chunks": mr.get("chunks") or [],
                     "docs": mr.get("docs") or [], "l1": l1}
            if args.judge and not case["expect"].get("expect_refusal"):
                try:
                    entry["l2"] = judge_llm(case, mr.get("answer") or "", cfg)
                except requests.RequestException as e:
                    entry["l2"] = {"score": None, "reason": f"裁判请求失败: {e}"}
            item["modes"][m] = entry
        item["elapsed_s"] = round(time.time() - t0, 1)
        results.append(item)

    report, stats = render_report(results, modes, {"backend": args.backend}, args.judge)
    print(report)

    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = out_dir / f"eval-{ts}.json"
    md_path = out_dir / f"eval-{ts}.md"
    json_path.write_text(json.dumps({
        "backend": args.backend, "modes": modes, "judge": args.judge,
        "ask_params": pre.get("params"), "vector_available": pre.get("vector_ok"),
        "stats": stats, "results": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(f"# 问答效果验证 {ts}\n\n```\n{report}\n```\n", encoding="utf-8")
    print(f"结果已保存: {json_path} / {md_path}")

    if any(not item["modes"][m]["l1"]["passed"] for item in results for m in modes):
        sys.exit(1)


if __name__ == "__main__":
    main()
