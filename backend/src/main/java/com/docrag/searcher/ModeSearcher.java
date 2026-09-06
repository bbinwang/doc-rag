package com.docrag.searcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
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
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;

import com.docrag.indexer.Chunker;
import com.docrag.mode.Mode;
import com.docrag.vector.VectorClient;
import com.docrag.vector.VectorHit;

/**
 * 双模式共用的混合检索（plain / deep 各一个实例）：
 * 检索页 search() 做 docId 级 BM25+向量 RRF 融合 + 高亮；
 * 问答 recallChunks() 做 chunk 级混合召回（全库，不限 docIds）。
 * 向量服务不可用时该模式自动降级纯 BM25（degraded=true）。
 * 实例由 LuceneConfig 的 @Bean 工厂产出（qualifier 收敛在 config 一个文件内）。
 */
public class ModeSearcher {

    private static final Logger log = LoggerFactory.getLogger(ModeSearcher.class);

    private static final String[] SEARCH_FIELDS = {"filename", "content"};
    private static final int FRAGMENT_CHARS = 160;
    /** 两路召回池大小（融合后再分页） */
    private static final int RRF_POOL = 50;
    /** RRF 平滑常数 */
    private static final int RRF_K = 60;
    /** 问答 BM25 路文档召回池下限（切块前至少召回的文档数） */
    private static final int BM25_DOC_POOL_MIN = 5;
    /** 问答 BM25 路文档召回池上限 */
    private static final int BM25_DOC_POOL_MAX = 20;
    /** deep 统一文本中表格标题行的样式（deepmd 约定：`表格 N` 紧贴 markdown 表格） */
    private static final Pattern TABLE_TITLE = Pattern.compile("^表格 \\d+$");

    private final Mode mode;
    private final SearcherManager searcherManager;
    /** 查询侧：智能切分，贴近用户输入 */
    private final Analyzer queryAnalyzer;
    /** 索引侧：细粒度切分，高亮时用它重切文本才能与索引 token 的 offset 对齐 */
    private final Analyzer indexAnalyzer;
    private final VectorClient vectorClient;

    public ModeSearcher(Mode mode, SearcherManager searcherManager,
                        Analyzer queryAnalyzer, Analyzer indexAnalyzer, VectorClient vectorClient) {
        this.mode = mode;
        this.searcherManager = searcherManager;
        this.queryAnalyzer = queryAnalyzer;
        this.indexAnalyzer = indexAnalyzer;
        this.vectorClient = vectorClient;
    }

