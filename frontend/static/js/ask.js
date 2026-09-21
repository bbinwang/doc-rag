// 独立问答页：全库检索（不选文档）→ 每模式独立 LLM → 两栏答案 + 召回 chunks/文档明细
// 渲染逻辑在 ask-render.js（AskRender 全局），便于单测与跨页复用
(function () {
  const form = document.getElementById("ask-form");
  if (!form) return;
  const btn = document.getElementById("ask-btn");
  const questionEl = document.getElementById("ask-question");
  const statusEl = document.getElementById("ask-status");
  const resultEl = document.getElementById("ask-result");
  const grid = document.getElementById("ask-grid");

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
          grid.appendChild(AskRender.renderMode(m, modes[m]));
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
