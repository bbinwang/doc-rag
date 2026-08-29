// 搜索结果卡片「查看原文」：首次点击从 /doc/<id> 惰性加载，之后仅切换显示
document.addEventListener("click", function (e) {
  const btn = e.target.closest(".toggle-doc");
  if (!btn) return;
  const box = document.getElementById("doc-" + btn.dataset.doc);
  if (!box) return;

  if (box.dataset.loaded) {
    if (box.hasAttribute("hidden")) {
      box.removeAttribute("hidden");
      btn.textContent = "收起原文";
    } else {
      box.setAttribute("hidden", "");
      btn.textContent = "查看原文";
    }
    return;
  }

  btn.disabled = true;
  btn.textContent = "加载中…";
  fetch("/doc/" + btn.dataset.doc)
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.text();
    })
    .then(function (html) {
      box.innerHTML = html;
      box.dataset.loaded = "1";
      box.removeAttribute("hidden");
      btn.textContent = "收起原文";
    })
    .catch(function () {
      box.innerHTML = '<p class="err">原文加载失败（后端服务不可用）</p>';
      box.removeAttribute("hidden");
      btn.textContent = "查看原文";
    })
    .finally(function () {
      btn.disabled = false;
    });
});

// ---- 表格索引 snippet：markdown 表格 → HTML table（保留后端 <em> 高亮） ----
(function () {
  // 按未转义的 | 切分单元格，\| 还原为字面管道符
  function splitRow(line) {
    let s = line.trim();
    if (s.startsWith("|")) s = s.slice(1);
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.slice(0, -1);
    const cells = [];
    let cur = "";
    for (let i = 0; i < s.length; i++) {
      if (s[i] === "\\" && s[i + 1] === "|") {
        cur += "|";
        i++;
      } else if (s[i] === "|") {
        cells.push(cur.trim());
        cur = "";
      } else {
        cur += s[i];
      }
    }
    cells.push(cur.trim());
    return cells;
  }

  function isSeparator(cells) {
    return cells.length > 0 && cells.every((c) => /^-{3,}$/.test(c));
  }

  function renderMarkdownTables(root) {
    root.querySelectorAll("[data-md-table]").forEach(function (el) {
      const lines = el.innerHTML.split("\n").map((l) => l.trim()).filter(Boolean);
      // 只有整体是合法 markdown 表格（表头 + 分隔行 + 数据行）才渲染，否则按纯文本展示
      if (lines.length < 2 || !lines.every((l) => l.startsWith("|"))) return;
      const rows = lines.map(splitRow);
      if (rows.length < 2 || !isSeparator(rows[1])) return;

      const table = document.createElement("table");
      table.className = "table-view md-table";
      const thead = document.createElement("thead");
      const headTr = document.createElement("tr");
      rows[0].forEach(function (cell) {
        const th = document.createElement("th");
        th.innerHTML = cell; // 后端已做 HTML 转义并保留 <em>
        headTr.appendChild(th);
      });
      thead.appendChild(headTr);
      table.appendChild(thead);
      const tbody = document.createElement("tbody");
      for (let r = 2; r < rows.length; r++) {
        const tr = document.createElement("tr");
        rows[r].forEach(function (cell) {
          const td = document.createElement("td");
          td.innerHTML = cell;
          tr.appendChild(td);
        });
        tbody.appendChild(tr);
      }
      table.appendChild(tbody);
      const wrap = document.createElement("div");
      wrap.className = "table-wrap";
      wrap.appendChild(table);
      el.innerHTML = "";
      el.appendChild(wrap);
    });
  }

  renderMarkdownTables(document);
})();

// ---- 结果勾选 + 内嵌问答（检索 → 勾选 → 提问 → 答案 + 引用） ----
(function () {
  const bar = document.getElementById("ask-bar");
  if (!bar) return;
  const selected = new Map(); // docId -> filename
  const countEl = document.getElementById("ask-count");
  const filesEl = document.getElementById("ask-files");
  const resultEl = document.getElementById("ask-result");
  const answerEl = document.getElementById("ask-answer");
  const citationsEl = document.getElementById("ask-citations");
  const statusEl = document.getElementById("ask-status");
  const btn = document.getElementById("ask-btn");
  const questionEl = document.getElementById("ask-question");

  function refreshBar() {
    countEl.textContent = selected.size;
    filesEl.textContent = selected.size ? "：" + [...selected.values()].join("、") : "";
    bar.hidden = selected.size === 0;
  }

  document.addEventListener("change", function (e) {
    const box = e.target.closest(".hit-select");
    if (!box) return;
    if (box.checked) {
      selected.set(box.dataset.doc, box.dataset.filename);
    } else {
      selected.delete(box.dataset.doc);
    }
    refreshBar();
  });

  function fail(message) {
    statusEl.hidden = false;
    statusEl.className = "err";
    statusEl.textContent = message;
  }

  function renderCitations(citations) {
    citationsEl.innerHTML = "";
    citations.forEach(function (c) {
      const li = document.createElement("li");
      const name = document.createElement("span");
      name.textContent = `[${c.ref}] ${c.filename}`;
      li.appendChild(name);
      if (c.title) {
        const title = document.createElement("span");
        title.className = "cite-title";
        title.textContent = ` · ${c.title}`;
        li.appendChild(title);
      }
      if (c.excerpt) {
        const excerpt = document.createElement("div");
        excerpt.className = "cite-excerpt";
        excerpt.textContent = c.excerpt;
        li.appendChild(excerpt);
      }
      citationsEl.appendChild(li);
    });
  }

  function ask() {
    const question = questionEl.value.trim();
    if (!selected.size) {
      fail("请先在检索结果中勾选至少一个文档");
      return;
    }
    if (!question) {
      fail("请输入问题");
      return;
    }
    const formatSel = document.querySelector('select[name="format"]');
    btn.disabled = true;
    btn.textContent = "提问中…";
    statusEl.hidden = true;
    resultEl.hidden = true;
    fetch("/ask", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        question: question,
        docIds: [...selected.keys()],
        format: formatSel ? formatSel.value : "full",
      }),
    })
      .then(function (r) {
        return r.json().then(function (data) {
          return { ok: r.ok, data: data };
        });
      })
      .then(function (rv) {
        if (!rv.ok || rv.data.error) {
          throw new Error(rv.data.error || "后端返回异常");
        }
        answerEl.textContent = rv.data.answer;
        renderCitations(rv.data.citations || []);
        resultEl.hidden = false;
        resultEl.scrollIntoView({ behavior: "smooth", block: "nearest" });
      })
      .catch(function (err) {
        fail("问答失败：" + err.message);
      })
      .finally(function () {
        btn.disabled = false;
        btn.textContent = "提问";
      });
  }

  btn.addEventListener("click", ask);
  questionEl.addEventListener("keydown", function (e) {
    if (e.key === "Enter") ask();
  });
})();
