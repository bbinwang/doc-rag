package com.docrag.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docrag.mode.Mode;
import com.docrag.searcher.DocumentDetail;
import com.docrag.searcher.ModeSearcher;
import com.docrag.searcher.StoreListItem;

/** 索引明细：两模式索引的文档列表与内容查看（plain 明细复用 GET /api/documents/{docId}） */
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final Map<Mode, ModeSearcher> modeSearchers;

    public StoreController(Map<Mode, ModeSearcher> modeSearchers) {
        this.modeSearchers = modeSearchers;
    }

    @GetMapping("/plain")
    public Map<String, Object> listPlain() {
        return listing(modeSearchers.get(Mode.PLAIN).listAll());
    }

    @GetMapping("/deep")
    public Map<String, Object> listDeep() {
        return listing(modeSearchers.get(Mode.DEEP).listAll());
    }

    @GetMapping("/deep/{docId}")
    public DocumentDetail deepDoc(@PathVariable String docId) throws IOException {
        DocumentDetail detail = modeSearchers.get(Mode.DEEP).getById(docId);
        if (detail == null) {
            throw new ResourceNotFoundException("文档不存在: " + docId);
        }
        return detail;
    }

    private static Map<String, Object> listing(List<StoreListItem> docs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", docs.size());
        out.put("docs", docs);
        return out;
    }
}
