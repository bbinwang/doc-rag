package com.docrag.deepmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class XlsxDeepmdExtractorTest {

    private final XlsxDeepmdExtractor extractor = new XlsxDeepmdExtractor();

    @Test
    void sheetBecomesUnifiedTextWithTitleAndHeader() throws Exception {
        DeepDocument doc = extractor.extract(new ByteArrayInputStream(xlsx("预算表", 2)));
        String text = doc.content();
        assertTrue(text.startsWith("预算表\n"), text);
        assertTrue(text.contains("| 部门 | 预算金额 |"), text);
        assertTrue(text.contains("| --- | --- |"), text);
        assertTrue(text.contains("| 销售部 | R1 |"), text);
        assertEquals(1, doc.tableCount());
    }

    @Test
    void multipleSheetsKeptInOrderWithBlankLineSeparator() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            fillSheet(wb.createSheet("预算表"), 2);
            fillSheet(wb.createSheet("人员编制"), 3);
            wb.write(out);
        }
        DeepDocument doc = extractor.extract(new ByteArrayInputStream(out.toByteArray()));
        String text = doc.content();
        int iBudget = text.indexOf("预算表");
        int iStaff = text.indexOf("人员编制");
        assertTrue(iBudget >= 0 && iBudget < iStaff, "sheet 按工作簿顺序: " + text);
        assertTrue(text.contains("\n\n人员编制"), "sheet 间空行分隔: " + text);
        assertEquals(2, doc.tableCount());
        // 每个 sheet 各一条分隔行
        assertEquals(2, countOccurrences(text, "| --- |"), text);
    }

    @Test
    void bigSheetNotSplit() throws Exception {
        // 表头 + 61 行数据 = 62 行：统一文本不分片，全部行共存、分隔行仅一条
        DeepDocument doc = extractor.extract(new ByteArrayInputStream(xlsx("大表", 62)));
        String text = doc.content();
        assertTrue(text.contains("R1"), text);
        assertTrue(text.contains("R60"), text);
        assertTrue(text.contains("R61"), text);
        assertEquals(1, countOccurrences(text, "| --- |"), text);
        assertEquals(1, doc.tableCount());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static void fillSheet(org.apache.poi.ss.usermodel.Sheet sheet, int totalRows) {
        var header = sheet.createRow(0);
        header.createCell(0).setCellValue("部门");
        header.createCell(1).setCellValue("预算金额");
        for (int r = 1; r < totalRows; r++) {
            var row = sheet.createRow(r);
            row.createCell(0).setCellValue("销售部");
            row.createCell(1).setCellValue("R" + r);
        }
    }

    /** 生成 1 个 sheet：首行表头（部门/预算金额），随后 totalRows-1 行数据（销售部 / R{n}） */
    private static byte[] xlsx(String sheetName, int totalRows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            fillSheet(wb.createSheet(sheetName), totalRows);
            wb.write(out);
        }
        return out.toByteArray();
    }
}
