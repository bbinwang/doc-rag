package com.docrag.api;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docrag.ask.AskRequest;
import com.docrag.ask.AskResponse;
import com.docrag.ask.AskService;
import com.docrag.ask.LlmClient;
import com.docrag.mode.Modes;
import com.docrag.parser.DocumentParseException;

/** 文档问答：选中范围内按模式检索 → LLM → 答案 + 引用（含来源模式） */
@RestController
@RequestMapping("/api/ask")
public class AskController {

    /** 单次问答可勾选的文档数上限 */
    private static final int MAX_DOC_IDS = 50;

    private final AskService askService;
    private final LlmClient llmClient;

    public AskController(AskService askService, LlmClient llmClient) {
        this.askService = askService;
        this.llmClient = llmClient;
    }

    @PostMapping
    public AskResponse ask(@RequestBody AskRequest request)
            throws IOException, ParseException, DocumentParseException {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new DocumentParseException("问题不能为空");
        }
        List<String> docIds = request.docIds();
        if (docIds == null || docIds.isEmpty()) {
            throw new DocumentParseException("请先勾选至少一个文档");
        }
        if (docIds.size() > MAX_DOC_IDS) {
            throw new DocumentParseException("一次最多选择 " + MAX_DOC_IDS + " 个文档");
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
        return askService.ask(request.question().trim(), docIds, modes);
    }
}
