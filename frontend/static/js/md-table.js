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
