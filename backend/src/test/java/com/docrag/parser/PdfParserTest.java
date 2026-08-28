package com.docrag.parser;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class PdfParserTest {

    private final PdfParser parser = new PdfParser();

    @Test
    void supportsPdfOnly() {
        assertTrue(parser.supports("pdf"));
        assertTrue(parser.supports("PDF"));
        assertTrue(!parser.supports("docx"));
    }

    @Test
    void extractsText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("hello lucene search contract");
                cs.endText();
            }
            doc.save(out);
        }
        String text = parser.parse(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(text.contains("hello lucene search"));
    }

    @Test
    void corruptedFileThrowsParseException() {
        byte[] garbage = "not a pdf".getBytes();
        assertThrows(DocumentParseException.class,
                () -> parser.parse(new ByteArrayInputStream(garbage)));
    }
}
