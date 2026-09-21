// 问答双栏渲染单测（node:test + jsdom）：栏目结构 / [n] 引用 / 降级与错误态 / docs 链接 / XSS 转义
// 运行：cd frontend && npm test；页面路由与转发层测试在 test_app.py（pytest）
import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { createRequire } from "node:module";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
global.document = dom.window.document;

const require = createRequire(import.meta.url);
const AskRender = require("../static/js/ask-render.js");
const { renderMode, chunkLi } = AskRender;

/** 与 docs/api.md §ask 的 chunk 字段一致 */
function chunk(over) {
  return Object.assign({
    ref: 1, docId: "abc-123", filename: "劳动合同.docx", type: "docx",
    title: null, source: "both", score: 0.0328, text: "试用期六个月",
  }, over);
}

function result(over) {
  return Object.assign({
    answer: "试用期最长不超过六个月[1]。", error: null, degraded: false,
    chunks: [chunk()],
    docs: [{ docId: "abc-123", filename: "劳动合同.docx", type: "docx", path: "/p" }],
  }, over);
}

// ---- renderMode：栏目结构 ----

test("双模式各出一栏：模式标签 + chunks 计数", () => {
  const plain = renderMode("plain", result());
  const deep = renderMode("deep", result({ chunks: [chunk(), chunk({ ref: 2 })] }));
  assert.match(plain.querySelector(".mode-head").textContent, /纯文本解析/);
  assert.match(deep.querySelector(".mode-head").textContent, /深度解析/);
  assert.match(plain.querySelector(".mode-head .meta").textContent, /1 chunks/);
  assert.match(deep.querySelector(".mode-head .meta").textContent, /2 chunks/);
  assert.equal(plain.className, "mode-col");
});

test("未知 mode key 回退显示原始值", () => {
  const col = renderMode("future", result({ chunks: [], docs: [] }));
  assert.match(col.querySelector(".mode-head").textContent, /future/);
});

test("answer 渲染 + chunks 列表 + 涉及文档链接", () => {
  const col = renderMode("plain", result());
  assert.equal(col.querySelector(".ask-answer").textContent,
    "试用期最长不超过六个月[1]。");
  const lis = col.querySelectorAll("ol.chunk-list > li.chunk-item");
  assert.equal(lis.length, 1);
  assert.equal(lis[0].querySelector(".chunk-meta > span").textContent,
    "[1] 劳动合同.docx");
  const a = col.querySelector(".docs-list a");
  assert.equal(a.getAttribute("href"), "/store/plain/abc-123");
  assert.equal(a.textContent, "劳动合同.docx");
});

test("error 态：⚠️ 摘要 + 错误段落 + 无答案，chunks 照常返回供调试", () => {
  const col = renderMode("deep", result({ answer: null, error: "LLM 调用失败: 超时" }));
  assert.match(col.querySelector(".mode-head .meta").textContent,
    /⚠️ LLM 调用失败: 超时/);
  assert.ok(col.querySelector("p.err"));
  assert.equal(col.querySelector(".ask-answer"), null);
  assert.equal(col.querySelectorAll("li.chunk-item").length, 1);
});

test("degraded：模式头出现降级提示", () => {
  const col = renderMode("plain", result({ degraded: true }));
  assert.match(col.querySelector(".mode-head .meta").textContent, /向量不可用已降级/);
});

test("空 chunks / 空 docs 不渲染对应区块", () => {
  const col = renderMode("plain", result({ chunks: [], docs: [] }));
  assert.equal(col.querySelector("ol.chunk-list"), null);
  assert.equal(col.querySelector(".docs-list"), null);
});

// ---- chunkLi：引用编号与徽标细节 ----

test("来源徽标中文映射 + score 四位小数；plain 不带表格渲染标记", () => {
  const li = chunkLi(chunk({ source: "both" }), "plain");
  assert.equal(li.querySelector(".badge").textContent, "混合");
  assert.match(li.querySelector(".chunk-meta .meta").textContent, /score 0\.0328$/);
  assert.equal(li.querySelector(".chunk-text").dataset.mdTable, undefined);
});

test("deep 表格块：title 展示 + data-md-table；未知 source 回退原值", () => {
  const li = chunkLi(chunk({ title: "表格 1", source: "vector" }), "deep");
  assert.match(li.querySelector(".cite-title").textContent, /表格 1/);
  assert.equal(li.querySelector(".badge").textContent, "语义");
  assert.equal(li.querySelector(".chunk-text").dataset.mdTable, "1");
  assert.equal(chunkLi(chunk({ source: "hybrid" }), "plain")
    .querySelector(".badge").textContent, "hybrid");
});

test("XSS：chunk 文本原样展示，不产生脚本节点", () => {
  const li = chunkLi(chunk({ text: "<script>alert(1)</script>表格内容" }), "deep");
  assert.equal(li.querySelectorAll("script").length, 0);
  assert.equal(li.querySelector(".chunk-text").textContent,
    "<script>alert(1)</script>表格内容");
});
