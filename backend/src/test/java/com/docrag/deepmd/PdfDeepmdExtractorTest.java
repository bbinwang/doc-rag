package com.docrag.deepmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import com.docrag.parser.DocumentParseException;

class PdfDeepmdExtractorTest {

    private final PdfDeepmdExtractor extractor = new PdfDeepmdExtractor();

    @Test
    void pdfYieldsPlainTextUnifiedDocument() throws Exception {
        DeepDocument doc = extractor.extract(new ByteArrayInputStream(textPdf("hello lucene budget text")));
        assertTrue(doc.content().contains("budget"), doc.content());
        assertEquals(0, doc.tableCount(), "PDF 无结构化表格，tableCount=0");
        assertTrue(!doc.content().contains("| --- |"), "PDF 不做启发式猜表: " + doc.content());
    }

    @Test
    void blankPdfThrowsParseException() throws Exception {
        // 无文本层的空页（模拟扫描件）→ 统一拒绝，与全文 Parser 行为一致
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(out);
        }
        assertThrows(DocumentParseException.class,
                () -> extractor.extract(new ByteArrayInputStream(out.toByteArray())));
    }

    private static byte[] textPdf(String line) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(line);
                cs.endText();
            }
            doc.save(out);
        }
        return out.toByteArray();
    }
}
