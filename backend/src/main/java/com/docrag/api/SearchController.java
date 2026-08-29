package com.docrag.api;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.docrag.parser.DocumentParseException;
import com.docrag.searcher.DocumentSearcher;
import com.docrag.searcher.SearchResponse;
import com.docrag.searcher.TableSearcher;

/** 关键词检索：format=full 走混合检索（BM25+向量），format=table 走表格 markdown 索引 */
@RestController
public class SearchController {

    private final DocumentSearcher searcher;
    private final TableSearcher tableSearcher;

    public SearchController(DocumentSearcher searcher, TableSearcher tableSearcher) {
        this.searcher = searcher;
        this.tableSearcher = tableSearcher;
    }

    @GetMapping("/api/search")
    public SearchResponse search(@RequestParam String q,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(defaultValue = "full") String format)
            throws IOException, ParseException, DocumentParseException {
        if (q == null || q.isBlank()) {
            return new SearchResponse(0, List.of(), false);
        }
        int p = Math.max(1, page);
        int s = Math.min(50, Math.max(1, size));
        String f = format == null || format.isBlank() ? "full" : format;
        switch (f) {
            case "full":
                return searcher.search(q.trim(), p, s);
            case "table":
                return tableSearcher.search(q.trim(), p, s);
            default:
                throw new DocumentParseException("不支持的索引格式: " + f + "（可选 full / table）");
        }
    }
}
