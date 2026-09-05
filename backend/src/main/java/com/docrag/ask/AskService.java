package com.docrag.ask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.docrag.config.DocRagProperties;
import com.docrag.indexer.Chunker;
import com.docrag.mode.Mode;
import com.docrag.searcher.ModeSearcher;
import com.docrag.searcher.RankedDoc;

import java.util.function.Function;

/**
 * 问答编排：选中 docIds + modes → 各模式独立倒排检索上下文 → 合并 → 单次 LLM 调用 → 答案 + 引用。
 * 各模式同策略：BM25 召回选中文档 → 查询时切块（plain=Chunker.chunk；
 * deep=chunkKeepingTables 块感知切块，markdown 表格块含标题行整体保留）→ 按分词重叠排序。
 * 双模式配额：每模式 chunk 数上限 max(2, maxContextChunks/2)、字符预算减半，保证两边都有上下文。
 * 问答检索只走倒排（BM25），不走向量服务；全部模式都检索不到内容时直接返回提示，不强行调用 LLM。
 */
@Service
public class AskService {

    /** 切块前召回的文档数上限（各模式共用） */
    private static final int RECALL_DOCS = 5;
    /** deep 模式块感知切块的块上限（问答上下文用，与向量切块的 256 是不同用途） */
    private static final int TABLE_CHUNK_CHARS = 512;
    /** 引用 excerpt 展示长度 */
    private static final int EXCERPT_CHARS = 160;
    /** 双模式时每模式的上下文块数下限 */
    private static final int MIN_CHUNKS_PER_MODE = 2;

    private final Map<Mode, ModeSearcher> modeSearchers;
    private final LlmClient llmClient;
    private final Analyzer queryAnalyzer;
    private final Analyzer indexAnalyzer;
    private final DocRagProperties.Llm llm;

    public AskService(Map<Mode, ModeSearcher> modeSearchers, LlmClient llmClient,
                      @Qualifier("queryAnalyzer") Analyzer queryAnalyzer,
                      @Qualifier("indexAnalyzer") Analyzer indexAnalyzer,
                      DocRagProperties props) {
        this.modeSearchers = modeSearchers;
        this.llmClient = llmClient;
        this.queryAnalyzer = queryAnalyzer;
        this.indexAnalyzer = indexAnalyzer;
        this.llm = props.getLlm();
    }

