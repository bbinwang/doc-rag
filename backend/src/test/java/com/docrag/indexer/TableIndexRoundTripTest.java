package com.docrag.indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.docrag.searcher.FragmentHit;
import com.docrag.searcher.SearchHit;
import com.docrag.searcher.SearchResponse;
import com.docrag.searcher.TableSearcher;
import com.docrag.tablemd.TableFragment;

/** 表格索引 TableIndexer 入库 → TableSearcher 检索 round-trip（真实 IK 分词 + Lucene 磁盘索引） */
class TableIndexRoundTripTest {

    @TempDir
    Path indexDir;

    private Analyzer indexAnalyzer;
    private Analyzer queryAnalyzer;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private TableIndexer indexer;
    private TableSearcher searcher;

    @BeforeEach
    void setUp() throws IOException {
        indexAnalyzer = new IKAnalyzer(false);
        queryAnalyzer = new IKAnalyzer(true);
        writer = new IndexWriter(FSDirectory.open(indexDir), new IndexWriterConfig(indexAnalyzer));
        searcherManager = new SearcherManager(writer, new SearcherFactory());
        indexer = new TableIndexer(writer, searcherManager);
        searcher = new TableSearcher(searcherManager, queryAnalyzer, indexAnalyzer);
    }

    @AfterEach
    void tearDown() throws IOException {
        searcherManager.close();
        writer.close();
        indexAnalyzer.close();
        queryAnalyzer.close();
    }

    @Test
    void searchAggregatesByDocIdWithMarkdownSnippet() throws Exception {
        indexer.index("d1", "预算表.xlsx", "/tmp/预算表.xlsx", "xlsx", List.of(
                TableFragment.table("Sheet1",
                        "| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |"),
                TableFragment.text("正文", "这是本年度预算的总体说明。")));
        indexer.index("d2", "其它.docx", "/tmp/其它.docx", "docx",
                List.of(TableFragment.text("正文", "完全无关的叙述内容。")));

        SearchResponse resp = searcher.search("预算", 1, 10);
        assertEquals(1, resp.total(), "只有 d1 命中");
        SearchHit hit = resp.hits().get(0);
        assertEquals("d1", hit.docId());
        assertEquals("预算表.xlsx", hit.filename());
        assertEquals("table", hit.source());
        assertEquals(2, hit.matchCount(), "d1 两个片段都含「预算」");
        assertTrue(hit.snippet().contains("<em>"), "snippet 应有高亮: " + hit.snippet());
        assertTrue(hit.snippet().contains("|"), "snippet 保留 markdown 表格结构");
        assertTrue(!resp.degraded());
    }

    @Test
    void deleteCascadesAllFragmentsOfDoc() throws Exception {
        indexer.index("d-del", "删表.xlsx", "/tmp/x.xlsx", "xlsx", List.of(
                TableFragment.table("Sheet1", "| 部门 | 待删除预算 |\n| --- | --- |\n| a | b |")));
        assertEquals(1, searcher.search("待删除", 1, 10).total());
        indexer.delete("d-del");
        assertEquals(0, searcher.search("待删除", 1, 10).total());
    }

    @Test
    void reindexSameDocIsIdempotent() throws Exception {
        var fragments = List.of(TableFragment.table("Sheet1",
                "| 部门 | 预算 |\n| --- | --- |\n| 销售部 | 1 |"));
        indexer.index("d-up", "重复入库.xlsx", "/tmp/x.xlsx", "xlsx", fragments);
        indexer.index("d-up", "重复入库.xlsx", "/tmp/x.xlsx", "xlsx", fragments);
        SearchResponse resp = searcher.search("预算", 1, 10);
        assertEquals(1, resp.total());
        assertEquals(1, resp.hits().get(0).matchCount(), "重写不残留旧片段");
    }

    @Test
    void topFragmentsFiltersByDocIdsAndBudget() throws Exception {
        indexer.index("d1", "预算表.xlsx", "/tmp/x.xlsx", "xlsx", List.of(
                TableFragment.table("Sheet1",
                        "| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |"),
                TableFragment.text("正文", "本年度预算说明。")));
        indexer.index("d2", "别家预算.xlsx", "/tmp/y.xlsx", "xlsx", List.of(
                TableFragment.table("Sheet1", "| 部门 | 预算 |\n| --- | --- |\n| b | 2 |")));

        // docId 过滤：只取 d1 的片段
        List<FragmentHit> ofD1 = searcher.topFragments("预算", List.of("d1"), 10, 100_000);
        assertEquals(2, ofD1.size());
        ofD1.forEach(f -> assertEquals("d1", f.docId()));

        // 字符预算：给不够放任何片段的预算 → 空
        assertTrue(searcher.topFragments("预算", List.of("d1"), 10, 5).isEmpty());

        // 未选中的文档不出现
        List<FragmentHit> ofD2 = searcher.topFragments("预算", List.of("d2"), 10, 100_000);
        assertEquals(1, ofD2.size());
        assertEquals("d2", ofD2.get(0).docId());
    }
}
