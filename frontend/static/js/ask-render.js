// 问答结果渲染（纯数据 → DOM，不发请求）：ask.js 负责表单与 fetch 编排，本模块只管渲染，
// 独立成文件便于 node --test 单测与后续跨页复用（如搜索页内嵌问答）
var AskRender = (function () {
  const MODE_LABELS = { plain: "纯文本解析", deep: "深度解析" };
  const SOURCE_LABELS = { both: "混合", bm25: "关键词", vector: "语义" };

  function chunkLi(c, mode) {
    const li = document.createElement("li");
    li.className = "chunk-item";
    const head = document.createElement("div");
    head.className = "chunk-meta";
    const name = document.createElement("span");
    name.textContent = `[${c.ref}] ${c.filename}`;
    head.appendChild(name);
    if (c.title) {
      const title = document.createElement("span");
      title.className = "cite-title";
      title.textContent = " · " + c.title;
      head.appendChild(title);
    }
    const src = document.createElement("span");
    src.className = "badge src " + c.source;
    src.textContent = SOURCE_LABELS[c.source] || c.source;
    head.appendChild(src);
    const score = document.createElement("span");
    score.className = "meta";
    score.textContent = " score " + c.score.toFixed(4);
    head.appendChild(score);
    li.appendChild(head);
    const text = document.createElement("div");
    text.className = "chunk-text";
    text.textContent = c.text;
    if (mode === "deep") text.dataset.mdTable = "1";
    li.appendChild(text);
    return li;
  }

  function renderMode(m, r) {
    const col = document.createElement("section");
    col.className = "mode-col";
    const head = document.createElement("h2");
    head.className = "mode-head";
    head.textContent = MODE_LABELS[m] || m;
    const meta = document.createElement("span");
    meta.className = "meta";
    const parts = [];
    if (r.error) parts.push("⚠️ " + r.error);
    else parts.push((r.chunks || []).length + " chunks");
    if (r.degraded) parts.push("⚠️ 向量不可用已降级");
    meta.textContent = " " + parts.join(" · ");
    head.appendChild(meta);
    col.appendChild(head);

    if (r.error) {
      const err = document.createElement("p");
      err.className = "err";
      err.textContent = r.error;
      col.appendChild(err);
    }
    if (r.answer) {
      const answer = document.createElement("div");
      answer.className = "ask-answer";
      answer.textContent = r.answer;
      col.appendChild(answer);
    }
    if (r.chunks && r.chunks.length) {
      const chunksHead = document.createElement("h3");
      chunksHead.className = "meta";
      chunksHead.textContent = "召回 chunks（送入 LLM 的上下文，[n] 对应答案引用）";
      col.appendChild(chunksHead);
      const ol = document.createElement("ol");
      ol.className = "chunk-list";
      r.chunks.forEach(function (c) {
        ol.appendChild(chunkLi(c, m));
      });
      col.appendChild(ol);
    }
    if (r.docs && r.docs.length) {
      const docsHead = document.createElement("h3");
      docsHead.className = "meta";
      docsHead.textContent = "涉及文档";
      col.appendChild(docsHead);
      const ul = document.createElement("ul");
      ul.className = "docs-list";
      r.docs.forEach(function (d) {
        const li = document.createElement("li");
        const a = document.createElement("a");
        a.href = "/store/" + m + "/" + d.docId;
        a.textContent = d.filename;
        li.appendChild(a);
        ul.appendChild(li);
      });
      col.appendChild(ul);
    }
    return col;
  }

  return { MODE_LABELS: MODE_LABELS, SOURCE_LABELS: SOURCE_LABELS,
           chunkLi: chunkLi, renderMode: renderMode };
})();

// CommonJS 导出仅供 node --test 加载；浏览器侧走全局 var AskRender
if (typeof module !== "undefined" && module.exports) {
  module.exports = AskRender;
}
