package com.docrag.tablemd;

import java.util.List;

import org.springframework.stereotype.Component;

import com.docrag.parser.DocumentParseException;

/** 按扩展名把文档路由到对应的表格 markdown 提取器 */
@Component
public class TablemdRouter {

    private final List<TablemdExtractor> extractors;

    public TablemdRouter(List<TablemdExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    public TablemdExtractor route(String ext) throws DocumentParseException {
        return extractors.stream()
                .filter(e -> e.supports(ext))
                .findFirst()
                .orElseThrow(() -> new DocumentParseException(
                        "不支持的文件类型: " + ext + "（仅支持 docx/xlsx/pdf）"));
    }
}
