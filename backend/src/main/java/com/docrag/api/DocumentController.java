package com.docrag.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.docrag.indexer.IngestService;
import com.docrag.mode.Mode;
import com.docrag.mode.Modes;
import com.docrag.parser.DocumentParseException;
import com.docrag.searcher.DocumentDetail;
import com.docrag.searcher.ModeSearcher;
import com.docrag.vector.VectorClient;

/** 文档上传入库（按 modes 写 plain/deep 各自的倒排+向量）、取 plain 原文、级联删除 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestService ingestService;
    private final ModeSearcher plainSearcher;
    private final VectorClient vectorClient;
    private final Map<Mode, com.docrag.indexer.ModeIndexer> modeIndexers;
    private final DocRagProperties props;

    public DocumentController(IngestService ingestService, ModeSearcher plainSearcher,
                              VectorClient vectorClient,
                              Map<Mode, com.docrag.indexer.ModeIndexer> modeIndexers,
                              DocRagProperties props) {
        this.ingestService = ingestService;
        this.plainSearcher = plainSearcher;
        this.vectorClient = vectorClient;
        this.modeIndexers = modeIndexers;
        this.props = props;
    }

    /** 取该文档 plain 模式写入索引的原始纯文本 */
    @GetMapping("/{docId}")
    public DocumentDetail get(@PathVariable String docId) throws IOException {
        DocumentDetail detail = plainSearcher.getById(docId);
        if (detail == null) {
            throw new ResourceNotFoundException("文档不存在: " + docId);
        }
        return detail;
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "modes", defaultValue = "plain,deep") List<String> modes)
            throws DocumentParseException, IOException {
        if (file == null || file.isEmpty()) {
            throw new DocumentParseException("上传文件为空");
        }
        Set<Mode> selected = Modes.parseList(modes);
        String filename = Filenames.sanitize(file.getOriginalFilename());
        String ext = Filenames.extOf(filename);

        Path uploadDir = Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
        String docId = UUID.randomUUID().toString();
        // 存储名加 uuid 前缀，避免同名文件互相覆盖
        Path target = uploadDir.resolve(docId + "_" + filename);

        file.transferTo(target);
        try (InputStream in = Files.newInputStream(target)) {
            IngestService.IngestResult result =
                    ingestService.ingest(docId, filename, target.toString(), ext, in, selected);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("docId", docId);
            out.put("filename", filename);
            out.put("type", ext);
            out.put("modes", Modes.ids(selected));
            out.put("chunkCount", result.chunkCounts());
            if (result.tableCount() != null) {
                out.put("tableCount", result.tableCount());
            }
            return out;
        } catch (DocumentParseException e) {
            Files.deleteIfExists(target); // 解析失败不留脏文件
            throw e;
        }
    }

    @DeleteMapping("/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) throws IOException {
        // 先删向量（双 collection）再删两个倒排；任一失败整体报错，用户可重试（各库删除幂等）
        vectorClient.delete(docId, null);
        for (Mode m : Mode.values()) {
            modeIndexers.get(m).delete(docId);
        }
        return Map.of("deleted", docId);
    }
}
