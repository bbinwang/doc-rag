package com.docrag.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class XlsxParserTest {

    private final XlsxParser parser = new XlsxParser();

    @Test
    void supportsXlsxOnly() {
        assertTrue(parser.supports("xlsx"));
        assertTrue(parser.supports("XLSX"));
        assertTrue(!parser.supports("xls"));
    }

    @Test
    void extractsRowsWithSheetName() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("2026预算");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("部门");
            header.createCell(1).setCellValue("预算金额");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("销售部");
            row.createCell(1).setCellValue(1000000);
            wb.write(out);
        }
        String text = parser.parse(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(text.contains("## 2026预算"));
        assertTrue(text.contains("部门\t预算金额"));
        assertTrue(text.contains("销售部\t1000000"));
    }
}
