package com.docrag.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
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

import com.docrag.config.DocRagProperties;
import com.docrag.indexer.ModeIndexer;
import com.docrag.mode.Mode;
import com.docrag.searcher.ModeSearcher;
import com.docrag.vector.VectorClient;

/** AskService 编排：双临时索引 + 打桩 LlmClient（不发起真实 HTTP） */
class AskServiceTest {

    @TempDir
    Path plainIndexDir;
    @TempDir
    Path deepIndexDir;

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
    private IndexWriter plainWriter;
    private SearcherManager plainSm;
    private IndexWriter deepWriter;
    private SearcherManager deepSm;
    private Map<Mode, ModeIndexer> indexers;
    private FakeLlm llm;
    private AskService service;

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

        Map<Mode, ModeSearcher> searchers = new EnumMap<>(Mode.class);
        // 问答检索只走倒排，VectorClient 不会被调用；按单测惯例指向封闭端口，
        // 避免误连本机真实 vector-service 混入不确定的向量召回
        DocRagProperties props = new DocRagProperties();
        props.setVectorServiceUrl("http://127.0.0.1:1");
        VectorClient closedPortVector = new VectorClient(props);
        for (Mode m : Mode.values()) {
            searchers.put(m, new ModeSearcher(m, m == Mode.PLAIN ? plainSm : deepSm,
                    queryAnalyzer, indexAnalyzer, closedPortVector));
        }

        llm = new FakeLlm("销售部的预算金额为 100 万[1]。");
        service = new AskService(searchers, llm, queryAnalyzer, indexAnalyzer, props);
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
    void plainModeSelectsChunksAndMapsCitations() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "预算说明.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万，用于市场推广。其余部门预算另行说明。");
        indexers.get(Mode.PLAIN).index("d2", "无关.docx", "/tmp/b.docx", "docx",
                "本文件讨论组织架构与人员编制，不含财务信息。");

        AskResponse resp = service.ask("销售部预算是多少", List.of("d1", "d2"), Set.of(Mode.PLAIN));
        assertEquals(llm.reply, resp.answer());
        assertEquals(List.of("plain"), resp.modes());
        assertTrue(!resp.citations().isEmpty());
        assertTrue(llm.calls == 1, "应恰好调用一次 LLM");
        // prompt 带编号资料（文件名 + 模式名）与问题
        assertTrue(llm.lastUser.contains("[1] 预算说明.docx · 纯文本解析"), llm.lastUser);
        assertTrue(llm.lastUser.contains("【问题】"));
        // 引用编号与上下文一一对应，且只引用相关文档
        assertEquals(1, resp.citations().get(0).ref());
        assertEquals("d1", resp.citations().get(0).docId());
        assertEquals("plain", resp.citations().get(0).mode());
        assertTrue(resp.citations().stream().allMatch(c -> "d1".equals(c.docId())),
                "无关文档不应进入引用");
    }

    @Test
    void deepModeSendsWholeTableBlockWithTitle() throws Exception {
        String unified = "组织架构说明正文。\n\n表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |";
        indexers.get(Mode.DEEP).index("t1", "预算表.xlsx", "/tmp/t.xlsx", "xlsx", unified);
        indexers.get(Mode.DEEP).index("t2", "别家.xlsx", "/tmp/u.xlsx", "xlsx",
                "| 部门 | 预算 |\n| --- | --- |\n| b | 2 |");

        AskResponse resp = service.ask("销售部预算", List.of("t1"), Set.of(Mode.DEEP));
        assertEquals(List.of("deep"), resp.modes());
        assertEquals(1, resp.citations().size());
        assertNull(resp.citations().get(0).title(), "deep 模式引用无块内标题，仅显示文件名");
        assertEquals("deep", resp.citations().get(0).mode());
        assertTrue(resp.citations().get(0).excerpt().contains("部门"), resp.citations().get(0).excerpt());
        // 块感知切块：标题行 + markdown 表格整体送 LLM，不被按行拆开
        assertTrue(llm.lastUser.contains("表格 1\n| 部门 | 预算金额 |"), llm.lastUser);
        assertTrue(llm.lastUser.contains("| 销售部 | 100万 |"), llm.lastUser);
        // 与问题无重叠的正文块不进入上下文
        assertTrue(!llm.lastUser.contains("组织架构说明正文"), llm.lastUser);
    }

    @Test
    void dualModeMergesContextsFromBothModesInOneLlmCall() throws Exception {
        // 同一 docId 在两模式各有内容：plain 命中、deep 表格命中
        indexers.get(Mode.PLAIN).index("d1", "预算.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万。");
        indexers.get(Mode.DEEP).index("d1", "预算.docx", "/tmp/a.docx", "docx",
                "表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |");

        // 与生产一致：modes 走 Modes.parseList（LinkedHashSet 保序，按请求顺序遍历）
        AskResponse resp = service.ask("销售部预算是多少", List.of("d1"),
                com.docrag.mode.Modes.parseList(List.of("plain", "deep")));
        assertEquals(List.of("plain", "deep"), resp.modes());
        assertEquals(1, llm.calls, "双模式合并为一次 LLM 调用");
        // 两种模式的上下文都进入同一 prompt，且带模式标签
        assertTrue(llm.lastUser.contains("预算.docx · 纯文本解析"), llm.lastUser);
        assertTrue(llm.lastUser.contains("预算.docx · 深度解析"), llm.lastUser);
        // 引用编号连续且各带来源模式
        assertEquals(2, resp.citations().size());
        assertEquals(1, resp.citations().get(0).ref());
        assertEquals(2, resp.citations().get(1).ref());
        Set<String> citeModes = Set.of(resp.citations().get(0).mode(), resp.citations().get(1).mode());
        assertTrue(citeModes.contains("plain") && citeModes.contains("deep"),
                "引用应同时覆盖两种模式: " + resp.citations());
    }

    @Test
    void noContextSkipsLlm() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "预算说明.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万。");
        AskResponse resp = service.ask("完全无关的问题量子力学", List.of("d1"), Set.of(Mode.PLAIN));
        assertEquals(0, llm.calls, "检索不到上下文不应调用 LLM");
        assertTrue(resp.answer().contains("未检索到"));
        assertTrue(resp.citations().isEmpty());
    }
}
