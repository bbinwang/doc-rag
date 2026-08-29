package com.docrag.indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wltea.analyzer.lucene.IKAnalyzer;

import com.docrag.searcher.DocumentSearcher;
import com.docrag.searcher.SearchResponse;

/** indexer 入库 → searcher 检索 round-trip（真实 IK 分词 + Lucene 磁盘索引） */
class SearchRoundTripTest {

    @TempDir
    Path indexDir;

    private Analyzer indexAnalyzer;
    private Analyzer queryAnalyzer;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private DocumentIndexer indexer;
    private DocumentSearcher searcher;

    @BeforeEach
    void setUp() throws IOException {
        // 与生产一致：索引细粒度（多召回），查询智能（贴近用户输入）
        indexAnalyzer = new IKAnalyzer(false);
        queryAnalyzer = new IKAnalyzer(true);
        writer = new IndexWriter(FSDirectory.open(indexDir), new IndexWriterConfig(indexAnalyzer));
        searcherManager = new SearcherManager(writer, new SearcherFactory());
        indexer = new DocumentIndexer(writer, searcherManager);
        // 指向封闭端口：本机可能真跑着 vector-service（含历史数据），会让 RRF 混入真实向量命中；
        // 单测必须确定性走「向量不可用 → 降级纯 BM25」路径
        var vectorProps = new com.docrag.config.DocRagProperties();
        vectorProps.setVectorServiceUrl("http://127.0.0.1:1");
        searcher = new DocumentSearcher(searcherManager, queryAnalyzer, indexAnalyzer,
                new com.docrag.vector.VectorClient(vectorProps));
    }

    @AfterEach
    void tearDown() throws IOException {
        searcherManager.close();
        writer.close();
        indexAnalyzer.close();
        queryAnalyzer.close();
    }

    @Test
    void indexThenSearchReturnsHighlightedSnippet() throws Exception {
        indexer.index("id-1", "劳动合同.docx", "/tmp/劳动合同.docx", "docx",
                "本合同条款约定双方的权利与义务，任何一方违约需支付违约金。");
        indexer.index("id-2", "预算表.xlsx", "/tmp/预算表.xlsx", "xlsx",
                "部门\t预算金额\n销售部\t100万");

        SearchResponse resp = searcher.search("合同", 1, 10);
        assertEquals(1, resp.total());
        assertEquals("id-1", resp.hits().get(0).docId());
        assertEquals("劳动合同.docx", resp.hits().get(0).filename());
        assertTrue(resp.hits().get(0).snippet().contains("<em>"),
                "snippet 应包含高亮标记: " + resp.hits().get(0).snippet());
        // 单测环境向量服务不可用 → 降级纯 BM25，source=bm25
        assertTrue(resp.degraded(), "无 vector-service 时应标记 degraded");
        assertEquals("bm25", resp.hits().get(0).source());

        SearchResponse resp2 = searcher.search("预算", 1, 10);
        assertEquals(1, resp2.total());
        assertEquals("预算表.xlsx", resp2.hits().get(0).filename());
    }

    @Test
    void deleteRemovesDocument() throws Exception {
        indexer.index("id-del", "删除测试.pdf", "/tmp/x.pdf", "pdf", "这是一份待删除的文档内容");
        assertEquals(1, searcher.search("待删除", 1, 10).total());
        indexer.delete("id-del");
        assertEquals(0, searcher.search("待删除", 1, 10).total());
    }

    @Test
    void noMatchReturnsEmpty() throws Exception {
        indexer.index("id-3", "a.docx", "/tmp/a.docx", "docx", "普通内容");
        assertEquals(0, searcher.search("完全不存在的词组xyzq", 1, 10).total());
    }

    @Test
    void getByIdReturnsIndexedRawText() throws Exception {
        indexer.index("id-full", "全文查看.docx", "/tmp/full.docx", "docx",
                "第一行原始文本\n第二行\t带制表符");
        var detail = searcher.getById("id-full");
        assertEquals("全文查看.docx", detail.filename());
        assertEquals("docx", detail.type());
        assertEquals("第一行原始文本\n第二行\t带制表符", detail.content());
        assertTrue(detail.modified() > 0);
        assertNull(searcher.getById("不存在的id"));
    }

    @Test
    void htmlInContentIsEscapedInSnippet() throws Exception {
        indexer.index("id-4", "风险披露.docx", "/tmp/r.docx", "docx",
                "警告 <script>alert(1)</script> 本风险披露含恶意标记");
        SearchResponse resp = searcher.search("风险", 1, 10);
        assertEquals(1, resp.total());
        String snippet = resp.hits().get(0).snippet();
        assertTrue(!snippet.contains("<script>"), "script 标签必须被转义: " + snippet);
        assertTrue(snippet.contains("&lt;script&gt;"));
    }
}
