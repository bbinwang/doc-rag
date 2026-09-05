package com.docrag.indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import com.docrag.deepmd.DeepDocument;
import com.docrag.deepmd.DeepmdExtractor;
import com.docrag.deepmd.DeepmdRouter;
import com.docrag.mode.Mode;
import com.docrag.parser.DocumentParseException;
import com.docrag.parser.DocumentParser;
import com.docrag.parser.ParserRouter;
import com.docrag.searcher.ModeSearcher;
import com.docrag.vector.VectorClient;
import com.docrag.vector.VectorStats;

/**
 * IngestService 入库编排：打桩 parser/deepmd/vector（不发 HTTP），覆盖
 * 双模式成功 / 仅单模式 / deep 向量失败回滚矩阵 / 解析失败零写入。
 */
class IngestServiceTest {

    @TempDir
    Path plainDir;
    @TempDir
    Path deepDir;

    /** 打桩纯文本解析：固定返回注入文本 */
    static class FakeParser implements DocumentParser {
        final String text;

        FakeParser(String text) {
            this.text = text;
        }

        @Override
        public boolean supports(String ext) {
            return "docx".equals(ext);
        }

        @Override
        public String parse(java.io.InputStream in) throws DocumentParseException {
            return text;
        }
    }

    /** 打桩深度提取：固定返回统一文本 + 表格数；可配置抛异常模拟坏文件 */
    static class FakeDeepmd implements DeepmdExtractor {
        final DeepDocument doc;
        boolean fail = false;

        FakeDeepmd(DeepDocument doc) {
            this.doc = doc;
        }

        @Override
        public boolean supports(String ext) {
            return "docx".equals(ext);
        }

        @Override
        public DeepDocument extract(java.io.InputStream in) throws DocumentParseException {
            if (fail) {
                throw new DocumentParseException("模拟损坏文件");
            }
            return doc;
        }
    }

    /** 打桩向量：记录 upsert/delete 调用；可配置 upsert 抛异常模拟写库失败 */
    static class FakeVector extends VectorClient {
        final List<String> upserts = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        boolean failUpsert = false;

        FakeVector() {
            super(new com.docrag.config.DocRagProperties());
        }

        @Override
        public int upsert(Mode mode, String docId, String filename, String type, List<String> chunks) {
            if (failUpsert && mode == Mode.DEEP) {
                throw new RuntimeException("模拟 vector-service 写入失败");
            }
            upserts.add(mode + ":" + docId + ":" + chunks.size());
            return chunks.size();
        }

        @Override
        public void delete(String docId, Mode mode) {
            deletes.add((mode == null ? "all" : mode.id()) + ":" + docId);
        }

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public VectorStats stats() {
            return new VectorStats(true, null, "fake-bge");
        }
    }

    private Analyzer indexAnalyzer;
    private IndexWriter plainWriter;
    private IndexWriter deepWriter;
    private SearcherManager plainSm;
    private SearcherManager deepSm;
    private Map<Mode, ModeIndexer> indexers;
    private FakeVector vector;
    private FakeDeepmd deepmd;
    private IngestService service;

    @BeforeEach
    void setUp() throws IOException {
        indexAnalyzer = new IKAnalyzer(false);
        plainWriter = new IndexWriter(FSDirectory.open(plainDir), new IndexWriterConfig(indexAnalyzer));
        deepWriter = new IndexWriter(FSDirectory.open(deepDir), new IndexWriterConfig(indexAnalyzer));
        plainSm = new SearcherManager(plainWriter, new SearcherFactory());
        deepSm = new SearcherManager(deepWriter, new SearcherFactory());
        indexers = new EnumMap<>(Mode.class);
        indexers.put(Mode.PLAIN, new ModeIndexer(Mode.PLAIN, plainWriter, plainSm));
        indexers.put(Mode.DEEP, new ModeIndexer(Mode.DEEP, deepWriter, deepSm));
        vector = new FakeVector();
        deepmd = new FakeDeepmd(new DeepDocument("统一文本正文。\n\n表格 1\n| a | b |", 1));
        service = new IngestService(
                new ParserRouter(List.of(new FakeParser("纯文本正文内容。"))),
                new DeepmdRouter(List.of(deepmd)),
                indexers,
                vector);
    }

    @AfterEach
    void tearDown() throws IOException {
        plainSm.close();
        plainWriter.close();
        deepSm.close();
        deepWriter.close();
        indexAnalyzer.close();
    }

