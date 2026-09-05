package com.docrag.indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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

import com.docrag.mode.Mode;
import com.docrag.searcher.DocumentDetail;
import com.docrag.searcher.ModeSearcher;
import com.docrag.searcher.RankedDoc;
import com.docrag.searcher.SearchHit;
import com.docrag.searcher.SearchResponse;
import com.docrag.searcher.StoreListItem;

/** deep 模式入库（统一文本）→ 检索 round-trip（一文一 Document，真实 IK + Lucene 磁盘索引） */
class DeepModeRoundTripTest {

    @TempDir
    Path indexDir;

    private Analyzer indexAnalyzer;
    private Analyzer queryAnalyzer;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private ModeIndexer indexer;
    private ModeSearcher searcher;

    @BeforeEach
    void setUp() throws IOException {
        indexAnalyzer = new IKAnalyzer(false);
        queryAnalyzer = new IKAnalyzer(true);
        writer = new IndexWriter(FSDirectory.open(indexDir), new IndexWriterConfig(indexAnalyzer));
        searcherManager = new SearcherManager(writer, new SearcherFactory());
        indexer = new ModeIndexer(Mode.DEEP, writer, searcherManager);
        // 指向封闭端口：单测必须确定性走「向量不可用 → 降级纯 BM25」路径
        var vectorProps = new com.docrag.config.DocRagProperties();
        vectorProps.setVectorServiceUrl("http://127.0.0.1:1");
        searcher = new ModeSearcher(Mode.DEEP, searcherManager, queryAnalyzer, indexAnalyzer,
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
    void searchIsDocLevelWithHighlightedMarkdownSnippet() throws Exception {
        indexer.index("d1", "预算表.xlsx", "/tmp/预算表.xlsx", "xlsx",
                "本年度预算总体说明。\n\n表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |");
        indexer.index("d2", "其它.docx", "/tmp/其它.docx", "docx", "完全无关的叙述内容。");

        SearchResponse resp = searcher.search("预算", 1, 10);
        assertEquals(1, resp.total(), "只有 d1 命中");
        SearchHit hit = resp.hits().get(0);
        assertEquals("d1", hit.docId());
        assertEquals("预算表.xlsx", hit.filename());
        assertEquals("bm25", hit.source(), "向量不可用降级 → source=bm25");
        assertTrue(resp.degraded(), "无 vector-service 时应标记 degraded");
        assertTrue(hit.snippet().contains("<em>"), "snippet 应有高亮: " + hit.snippet());
        assertTrue(hit.snippet().contains("|"), "snippet 保留 markdown 表格结构");
    }

    @Test
    void searchPaginatesByOffset() throws Exception {
        for (int i = 1; i <= 3; i++) {
            indexer.index("d" + i, "预算表" + i + ".xlsx", "/tmp/x" + i + ".xlsx", "xlsx",
                    "预算内容 " + i);
        }
        SearchResponse page1 = searcher.search("预算", 1, 2);
        SearchResponse page2 = searcher.search("预算", 2, 2);
        assertEquals(3, page1.total());
        assertEquals(2, page1.hits().size());
        assertEquals(3, page2.total());
        assertEquals(1, page2.hits().size(), "第 2 页应只剩 1 条（而非重复第 1 页）");
    }

    @Test
    void deleteRemovesDoc() throws Exception {
        indexer.index("d-del", "删表.xlsx", "/tmp/x.xlsx", "xlsx",
                "| 部门 | 待删除预算 |\n| --- | --- |\n| a | b |");
        assertEquals(1, searcher.search("待删除", 1, 10).total());
        indexer.delete("d-del");
        assertEquals(0, searcher.search("待删除", 1, 10).total());
        assertEquals(0, indexer.count());
    }

    @Test
    void reindexSameDocIsUpsertNotDuplicate() throws Exception {
        indexer.index("d-up", "重复入库.xlsx", "/tmp/x.xlsx", "xlsx",
                "| 部门 | 预算 |\n| --- | --- |\n| 销售部 | 1 |");
        indexer.index("d-up", "重复入库.xlsx", "/tmp/x.xlsx", "xlsx", "更新后的统一文本内容。");
        assertEquals(1, indexer.count(), "同 docId 重写是 upsert，不残留旧文档");
        assertEquals(1, searcher.search("更新后", 1, 10).total());
        assertEquals(0, searcher.search("销售部", 1, 10).total(), "旧 content 应被覆盖");
    }

    @Test
    void topDocsByDocIdsFiltersRange() throws Exception {
        indexer.index("d1", "预算表.xlsx", "/tmp/x.xlsx", "xlsx",
                "表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |");
        indexer.index("d2", "别家预算.xlsx", "/tmp/y.xlsx", "xlsx",
                "| 部门 | 预算 |\n| --- | --- |\n| b | 2 |");

        List<RankedDoc> ofD1 = searcher.topDocsByDocIds("预算", List.of("d1"), 10);
        assertEquals(1, ofD1.size());
        assertEquals("d1", ofD1.get(0).docId());
        assertTrue(ofD1.get(0).content().contains("销售部"), "召回取统一文本整篇");
        List<RankedDoc> ofD2 = searcher.topDocsByDocIds("预算", List.of("d2"), 10);
        assertEquals(1, ofD2.size());
        assertEquals("d2", ofD2.get(0).docId());
    }

    @Test
    void getByIdReturnsUnifiedText() throws Exception {
        indexer.index("d1", "预算表.xlsx", "/tmp/x.xlsx", "xlsx",
                "预算说明。\n\n表格 1\n| a | b |");
        DocumentDetail detail = searcher.getById("d1");
        assertNotNull(detail);
        assertEquals("预算表.xlsx", detail.filename());
        assertTrue(detail.content().contains("| a | b |"));
        assertNull(searcher.getById("missing"), "不存在返回 null（API 层转 404）");
    }

    @Test
    void listAllSortsByModifiedDesc() throws Exception {
        indexer.index("d1", "旧文档.xlsx", "/tmp/1.xlsx", "xlsx", "预算一");
        Thread.sleep(5); // 保证 modified 严格递增（同毫秒时会退化为 docId 字典序）
        indexer.index("d2", "新文档.xlsx", "/tmp/2.xlsx", "xlsx", "预算二");
        List<StoreListItem> items = searcher.listAll();
        assertEquals(2, items.size());
        assertEquals("d2", items.get(0).docId(), "modified 倒序");
        assertEquals("d1", items.get(1).docId());
        assertTrue(items.get(0).filename().equals("新文档.xlsx"));
    }
}
