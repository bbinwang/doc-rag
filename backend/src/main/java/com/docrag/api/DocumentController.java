package com.docrag.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.docrag.config.DocRagProperties;
import com.docrag.indexer.Chunker;
import com.docrag.indexer.DocumentIndexer;
import com.docrag.indexer.TableIndexer;
import com.docrag.parser.DocumentParseException;
import com.docrag.parser.DocumentParser;
import com.docrag.parser.ParserRouter;
import com.docrag.searcher.DocumentDetail;
import com.docrag.searcher.DocumentSearcher;
import com.docrag.tablemd.TableFragment;
import com.docrag.tablemd.TablemdRouter;
import com.docrag.vector.VectorClient;

/** 文档上传入库（全文 + 表格 markdown 双倒排 + 向量三库）、按 ID 取原文、删除 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final ParserRouter parserRouter;
    private final TablemdRouter tablemdRouter;
    private final DocumentIndexer indexer;
    private final TableIndexer tableIndexer;
    private final DocumentSearcher searcher;
    private final VectorClient vectorClient;
    private final DocRagProperties props;

    public DocumentController(ParserRouter parserRouter, TablemdRouter tablemdRouter,
                              DocumentIndexer indexer, TableIndexer tableIndexer,
                              DocumentSearcher searcher, VectorClient vectorClient,
                              DocRagProperties props) {
        this.parserRouter = parserRouter;
        this.tablemdRouter = tablemdRouter;
        this.indexer = indexer;
        this.tableIndexer = tableIndexer;
        this.searcher = searcher;
        this.vectorClient = vectorClient;
        this.props = props;
    }

    /** 取该文档写入索引的原始纯文本 */
    @GetMapping("/{docId}")
    public DocumentDetail get(@PathVariable String docId) throws IOException {
        DocumentDetail detail = searcher.getById(docId);
        if (detail == null) {
            throw new ResourceNotFoundException("文档不存在: " + docId);
        }
        return detail;
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file)
            throws DocumentParseException, IOException {
        if (file == null || file.isEmpty()) {
            throw new DocumentParseException("上传文件为空");
        }
        String filename = Filenames.sanitize(file.getOriginalFilename());
        String ext = Filenames.extOf(filename);
        DocumentParser parser = parserRouter.route(ext);

        Path uploadDir = Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
        String docId = UUID.randomUUID().toString();
        // 存储名加 uuid 前缀，避免同名文件互相覆盖
        Path target = uploadDir.resolve(docId + "_" + filename);

        file.transferTo(target);
        try (InputStream in = Files.newInputStream(target)) {
            String content = parser.parse(in);
            // 两种倒排格式先全部解析成功，再开始写库（fail-fast，避免半成品回滚链变长）
            List<TableFragment> fragments;
            try (InputStream tableIn = Files.newInputStream(target)) {
                fragments = tablemdRouter.route(ext).extract(tableIn);
            }
            List<String> chunks = Chunker.chunk(content);

            // 三库写入：全文倒排 → 表格倒排 → 向量；任一失败回滚已写库
            indexer.index(docId, filename, target.toString(), ext, content);
            try {
                tableIndexer.index(docId, filename, target.toString(), ext, fragments);
            } catch (Exception te) {
                indexer.delete(docId);
                throw new IllegalStateException("表格索引构建失败: " + te.getMessage(), te);
            }
            int chunkCount;
            try {
                chunkCount = vectorClient.upsert(docId, filename, ext, chunks);
            } catch (Exception ve) {
                indexer.delete(docId);
                tableIndexer.delete(docId);
                throw new IllegalStateException(
                        "向量索引构建失败（请确认 vector-service 已启动）: " + ve.getMessage(), ve);
            }
            return Map.of("docId", docId, "filename", filename, "type", ext,
                    "chunkCount", chunkCount, "fragmentCount", fragments.size());
        } catch (DocumentParseException e) {
            Files.deleteIfExists(target); // 解析失败不留脏文件
            throw e;
        }
    }

    @DeleteMapping("/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) throws IOException {
        // 先删向量再删两个倒排；任一失败整体报错，用户可重试（各库删除幂等）
        vectorClient.delete(docId);
        tableIndexer.delete(docId);
        indexer.delete(docId);
        return Map.of("deleted", docId);
    }
}
