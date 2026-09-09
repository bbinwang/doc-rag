package com.docrag.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
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
import com.docrag.vector.VectorHit;

/** AskService 编排：双临时索引 + 打桩 LlmClient/VectorClient（不发起真实 HTTP） */
class AskServiceTest {

    @TempDir
    Path plainIndexDir;
    @TempDir
    Path deepIndexDir;

    /** 打桩 LLM：记录每次 prompt；failOnCall（1 起）模拟第 N 次调用失败 */
    static class FakeLlm extends LlmClient {
        final String reply;
        final List<String> userPrompts = new ArrayList<>();
        int failOnCall = -1;

        FakeLlm(String reply) {
            super(new DocRagProperties());
            this.reply = reply;
        }

        @Override
        public String model() {
            return "fake-model";
        }

        @Override
        public String chat(String system, String user) throws IOException {
            if (userPrompts.size() + 1 == failOnCall) {
                throw new IOException("模拟 LLM 服务不可用");
            }
            userPrompts.add(user);
            return reply;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    /** 打桩向量：per-mode 预设命中；fail 模拟服务不可用；记录最近一次 topK */
    static class FakeVector extends VectorClient {
        final Map<Mode, List<VectorHit>> hits = new EnumMap<>(Mode.class);
        boolean fail = false;
        int lastTopK = -1;

        FakeVector() {
            super(new DocRagProperties());
        }

        @Override
        public List<VectorHit> query(Mode mode, String text, int topK) throws IOException {
            lastTopK = topK;
            if (fail) {
                throw new IOException("模拟向量服务不可用");
            }
            List<VectorHit> list = hits.getOrDefault(mode, List.of());
            return list.subList(0, Math.min(list.size(), topK));
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
    private FakeVector vector;
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

        vector = new FakeVector();
        Map<Mode, ModeSearcher> searchers = new EnumMap<>(Mode.class);
        for (Mode m : Mode.values()) {
            searchers.put(m, new ModeSearcher(m, m == Mode.PLAIN ? plainSm : deepSm,
                    queryAnalyzer, indexAnalyzer, vector));
        }
        llm = new FakeLlm("销售部的预算金额为 100 万[1]。");
        service = new AskService(searchers, llm, new DocRagProperties());
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
    void plainModeSingleCallAndChunkMapping() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "预算说明.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万，用于市场推广。其余部门预算另行说明。");
        indexers.get(Mode.PLAIN).index("d2", "无关.docx", "/tmp/b.docx", "docx",
                "本文件讨论组织架构与人员编制，不含财务信息。");

        AskResponse resp = service.ask("销售部预算是多少", Set.of(Mode.PLAIN), null, null, null);
        assertEquals(Set.of("plain"), resp.modes().keySet());
        assertEquals(1, llm.userPrompts.size(), "单模式恰好调用一次 LLM");
        assertTrue(llm.userPrompts.get(0).contains("[1] 预算说明.docx"), llm.userPrompts.get(0));
        assertTrue(llm.userPrompts.get(0).contains("【问题】"));
        AskModeResult plain = resp.modes().get("plain");
        assertEquals(llm.reply, plain.answer());
        assertNull(plain.error());
        assertTrue(!plain.degraded(), "打桩向量可用，不降级");
        assertTrue(!plain.chunks().isEmpty());
        assertEquals(1, plain.chunks().get(0).ref(), "ref 与 prompt 资料编号一一对应");
        assertTrue(plain.chunks().stream().allMatch(c -> "d1".equals(c.docId())),
                "无关文档不应进入上下文: " + plain.chunks());
        // docs = chunks 按 docId 去重
        assertEquals(1, plain.docs().size());
        assertEquals("d1", plain.docs().get(0).docId());
        assertEquals("/tmp/a.docx", plain.docs().get(0).path());
        // 参数未覆盖时回显配置默认（DocRagProperties 默认 5/5/8）
        assertEquals(new AskParams(5, 5, 8), resp.params());
    }

    @Test
    void deepModeSendsWholeTableBlockWithTitle() throws Exception {
        String unified = "组织架构说明正文。\n\n表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |";
        indexers.get(Mode.DEEP).index("t1", "预算表.xlsx", "/tmp/t.xlsx", "xlsx", unified);
        indexers.get(Mode.DEEP).index("t2", "别家.xlsx", "/tmp/u.xlsx", "xlsx",
                "| 部门 | 预算 |\n| --- | --- |\n| b | 2 |");

        AskResponse resp = service.ask("销售部预算", Set.of(Mode.DEEP), null, null, null);
        String prompt = llm.userPrompts.get(0);
        // 块感知切块：标题行 + markdown 表格整体送 LLM，不被按行拆开
        assertTrue(prompt.contains("表格 1\n| 部门 | 预算金额 |"), prompt);
        assertTrue(prompt.contains("| 销售部 | 100万 |"), prompt);
        // 与问题无重叠的正文块不进入上下文
        assertTrue(!prompt.contains("组织架构说明正文"), prompt);
        AskModeResult deep = resp.modes().get("deep");
        AskChunk table = deep.chunks().stream()
                .filter(c -> "表格 1".equals(c.title()))
                .findFirst().orElseThrow();
        assertEquals("t1", table.docId());
        assertTrue(table.text().contains("| 销售部 | 100万 |"));
    }

    @Test
    void dualModeMakesTwoIndependentCalls() throws Exception {
        // 同一 docId 在两模式各有内容：plain 正文、deep 表格
        indexers.get(Mode.PLAIN).index("d1", "预算.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万。");
        indexers.get(Mode.DEEP).index("d1", "预算.docx", "/tmp/a.docx", "docx",
                "表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |");

        Set<Mode> modes = new LinkedHashSet<>(List.of(Mode.PLAIN, Mode.DEEP));
        AskResponse resp = service.ask("销售部预算是多少", modes, null, null, null);
        assertEquals(2, llm.userPrompts.size(), "双模式各自独立调用 LLM");
        assertEquals(List.of("plain", "deep"), new ArrayList<>(resp.modes().keySet()), "key 顺序=请求顺序");
        // 两次 prompt 各只含本模式上下文
        assertTrue(llm.userPrompts.get(0).contains("销售部预算金额为100万。"), llm.userPrompts.get(0));
        assertTrue(!llm.userPrompts.get(0).contains("| 部门"), llm.userPrompts.get(0));
        assertTrue(llm.userPrompts.get(1).contains("| 部门 | 预算金额 |"), llm.userPrompts.get(1));
        // 各模式 ref 独立从 1 起
        assertEquals(1, resp.modes().get("plain").chunks().get(0).ref());
        assertEquals(1, resp.modes().get("deep").chunks().get(0).ref());
    }

    @Test
    void vectorOnlyChunkSurvivesRerank() throws Exception {
        // 词面与问题无重叠（BM25 路无命中），仅向量路语义召回——重构要修的「overlap 误杀」场景
        indexers.get(Mode.PLAIN).index("d1", "团建安排.docx", "/tmp/a.docx", "docx",
                "公司决定下月组织全员团建活动。");
        vector.hits.put(Mode.PLAIN, List.of(
                new VectorHit("d1", "团建安排.docx", "docx", "户外拓展与活动的具体安排细则", 0.9)));

        AskResponse resp = service.ask("户外拓展怎么安排", Set.of(Mode.PLAIN), null, null, null);
        assertEquals(1, llm.userPrompts.size(), "向量召回的 chunk 不应被 overlap 过滤淘汰");
        AskChunk chunk = resp.modes().get("plain").chunks().get(0);
        assertEquals("vector", chunk.source());
        assertEquals("户外拓展与活动的具体安排细则", chunk.text());
        assertEquals("/tmp/a.docx", resp.modes().get("plain").docs().get(0).path(),
                "仅向量命中的文档 path 从索引补全");
    }

    @Test
    void vectorUnavailableDegradesToBm25() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "预算说明.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万。");
        vector.fail = true;