    private static java.io.InputStream stream() {
        return new ByteArrayInputStream("fake-bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void dualModeWritesBothInvertedAndBothVectors() throws Exception {
        var result = service.ingest("d1", "报告.docx", "/tmp/a.docx", "docx",
                stream(), Set.of(Mode.PLAIN, Mode.DEEP));

        assertEquals(1, indexers.get(Mode.PLAIN).count());
        assertEquals(1, indexers.get(Mode.DEEP).count());
        // 两模式各自独立切块入库（deep 统一文本按块切：正文一块 + 表格一块）
        assertEquals(List.of("plain:d1:1", "deep:d1:2"), vector.upserts);
        assertEquals(Map.of("plain", 1, "deep", 2), result.chunkCounts());
        assertEquals(1, result.tableCount(), "deep 模式返回 markdown 表格数");
    }

    @Test
    void plainOnlySkipsDeepExtractionAndDeepVector() throws Exception {
        var result = service.ingest("d2", "报告.docx", "/tmp/a.docx", "docx",
                stream(), Set.of(Mode.PLAIN));

        assertEquals(1, indexers.get(Mode.PLAIN).count());
        assertEquals(0, indexers.get(Mode.DEEP).count(), "未选 deep 不应写 deep 倒排");
        assertEquals(List.of("plain:d2:1"), vector.upserts);
        assertNull(result.tableCount(), "未选 deep 时 tableCount 为 null");
    }

    @Test
    void dualModeParsesEachModeFromFreshStream() throws Exception {
        // 回归：同一上传流被 plain 消费后，deep 必须仍能从头解析（而非读到 EOF）
        byte[] pdfBytes = "%PDF-1.4 fake body".getBytes(StandardCharsets.UTF_8);
        FakeParser countingParser = new FakeParser("纯文本正文内容。") {
            @Override
            public String parse(java.io.InputStream in) throws DocumentParseException {
                try {
                    byte[] all = in.readAllBytes();
                    if (all.length != pdfBytes.length) {
                        throw new DocumentParseException("流已被消费过，剩余 " + all.length);
                    }
                } catch (IOException e) {
                    throw new DocumentParseException("读取失败: " + e.getMessage());
                }
                return text;
            }
        };
        service = new IngestService(new ParserRouter(List.of(countingParser)),
                new DeepmdRouter(List.of(deepmd)), indexers, vector);

        var result = service.ingest("d7", "报告.docx", "/tmp/a.docx", "docx",
                new ByteArrayInputStream(pdfBytes), Set.of(Mode.PLAIN, Mode.DEEP));
        assertEquals(1, result.chunkCounts().get("plain"));
    }

    @Test
    void deepOnlySkipsPlain() throws Exception {
        service.ingest("d3", "报告.docx", "/tmp/a.docx", "docx", stream(), Set.of(Mode.DEEP));

        assertEquals(0, indexers.get(Mode.PLAIN).count());
        assertEquals(1, indexers.get(Mode.DEEP).count());
        assertEquals(List.of("deep:d3:2"), vector.upserts);
    }

    @Test
    void deepVectorFailureRollsBackEverything() throws Exception {
        vector.failUpsert = true;

        var e = assertThrows(IllegalStateException.class, () ->
                service.ingest("d4", "报告.docx", "/tmp/a.docx", "docx",
                        stream(), Set.of(Mode.PLAIN, Mode.DEEP)));

        assertTrue(e.getMessage().contains("已回滚"), e.getMessage());
        // 回滚矩阵：deep 向量在最后一步失败，此前已写 plain/deep 倒排 + plain 向量；
        // 倒排回滚走 indexer.delete（numDocs 归零断言），向量回滚只有已写成功的 plain
        assertEquals(0, indexers.get(Mode.PLAIN).count());
        assertEquals(0, indexers.get(Mode.DEEP).count());
        assertEquals(List.of("plain:d4"), vector.deletes, "只撤销已写成功的 plain 向量: " + vector.deletes);
    }

    @Test
    void parseFailureWritesNothing() throws Exception {
        deepmd.fail = true;

        assertThrows(DocumentParseException.class, () ->
                service.ingest("d5", "报告.docx", "/tmp/a.docx", "docx",
                        stream(), Set.of(Mode.PLAIN, Mode.DEEP)));

        // fail-fast：解析阶段失败，任何库都不应有写入痕迹
        assertEquals(0, indexers.get(Mode.PLAIN).count());
        assertEquals(0, indexers.get(Mode.DEEP).count());
        assertEquals(List.of(), vector.upserts);
        assertEquals(List.of(), vector.deletes);
    }

    @Test
    void deepChunksUseBlockAwareChunkingKeepingTableWhole() throws Exception {
        // 表格块（含标题行）超出 256 字符也不被按行拆开
        String row = "| 项目" + "甲乙丙丁".repeat(20) + " | 值 |\n";
        StringBuilder unified = new StringBuilder("说明。\n\n表格 1\n| 项目 | 值 |\n| --- | --- |\n");
        for (int i = 0; i < 3; i++) {
            unified.append(row);
        }
        deepmd = new FakeDeepmd(new DeepDocument(unified.toString(), 1));
        service = new IngestService(
                new ParserRouter(List.of(new FakeParser("纯文本正文内容。"))),
                new DeepmdRouter(List.of(deepmd)),
                indexers,
                vector);

        var result = service.ingest("d6", "报告.docx", "/tmp/a.docx", "docx",
                stream(), Set.of(Mode.DEEP));

        int deepChunks = result.chunkCounts().get("deep");
        assertTrue(deepChunks >= 1);
        // 表格整块语义：含标题行的表格块不被按行拆碎，标题行与表格保留在同一 chunk
        for (String chunk : Chunker.chunkKeepingTables(unified.toString(), Chunker.DEEP_EMBED_MAX_CHARS)) {
            if (chunk.contains("| 项目 | 值 |")) {
                assertTrue(chunk.contains("| --- | --- |"),
                        "标题行与表体应整体保留: " + chunk);
            }
        }
    }
}
