// 搜索结果卡片「查看原文」：首次点击从 /doc/<id>（plain）或 /deep-doc/<id>（deep）
// 惰性加载，之后仅切换显示
document.addEventListener("click", function (e) {
  const btn = e.target.closest(".toggle-doc");
  if (!btn) return;
  // 容器 id 含模式前缀：双栏同文档各自独立原文框（plain/deep 内容不同，互不复用）
  const box = document.getElementById("doc-" + btn.dataset.mode + "-" + btn.dataset.doc);
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
      // 原文片段均为原始文本原样展示（deep 保持 markdown 管道符），无需二次渲染
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
