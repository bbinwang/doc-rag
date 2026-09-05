package com.docrag.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
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
import com.docrag.searcher.ModeSearcher;
import com.docrag.vector.VectorClient;
import com.docrag.vector.VectorStats;

/** 状态查询 + 一键清理：双模式临时索引 + 封闭端口 vector（不可用路径） */
class StatusControllerTest {

    @TempDir
    Path plainIndexDir;
    @TempDir
    Path deepIndexDir;
    @TempDir
    Path uploadDir;

    private Analyzer indexAnalyzer;
    private Analyzer queryAnalyzer;
    private IndexWriter plainWriter;
    private SearcherManager plainSm;
    private IndexWriter deepWriter;
    private SearcherManager deepSm;
    private Map<Mode, ModeIndexer> indexers;
    private DocRagProperties props;
    private StatusController controller;

    @BeforeEach
    void setUp() throws IOException {
        indexAnalyzer = new IKAnalyzer(false);
        queryAnalyzer = new IKAnalyzer(true);
        plainWriter = new IndexWriter(FSDirectory.open(plainIndexDir), new IndexWriterConfig(indexAnalyzer));
        plainSm = new SearcherManager(plainWriter, new SearcherFactory());
        deepWriter = new IndexWriter(FSDirectory.open(deepIndexDir), new IndexWriterConfig(indexAnalyzer));
        deepSm = new SearcherManager(deepWriter, new SearcherFactory());
        indexers = new EnumMap<>(Mode.class);
        indexers.put(Mode.PLAIN, new ModeIndexer(Mode.PLAIN, plainWriter, plainSm));
        indexers.put(Mode.DEEP, new ModeIndexer(Mode.DEEP, deepWriter, deepSm));

        // 指向封闭端口：本机可能真跑着 vector-service（含历史数据），状态/清理单测必须
        // 确定性走「向量不可用」路径，否则真实服务状态会混入断言
        props = new DocRagProperties();
        props.setVectorServiceUrl("http://127.0.0.1:1");
        props.setUploadDir(uploadDir.toString());
        controller = new StatusController(indexers, new VectorClient(props), props);
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
    void statusReportsCountsAndVectorOffline() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx", "合同条款内容");
        indexers.get(Mode.PLAIN).index("d2", "预算.xlsx", "/tmp/b.xlsx", "xlsx", "预算内容");
        indexers.get(Mode.DEEP).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx",
                "正文片段\n\n表格 1\n| a | b |\n| --- | --- |\n| 1 | 2 |");
        Files.writeString(uploadDir.resolve("sample.docx"), "fake");

        Map<String, Object> status = controller.status();
        assertEquals(2, ((Map<?, ?>) status.get("plainIndex")).get("docs"));
        assertEquals(1, ((Map<?, ?>) status.get("deepIndex")).get("docs"));
        assertEquals(1, status.get("uploads"));
        Map<?, ?> vector = (Map<?, ?>) status.get("vector");
        assertEquals(false, vector.get("available"));
        assertTrue(vector.get("vectors") == null, "不可达时 vectors 应为 null");
    }

    @Test
    void clearRefusedWhenVectorUnavailable() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx", "合同条款内容");
        Files.writeString(uploadDir.resolve("sample.docx"), "fake");

        var resp = controller.clear();
        assertEquals(400, resp.getStatusCode().value());
        assertTrue(resp.getBody().get("error").toString().contains("vector-service"),
                "拒绝原因应指向 vector-service: " + resp.getBody());
        // 拒绝时不得动任何库：倒排仍可检索、原文件仍在
        ModeSearcher plainSearcher = new ModeSearcher(Mode.PLAIN, plainSm, queryAnalyzer,
                indexAnalyzer, new VectorClient(props));
        assertEquals(1, plainSearcher.search("合同", 1, 10).total(), "拒绝清理时索引应保持原样");
        assertTrue(Files.exists(uploadDir.resolve("sample.docx")), "拒绝清理时原文件应保留");
    }

    /** 打桩 vector：可用且全清成功（不发起真实 HTTP） */
    static class FakeVector extends VectorClient {
        FakeVector() {
            super(new DocRagProperties());
        }

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public VectorStats stats() {
            return new VectorStats(true, Map.of("plain", 0, "deep", 0), "fake-bge");
        }

        @Override
        public void clearAll() {
            // 全清由单测断言各自库状态，无需动作
        }
    }

    @Test
    void clearWipesAllStoresAndUploadFiles() throws Exception {
        StatusController fullController = new StatusController(indexers, new FakeVector(), props);
        indexers.get(Mode.PLAIN).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx", "合同条款内容");
        indexers.get(Mode.DEEP).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx",
                "表格 1\n| a | b |\n| --- | --- |\n| 1 | 2 |");
        Files.writeString(uploadDir.resolve("合同.docx"), "fake");
        Files.writeString(uploadDir.resolve("预算.xlsx"), "fake");

        var resp = fullController.clear();
        assertEquals(200, resp.getStatusCode().value());
        // 四项清理范围：向量 + 两模式倒排 + 上传原文件
        assertEquals(List.of("vector", "plain", "deep", "uploads"), resp.getBody().get("cleared"));
        assertEquals(0, resp.getBody().get("uploads"), "原文件计数归零");
        assertEquals(0, indexers.get(Mode.PLAIN).count());
        assertEquals(0, indexers.get(Mode.DEEP).count());
        // upload 目录本身保留、内部常规文件全删
        assertTrue(Files.isDirectory(uploadDir));
        try (var stream = Files.list(uploadDir)) {
            assertEquals(0, stream.count());
        }
    }

    @Test
    void clearAllEmptiesBothIndexesForReuse() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx", "合同条款内容");
        indexers.get(Mode.DEEP).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx",
                "表格 1\n| a | b |\n| --- | --- |\n| 1 | 2 |");
        assertEquals(1, indexers.get(Mode.PLAIN).count());
        assertEquals(1, indexers.get(Mode.DEEP).count());

        indexers.get(Mode.PLAIN).clearAll();
        indexers.get(Mode.DEEP).clearAll();

        assertEquals(0, indexers.get(Mode.PLAIN).count());
        assertEquals(0, indexers.get(Mode.DEEP).count());
        ModeSearcher plainSearcher = new ModeSearcher(Mode.PLAIN, plainSm, queryAnalyzer,
                indexAnalyzer, new VectorClient(props));
        ModeSearcher deepSearcher = new ModeSearcher(Mode.DEEP, deepSm, queryAnalyzer,
                indexAnalyzer, new VectorClient(props));
        assertEquals(0, plainSearcher.search("合同", 1, 10).total());
        assertEquals(0, deepSearcher.search("表格", 1, 10).total());
    }
}
