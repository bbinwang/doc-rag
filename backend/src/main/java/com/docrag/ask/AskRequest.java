package com.docrag.ask;

import java.util.List;

/** 问答请求：全库检索不选文档；三个检索参数可选（null/缺省 = 用配置默认，后端统一钳制） */
public record AskRequest(String question, List<String> modes,
                         Integer bm25Chunks, Integer vectorChunks, Integer contextChunks) {
}
