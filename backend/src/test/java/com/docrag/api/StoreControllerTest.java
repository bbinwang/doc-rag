package com.docrag.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

import com.docrag.config.DocRagProperties;
import com.docrag.indexer.ModeIndexer;
import com.docrag.mode.Mode;
import com.docrag.searcher.DocumentDetail;
import com.docrag.searcher.ModeSearcher;
import com.docrag.searcher.StoreListItem;
import com.docrag.vector.VectorClient;

/** 索引明细：双模式临时索引 + 封闭端口 vector（读路径不触发向量调用） */
class StoreControllerTest {

    @TempDir
    Path plainIndexDir;
    @TempDir
    Path deepIndexDir;

    private Analyzer indexAnalyzer;
    private Analyzer queryAnalyzer;
    private IndexWriter plainWriter;
    private SearcherManager plainSm;
    private IndexWriter deepWriter;
    private SearcherManager deepSm;
    private StoreController controller;

    @BeforeEach
    void setUp() throws IOException {
        indexAnalyzer = new IKAnalyzer(false);
        queryAnalyzer = new IKAnalyzer(true);
        plainWriter = new IndexWriter(FSDirectory.open(plainIndexDir), new IndexWriterConfig(indexAnalyzer));
        plainSm = new SearcherManager(plainWriter, new SearcherFactory());
        deepWriter = new IndexWriter(FSDirectory.open(deepIndexDir), new IndexWriterConfig(indexAnalyzer));
        deepSm = new SearcherManager(deepWriter, new SearcherFactory());

        new ModeIndexer(Mode.PLAIN, plainWriter, plainSm)
                .index("f1", "劳动合同.docx", "/tmp/a.docx", "docx", "合同条款全文");
        ModeIndexer deepIndexer = new ModeIndexer(Mode.DEEP, deepWriter, deepSm);
        deepIndexer.index("f1", "劳动合同.docx", "/tmp/a.docx", "docx",
                "合同正文。\n\n表格 1\n| 条款 | 内容 |");
        deepIndexer.index("t2", "预算表.xlsx", "/tmp/b.xlsx", "xlsx",
                "| 部门 | 金额 |\n| --- | --- |\n| 销售 | 1 |");

        Map<Mode, ModeSearcher> searchers = new EnumMap<>(Mode.class);
        // 指向封闭端口：本机可能真跑着 vector-service（含历史数据），明细读路径不应受其干扰
        DocRagProperties props = new DocRagProperties();
        props.setVectorServiceUrl("http://127.0.0.1:1");
        VectorClient closedPortVector = new VectorClient(props);
        for (Mode m : Mode.values()) {
            searchers.put(m, new ModeSearcher(m, m == Mode.PLAIN ? plainSm : deepSm,
                    queryAnalyzer, indexAnalyzer, closedPortVector));
        }
        controller = new StoreController(searchers);
    }

    @AfterEach
    void tearDown() throws IOException {
        plainSm.close();
        plainWriter.close();
        deepSm.close();
        deepWriter.close();
        indexAnalyzer.close();
        queryAnalyzer.close();
    }

    @Test
    void listPlainReturnsOnlyPlainIndexDocs() {
        var out = controller.listPlain();
        assertEquals(1, out.get("total"));
        @SuppressWarnings("unchecked")
        List<StoreListItem> docs = (List<StoreListItem>) out.get("docs");
        assertEquals("f1", docs.get(0).docId());
        assertEquals("劳动合同.docx", docs.get(0).filename());
    }

    @Test
    void listDeepReturnsAllDeepIndexDocs() {
        var out = controller.listDeep();
        assertEquals(2, out.get("total"));
        @SuppressWarnings("unchecked")
        List<StoreListItem> docs = (List<StoreListItem>) out.get("docs");
        assertTrue(docs.stream().anyMatch(d -> "t2".equals(d.docId())), String.valueOf(docs));
    }

    @Test
    void deepDocReturnsUnifiedText() throws IOException {
        DocumentDetail detail = controller.deepDoc("t2");
        assertEquals("预算表.xlsx", detail.filename());
        assertTrue(detail.content().contains("| --- | --- |"), detail.content());
    }

    @Test
    void deepDocMissingThrowsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> controller.deepDoc("missing"));
    }
}