        AskResponse resp = service.ask("销售部预算是多少", Set.of(Mode.PLAIN), null, null, null);
        AskModeResult plain = resp.modes().get("plain");
        assertTrue(plain.degraded(), "向量不可用该模式降级纯 BM25");
        assertEquals(llm.reply, plain.answer(), "降级后仍照常作答");
        assertTrue(plain.chunks().stream().allMatch(c -> "bm25".equals(c.source())));
        assertEquals(5, vector.lastTopK, "向量路 topK 应取 vectorChunks 参数");
    }

    @Test
    void bothRoutesDedupeAndFuse() throws Exception {
        // 同一 (docId, 精确 chunk 文本) 两路都召回 → 去重为一条 both，RRF 分最高排最前
        String content = "本合同条款约定双方的权利与义务。";
        indexers.get(Mode.PLAIN).index("d1", "劳动合同.docx", "/tmp/1.docx", "docx", content);
        indexers.get(Mode.PLAIN).index("d2", "其它条款.docx", "/tmp/2.docx", "docx",
                "合同备案与归档说明。");
        vector.hits.put(Mode.PLAIN, List.of(
                new VectorHit("d1", "劳动合同.docx", "docx", content, 0.9)));

        // 注意问题用词：IK smart 把「合同条款」切成单个复合词，d2 无该词会被 overlap 淘汰
        AskResponse resp = service.ask("合同", Set.of(Mode.PLAIN), null, null, null);
        List<AskChunk> chunks = resp.modes().get("plain").chunks();
        assertEquals("d1", chunks.get(0).docId(), "两路累加的 RRF 分应排最前");
        assertEquals("both", chunks.get(0).source());
        assertEquals(1, (int) chunks.stream().filter(c -> "d1".equals(c.docId())).count(),
                "同一 chunk 两路命中应去重为一条");
        assertTrue(chunks.get(0).score() > chunks.get(chunks.size() - 1).score(),
                "both 分数应高于单路 chunk");
    }

