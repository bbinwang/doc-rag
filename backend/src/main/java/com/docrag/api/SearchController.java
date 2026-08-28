package com.docrag.api;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.docrag.searcher.DocumentSearcher;
import com.docrag.searcher.SearchResponse;

/** 关键词检索 */
@RestController
public class SearchController {

    private final DocumentSearcher searcher;

    public SearchController(DocumentSearcher searcher) {
        this.searcher = searcher;
    }

    @GetMapping("/api/search")
    public SearchResponse search(@RequestParam String q,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size)
            throws IOException, ParseException {
        if (q == null || q.isBlank()) {
            return new SearchResponse(0, List.of(), false);
        }
        int p = Math.max(1, page);
        int s = Math.min(50, Math.max(1, size));
        return searcher.search(q.trim(), p, s);
    }
}
