package com.docrag.deepmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import com.docrag.parser.DocumentParseException;

class DocxDeepmdExtractorTest {

    private final DocxDeepmdExtractor extractor = new DocxDeepmdExtractor();

    @Test
    void supportsDocxOnly() {
        assertTrue(extractor.supports("docx"));
        assertTrue(!extractor.supports("xlsx"));
        assertTrue(!extractor.supports("pdf"));
    }

    @Test
    void bodyOrderKeptInUnifiedText() throws Exception {
        DeepDocument doc = extractor.extract(new ByteArrayInputStream(docxWithParagraphAndTable()));

        String text = doc.content();
        // 顺序：正文 → 表格标题+markdown → 正文，按 indexOf 断言先后
        int iBody = text.indexOf("合同正文开始");
        int iTitle = text.indexOf("表格 1");
        int iHeader = text.indexOf("| 姓名 | 年龄 |");
        int iRow = text.indexOf("| 张三 | 25 |");
        int iAfter = text.indexOf("表格之后的正文");
        assertTrue(iBody >= 0 && iBody < iTitle, text);
        assertTrue(iTitle < iHeader && iHeader < iRow, text);
        assertTrue(iRow < iAfter, text);
        // 表格标题行与表头行紧连（中间无空行），保证块感知切块时整体保留
        int titleLineEnd = text.indexOf('\n', iTitle);
        assertTrue(text.substring(titleLineEnd + 1, iHeader).isEmpty(),
                "标题行后应紧跟表头行: " + text.substring(iTitle, iHeader + 10));
        // markdown 表格结构
        assertTrue(text.contains("| --- | --- |"), text);
        // 单元格内管道符转义
        assertTrue(text.contains("备\\|注"), text);
        // 表格前有空行分隔
        assertTrue(text.contains("\n\n表格 1"), text);
        // tableCount = body 中表格数
        assertEquals(1, doc.tableCount());
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
