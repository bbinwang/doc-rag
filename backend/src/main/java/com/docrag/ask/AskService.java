package com.docrag.ask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.docrag.config.DocRagProperties;
import com.docrag.indexer.Chunker;
import com.docrag.searcher.DocumentSearcher;
import com.docrag.searcher.FragmentHit;
import com.docrag.searcher.RankedDoc;
import com.docrag.searcher.TableSearcher;

/**
 * 问答编排：选中 docIds + 索引格式 → 倒排检索上下文 → 拼 prompt → LLM → 答案 + 引用。
 * full：BM25 召回选中文档 → Chunker 查询时切块 → 按与问题的分词重叠度选 top-K；
 * table：直接取表格 markdown 索引 top 片段（整片段计字符预算）。
 * 问答检索只走倒排（BM25），不走向量服务；检索不到内容时直接返回提示，不强行调用 LLM。
 */
@Service
public class AskService {

    public static final String FORMAT_FULL = "full";
    public static final String FORMAT_TABLE = "table";

    /** full 格式切块前召回的文档数上限 */
    private static final int FULL_RECALL_DOCS = 5;
    /** 引用 excerpt 展示长度 */
    private static final int EXCERPT_CHARS = 160;

    private final DocumentSearcher documentSearcher;
    private final TableSearcher tableSearcher;
    private final LlmClient llmClient;
    private final Analyzer queryAnalyzer;
    private final Analyzer indexAnalyzer;
    private final DocRagProperties.Llm llm;

    public AskService(DocumentSearcher documentSearcher, TableSearcher tableSearcher,
                      LlmClient llmClient,
                      @Qualifier("queryAnalyzer") Analyzer queryAnalyzer,
                      @Qualifier("indexAnalyzer") Analyzer indexAnalyzer,
                      DocRagProperties props) {
        this.documentSearcher = documentSearcher;
        this.tableSearcher = tableSearcher;
        this.llmClient = llmClient;
        this.queryAnalyzer = queryAnalyzer;
        this.indexAnalyzer = indexAnalyzer;
        this.llm = props.getLlm();
    }

    public AskResponse ask(String question, List<String> docIds, String format)
            throws IOException, ParseException {
        List<Context> contexts = FORMAT_TABLE.equals(format)
                ? tableContexts(question, docIds)
                : fullContexts(question, docIds);
        if (contexts.isEmpty()) {
            String indexName = FORMAT_TABLE.equals(format) ? "表格索引" : "全文索引";
            return new AskResponse("在选中文档的" + indexName + "中未检索到与问题相关的内容，无法作答。"
                    + "请调整问题关键词，或换一种索引格式重试。", llmClient.model(), format, List.of());
        }
        String answer = llmClient.chat(systemPrompt(), userPrompt(question, contexts));
        List<AskCitation> citations = new ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            Context c = contexts.get(i);
            citations.add(new AskCitation(i + 1, c.docId, c.filename, c.type, c.title, excerpt(c.content)));
        }
        return new AskResponse(answer, llmClient.model(), format, citations);
    }

    /** 送 LLM 的一条上下文（full=chunk，table=片段） */
    private record Context(String docId, String filename, String type, String title, String content) {
    }

    private record ScoredChunk(Context context, int score, int docRank) {
    }

    /** full：选中文档范围内 BM25 召回 → 切块 → 与问题的分词重叠度选 top-K（受字符预算约束） */
    private List<Context> fullContexts(String question, List<String> docIds)
            throws IOException, ParseException {
        List<RankedDoc> docs = documentSearcher.topDocsByDocIds(question, docIds, FULL_RECALL_DOCS);
        Set<String> questionTerms = terms(question, queryAnalyzer);
        List<ScoredChunk> scored = new ArrayList<>();
        for (int d = 0; d < docs.size(); d++) {
            RankedDoc doc = docs.get(d);
            for (String chunk : Chunker.chunk(doc.content())) {
                int score = overlap(chunk, questionTerms);
                if (score > 0) {
                    scored.add(new ScoredChunk(
                            new Context(doc.docId(), doc.filename(), doc.type(), "正文片段", chunk),
                            score, d));
                }
            }
        }
        scored.sort(Comparator.comparingInt(ScoredChunk::score).reversed()
                .thenComparingInt(ScoredChunk::docRank));
        List<Context> out = new ArrayList<>();
        int used = 0;
        for (ScoredChunk sc : scored) {
            if (out.size() >= llm.getMaxContextChunks()
                    || used + sc.context().content().length() > llm.getContextCharBudget()) {
                break;
            }
            used += sc.context().content().length();
            out.add(sc.context());
        }
        return out;
    }

    /** table：直接取表格 markdown 索引 top 片段 */
    private List<Context> tableContexts(String question, List<String> docIds)
            throws IOException, ParseException {
        List<Context> out = new ArrayList<>();
        for (FragmentHit f : tableSearcher.topFragments(question, docIds,
                llm.getMaxContextChunks(), llm.getContextCharBudget())) {
            out.add(new Context(f.docId(), f.filename(), f.type(), f.title(), f.content()));
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
            sb.append('[').append(i + 1).append("] ").append(c.filename());
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
