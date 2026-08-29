package com.docrag.tablemd;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.docrag.indexer.Chunker;
import com.docrag.parser.DocumentParseException;

/**
 * pdf → 仅正文片段：PDFTextStripper 只有文本流、无表格结构，
 * 不做启发式猜表（架构定位边界），表格 markdown 片段恒为空。
 */
@Component
public class PdfTablemdExtractor implements TablemdExtractor {

    @Override
    public boolean supports(String ext) {
        return "pdf".equalsIgnoreCase(ext);
    }

    @Override
    public List<TableFragment> extract(InputStream in) throws DocumentParseException {
        try (PDDocument doc = PDDocument.load(in)) {
            String text = new PDFTextStripper().getText(doc);
            if (text.isBlank()) {
                throw new DocumentParseException("PDF 未提取到文本，可能为扫描件（不支持 OCR）");
            }
            List<TableFragment> fragments = new ArrayList<>();
            for (String chunk : Chunker.chunk(text, BODY_CHUNK_CHARS)) {
                fragments.add(TableFragment.text("正文", chunk));
            }
            return fragments;
        } catch (IOException e) {
            throw new DocumentParseException("pdf 表格提取失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
    }
}
