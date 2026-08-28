package com.docrag.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocxParserTest {

    private final DocxParser parser = new DocxParser();

    @Test
    void supportsDocxOnly() {
        assertTrue(parser.supports("docx"));
        assertTrue(parser.supports("DOCX"));
        assertEquals(false, parser.supports("doc"));
        assertEquals(false, parser.supports("pdf"));
    }

    @Test
    void extractsParagraphText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Hello doc-rag 世界");
            doc.createParagraph().createRun().setText("第二段落内容");
            doc.write(out);
        }
        String text = parser.parse(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(text.contains("Hello doc-rag 世界"));
        assertTrue(text.contains("第二段落内容"));
    }

    @Test
    void corruptedFileThrowsParseException() {
        byte[] garbage = "not a docx file".getBytes();
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> parser.parse(new ByteArrayInputStream(garbage)));
        assertTrue(e.getMessage().contains("docx 解析失败"));
    }
}
