package com.docrag.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
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
import org.springframework.mock.web.MockMultipartFile;
import org.wltea.analyzer.lucene.IKAnalyzer;

import com.docrag.config.DocRagProperties;
import com.docrag.deepmd.DeepmdRouter;
import com.docrag.deepmd.DocxDeepmdExtractor;
import com.docrag.deepmd.PdfDeepmdExtractor;
import com.docrag.deepmd.XlsxDeepmdExtractor;
import com.docrag.indexer.IngestService;
import com.docrag.indexer.ModeIndexer;
import com.docrag.mode.Mode;
import com.docrag.parser.DocumentParseException;
import com.docrag.parser.DocxParser;
import com.docrag.parser.PdfParser;
import com.docrag.parser.ParserRouter;
import com.docrag.parser.XlsxParser;
import com.docrag.searcher.DocumentDetail;
import com.docrag.searcher.ModeSearcher;
import com.docrag.vector.VectorClient;
import com.docrag.vector.VectorHit;

/** 上传入库 / 取原文 / 级联删除：真实 IngestService + 双临时索引 + 打桩 vector（不发起真实 HTTP） */
class DocumentControllerTest {

    @TempDir
    Path plainIndexDir;
    @TempDir
    Path deepIndexDir;
    @TempDir
    Path uploadDir;

    /** 打桩 vector：记录 upsert/delete 调用；query 返回空命中（入库与删除路径不触网） */
    static class FakeVector extends VectorClient {
        final List<String> upserts = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();

        FakeVector() {
            super(new DocRagProperties());
        }

        @Override
        public int upsert(Mode mode, String docId, String filename, String type, List<String> chunks) {
            upserts.add(mode.id() + ":" + docId + ":" + chunks.size());
            return chunks.size();
        }

        @Override
        public void delete(String docId, Mode mode) {
            deletes.add(docId + ":" + (mode == null ? "all" : mode.id()));
        }

        @Override
        public List<VectorHit> query(Mode mode, String text, int topK) {
            return List.of();
        }
    }

    private Analyzer indexAnalyzer;
    private Analyzer queryAnalyzer;
    private IndexWriter plainWriter;
    private SearcherManager plainSm;
    private IndexWriter deepWriter;
    private SearcherManager deepSm;
    private Map<Mode, ModeIndexer> indexers;
    private FakeVector vector;
    private ModeSearcher plainSearcher;
    private DocumentController controller;

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

        vector = new FakeVector();
        IngestService ingest = new IngestService(
                new ParserRouter(List.of(new DocxParser(), new XlsxParser(), new PdfParser())),
                new DeepmdRouter(List.of(new DocxDeepmdExtractor(), new XlsxDeepmdExtractor(),
                        new PdfDeepmdExtractor())),
                indexers, vector);
        plainSearcher = new ModeSearcher(Mode.PLAIN, plainSm, queryAnalyzer, indexAnalyzer, vector);

        DocRagProperties props = new DocRagProperties();
        props.setUploadDir(uploadDir.toString());
        controller = new DocumentController(ingest, plainSearcher, vector, indexers, props);
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

    private static byte[] pdfBytes(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
        }
        return out.toByteArray();
    }

    @Test
    void emptyFileRejectedWithoutTouchingStores() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "空.pdf", "application/pdf", new byte[0]);
        DocumentParseException ex = assertThrows(DocumentParseException.class,
                () -> controller.upload(file, List.of("plain")));
        assertTrue(ex.getMessage().contains("上传文件为空"), ex.getMessage());
        assertEquals(0, indexers.get(Mode.PLAIN).count());
        try (var s = Files.list(uploadDir)) {
            assertEquals(0, s.count(), "空文件不应落盘");
        }
    }

    @Test
    void uploadStoresWithDocIdPrefixAndIngestsSelectedModesOnly() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "合同.pdf", "application/pdf",
                pdfBytes("hello docrag ingest contract"));
        Map<String, Object> resp = controller.upload(file, List.of("plain"));

        String docId = (String) resp.get("docId");
        assertEquals("合同.pdf", resp.get("filename"));
        assertEquals("pdf", resp.get("type"));
        assertEquals(List.of("plain"), resp.get("modes"));
        assertEquals(1, ((Map<?, ?>) resp.get("chunkCount")).get("plain"));
        assertTrue(!resp.containsKey("tableCount"), "未选 deep 不应返回 tableCount");

        // 存储名 = {docId}_{filename}，uuid 前缀避免同名覆盖（api.md 约定）
        try (var s = Files.list(uploadDir)) {
            assertEquals(List.of(docId + "_合同.pdf"),
                    s.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals(1, indexers.get(Mode.PLAIN).count());
        assertEquals(0, indexers.get(Mode.DEEP).count(), "未选模式不写库");
        assertEquals(1, vector.upserts.size());
        assertTrue(vector.upserts.get(0).startsWith("plain:" + docId + ":"));
        // 入库后即可检索
        assertEquals(1, plainSearcher.search("docrag", 1, 10).total());
    }

    @Test
    void dualModeUploadReportsPdfTableCountZero() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "合同.pdf", "application/pdf",
                pdfBytes("hello docrag ingest contract"));
        Map<String, Object> resp = controller.upload(file, List.of("plain", "deep"));

        assertEquals(List.of("plain", "deep"), resp.get("modes"), "key 顺序=请求顺序");
        assertEquals(0, resp.get("tableCount"), "PDF 无结构化表格：deep 统一文本与 plain 同文，tableCount=0");
        Map<?, ?> counts = (Map<?, ?>) resp.get("chunkCount");
        assertEquals(1, counts.get("deep"));
        assertEquals(1, indexers.get(Mode.DEEP).count());
        assertEquals(2, vector.upserts.size(), "双模式各 upsert 一次");
    }

    @Test
    void parseFailureDeletesDroppedFileAndWritesNothing() throws Exception {
        // txt 不在 ParserRouter 支持列表：落盘后解析失败，须删掉落盘文件且不写任何库
        MockMultipartFile file = new MockMultipartFile("file", "笔记.txt", "text/plain",
                "不是支持的格式".getBytes());
        assertThrows(DocumentParseException.class,
                () -> controller.upload(file, List.of("plain", "deep")));
        try (var s = Files.list(uploadDir)) {
            assertEquals(0, s.count(), "解析失败不留脏文件");
        }
        assertEquals(0, indexers.get(Mode.PLAIN).count());
        assertEquals(0, indexers.get(Mode.DEEP).count());
        assertEquals(0, vector.upserts.size());
    }

    @Test
    void getReturnsPlainContentAndMissingThrowsNotFound() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx", "合同条款内容");
        DocumentDetail detail = controller.get("d1");
        assertEquals("劳动合同.docx", detail.filename());
        assertEquals("合同条款内容", detail.content());
        assertThrows(ResourceNotFoundException.class, () -> controller.get("missing"));
    }

    @Test
    void deleteCascadesVectorThenBothIndexes() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx", "合同条款内容");
        indexers.get(Mode.DEEP).index("d1", "劳动合同.docx", "/tmp/a.docx", "docx",
                "表格 1\n| a | b |\n| --- | --- |\n| 1 | 2 |");

        assertEquals(Map.of("deleted", "d1"), controller.delete("d1"));
        assertEquals(List.of("d1:all"), vector.deletes, "向量侧一次双删（mode=null 即 all）");
        assertEquals(0, indexers.get(Mode.PLAIN).count());
        assertEquals(0, indexers.get(Mode.DEEP).count());
    }
}
