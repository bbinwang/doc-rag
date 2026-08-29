package com.docrag.searcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Formatter;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.docrag.vector.VectorClient;
import com.docrag.vector.VectorHit;

/**
 * 混合检索：BM25（Lucene 多字段）+ 语义（vector-service）两路召回，
 * docId 级 RRF 融合排序。向量服务不可用时自动降级纯 BM25（degraded=true）。
 */
@Component
public class DocumentSearcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearcher.class);

    private static final String[] SEARCH_FIELDS = {"filename", "content"};
    private static final int FRAGMENT_CHARS = 160;
    /** 两路召回池大小（融合后再分页） */
    private static final int RRF_POOL = 50;
    /** RRF 平滑常数 */
    private static final int RRF_K = 60;

    private final SearcherManager searcherManager;
    /** 查询侧：智能切分，贴近用户输入 */
    private final Analyzer queryAnalyzer;
    /** 索引侧：细粒度切分，高亮时用它重切文本才能与索引 token 的 offset 对齐 */
    private final Analyzer indexAnalyzer;
    private final VectorClient vectorClient;

    public DocumentSearcher(@Qualifier("searcherManager") SearcherManager searcherManager,
                            @Qualifier("queryAnalyzer") Analyzer queryAnalyzer,
                            @Qualifier("indexAnalyzer") Analyzer indexAnalyzer,
                            VectorClient vectorClient) {
        this.searcherManager = searcherManager;
        this.queryAnalyzer = queryAnalyzer;
        this.indexAnalyzer = indexAnalyzer;
        this.vectorClient = vectorClient;
    }

    public SearchResponse search(String q, int page, int size) throws IOException, ParseException {
        Query query = new MultiFieldQueryParser(SEARCH_FIELDS, queryAnalyzer).parse(q);

        // ① BM25 召回（含高亮 snippet）
        Map<String, SearchHit> bm25ByDoc = new LinkedHashMap<>();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs top = searcher.search(query, RRF_POOL);
            Highlighter highlighter = newHighlighter(query);
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                bm25ByDoc.put(doc.get("id"), new SearchHit(
                        doc.get("id"), doc.get("filename"), doc.get("path"), doc.get("type"),
                        snippet(highlighter, doc.get("content")), sd.score, SearchHit.SOURCE_BM25));
            }
        } finally {
            searcherManager.release(searcher);
        }

        // ② 向量召回（不可用则降级）
        Map<String, VectorHit> vectorByDoc = new LinkedHashMap<>();
        boolean degraded = false;
        try {
            for (VectorHit hit : vectorClient.query(q, RRF_POOL)) {
                vectorByDoc.putIfAbsent(hit.docId(), hit); // 每 docId 取最靠前的 chunk
            }
        } catch (IOException e) {
            degraded = true;
            log.warn("向量检索降级为纯 BM25: {}", e.getMessage());
        }

        // ③ RRF 融合：score = Σ 1/(k + rank)
        Map<String, Double> rrf = new HashMap<>();
        int rank = 1;
        for (String id : bm25ByDoc.keySet()) {
            rrf.merge(id, 1.0 / (RRF_K + rank++), Double::sum);
        }
        rank = 1;
        for (String id : vectorByDoc.keySet()) {
            rrf.merge(id, 1.0 / (RRF_K + rank++), Double::sum);
        }
        List<String> ordered = rrf.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // ④ 融合后分页，组装结果
        int from = Math.max(0, (page - 1) * size);
        List<SearchHit> hits = new ArrayList<>();
        for (int i = from; i < Math.min(ordered.size(), from + size); i++) {
            String id = ordered.get(i);
            SearchHit bm = bm25ByDoc.get(id);
            VectorHit vh = vectorByDoc.get(id);
            float score = rrf.get(id).floatValue();
            if (bm != null) {
                String source = vh != null ? SearchHit.SOURCE_BOTH : SearchHit.SOURCE_BM25;
                hits.add(new SearchHit(id, bm.filename(), bm.path(), bm.type(),
                        bm.snippet(), score, source));
            } else {
                // 仅向量命中：chunk 作 snippet（转义无高亮），元数据从索引补全
                DocumentDetail detail = getById(id);
                hits.add(new SearchHit(id,
                        detail != null ? detail.filename() : vh.filename(),
                        detail != null ? detail.path() : "",
                        detail != null ? detail.type() : vh.type(),
                        escapeKeepEm(vh.chunk()), score, SearchHit.SOURCE_VECTOR));
            }
        }
        return new SearchResponse(ordered.size(), hits, degraded);
    }

    /** 按 docId 取索引库中的完整文档（原始纯文本），不存在返回 null */
    public DocumentDetail getById(String docId) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs top = searcher.search(new TermQuery(new Term("id", docId)), 1);
            if (top.scoreDocs.length == 0) {
                return null;
            }
            Document doc = searcher.doc(top.scoreDocs[0].doc);
            long modified = 0L;
            if (doc.getField("modified") != null && doc.getField("modified").numericValue() != null) {
                modified = doc.getField("modified").numericValue().longValue();
            }
            return new DocumentDetail(doc.get("id"), doc.get("filename"), doc.get("path"),
                    doc.get("type"), modified, doc.get("content"));
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * 问答用：BM25 在指定 docId 范围内召回整篇文档（按分排序）。
     * 只走倒排、不走向量——问答上下文的选取必须可在选中文件范围内解释。
     */
    public List<RankedDoc> topDocsByDocIds(String q, Collection<String> docIds, int topN)
            throws IOException, ParseException {
        Query query = new MultiFieldQueryParser(SEARCH_FIELDS, queryAnalyzer).parse(q);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(query, BooleanClause.Occur.MUST);
        builder.add(new TermInSetQuery("id",
                docIds.stream().map(BytesRef::new).toList()), BooleanClause.Occur.FILTER);
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs top = searcher.search(builder.build(), topN);
            List<RankedDoc> out = new ArrayList<>();
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                out.add(new RankedDoc(doc.get("id"), doc.get("filename"), doc.get("type"),
                        doc.get("content"), sd.score));
            }
            return out;
        } finally {
            searcherManager.release(searcher);
        }
    }

    private Highlighter newHighlighter(Query query) {
        Formatter formatter = new SimpleHTMLFormatter("<em>", "</em>");
        Highlighter highlighter = new Highlighter(formatter, new QueryScorer(query));
        highlighter.setTextFragmenter(new SimpleFragmenter(FRAGMENT_CHARS));
        return highlighter;
    }

    /** 取 content 最佳片段；无命中（如仅文件名命中）时回退为开头截断 */
    private String snippet(Highlighter highlighter, String content) throws IOException {
        String fragment = null;
        try {
            fragment = highlighter.getBestFragment(indexAnalyzer, "content", content);
        } catch (InvalidTokenOffsetsException e) {
            // 高亮偏移异常时退回截断片段
        }
        if (fragment == null) {
            fragment = content.length() <= FRAGMENT_CHARS
                    ? content
                    : content.substring(0, FRAGMENT_CHARS) + "…";
        }
        return escapeKeepEm(fragment);
    }

    /**
     * 对片段做 HTML 转义，但保留高亮标记。
     * Highlighter 输出为「文本 + 成对的 em 标签」交替结构，按标签切开后：
     * 偶数段是 <em>，奇数段是 </em>，其余为原文，仅对原文转义。
     */
    static String escapeKeepEm(String fragment) {
        String[] parts = fragment.split("</?em>", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            out.append(parts[i]
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;"));
            if (i < parts.length - 1) {
                out.append(i % 2 == 0 ? "<em>" : "</em>");
            }
        }
        return out.toString();
    }
}
