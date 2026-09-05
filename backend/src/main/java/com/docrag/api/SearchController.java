package com.docrag.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.docrag.mode.Mode;
import com.docrag.mode.Modes;
import com.docrag.parser.DocumentParseException;
import com.docrag.searcher.ModeSearcher;
import com.docrag.searcher.SearchResponse;

/** 关键词检索：按 modes（plain/deep，可多选）对每模式独立执行混合检索（BM25+向量） */
@RestController
public class SearchController {

    private final Map<Mode, ModeSearcher> modeSearchers;

    public SearchController(Map<Mode, ModeSearcher> modeSearchers) {
        this.modeSearchers = modeSearchers;
    }

    @GetMapping("/api/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int size,
                                      @RequestParam(defaultValue = "plain") List<String> modes)
            throws IOException, ParseException, DocumentParseException {
        Set<Mode> selected = Modes.parseList(modes);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> byMode = new LinkedHashMap<>();
        if (q == null || q.isBlank()) {
            for (Mode m : selected) {
                byMode.put(m.id(), new SearchResponse(0, List.of(), false));
            }
        } else {
            int p = Math.max(1, page);
            int s = Math.min(50, Math.max(1, size));
            for (Mode m : selected) {
                byMode.put(m.id(), modeSearchers.get(m).search(q.trim(), p, s));
            }
        }
        out.put("modes", byMode);
        return out;
    }
}
