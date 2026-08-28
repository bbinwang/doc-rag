package com.docrag.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import com.docrag.parser.DocumentParseException;
import com.docrag.parser.DocxParser;
import com.docrag.parser.XlsxParser;

class DebugParseServiceTest {

    /** 1x1 透明 PNG，用于构造带图 docx */
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    private final DebugParseService service = new DebugParseService(new DocxParser(), new XlsxParser());

    @Test
    void docxTablesAndImageCount() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("表格前的说明段落");
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("姓名");
            table.getRow(0).getCell(1).setText("年龄");
            table.getRow(1).getCell(0).setText("张三");
            table.getRow(1).getCell(1).setText("25");
            XWPFRun run = doc.createParagraph().createRun();
            run.addPicture(new java.io.ByteArrayInputStream(TINY_PNG),
                    XWPFDocument.PICTURE_TYPE_PNG, "pic.png",
                    Units.toEMU(10), Units.toEMU(10));
            doc.write(out);
        }

        DebugParseResult result = service.parse("员工表.docx", out.toByteArray());
        assertEquals("docx", result.type());
        assertEquals(1, result.imageCount());
        assertEquals(1, result.tableCount());
        assertEquals("表格 1", result.tables().get(0).title());
        assertEquals(java.util.List.of(java.util.List.of("姓名", "年龄"),
                java.util.List.of("张三", "25")), result.tables().get(0).rows());
        // indexedText 与生产 DocxParser 输出一致（含表格拍平文本与段落）
        assertTrue(result.indexedText().contains("表格前的说明段落"));
        assertTrue(result.indexedText().contains("张三"));
        assertFalse(result.textTruncated());
    }

    @Test
    void xlsxSheetsAsTables() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("花名册");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("部门");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("李四");
            row.createCell(1).setCellValue("研发部");
            wb.write(out);
        }

        DebugParseResult result = service.parse("花名册.xlsx", out.toByteArray());
        assertEquals("xlsx", result.type());
        assertEquals(0, result.imageCount());
        assertEquals(1, result.tableCount());
        assertEquals("花名册", result.tables().get(0).title());
        assertEquals("研发部", result.tables().get(0).rows().get(1).get(1));
        assertTrue(result.indexedText().contains("李四\t研发部"));
    }

    @Test
    void bigTableTruncated() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("大表");
            for (int i = 0; i < DebugParseService.MAX_TABLE_ROWS + 50; i++) {
                sheet.createRow(i).createCell(0).setCellValue("行" + i);
            }
            wb.write(out);
        }
        DebugParseResult result = service.parse("大表.xlsx", out.toByteArray());
        assertEquals(DebugParseService.MAX_TABLE_ROWS, result.tables().get(0).rows().size());
        assertTrue(result.tablesTruncated());
        // 索引文本不截断的场合必须完整（对比意义所在）；此用例文本远小于上限
        assertFalse(result.textTruncated());
    }

    @Test
    void pdfNotSupportedYet() {
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> service.parse("x.pdf", new byte[]{1, 2, 3}));
        assertTrue(e.getMessage().contains("暂未实现"));
    }
}
