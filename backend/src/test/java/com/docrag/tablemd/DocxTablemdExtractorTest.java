package com.docrag.tablemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import com.docrag.parser.DocumentParseException;

class DocxTablemdExtractorTest {

    private final DocxTablemdExtractor extractor = new DocxTablemdExtractor();

    @Test
    void supportsDocxOnly() {
        assertTrue(extractor.supports("docx"));
        assertTrue(!extractor.supports("xlsx"));
        assertTrue(!extractor.supports("pdf"));
    }

    @Test
    void bodyOrderKeptAndTableBecomesMarkdown() throws Exception {
        byte[] docx = docxWithParagraphAndTable();
        List<TableFragment> fragments = extractor.extract(new ByteArrayInputStream(docx));

        // 顺序：正文 → 表格 → 正文，表格与正文不混排
        assertEquals(3, fragments.size());
        assertEquals(TableFragment.KIND_TEXT, fragments.get(0).kind());
        assertEquals(TableFragment.KIND_TABLE, fragments.get(1).kind());
        assertEquals("表格 1", fragments.get(1).title());
        assertEquals(TableFragment.KIND_TEXT, fragments.get(2).kind());

        assertTrue(fragments.get(0).content().contains("合同正文开始"));
        String md = fragments.get(1).content();
        assertTrue(md.contains("| 姓名 | 年龄 |"), md);
        assertTrue(md.contains("| --- | --- |"));
        assertTrue(md.contains("| 张三 | 25 |"));
        // 单元格内管道符转义
        assertTrue(md.contains("备\\|注"), md);
        assertTrue(fragments.get(2).content().contains("表格之后的正文"));
    }

    @Test
    void corruptedFileThrowsParseException() {
        byte[] garbage = "not a docx".getBytes();
        assertThrows(DocumentParseException.class,
                () -> extractor.extract(new ByteArrayInputStream(garbage)));
    }

    private static byte[] docxWithParagraphAndTable() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("合同正文开始，说明条款。");
            XWPFTable table = doc.createTable(2, 3);
            table.getRow(0).getCell(0).setText("姓名");
            table.getRow(0).getCell(1).setText("年龄");
            table.getRow(0).getCell(2).setText("备注");
            table.getRow(1).getCell(0).setText("张三");
            table.getRow(1).getCell(1).setText("25");
            table.getRow(1).getCell(2).setText("备|注");
            doc.createParagraph().createRun().setText("表格之后的正文。");
            doc.write(out);
        }
        return out.toByteArray();
    }
}
