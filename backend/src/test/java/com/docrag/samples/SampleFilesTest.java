package com.docrag.samples;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/**
 * 生成三种格式的样例文档到 target/samples，
 * 供端到端冒烟测试使用（mvn test 后 curl 上传）。
 */
class SampleFilesTest {

    /** macOS 常见中文字体（ttf），找不到则 PDF 退回英文样例 */
    private static final String[] CN_FONTS = {
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Songti.ttc",
            "/Library/Fonts/Arial Unicode.ttf"
    };

    @Test
    void generateSampleDocuments() throws Exception {
        Path dir = Path.of("target/samples");
        Files.createDirectories(dir);

        // docx 样例
        try (XWPFDocument doc = new XWPFDocument();
             OutputStream out = Files.newOutputStream(dir.resolve("劳动合同 sample.docx"))) {
            doc.createParagraph().createRun().setText("劳动合同范本");
            doc.createParagraph().createRun().setText("本合同条款约定双方的权利与义务，试用期三个月。");
            doc.createParagraph().createRun().setText("任何一方违约，需向守约方支付合同总金额百分之二十的违约金。");
            doc.write(out);
        }

        // xlsx 样例
        try (XSSFWorkbook wb = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(dir.resolve("部门预算 sample.xlsx"))) {
            var sheet = wb.createSheet("2026预算");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("部门");
            header.createCell(1).setCellValue("预算金额");
            header.createCell(2).setCellValue("备注");
            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("销售部");
            row1.createCell(1).setCellValue(1000000);
            row1.createCell(2).setCellValue("含市场推广费");
            var row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("研发部");
            row2.createCell(1).setCellValue(800000);
            row2.createCell(2).setCellValue("新品研发预算");
            wb.write(out);
        }

        // pdf 样例
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            File cnFont = findChineseFont();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                if (cnFont != null) {
                    cs.setFont(PDType0Font.load(doc, cnFont), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("合同条款：本合同自双方签字之日起生效，有效期一年。");
                } else {
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("The contract terms take effect upon signature.");
                }
                cs.endText();
            }
            doc.save(dir.resolve("合同条款 sample.pdf").toFile());
        }
    }

    private static File findChineseFont() {
        for (String path : CN_FONTS) {
            File f = new File(path);
            if (f.exists()) {
                return f;
            }
        }
        return null;
    }
}
