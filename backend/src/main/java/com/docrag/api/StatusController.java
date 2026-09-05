package com.docrag.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docrag.config.DocRagProperties;
import com.docrag.indexer.ModeIndexer;
import com.docrag.mode.Mode;
import com.docrag.vector.VectorClient;
import com.docrag.vector.VectorStats;

/**
 * 各库状态查询与一键清理（全量清空语义）。
 *
 * <p>清理一致性约定（CLAUDE.md）：vector-service 不可用时直接 400 拒绝——
 * 只清倒排会残留向量「幽灵命中」（仅向量召回的结果查看原文 404、问答无上下文）。</p>
 *
 * <p>清理范围：向量库（双 collection）→ plain 索引 → deep 索引 → data/upload/ 全部原文件
 * （目录保留），系统回到零状态，之后需重新上传。</p>
 */
@RestController
public class StatusController {

    private final Map<Mode, ModeIndexer> modeIndexers;
    private final VectorClient vectorClient;
    private final DocRagProperties props;

    public StatusController(Map<Mode, ModeIndexer> modeIndexers, VectorClient vectorClient,
                            DocRagProperties props) {
        this.modeIndexers = modeIndexers;
        this.vectorClient = vectorClient;
        this.props = props;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() throws IOException {
        return buildStatus();
    }

    @PostMapping("/api/admin/clear")
    public ResponseEntity<Map<String, Object>> clear() throws IOException {
        if (!vectorClient.ping()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "vector-service 不可用：为避免清理后残留幽灵命中已拒绝清理，请先启动 vector-service 再试"));
        }
        vectorClient.clearAll();
        modeIndexers.get(Mode.PLAIN).clearAll();
        modeIndexers.get(Mode.DEEP).clearAll();
        clearUploads();
        Map<String, Object> body = new LinkedHashMap<>(buildStatus());
        body.put("cleared", List.of("vector", "plain", "deep", "uploads"));
        return ResponseEntity.ok(body);
    }

    /** 全量清理：删除 upload 目录全部常规文件（目录本身保留） */
    private void clearUploads() throws IOException {
        Path dir = Paths.get(props.getUploadDir());
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private Map<String, Object> buildStatus() throws IOException {
        VectorStats stats = vectorClient.stats();
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("available", stats.available());
        vector.put("vectors", stats.vectors());
        vector.put("model", stats.model());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plainIndex", Map.of("docs", modeIndexers.get(Mode.PLAIN).count()));
        out.put("deepIndex", Map.of("docs", modeIndexers.get(Mode.DEEP).count()));
        out.put("vector", vector);
        out.put("uploads", countUploads());
        return out;
    }

    /** data/upload/ 常规文件数（隐藏占位文件如 .gitkeep 不计） */
    private int countUploads() {
        Path dir = Paths.get(props.getUploadDir());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }
}
