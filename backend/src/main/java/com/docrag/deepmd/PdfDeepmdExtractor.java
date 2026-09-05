package com.docrag.deepmd;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.docrag.parser.DocumentParseException;

/**
 * pdf → 统一文本：PDFTextStripper 只有文本流、无表格结构，
 * 整体纯文本直接作为统一文本（tableCount=0，不做启发式猜表）。
 */
@Component
public class PdfDeepmdExtractor implements DeepmdExtractor {

    @Override
    public boolean supports(String ext) {
        return "pdf".equalsIgnoreCase(ext);
    }

    @Override
    public DeepDocument extract(InputStream in) throws DocumentParseException {
        try (PDDocument doc = PDDocument.load(in)) {
            String text = new PDFTextStripper().getText(doc);
            if (text.isBlank()) {
                throw new DocumentParseException("PDF 未提取到文本，可能为扫描件（不支持 OCR）");
            }
            return new DeepDocument(text.stripTrailing(), 0);
        } catch (IOException e) {
            throw new DocumentParseException("pdf 表格提取失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
    }
}
