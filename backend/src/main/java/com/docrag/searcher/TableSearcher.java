package com.docrag.searcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Formatter;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.NullFragmenter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 表格 markdown 索引检索：纯 BM25（filename/title/content 三字段），
 * 结果按 docId 聚合——每文档取最佳片段整段高亮为 snippet（不切碎，保证 markdown 表格完整），
 * 并统计该文档命中片段数 matchCount。
 */
@Component
public class TableSearcher {

    private static final String[] SEARCH_FIELDS = {"filename", "title", "content"};
    /** 聚合前的召回池：片段数可能远大于文档数 */
    private static final int POOL = 200;
    /** snippet 展示上限：超出按行边界截断（markdown 表格按行切才可渲染） */
    private static final int SNIPPET_MAX_CHARS = 2500;

    private final SearcherManager searcherManager;
    /** 查询侧：智能切分；高亮用索引侧 analyzer 对齐 offset（与全文检索同一策略） */
    private final Analyzer queryAnalyzer;
    private final Analyzer indexAnalyzer;

    public TableSearcher(@Qualifier("tableSearcherManager") SearcherManager searcherManager,
                         @Qualifier("queryAnalyzer") Analyzer queryAnalyzer,
                         @Qualifier("indexAnalyzer") Analyzer indexAnalyzer) {
        this.searcherManager = searcherManager;
        this.queryAnalyzer = queryAnalyzer;
        this.indexAnalyzer = indexAnalyzer;
    }

    /** docId 聚合结果：最佳片段 + 命中片段数 */
    private static final class DocAgg {
        float bestScore;
        Document bestDoc;
        int matchCount;
    }

    public SearchResponse search(String q, int page, int size) throws IOException, ParseException {
        Query query = new MultiFieldQueryParser(SEARCH_FIELDS, queryAnalyzer).parse(q);
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs top = searcher.search(query, POOL);
            Map<String, DocAgg> aggByDoc = new LinkedHashMap<>();
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                DocAgg agg = aggByDoc.computeIfAbsent(doc.get("docId"), k -> new DocAgg());
                agg.matchCount++;
                if (agg.bestDoc == null || sd.score > agg.bestScore) {
                    agg.bestScore = sd.score;
                    agg.bestDoc = doc;
                }
            }
            List<Map.Entry<String, DocAgg>> ordered = aggByDoc.entrySet().stream()
                    .sorted((a, b) -> Float.compare(b.getValue().bestScore, a.getValue().bestScore))
                    .toList();

            Highlighter highlighter = newHighlighter(query);
            int from = Math.max(0, (page - 1) * size);
            List<SearchHit> hits = new ArrayList<>();
            for (int i = from; i < Math.min(ordered.size(), from + size); i++) {
                DocAgg agg = ordered.get(i).getValue();
                hits.add(new SearchHit(ordered.get(i).getKey(),
                        agg.bestDoc.get("filename"), agg.bestDoc.get("path"), agg.bestDoc.get("type"),
                        snippet(highlighter, agg.bestDoc.get("content")),
                        agg.bestScore, SearchHit.SOURCE_TABLE, agg.matchCount));
            }
            return new SearchResponse(ordered.size(), hits, false);
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * 问答用：BM25 在指定 docId 范围内取 top 片段，附字符预算（整片段计，不在片段中间截断）。
     */
    public List<FragmentHit> topFragments(String q, Collection<String> docIds, int limit, int charBudget)
            throws IOException, ParseException {
        Query query = new MultiFieldQueryParser(SEARCH_FIELDS, queryAnalyzer).parse(q);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(query, BooleanClause.Occur.MUST);
        builder.add(new TermInSetQuery("docId",
                docIds.stream().map(BytesRef::new).toList()), BooleanClause.Occur.FILTER);
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs top = searcher.search(builder.build(), limit);
            List<FragmentHit> out = new ArrayList<>();
            int used = 0;
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                String content = doc.get("content");
                if (used + content.length() > charBudget) {
                    break;
                }
                used += content.length();
                out.add(new FragmentHit(doc.get("docId"), doc.get("filename"), doc.get("type"),
                        doc.get("kind"), doc.get("title"), content, sd.score));
            }
            return out;
        } finally {
            searcherManager.release(searcher);
        }
    }

    private static Highlighter newHighlighter(Query query) {
        Formatter formatter = new SimpleHTMLFormatter("<em>", "</em>");
        Highlighter highlighter = new Highlighter(formatter, new QueryScorer(query));
        // 整段高亮不切碎：markdown 表格被 fragmenter 腰斩后前端无法渲染
        highlighter.setTextFragmenter(new NullFragmenter());
        return highlighter;
    }

    /** 整段高亮；无命中（如仅文件名命中）回退原文，超长按行边界截断 */
    private String snippet(Highlighter highlighter, String content) throws IOException {
        String fragment = null;
        try {
            fragment = highlighter.getBestFragment(indexAnalyzer, "content", content);
        } catch (InvalidTokenOffsetsException e) {
            // 高亮偏移异常时退回原文
        }
        if (fragment == null) {
            fragment = content == null ? "" : content;
        }
        fragment = truncateAtLineBoundary(fragment, SNIPPET_MAX_CHARS);
        return DocumentSearcher.escapeKeepEm(fragment);
    }

    private static String truncateAtLineBoundary(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf('\n', maxChars);
        if (cut <= 0) {
            cut = maxChars;
        }
        return text.substring(0, cut) + "\n…（片段过长已截断）";
    }
}
