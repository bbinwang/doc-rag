package com.docrag.tablemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class XlsxTablemdExtractorTest {

    private final XlsxTablemdExtractor extractor = new XlsxTablemdExtractor();

    @Test
    void sheetBecomesSingleMarkdownFragmentWithHeader() throws Exception {
        List<TableFragment> fragments = extractor.extract(
                new ByteArrayInputStream(xlsx("预算表", 2)));
        assertEquals(1, fragments.size());
        TableFragment f = fragments.get(0);
        assertEquals(TableFragment.KIND_TABLE, f.kind());
        assertEquals("预算表", f.title());
        assertTrue(f.content().contains("| 部门 | 预算金额 |"), f.content());
        assertTrue(f.content().contains("| --- | --- |"));
        assertTrue(f.content().contains("| 销售部 | R1 |"));
    }

    @Test
    void bigSheetSplitsWithRepeatedHeader() throws Exception {
        // 表头 + 61 行数据 = 62 行 → 2 片段（60 行/片段），后续片段重复表头
        List<TableFragment> fragments = extractor.extract(
                new ByteArrayInputStream(xlsx("大表", 62)));
        assertEquals(2, fragments.size());
        assertEquals("大表 (1/2)", fragments.get(0).title());
        assertEquals("大表 (2/2)", fragments.get(1).title());
        assertTrue(fragments.get(0).content().contains("R1"));
        assertTrue(!fragments.get(0).content().contains("R60"));
        // 第二片段重复表头且包含尾部数据行
        assertTrue(fragments.get(1).content().contains("| 部门 | 预算金额 |"));
        assertTrue(fragments.get(1).content().contains("R60"));
        assertTrue(fragments.get(1).content().contains("R61"));
    }

    /** 生成 1 个 sheet：首行表头（部门/预算金额），随后 dataRows 行（销售部 / R{n}） */
    private static byte[] xlsx(String sheetName, int totalRows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet(sheetName);
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("部门");
            header.createCell(1).setCellValue("预算金额");
            for (int r = 1; r < totalRows; r++) {
                var row = sheet.createRow(r);
                row.createCell(0).setCellValue("销售部");
                row.createCell(1).setCellValue("R" + r);
            }
            wb.write(out);
        }
        return out.toByteArray();
    }
}
