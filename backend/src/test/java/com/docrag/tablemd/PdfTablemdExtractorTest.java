package com.docrag.tablemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class PdfTablemdExtractorTest {

    private final PdfTablemdExtractor extractor = new PdfTablemdExtractor();

    @Test
    void pdfYieldsTextFragmentsOnly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("hello lucene budget text");
                cs.endText();
            }
            doc.save(out);
        }
        List<TableFragment> fragments = extractor.extract(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(fragments.size() >= 1);
        fragments.forEach(f -> {
            assertEquals(TableFragment.KIND_TEXT, f.kind(), "PDF 不产生表格片段");
            assertEquals("正文", f.title());
        });
        assertTrue(fragments.get(0).content().contains("budget"));
    }
}
