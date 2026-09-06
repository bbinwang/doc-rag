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
import com.docrag.vector.VectorClient;

/** 索引明细：两模式索引的文档列表与内容查看（plain 明细复用 GET /api/documents/{docId}），
 * 以及双向量 collection 的文档列表与 chunk 明细（透传 vector-service）。 */
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final Map<Mode, ModeSearcher> modeSearchers;
    private final VectorClient vectorClient;

    public StoreController(Map<Mode, ModeSearcher> modeSearchers, VectorClient vectorClient) {
        this.modeSearchers = modeSearchers;
        this.vectorClient = vectorClient;
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

    /** 向量库文档列表（该模式 collection，docId 维度聚合）；vector-service 不可用上抛转 500 */
    @GetMapping("/vector/{mode}")
    public Map<String, Object> vectorList(@PathVariable String mode) throws IOException {
        List<VectorClient.VectorDocSummary> docs = vectorClient.listDocs(Mode.of(mode));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("total", docs.size());
        out.put("chunkTotal", docs.stream().mapToInt(VectorClient.VectorDocSummary::chunkCount).sum());
        out.put("docs", docs);
        return out;
    }

    /** 向量库单文档 chunk 明细（按 chunkIndex 升序）；向量库中不存在转 404 */
    @GetMapping("/vector/{mode}/{docId}")
    public VectorClient.VectorDocDetail vectorDoc(@PathVariable String mode, @PathVariable String docId)
            throws IOException {
        VectorClient.VectorDocDetail detail = vectorClient.getDoc(Mode.of(mode), docId);
        if (detail == null) {
            throw new ResourceNotFoundException("向量库中不存在文档: " + docId);
        }
        return detail;
    }
}