    public Mode mode() {
        return mode;
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

        // ② 向量召回（查该模式的 collection；不可用则该模式降级）
        Map<String, VectorHit> vectorByDoc = new LinkedHashMap<>();
        boolean degraded = false;
        try {
            for (VectorHit hit : vectorClient.query(mode, q, RRF_POOL)) {
                vectorByDoc.putIfAbsent(hit.docId(), hit); // 每 docId 取最靠前的 chunk
            }
        } catch (IOException e) {
            degraded = true;
            log.warn("[{}] 向量检索降级为纯 BM25: {}", mode, e.getMessage());
        }

        // ③ RRF 融合：score = Σ 1/(k + rank)
        Map<String, Double> rrf = new HashMap<>();
        accumulateRrf(rrf, bm25ByDoc.keySet());
        accumulateRrf(rrf, vectorByDoc.keySet());
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

    /**
     * 问答用：单模式 chunk 级混合召回（全库，不限 docIds）。
     *
     * <p>BM25 路：全库 doc 级召回 pool=clamp(bm25Chunks, 5, 20) 篇 → 按与入库一致的
     * 切块策略查询时切块（plain=Chunker.chunk、deep=chunkKeepingTables(256)，保证与
     * 向量库 chunk 逐字节同源）→ 与问题分词重叠度排序（score&gt;0）取前 bm25Chunks 个；
     * 向量路：vectorClient.query(mode, q, vectorChunks)（不可用→degraded=true）。
     * 两路按 (docId, chunk 精确文本) 去重后做 chunk 级 RRF（k=60，与 search() 同常数）
     * 融合，返回全部融合结果（截断到 contextChunks 与字符预算由调用方负责）。</p>
     */
    public ChunkRecall recallChunks(String q, int bm25Chunks, int vectorChunks)
            throws IOException, ParseException {
        // ① BM25 路：doc 召回 → 入库同款切块 → overlap 降序、doc rank 升序取前 N
        Set<String> questionTerms = terms(q.trim(), queryAnalyzer);
        List<ScoredChunk> scored = new ArrayList<>();
        List<RankedDoc> docs = topDocsWithContent(q,
                Math.min(BM25_DOC_POOL_MAX, Math.max(BM25_DOC_POOL_MIN, bm25Chunks)));
        for (int d = 0; d < docs.size(); d++) {
            RankedDoc doc = docs.get(d);
            for (String text : chunkForMode(doc.content())) {
                int score = overlap(text, questionTerms);
                if (score > 0) {
                    scored.add(new ScoredChunk(new RecalledChunk(doc.docId(), doc.filename(),
                            doc.type(), doc.path(), tableTitle(text), text,
                            RecalledChunk.SOURCE_BM25, 0f), score, d));
                }
            }
        }
        scored.sort(Comparator.comparingInt(ScoredChunk::score).reversed()
                .thenComparingInt(ScoredChunk::docRank));
        List<RecalledChunk> bm25 = scored.stream()
                .limit(bm25Chunks)
                .map(ScoredChunk::chunk)
                .toList();

        // ② 向量路（不可用则该模式降级）
        List<VectorHit> vectorHits = List.of();
        boolean degraded = false;
        try {
            vectorHits = vectorClient.query(mode, q, vectorChunks);
        } catch (IOException e) {
            degraded = true;
            log.warn("[{}] 问答向量召回降级为纯 BM25: {}", mode, e.getMessage());
        }

        // ③ chunk 级 RRF：key = docId + NUL + chunk 文本（切块策略与入库一致，两路可精确去重）
        Map<String, Double> rrf = new HashMap<>();
        Map<String, RecalledChunk> byKey = new LinkedHashMap<>();
        Set<String> bm25Keys = new HashSet<>();
        for (RecalledChunk c : bm25) {
            String key = chunkKey(c.docId(), c.text());
            bm25Keys.add(key);
            byKey.putIfAbsent(key, c);
        }
        Set<String> vectorKeys = new HashSet<>();
        for (VectorHit h : vectorHits) {
            String key = chunkKey(h.docId(), h.chunk());
            vectorKeys.add(key);
            byKey.putIfAbsent(key, new RecalledChunk(h.docId(), h.filename(), h.type(),
                    "", tableTitle(h.chunk()), h.chunk(), RecalledChunk.SOURCE_VECTOR, 0f));
        }
        accumulateRrf(rrf, bm25Keys);
        accumulateRrf(rrf, vectorKeys);

        // ④ 组装：仅向量命中的文档回读索引补 path（有界 ≤ vectorChunks 个 docId）
        Map<String, String> pathByDoc = new HashMap<>();
        for (VectorHit h : vectorHits) {
            if (!pathByDoc.containsKey(h.docId()) && !hasBm25Doc(bm25, h.docId())) {
                DocumentDetail detail = getById(h.docId());
                pathByDoc.put(h.docId(), detail != null ? detail.path() : "");
            }
        }
        List<RecalledChunk> fused = new ArrayList<>();
        for (Map.Entry<String, RecalledChunk> e : byKey.entrySet()) {
            String key = e.getKey();
            boolean inBm25 = bm25Keys.contains(key);
            boolean inVector = vectorKeys.contains(key);
            String source = inBm25 && inVector ? RecalledChunk.SOURCE_BOTH
                    : inBm25 ? RecalledChunk.SOURCE_BM25 : RecalledChunk.SOURCE_VECTOR;
            RecalledChunk c = e.getValue();
            String path = c.path() == null || c.path().isEmpty()
                    ? pathByDoc.getOrDefault(c.docId(), "") : c.path();
            fused.add(new RecalledChunk(c.docId(), c.filename(), c.type(), path,
                    c.title(), c.text(), source, rrf.get(key).floatValue()));
        }
        fused.sort(Comparator.comparingDouble(RecalledChunk::score).reversed());
        return new ChunkRecall(fused, degraded);
    }

    /** 按 docId 取该模式索引库中的完整文档（入库原始文本），不存在返回 null */
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

    /** 明细页：全部文档（modified 倒序） */
    public List<StoreListItem> listAll() {
        return StoreListing.listAll(searcherManager);
    }

    /** RRF 贡献累加：按传入序排名（1 起），score += 1/(RRF_K + rank) */
    private static void accumulateRrf(Map<String, Double> scores, Iterable<String> keys) {
        int rank = 1;
        for (String key : keys) {
            scores.merge(key, 1.0 / (RRF_K + rank++), Double::sum);
        }
    }

    /** 全库 BM25 召回整篇文档（按分排序，含存储 content），供问答切块 */
    private List<RankedDoc> topDocsWithContent(String q, int topN)
            throws IOException, ParseException {
        Query query = new MultiFieldQueryParser(SEARCH_FIELDS, queryAnalyzer).parse(q);
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs top = searcher.search(query, topN);
            List<RankedDoc> out = new ArrayList<>();
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                out.add(new RankedDoc(doc.get("id"), doc.get("filename"), doc.get("type"),
                        doc.get("path"), doc.get("content"), sd.score));
            }
            return out;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /** 与入库一致的 per-mode 切块（IngestService 契约：两路 chunk 同源，精确去重的前提） */
    private List<String> chunkForMode(String content) {
        return mode == Mode.DEEP
                ? Chunker.chunkKeepingTables(content, Chunker.DEEP_EMBED_MAX_CHARS)
                : Chunker.chunk(content);
    }

    /** deep 模式表格块标题（首行形如「表格 N」时取该行），其余返回 null */
    private String tableTitle(String text) {
        if (mode != Mode.DEEP) {
            return null;
        }
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        return TABLE_TITLE.matcher(first.trim()).matches() ? first.trim() : null;
    }

    private static boolean hasBm25Doc(List<RecalledChunk> bm25, String docId) {
        for (RecalledChunk c : bm25) {
            if (c.docId().equals(docId)) {
                return true;
            }
        }
        return false;
    }

    private static String chunkKey(String docId, String text) {
        return docId + " " + text;
    }

    /** BM25 路 chunk 排序用：重叠分 + 所在文档的召回排名 */
    private record ScoredChunk(RecalledChunk chunk, int score, int docRank) {
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

    /** 分词取词项集合（查询侧智能切分，贴近问题表述） */
    static Set<String> terms(String text, Analyzer analyzer) throws IOException {
        Set<String> out = new HashSet<>();
        try (TokenStream ts = analyzer.tokenStream("content", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(term.toString());
            }
            ts.end();
        }
        return out;
    }

    /** chunk（索引侧细粒度切分）与问题词项的重叠次数 */
    private int overlap(String text, Set<String> questionTerms) throws IOException {
        if (questionTerms.isEmpty()) {
            return 0;
        }
        int count = 0;
        try (TokenStream ts = indexAnalyzer.tokenStream("content", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                if (questionTerms.contains(term.toString())) {
                    count++;
                }
            }
            ts.end();
        }
        return count;
    }
}
