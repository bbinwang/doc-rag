package com.docrag.ask;

import java.util.List;

/** 问答请求：界面上勾选的 docIds + 索引格式（full/table，默认 full） */
public record AskRequest(String question, List<String> docIds, String format) {
}
