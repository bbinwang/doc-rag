package com.docrag.ask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.stereotype.Service;

import com.docrag.config.DocRagProperties;
import com.docrag.mode.Mode;
import com.docrag.searcher.ChunkRecall;
import com.docrag.searcher.ModeSearcher;
import com.docrag.searcher.RecalledChunk;

/**
 * 问答编排：全库检索（不限 docIds），每模式独立完成「chunk 级混合召回
 * （BM25 + 向量 RRF，ModeSearcher.recallChunks）→ 预算截断 → prompt → LLM」，
 * 各自出一份答案与召回明细（chunks + 去重文档），双模式两栏对比。
 *
 * <p>失败语义：某模式召回 0 chunk → 该模式返回占位提示、不调 LLM，另一模式照常；
 * LLM 调用失败按模式隔离（失败模式 answer=null + error，chunks 照常返回供调试），
 * 仅当全部选中模式的 LLM 调用都失败才整体抛出；向量不可用由 searcher 降级（degraded=true）。</p>
 *
 * <p>三个检索参数（bm25/vector/context chunks）支持请求级覆盖，
 * null → 配置默认（docrag.ask.*）→ 钳制 [1, 20]，生效值经 params 回显。</p>
 */
@Service
public class AskService {

    private static final int PARAM_MIN = 1;
    private static final int PARAM_MAX = 20;

    private static final String NO_CONTEXT_ANSWER =
            "未检索到与问题相关的内容，无法作答。请调整问题关键词，或换一种解析模式重试。";

    private final Map<Mode, ModeSearcher> modeSearchers;
    private final LlmClient llmClient;
    private final DocRagProperties.Ask askCfg;

    public AskService(Map<Mode, ModeSearcher> modeSearchers, LlmClient llmClient,
                      DocRagProperties props) {
        this.modeSearchers = modeSearchers;
        this.llmClient = llmClient;
        this.askCfg = props.getAsk();
    }

    public AskResponse ask(String question, Set<Mode> modes,
                           Integer bm25Override, Integer vectorOverride, Integer contextOverride)
            throws IOException, ParseException {
        AskParams params = effectiveParams(askCfg, bm25Override, vectorOverride, contextOverride);
        Map<String, AskModeResult> byMode = new LinkedHashMap<>();
        int llmFailures = 0;
        IOException lastLlmFailure = null;
        for (Mode mode : modes) {
            ChunkRecall recall = modeSearchers.get(mode)
                    .recallChunks(question, params.bm25Chunks(), params.vectorChunks());
            List<RecalledChunk> selected = trimToBudget(
                    recall.chunks(), params.contextChunks(), askCfg.getContextCharBudget());
            if (selected.isEmpty()) {
                byMode.put(mode.id(), new AskModeResult(NO_CONTEXT_ANSWER, null,
                        recall.degraded(), List.of(), List.of()));
                continue;
            }
            List<AskChunk> chunks = new ArrayList<>();
            List<AskDocRef> docs = new ArrayList<>();
            Set<String> seenDocIds = new HashSet<>();
            for (int i = 0; i < selected.size(); i++) {
                RecalledChunk c = selected.get(i);
                chunks.add(new AskChunk(i + 1, c.docId(), c.filename(), c.type(),
                        c.title(), c.source(), c.score(), c.text()));
                if (seenDocIds.add(c.docId())) {
                    docs.add(new AskDocRef(c.docId(), c.filename(), c.type(), c.path()));
                }
            }
            try {
                String answer = llmClient.chat(systemPrompt(), userPrompt(question, selected));
                byMode.put(mode.id(), new AskModeResult(answer, null,
                        recall.degraded(), chunks, docs));
            } catch (IOException e) {
                llmFailures++;
                lastLlmFailure = e;
                byMode.put(mode.id(), new AskModeResult(null, "LLM 调用失败: " + e.getMessage(),
                        recall.degraded(), chunks, docs));
            }
        }
        if (llmFailures == modes.size()) {
            throw lastLlmFailure;
        }
        return new AskResponse(llmClient.model(), byMode, params);
    }

    /** 请求覆盖 → 配置默认 → 钳制 [PARAM_MIN, PARAM_MAX]（单点，配置值也过同一钳制） */
    static AskParams effectiveParams(DocRagProperties.Ask cfg,
                                     Integer bm25, Integer vector, Integer context) {
        return new AskParams(
                clamp(bm25 != null ? bm25 : cfg.getBm25Chunks()),
                clamp(vector != null ? vector : cfg.getVectorChunks()),
                clamp(context != null ? context : cfg.getContextChunks()));
    }

    private static int clamp(int v) {
        return Math.min(PARAM_MAX, Math.max(PARAM_MIN, v));
    }

    /** 融合序贪心装填：块数达 contextChunks 或累计字符超预算即停（沿用旧 selectContexts 语义） */
    private static List<RecalledChunk> trimToBudget(List<RecalledChunk> chunks,
                                                    int maxChunks, int charBudget) {
        List<RecalledChunk> out = new ArrayList<>();
        int used = 0;
        for (RecalledChunk c : chunks) {
            if (out.size() >= maxChunks || used + c.text().length() > charBudget) {
                break;
            }
            used += c.text().length();
            out.add(c);
        }
        return out;
    }

    private static String systemPrompt() {
        return "你是文档问答助手。仅依据用户提供的参考资料回答问题，不要编造。"
                + "回答使用中文，引用资料时在相应句子末尾标注 [1]、[2] 等编号（编号对应参考资料）。"
                + "如果参考资料不足以回答问题，请明确说明无法从资料中找到答案。";
    }

    /** 资料头行 [n] filename（单模式调用；deep 表格块自带「表格 N」标题行，无需重复标注） */
    private static String userPrompt(String question, List<RecalledChunk> contexts) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (int i = 0; i < contexts.size(); i++) {
            RecalledChunk c = contexts.get(i);
            sb.append('[').append(i + 1).append("] ").append(c.filename())
                    .append('\n').append(c.text()).append("\n\n");
        }
        sb.append("【问题】\n").append(question);
        return sb.toString();
    }
}
