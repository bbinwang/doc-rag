// 独立问答页：全库检索（不选文档）→ 每模式独立 LLM → 两栏答案 + 召回 chunks/文档明细
(function () {
  const form = document.getElementById("ask-form");
  if (!form) return;
  const btn = document.getElementById("ask-btn");
  const questionEl = document.getElementById("ask-question");
  const statusEl = document.getElementById("ask-status");
  const resultEl = document.getElementById("ask-result");
  const grid = document.getElementById("ask-grid");

  const MODE_LABELS = { plain: "纯文本解析", deep: "深度解析" };
  const SOURCE_LABELS = { both: "混合", bm25: "关键词", vector: "语义" };

  function selectedModes() {
    const checks = form.querySelectorAll('input[name="modes"]:checked');
    const modes = [...checks].map(function (c) { return c.value; });
    return modes.length ? modes : ["plain"];
  }

  function paramValue(id, fallback) {
    const v = parseInt(document.getElementById(id).value, 10);
    return Number.isFinite(v) && v >= 1 ? v : fallback;
  }

  function fail(message) {
    statusEl.hidden = false;
    statusEl.className = "err";
    statusEl.textContent = message;
  }

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

  function ask() {
    const question = questionEl.value.trim();
    if (!question) {
      fail("请输入问题");
      return;
    }
    btn.disabled = true;
    btn.textContent = "提问中…";
    statusEl.hidden = true;
    resultEl.hidden = true;
    fetch("/ask", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        question: question,
        modes: selectedModes(),
        bm25Chunks: paramValue("param-bm25", 5),
        vectorChunks: paramValue("param-vector", 5),
        contextChunks: paramValue("param-context", 8),
      }),
    })
      .then(function (r) {
        return r.json().then(function (data) { return { ok: r.ok, data: data }; });
      })
      .then(function (rv) {
        if (!rv.ok || rv.data.error) {
          throw new Error(rv.data.error || "后端返回异常");
        }
        const modes = rv.data.modes || {};
        const keys = Object.keys(modes);
        grid.className = "results-grid" + (keys.length > 1 ? " dual" : "");
        grid.innerHTML = "";
        keys.forEach(function (m) {
          grid.appendChild(renderMode(m, modes[m]));
        });
        resultEl.hidden = false;
        resultEl.scrollIntoView({ behavior: "smooth", block: "nearest" });
        if (window.renderMarkdownTables) window.renderMarkdownTables(resultEl);
      })
      .catch(function (err) {
        fail("问答失败：" + err.message);
      })
      .finally(function () {
        btn.disabled = false;
        btn.textContent = "提问";
      });
  }

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    ask();
  });
  questionEl.addEventListener("keydown", function (e) {
    if (e.key === "Enter") ask();
  });
})();
