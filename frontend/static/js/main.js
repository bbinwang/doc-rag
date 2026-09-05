// 搜索结果卡片「查看原文」：首次点击从 /doc/<id>（plain）或 /deep-doc/<id>（deep）
// 惰性加载，之后仅切换显示
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

  const url = btn.dataset.mode === "deep"
    ? "/deep-doc/" + btn.dataset.doc
    : "/doc/" + btn.dataset.doc;
  btn.disabled = true;
  btn.textContent = "加载中…";
  fetch(url)
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.text();
    })
    .then(function (html) {
      box.innerHTML = html;
      // deep 统一文本片段含 markdown 表格，注入后需补一次表格渲染
      if (window.renderMarkdownTables) window.renderMarkdownTables(box);
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

// ---- 表格索引 snippet / 明细页：markdown 表格 → HTML table（保留后端 <em> 高亮） ----
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

  // 连续以 | 开头的行段若构成合法 markdown 表格（表头 + 分隔行 + 数据行），返回 table 元素；否则 null
  function tryRenderTableSegment(lines) {
    const rows = lines.map(splitRow);
    if (rows.length < 2 || !isSeparator(rows[1])) return null;
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
    return wrap;
  }

  // 分段渲染：[data-md-table] 元素按行分组，连续 | 行段尝试渲染成 table，
  // 校验不过或非表格行原样保留（统一文本明细页 = 正文 + 表格交错，依赖此分段逻辑；
  // 纯表格元素行为与旧版一致，向后兼容）
  function renderMarkdownTables(root) {
    root.querySelectorAll("[data-md-table]").forEach(function (el) {
      const lines = el.innerHTML.split("\n");
      el.innerHTML = "";
      let seg = [];
      lines.forEach(function (line, idx) {
        const isTableLine = line.trim().startsWith("|");
        const isLast = idx === lines.length - 1;
        if (isTableLine) {
          seg.push(line);
          if (!isLast) return;
        }
        // 段结束（遇到非表格行或末尾）：尝试渲染表格段
        if (seg.length) {
          const table = tryRenderTableSegment(seg);
          if (table) {
            el.appendChild(table);
          } else {
            seg.forEach(function (l) {
              el.appendChild(document.createTextNode(l + "\n"));
            });
          }
          seg = [];
        }
        if (!isTableLine) {
          el.appendChild(document.createTextNode(line + "\n"));
        }
      });
    });
  }

  renderMarkdownTables(document);
  // 供惰性加载的原文片段（/deep-doc）注入后再渲染
  window.renderMarkdownTables = renderMarkdownTables;
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
    // 双栏对比时同一文档可能出现在两栏，勾选状态保持同步
    document.querySelectorAll('.hit-select[data-doc="' + box.dataset.doc + '"]').forEach(function (twin) {
      twin.checked = box.checked;
    });
    refreshBar();
  });

  function fail(message) {
    statusEl.hidden = false;
    statusEl.className = "err";
    statusEl.textContent = message;
  }

  const MODE_LABELS = { plain: "纯文本解析", deep: "深度解析" };

  function renderCitations(citations) {
    citationsEl.innerHTML = "";
    citations.forEach(function (c) {
      const li = document.createElement("li");
      const name = document.createElement("span");
      name.textContent = `[${c.ref}] ${c.filename}`;
      li.appendChild(name);
      // 来源解析模式徽标（双模式问答时区分上下文来自哪套索引）
      if (c.mode && MODE_LABELS[c.mode]) {
        const mode = document.createElement("span");
        mode.className = "badge cite-mode " + c.mode;
        mode.textContent = MODE_LABELS[c.mode];
        li.appendChild(mode);
      }
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

  function selectedModes() {
    const checks = document.querySelectorAll('.search-form input[name="modes"]:checked');
    const modes = [...checks].map(function (c) {
      return c.value;
    });
    return modes.length ? modes : ["plain"];
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
        modes: selectedModes(),
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

// ---- 库状态条 + 一键清理（加载时 GET /status；清理 confirm 后 POST /clear） ----
(function () {
  const section = document.getElementById("store-status");
  if (!section) return;
  const plainEl = document.getElementById("stat-plain");
  const deepEl = document.getElementById("stat-deep");
  const vectorEl = document.getElementById("stat-vector");
  const vectorItemEl = document.getElementById("stat-vector-item");
  const uploadsEl = document.getElementById("stat-uploads");
  const noteEl = document.getElementById("store-note");
  const btn = document.getElementById("clear-btn");

  function render(s) {
    plainEl.textContent = s.plainIndex ? s.plainIndex.docs : "–";
    deepEl.textContent = s.deepIndex ? s.deepIndex.docs : "–";
    uploadsEl.textContent = s.uploads != null ? s.uploads : "–";
    const v = s.vector || {};
    // vectors 为 per-mode 计数（{plain: n, deep: m}），状态条展示合计
    let total = null;
    if (v.available && v.vectors && typeof v.vectors === "object") {
      total = Object.values(v.vectors).reduce(function (a, b) {
        return a + b;
      }, 0);
    }
    vectorItemEl.classList.toggle("offline", !v.available);
    vectorEl.textContent = v.available ? (total != null ? total : "–") : "离线";
    vectorItemEl.title = v.available
      ? (v.model || "vector-service") +
        (v.vectors ? `（纯文本 ${v.vectors.plain ?? "–"} / 深度解析 ${v.vectors.deep ?? "–"}）` : "")
      : "vector-service 不可用（检索降级纯 BM25，清理需先启动它）";
  }

  function note(message, isError) {
    noteEl.textContent = message || "";
    noteEl.className = isError ? "err" : "meta";
  }

  function jsonFetch(url, options) {
    return fetch(url, options).then(function (r) {
      return r.json().then(function (data) {
        return { ok: r.ok, data: data };
      });
    });
  }

  function load() {
    jsonFetch("/status")
      .then(function (rv) {
        if (!rv.ok || rv.data.error) {
          throw new Error(rv.data.error || "后端返回异常");
        }
        render(rv.data);
      })
      .catch(function (err) {
        note("状态加载失败：" + err.message, true);
      });
  }

  btn.addEventListener("click", function () {
    if (
      !window.confirm(
        "将清空纯文本/深度解析两个索引、向量库，并删除 data/upload/ 下全部已上传原文件（不可恢复），系统回到零状态，之后需重新上传才能检索。确定清理？"
      )
    ) {
      return;
    }
    btn.disabled = true;
    btn.textContent = "清理中…";
    note("");
    jsonFetch("/clear", { method: "POST" })
      .then(function (rv) {
        if (!rv.ok || rv.data.error) {
          throw new Error(rv.data.error || "清理失败");
        }
        render(rv.data);
        note("✅ 已清空两模式索引、向量库，并删除全部上传原文件");
        // 结果区/问答区已失效，短暂展示反馈后刷新页面
        setTimeout(function () {
          window.location.reload();
        }, 800);
      })
      .catch(function (err) {
        note("清理失败：" + err.message, true);
      })
      .finally(function () {
        btn.disabled = false;
        btn.textContent = "一键清理索引";
      });
  });

  load();
})();
