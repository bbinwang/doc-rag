package com.docrag.parser;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** pdf 解析：仅支持文本型 PDF，扫描件不做 OCR */
@Component
public class PdfParser implements DocumentParser {

    @Override
    public boolean supports(String ext) {
        return "pdf".equalsIgnoreCase(ext);
    }

    @Override
    public String parse(InputStream in) throws DocumentParseException {
        try (PDDocument doc = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text.isBlank()) {
                throw new DocumentParseException("PDF 未提取到文本，可能为扫描件（不支持 OCR）");
            }
            return text;
        } catch (IOException e) {
            throw new DocumentParseException("pdf 解析失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
    }
}