    @Test
    void contextChunksLimitTrimsContext() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "预算制度.docx", "/tmp/a.docx", "docx",
                "预算编制原则说明。\n预算执行流程说明。\n预算调整规则说明。\n预算考核办法说明。");

        // 注意问题用词须与内容有分词重叠（IK smart 会把「预算管理」切成单个复合词）
        AskResponse resp = service.ask("预算", Set.of(Mode.PLAIN), null, null, 2);
        AskModeResult plain = resp.modes().get("plain");
        assertEquals(2, plain.chunks().size(), "重排后按 contextChunks 截断");
        assertEquals(2, resp.params().contextChunks(), "生效参数回显");
        assertTrue(llm.userPrompts.get(0).contains("[1] "), "prompt 有资料 1");
        assertTrue(llm.userPrompts.get(0).contains("[2] "), "prompt 有资料 2");
        assertTrue(!llm.userPrompts.get(0).contains("[3] "), "不应有第三条资料");
    }

    @Test
    void paramOverrideClampedAndDocPoolBounded() throws Exception {
        // 7 篇全命中文档 + bm25Chunks=3 → doc 召回池 clamp(3,5,20)=5 篇，chunk 的 docId 至多 5 个
        for (int i = 1; i <= 7; i++) {
            indexers.get(Mode.PLAIN).index("d" + i, "预算文档" + i + ".docx",
                    "/tmp/" + i + ".docx", "docx", "预算相关内容编号" + i + "的说明文字。");
        }
        AskResponse resp = service.ask("预算", Set.of(Mode.PLAIN), 99, null, null);
        assertEquals(20, resp.params().bm25Chunks(), "请求覆盖值钳制到上限 20");
        long distinct = resp.modes().get("plain").chunks().stream()
                .map(AskChunk::docId).distinct().count();
        assertTrue(distinct <= 7);

        AskResponse small = service.ask("预算", Set.of(Mode.PLAIN), 3, null, null);
        long distinctSmall = small.modes().get("plain").chunks().stream()
                .map(AskChunk::docId).distinct().count();
        assertTrue(distinctSmall <= 5, "bm25Chunks=3 时 doc 召回池下限 5 篇: " + distinctSmall);
    }

    @Test
    void emptyModeSkipsLlmButOtherModeAnswers() throws Exception {
        // plain 索引为空、deep 有内容：plain 占位不调 LLM，deep 照常作答
        indexers.get(Mode.DEEP).index("t1", "预算表.xlsx", "/tmp/t.xlsx", "xlsx",
                "表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |");

        Set<Mode> modes = new LinkedHashSet<>(List.of(Mode.PLAIN, Mode.DEEP));
        AskResponse resp = service.ask("销售部预算是多少", modes, null, null, null);
        assertEquals(1, llm.userPrompts.size(), "仅 deep 调用 LLM");
        AskModeResult plain = resp.modes().get("plain");
        assertTrue(plain.answer().contains("未检索到"), plain.answer());
        assertTrue(plain.chunks().isEmpty());
        assertEquals(llm.reply, resp.modes().get("deep").answer());
    }

    @Test
    void llmFailureIsolatedPerMode() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "预算.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万。");
        indexers.get(Mode.DEEP).index("d1", "预算.docx", "/tmp/a.docx", "docx",
                "表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |");

        // 第二次调用（deep）失败：plain 照常作答，deep 报错但 chunks 照常返回供调试
        llm.failOnCall = 2;
        Set<Mode> modes = new LinkedHashSet<>(List.of(Mode.PLAIN, Mode.DEEP));
        AskResponse resp = service.ask("销售部预算是多少", modes, null, null, null);
        assertEquals(llm.reply, resp.modes().get("plain").answer());
        assertNull(resp.modes().get("plain").error());
        AskModeResult deep = resp.modes().get("deep");
        assertNull(deep.answer());
        assertNotNull(deep.error());
        assertTrue(deep.error().contains("LLM 调用失败"));
        assertTrue(!deep.chunks().isEmpty(), "失败模式仍返回召回明细供调试");

        // 全部模式都失败才整体抛出（先清零前一轮的 prompt 记录）
        llm.userPrompts.clear();
        llm.failOnCall = 1;
        assertThrows(IOException.class,
                () -> service.ask("销售部预算是多少", modes, null, null, null));
    }

    @Test
    void noContextAtAllSkipsLlm() throws Exception {
        indexers.get(Mode.PLAIN).index("d1", "预算说明.docx", "/tmp/a.docx", "docx",
                "销售部预算金额为100万。");
        AskResponse resp = service.ask("完全无关的问题量子力学", Set.of(Mode.PLAIN), null, null, null);
        assertEquals(0, llm.userPrompts.size(), "检索不到上下文不应调用 LLM");
        assertTrue(resp.modes().get("plain").answer().contains("未检索到"));
        assertTrue(resp.modes().get("plain").chunks().isEmpty());
    }

    @Test
    void contextCharBudgetTrimsBeforeChunkLimit() throws Exception {
        // 三个 chunk 全部召回（contextChunks 默认 8 不设限），字符预算压到 15 → 只装得下第一块：
        // 证明截断来自 context-char-budget 维度而非 contextChunks 维度
        indexers.get(Mode.PLAIN).index("d1", "预算制度.docx", "/tmp/a.docx", "docx",
                "预算编制原则。\n预算执行流程的具体操作与审批环节说明。\n预算考核办法与说明。");

        DocRagProperties tightProps = new DocRagProperties();
        tightProps.getAsk().setContextCharBudget(15);
        Map<Mode, ModeSearcher> searchers = new EnumMap<>(Mode.class);
        for (Mode m : Mode.values()) {
            searchers.put(m, new ModeSearcher(m, m == Mode.PLAIN ? plainSm : deepSm,
                    queryAnalyzer, indexAnalyzer, vector));
        }
        AskService tight = new AskService(searchers, llm, tightProps);

        AskResponse resp = tight.ask("预算", Set.of(Mode.PLAIN), null, null, null);
        assertEquals(8, resp.params().contextChunks(), "chunk 上限未动，截断只能来自字符预算");
        AskModeResult result = resp.modes().get("plain");
        assertEquals(1, result.chunks().size(), "预算先于 chunk 上限触发截断");
        assertTrue(result.chunks().get(0).text().length() <= 15);
        assertTrue(llm.userPrompts.get(0).contains("[1] "));
        assertTrue(!llm.userPrompts.get(0).contains("[2] "), "预算外的 chunk 不应进入 prompt");
    }
}
