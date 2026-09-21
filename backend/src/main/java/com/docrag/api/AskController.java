package com.docrag.api;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docrag.ask.AskParams;
import com.docrag.ask.AskRequest;
import com.docrag.ask.AskResponse;
import com.docrag.ask.AskService;
import com.docrag.ask.LlmClient;
import com.docrag.config.DocRagProperties;
import com.docrag.mode.Modes;
import com.docrag.parser.DocumentParseException;

/** 文档问答：全库按模式混合检索（BM25+向量 chunk 级 RRF）→ 每模式独立 LLM → 答案 + 召回明细 */
@RestController
@RequestMapping("/api/ask")
public class AskController {

    private final AskService askService;
    private final LlmClient llmClient;
    private final DocRagProperties.Ask askCfg;

    public AskController(AskService askService, LlmClient llmClient, DocRagProperties props) {
        this.askService = askService;
        this.llmClient = llmClient;
        this.askCfg = props.getAsk();
    }

    @PostMapping
    public AskResponse ask(@RequestBody AskRequest request)
            throws IOException, DocumentParseException {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new DocumentParseException("问题不能为空");
        }
        if (!llmClient.isEnabled()) {
            throw new DocumentParseException(
                    "LLM 未配置：请设置 DOCRAG_LLM_BASE_URL / DOCRAG_LLM_API_KEY / DOCRAG_LLM_MODEL 后重启后端");
        }
        List<String> modeIds = request.modes() == null || request.modes().isEmpty()
                ? List.of("plain") : request.modes();
        Set<com.docrag.mode.Mode> modes;
        try {
            modes = Modes.parseList(modeIds);
        } catch (IllegalArgumentException e) {
            throw new DocumentParseException(e.getMessage());
        }
        return askService.ask(request.question().trim(), modes,
                request.bm25Chunks(), request.vectorChunks(), request.contextChunks());
    }

    /** 问答三参数默认值（前端问答页初始值用，改 yml 即时生效） */
    @GetMapping("/params")
    public AskParams params() {
        return new AskParams(askCfg.getBm25Chunks(), askCfg.getVectorChunks(),
                askCfg.getContextChunks());
    }
}
