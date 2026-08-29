package com.docrag.ask;

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

import com.docrag.config.DocRagProperties;
import com.docrag.indexer.DocumentIndexer;
import com.docrag.indexer.TableIndexer;
import com.docrag.searcher.DocumentSearcher;
import com.docrag.searcher.TableSearcher;
import com.docrag.tablemd.TableFragment;
import com.docrag.vector.VectorClient;

/** AskService 编排：双临时索引 + 打桩 LlmClient（不发起真实 HTTP） */
class AskServiceTest {

    @TempDir
    Path fullIndexDir;
    @TempDir
    Path tableIndexDir;

    /** 打桩 LLM：记录 prompt、返回固定答案 */
    static class FakeLlm extends LlmClient {
        final String reply;
        String lastUser;
        int calls;

        FakeLlm(String reply) {
            super(new DocRagProperties());
            this.reply = reply;
        }

        @Override
        public String chat(String system, String user) {
            calls++;
            lastUser = user;
            return reply;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    private Analyzer indexAnalyzer;
    private Analyzer queryAnalyzer;
    private IndexWriter fullWriter;
    private SearcherManager fullSearcherManager;
    private IndexWriter tableWriter;
    private SearcherManager tableSearcherManager;
    private DocumentSearcher documentSearcher;
    private TableSearcher tableSearcher;
    private DocumentIndexer documentIndexer;
    private TableIndexer tableIndexer;
    private FakeLlm llm;
    private AskService service;

    @BeforeEach
    void setUp() throws IOException {
        indexAnalyzer = new IKAnalyzer(false);
        queryAnalyzer = new IKAnalyzer(true);
        fullWriter = new IndexWriter(FSDirectory.open(fullIndexDir), new IndexWriterConfig(indexAnalyzer));
        fullSearcherManager = new SearcherManager(fullWriter, new SearcherFactory());
        tableWriter = new IndexWriter(FSDirectory.open(tableIndexDir), new IndexWriterConfig(indexAnalyzer));
        tableSearcherManager = new SearcherManager(tableWriter, new SearcherFactory());
        documentIndexer = new DocumentIndexer(fullWriter, fullSearcherManager);
        tableIndexer = new TableIndexer(tableWriter, tableSearcherManager);
        // 问答检索只走倒排，VectorClient 不会被调用；按单测惯例给真实实例（连不上也不影响）
        documentSearcher = new DocumentSearcher(fullSearcherManager, queryAnalyzer, indexAnalyzer,
                new VectorClient(new DocRagProperties()));
        tableSearcher = new TableSearcher(tableSearcherManager, queryAnalyzer, indexAnalyzer);
        llm = new FakeLlm("销售部的预算金额为 100 万[1]。");
        service = new AskService(documentSearcher, tableSearcher, llm,
                queryAnalyzer, indexAnalyzer, new DocRagProperties());
    }

    @AfterEach
    void tearDown() throws IOException {
        fullSearcherManager.close();
        fullWriter.close();
        tableSearcherManager.close();
        tableWriter.close();
        indexAnalyzer.close();
        queryAnalyzer.close();
    }

    @Test
    void fullFormatSelectsChunksAndMapsCitations() throws Exception {
        documentIndexer.index("d1", "预算说明.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万，用于市场推广。其余部门预算另行说明。");
        documentIndexer.index("d2", "无关.docx", "/tmp/b.docx", "docx",
                "本文件讨论组织架构与人员编制，不含财务信息。");

        AskResponse resp = service.ask("销售部预算是多少", List.of("d1", "d2"), "full");
        assertEquals(llm.reply, resp.answer());
        assertEquals("full", resp.format());
        assertTrue(!resp.citations().isEmpty());
        assertTrue(llm.calls == 1, "应恰好调用一次 LLM");
        // prompt 带编号资料与问题
        assertTrue(llm.lastUser.contains("[1] 预算说明.docx"), llm.lastUser);
        assertTrue(llm.lastUser.contains("【问题】"));
        // 引用编号与上下文一一对应，且只引用相关文档
        assertEquals(1, resp.citations().get(0).ref());
        assertEquals("d1", resp.citations().get(0).docId());
        assertTrue(resp.citations().stream().allMatch(c -> "d1".equals(c.docId())),
                "无关文档不应进入引用");
    }

    @Test
    void tableFormatUsesMarkdownFragments() throws Exception {
        tableIndexer.index("t1", "预算表.xlsx", "/tmp/t.xlsx", "xlsx", List.of(
                TableFragment.table("Sheet1",
                        "| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |")));
        tableIndexer.index("t2", "别家.xlsx", "/tmp/u.xlsx", "xlsx", List.of(
                TableFragment.table("Sheet1", "| 部门 | 预算 |\n| --- | --- |\n| b | 2 |")));

        AskResponse resp = service.ask("销售部预算", List.of("t1"), "table");
        assertEquals("table", resp.format());
        assertEquals(1, resp.citations().size());
        assertEquals("Sheet1", resp.citations().get(0).title());
        assertTrue(resp.citations().get(0).excerpt().contains("部门"));
        assertTrue(llm.lastUser.contains("| 销售部 | 100万 |"), llm.lastUser);
    }

    @Test
    void noContextSkipsLlm() throws Exception {
        documentIndexer.index("d1", "预算说明.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万。");
        AskResponse resp = service.ask("完全无关的问题量子力学", List.of("d1"), "full");
        assertEquals(0, llm.calls, "检索不到上下文不应调用 LLM");
        assertTrue(resp.answer().contains("未检索到"));
        assertTrue(resp.citations().isEmpty());
    }
}