    public AskResponse ask(String question, List<String> docIds, Set<Mode> modes)
            throws IOException, ParseException {
        // 双模式按模式对半分预算（每模式保底），单模式全额
        int chunkQuota = modes.size() > 1
                ? Math.max(MIN_CHUNKS_PER_MODE, llm.getMaxContextChunks() / modes.size())
                : llm.getMaxContextChunks();
        int charBudget = modes.size() > 1 ? llm.getContextCharBudget() / modes.size()
                : llm.getContextCharBudget();

        List<Context> contexts = new ArrayList<>();
        for (Mode m : modes) {
            contexts.addAll(contextsForMode(m, question, docIds, chunkQuota, charBudget));
        }
        if (contexts.isEmpty()) {
            return new AskResponse("在选中文档中未检索到与问题相关的内容，无法作答。"
                    + "请调整问题关键词，或换一种解析模式重试。", llmClient.model(),
                    modes.stream().map(Mode::id).toList(), List.of());
        }
        String answer = llmClient.chat(systemPrompt(), userPrompt(question, contexts));
        List<AskCitation> citations = new ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            Context c = contexts.get(i);
            citations.add(new AskCitation(i + 1, c.docId, c.filename, c.type,
                    c.mode.id(), c.title, excerpt(c.content)));
        }
        return new AskResponse(answer, llmClient.model(),
                modes.stream().map(Mode::id).toList(), citations);
    }

    /** 送 LLM 的一条上下文（plain=chunk，deep=表格整块或文本片段） */
    private record Context(Mode mode, String docId, String filename, String type,
                           String title, String content) {
    }

    private record ScoredChunk(Context context, int score, int docRank) {
    }

    /** 单模式：选中文档范围内 BM25 召回 → 切块 → 与问题的分词重叠度选 top-K（受配额与字符预算约束） */
    private List<Context> contextsForMode(Mode mode, String question, List<String> docIds,
                                          int chunkQuota, int charBudget)
            throws IOException, ParseException {
        List<RankedDoc> docs = modeSearchers.get(mode).topDocsByDocIds(question, docIds, RECALL_DOCS);
        Function<String, List<String>> chunker = mode == Mode.DEEP
                ? c -> Chunker.chunkKeepingTables(c, TABLE_CHUNK_CHARS)
                : Chunker::chunk;
        return selectContexts(mode, docs, question, chunker, null, chunkQuota, charBudget);
    }

    /** 各模式共用：召回文档 → 切块 → 与问题分词重叠度选 top-K（受配额与字符预算约束） */
    private List<Context> selectContexts(Mode mode, List<RankedDoc> docs, String question,
                                         Function<String, List<String>> chunker, String title,
                                         int chunkQuota, int charBudget)
            throws IOException {
        Set<String> questionTerms = terms(question, queryAnalyzer);
        List<ScoredChunk> scored = new ArrayList<>();
        for (int d = 0; d < docs.size(); d++) {
            RankedDoc doc = docs.get(d);
            for (String chunk : chunker.apply(doc.content())) {
                int score = overlap(chunk, questionTerms);
                if (score > 0) {
                    scored.add(new ScoredChunk(
                            new Context(mode, doc.docId(), doc.filename(), doc.type(), title, chunk),
                            score, d));
                }
            }
        }
        scored.sort(Comparator.comparingInt(ScoredChunk::score).reversed()
                .thenComparingInt(ScoredChunk::docRank));
        List<Context> out = new ArrayList<>();
        int used = 0;
        for (ScoredChunk sc : scored) {
            if (out.size() >= chunkQuota
                    || used + sc.context().content().length() > charBudget) {
                break;
            }
            used += sc.context().content().length();
            out.add(sc.context());
        }
        return out;
    }

    private static String systemPrompt() {
        return "你是文档问答助手。仅依据用户提供的参考资料回答问题，不要编造。"
                + "回答使用中文，引用资料时在相应句子末尾标注 [1]、[2] 等编号（编号对应参考资料）。"
                + "如果参考资料不足以回答问题，请明确说明无法从资料中找到答案。";
    }

    private static String userPrompt(String question, List<Context> contexts) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (int i = 0; i < contexts.size(); i++) {
            Context c = contexts.get(i);
            sb.append('[').append(i + 1).append("] ").append(c.filename())
                    .append(" · ").append(c.mode.label());
            if (c.title() != null && !c.title().isBlank()) {
                sb.append(" · ").append(c.title());
            }
            sb.append('\n').append(c.content()).append("\n\n");
        }
        sb.append("【问题】\n").append(question);
        return sb.toString();
    }

    private static String excerpt(String content) {
        String flat = content.replace("\n", " ").trim();
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS) + "…";
    }

    /** 分词取词项集合（查询侧智能切分，贴近问题表述） */
    private Set<String> terms(String text, Analyzer analyzer) throws IOException {
        Set<String> out = new HashSet<>();
        try (TokenStream ts = analyzer.tokenStream("content", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(term.toString());
            }
            ts.end();
        }
        return out;
    }

    /** chunk（索引侧细粒度切分）与问题词项的重叠次数 */
    private int overlap(String text, Set<String> questionTerms) throws IOException {
        if (questionTerms.isEmpty()) {
            return 0;
        }
        int count = 0;
        try (TokenStream ts = indexAnalyzer.tokenStream("content", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                if (questionTerms.contains(term.toString())) {
                    count++;
                }
            }
            ts.end();
        }
        return count;
    }
}
