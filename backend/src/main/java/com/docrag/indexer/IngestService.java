package com.docrag.indexer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.docrag.deepmd.DeepDocument;
import com.docrag.deepmd.DeepmdRouter;
import com.docrag.mode.Mode;
import com.docrag.parser.DocumentParseException;
import com.docrag.parser.ParserRouter;
import com.docrag.vector.VectorClient;

/**
 * 入库编排（从 Controller 抽出）：按选中模式解析 → per-mode 切块 → 写库 + 回滚。
 *
 * <p>fail-fast：选中模式的解析全部成功才开始写库，解析失败时未写任何库。</p>
 *
 * <p>写库顺序：plain 倒排 → deep 倒排 → plain 向量 → deep 向量
 * （本地 Lucene 先写、外部 vector-service 后写）；任一失败按已写集合逆序回滚，
 * 回滚自身的失败 log.warn 吞掉、保证原始异常优先抛出。</p>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ParserRouter parserRouter;
    private final DeepmdRouter deepmdRouter;
    private final Map<Mode, ModeIndexer> modeIndexers;
    private final VectorClient vectorClient;

    public IngestService(ParserRouter parserRouter, DeepmdRouter deepmdRouter,
                         Map<Mode, ModeIndexer> modeIndexers, VectorClient vectorClient) {
        this.parserRouter = parserRouter;
        this.deepmdRouter = deepmdRouter;
        this.modeIndexers = modeIndexers;
        this.vectorClient = vectorClient;
    }

    /** 入库结果：各模式向量 chunk 数 + deep 模式的 markdown 表格数（未选 deep 时为 null） */
    public record IngestResult(Map<String, Integer> chunkCounts, Integer tableCount) {
    }

    public IngestResult ingest(String docId, String filename, String path, String ext,
                               InputStream in, Set<Mode> modes)
            throws IOException, DocumentParseException {
        // ① fail-fast 解析：全部选中模式解析成功才进写库。
        // 多模式共用上传流：先整体读入内存，各模式各自从独立流解析（流不可回读）
        byte[] bytes = in.readAllBytes();
        String plainText = null;
        DeepDocument deepDoc = null;
        if (modes.contains(Mode.PLAIN)) {
            plainText = parserRouter.route(ext).parse(new java.io.ByteArrayInputStream(bytes));
        }
        if (modes.contains(Mode.DEEP)) {
            deepDoc = deepmdRouter.route(ext).extract(new java.io.ByteArrayInputStream(bytes));
        }

        // ② per-mode 切块（独立两套）
        Map<Mode, List<String>> chunksByMode = new LinkedHashMap<>();
        if (plainText != null) {
            chunksByMode.put(Mode.PLAIN, Chunker.chunk(plainText));
        }
        if (deepDoc != null) {
            chunksByMode.put(Mode.DEEP, Chunker.chunkKeepingTables(
                    deepDoc.content(), Chunker.DEEP_EMBED_MAX_CHARS));
        }

        // ③ 写库 + 回滚：写入成功即登记撤销动作，失败逆序执行
        List<Runnable> rollbacks = new ArrayList<>();
        try {
            for (Mode m : modes) {
                String content = m == Mode.PLAIN ? plainText : deepDoc.content();
                modeIndexers.get(m).index(docId, filename, path, ext, content);
                rollbacks.add(() -> {
                    try {
                        modeIndexers.get(m).delete(docId);
                    } catch (IOException e) {
                        log.warn("[{}] 回滚倒排删除失败 docId={}: {}", m, docId, e.getMessage());
                    }
                });
            }
            for (Mode m : chunksByMode.keySet()) {
                vectorClient.upsert(m, docId, filename, ext, chunksByMode.get(m));
                rollbacks.add(() -> {
                    try {
                        vectorClient.delete(docId, m);
                    } catch (IOException e) {
                        log.warn("[{}] 回滚向量删除失败 docId={}: {}", m, docId, e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            for (int i = rollbacks.size() - 1; i >= 0; i--) {
                rollbacks.get(i).run();
            }
            throw new IllegalStateException(
                    "入库失败（已回滚已写入的库，请确认 vector-service 已启动）: " + e, e);
        }

        Map<String, Integer> chunkCounts = new LinkedHashMap<>();
        chunksByMode.forEach((m, chunks) -> chunkCounts.put(m.id(), chunks.size()));
        return new IngestResult(chunkCounts, deepDoc != null ? deepDoc.tableCount() : null);
    }
}
