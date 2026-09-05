package com.docrag.indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
import com.docrag.searcher.ModeSearcher;
import com.docrag.searcher.SearchHit;
import com.docrag.searcher.SearchResponse;
import com.docrag.vector.VectorClient;
import com.docrag.vector.VectorHit;
import com.docrag.vector.VectorStats;

/**
 * RRF 融合检索 round-trip：打桩 VectorClient（不发 HTTP），覆盖
 * both / vector-only / bm25-only / 向量不可用降级 四条路径。
 */
class FusionSearchRoundTripTest {

    @TempDir
    Path indexDir;

    /** 打桩向量：返回预设命中；可配置抛 IOException 模拟服务不可用 */
    static class FakeVector extends VectorClient {
        List<VectorHit> hits = new ArrayList<>();
        boolean fail = false;
        Mode lastQueryMode;

        FakeVector() {
            super(new com.docrag.config.DocRagProperties());
        }

        @Override
        public List<VectorHit> query(Mode mode, String text, int topK) throws IOException {
            lastQueryMode = mode;
            if (fail) {
                throw new IOException("模拟向量服务不可用");
            }
            return hits.subList(0, Math.min(hits.size(), topK));
        }

        @Override
        public boolean ping() {
            return !fail;
        }

        @Override
        public VectorStats stats() {
            return new VectorStats(!fail, null, "fake-bge");
        }
    }

    private Analyzer indexAnalyzer;
    private Analyzer queryAnalyzer;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private ModeIndexer indexer;
    private FakeVector vector;
    private ModeSearcher searcher;

    @BeforeEach
    void setUp() throws IOException {
        indexAnalyzer = new IKAnalyzer(false);
        queryAnalyzer = new IKAnalyzer(true);
        writer = new IndexWriter(FSDirectory.open(indexDir), new IndexWriterConfig(indexAnalyzer));
        searcherManager = new SearcherManager(writer, new SearcherFactory());
        indexer = new ModeIndexer(Mode.PLAIN, writer, searcherManager);
        vector = new FakeVector();
        searcher = new ModeSearcher(Mode.PLAIN, searcherManager, queryAnalyzer, indexAnalyzer, vector);
    }

    @AfterEach
    void tearDown() throws IOException {
        searcherManager.close();
        writer.close();
        indexAnalyzer.close();
        queryAnalyzer.close();
    }

    @Test
    void bothSourcesFuseToSourceBoth() throws Exception {
        // 同一文档两路都命中 → source=both
        indexer.index("d1", "劳动合同.docx", "/tmp/a.docx", "docx",
                "本合同条款约定双方的权利与义务。");
        vector.hits.add(new VectorHit("d1", "劳动合同.docx", "docx", "合同条款", 0.9));

        SearchResponse resp = searcher.search("合同", 1, 10);
        assertEquals(1, resp.total());
        assertEquals("both", resp.hits().get(0).source());
        assertTrue(!resp.degraded());
        assertEquals(Mode.PLAIN, vector.lastQueryMode, "应按模式查对应 collection");
        assertTrue(resp.hits().get(0).snippet().contains("<em>"), "两路命中时 snippet 仍取 BM25 高亮片段");
    }

    @Test
    void vectorOnlyHitFillsMetadataFromIndex() throws Exception {
        // 文档在索引中但不被 BM25 命中（仅文件名含查询词以外的路径）→ 仅向量召回
        indexer.index("d1", "年会通知.docx", "/tmp/a.docx", "docx",
                "公司年会定于下周五举办，请各部门安排节目。");
        vector.hits.add(new VectorHit("d1", "年会通知.docx", "docx", "团建活动安排", 0.8));

        SearchResponse resp = searcher.search("团建", 1, 10);
        assertEquals(1, resp.total(), "仅向量命中的文档也应出现在结果中");
        SearchHit hit = resp.hits().get(0);
        assertEquals("vector", hit.source());
        assertEquals("年会通知.docx", hit.filename(), "元数据从索引补全");
        assertEquals("/tmp/a.docx", hit.path());
        assertEquals("团建活动安排", hit.snippet(), "仅向量命中以 chunk 作 snippet");
    }

    @Test
    void vectorUnavailableDegradesToPureBm25() throws Exception {
        indexer.index("d1", "劳动合同.docx", "/tmp/a.docx", "docx", "本合同条款约定双方的权利与义务。");
        vector.fail = true;

        SearchResponse resp = searcher.search("合同", 1, 10);
        assertTrue(resp.degraded());
        assertEquals(1, resp.total());
        assertEquals("bm25", resp.hits().get(0).source());
    }

    @Test
    void rrfFusesRanksAcrossSources() throws Exception {
        // d1 仅 BM25 靠前、d2 两路命中 → RRF 累加后 d2 应排到 d1 之前
        indexer.index("d1", "甲.docx", "/tmp/1.docx", "docx", "预算管理原则与预算编制方法说明");
        indexer.index("d2", "乙.docx", "/tmp/2.docx", "docx", "预算执行与预算调整流程");
        vector.hits.add(new VectorHit("d2", "乙.docx", "docx", "预算流程", 0.85));

        SearchResponse resp = searcher.search("预算", 1, 10);
        assertEquals(2, resp.total());
        assertEquals("d2", resp.hits().get(0).docId(), "两路累加的 RRF 分应超过单路第一");
        assertEquals("both", resp.hits().get(0).source());
        assertEquals("d1", resp.hits().get(1).docId());
        assertEquals("bm25", resp.hits().get(1).source());
    }
}
