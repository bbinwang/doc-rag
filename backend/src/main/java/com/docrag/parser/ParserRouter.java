package com.docrag.parser;

import java.util.List;

import org.springframework.stereotype.Component;

/** 按扩展名把文档路由到对应解析器 */
@Component
public class ParserRouter {

    private final List<DocumentParser> parsers;

    public ParserRouter(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public DocumentParser route(String ext) throws DocumentParseException {
        return parsers.stream()
                .filter(p -> p.supports(ext))
                .findFirst()
                .orElseThrow(() -> new DocumentParseException(
                        "不支持的文件类型: " + ext + "（仅支持 docx/xlsx/pdf）"));
    }
}
