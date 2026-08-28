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
