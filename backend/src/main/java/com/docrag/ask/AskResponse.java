package com.docrag.ask;

import java.util.Map;

/** 问答响应：key 顺序=请求 modes 顺序；每模式独立调用 LLM、各自出答案与召回明细 */
public record AskResponse(String model, Map<String, AskModeResult> modes, AskParams params) {
}
