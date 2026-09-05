package com.docrag.ask;

import java.util.List;

/** 问答请求：界面上勾选的 docIds + 解析模式（plain/deep，可多选，默认 plain） */
public record AskRequest(String question, List<String> docIds, List<String> modes) {
}
