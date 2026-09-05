package com.docrag.ask;

import java.util.List;

/** 问答响应：答案 + 实际送入 LLM 的上下文引用列表（modes 为本次实际采用的模式） */
public record AskResponse(String answer, String model, List<String> modes, List<AskCitation> citations) {
}
