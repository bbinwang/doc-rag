package com.docrag.evalcorpus;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 生成问答效果验证（eval/）的固定评测语料到 ../eval/corpus（surefire cwd = backend/）。
 *
 * 注意：语料内容与 eval/dataset.json 的 golden 答案强耦合——
 * 修改本文件内容必须同步修改题集，否则评测判分失效。
 * PDF 依赖系统中文字体（找不到直接 fail，不做英文回退——回退会破坏金答案）。
 */
class EvalCorpusTest {

    /** macOS 常见中文字体（ttf） */
    private static final String[] CN_FONTS = {
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Songti.ttc",
            "/Library/Fonts/Arial Unicode.ttf"
    };

    @Test
    void generateEvalCorpus() throws Exception {
        Path dir = Path.of("../eval/corpus");
        Files.createDirectories(dir);

        employeeHandbook(dir.resolve("员工手册.docx"));
        adminPolicy(dir.resolve("行政管理制度.docx"));
        budget2026(dir.resolve("2026部门预算表.xlsx"));
        priceList(dir.resolve("产品价格表.xlsx"));
        serviceContract(dir.resolve("服务合作合同.pdf"));
    }

    /** 语料 1：员工手册（长散文，每事实独立成段，段长 &lt; 128 字符保证 plain 切块不跨事实） */
    private static void employeeHandbook(Path file) throws Exception {
        String[] paragraphs = {
                "员工手册",
                "一、公司概况",
                "公司全称为杭州云帆信息技术有限公司，成立于2016年，总部位于杭州市，全职员工约500人。",
                "二、工作时间与考勤",
                "公司实行标准工时制，工作时间为周一至周五9:00至18:00，午休时间为12:00至13:30。",
                "三、试用期与假期",
                "试用期一般为三个月，高级岗位试用期最长不超过六个月。",
                "年假制度：入职满一年享有10天年假，满五年享有15天年假。",
                "四、离职管理",
                "离职须提前通知：正式员工应提前三十日以书面形式通知公司，试用期员工提前三日通知。",
                "五、加班管理",
                "加班需提前一个工作日通过线上系统申请，补偿方式为调休或按1.5倍时薪支付加班费。",
                "六、差旅报销",
                "差旅报销须在行程结束后十五个工作日内提交，逾期不予报销。",
                "七、部门负责人",
                "各部门负责人如下：研发部陈立群，销售部周雅雯，市场部沈亦非，人力资源部顾晓岚，财务部方鸿轩，运营部程子谦。",
                "八、培训要求",
                "员工每年参加必修培训不少于40学时。"
        };
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            for (String p : paragraphs) {
                doc.createParagraph().createRun().setText(p);
            }
            doc.write(out);
        }
    }

    /** 语料 2：行政管理制度（散文 + 2 表格按 body 顺序交错，验证 deep「表格 N」标题链路） */
    private static void adminPolicy(Path file) throws Exception {
        String[][] leaveTable = {
                {"类型", "最短提前申请（天）", "审批人"},
                {"病假", "1", "部门负责人"},
                {"事假", "3", "部门负责人"},
                {"年假", "5", "人力资源部"},
                {"婚假", "10", "人力资源部"},
                {"产假", "98", "总经理"}
        };
        String[][] expenseTable = {
                {"费用类别", "单次上限（元）", "票据要求"},
                {"市内交通", "100", "行程单或发票"},
                {"住宿（一线城市）", "500", "酒店发票"},
                {"住宿（其他城市）", "350", "酒店发票"},
                {"业务招待", "300", "发票及事由说明"}
        };
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            doc.createParagraph().createRun()
                    .setText("为规范考勤与报销管理，特制定本制度，适用于公司全体正式员工与实习生。");
            writeTable(doc, leaveTable);
            doc.createParagraph().createRun()
                    .setText("每自然月补卡机会不超过2次，超过次数或超时未打卡按缺勤处理。");
            writeTable(doc, expenseTable);
            doc.createParagraph().createRun()
                    .setText("本制度自2025年7月1日起施行，由人力资源部负责解释。");
            doc.write(out);
        }
    }

    private static void writeTable(XWPFDocument doc, String[][] rows) {
        XWPFTable table = doc.createTable(rows.length, rows[0].length);
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length; c++) {
                table.getRow(r).getCell(c).setText(rows[r][c]);
            }
        }
    }

    /** 语料 3：2026 部门预算表（单 sheet，数值列 setCellValue(double)，DataFormatter 输出无千分位） */
    private static void budget2026(Path file) throws Exception {
        Object[][] rows = {
                {"部门", "预算金额（元）", "负责人", "备注"},
                {"销售部", 1000000d, "周雅雯", "含市场推广"},
                {"研发部", 800000d, "陈立群", "新品研发"},
                {"市场部", 450000d, "沈亦非", "品牌活动"},
                {"运营部", 320000d, "程子谦", "仓储物流"},
                {"人力资源部", 260000d, "顾晓岚", "招聘培训"},
                {"财务部", 180000d, "方鸿轩", "系统升级"}
        };
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            var sheet = wb.createSheet("2026预算");
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] instanceof Double d) {
                        row.createCell(c).setCellValue(d);
                    } else {
                        row.createCell(c).setCellValue((String) rows[r][c]);
                    }
                }
            }
            wb.write(out);
        }
    }

    /** 语料 4：产品价格表（单 sheet，质保期为文本列） */
    private static void priceList(Path file) throws Exception {
        Object[][] rows = {
                {"产品名称", "型号", "单价（元）", "质保期"},
                {"智能会议大屏", "MAX-98", 15999d, "3年"},
                {"智能会议大屏", "MAX-75", 8999d, "3年"},
                {"视频会议摄像头", "VC-4K", 2399d, "2年"},
                {"全向麦克风", "AU-360", 1299d, "2年"},
                {"无线投屏器", "CS-20", 459d, "1年"},
                {"远程会议音箱", "SP-01", 699d, "1年"}
        };
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            var sheet = wb.createSheet("价格表");
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] instanceof Double d) {
                        row.createCell(c).setCellValue(d);
                    } else {
                        row.createCell(c).setCellValue((String) rows[r][c]);
                    }
                }
            }
            wb.write(out);
        }
    }

    /** 语料 5：服务合作合同（PDF 散文，中文字体缺失直接 fail） */
    private static void serviceContract(Path file) throws Exception {
        String[] lines = {
                "服务合作合同",
                "甲方：杭州云帆信息技术有限公司",
                "乙方：上海航启智能科技有限公司",
                "双方经友好协商，就智能会议系统技术服务事宜达成如下协议：",
                "一、服务期限",
                "本合同服务期限自2026年1月1日起至2026年12月31日止，有效期一年。",
                "二、服务费用与支付",
                "全年服务费总额为人民币486000元，按季度支付，每季度支付121500元。",
                "逾期付款的，每逾期一日按未付金额的0.05%向乙方支付滞纳金。",
                "三、违约责任",
                "任何一方违约，需向守约方支付合同总额15%的违约金。",
                "四、争议解决",
                "因本合同引起的争议，双方协商不成的，提交杭州仲裁委员会仲裁。"
        };
        File cnFont = findChineseFont();
        Assertions.assertNotNull(cnFont, "未找到中文字体，eval 语料 PDF 无法生成（golden 答案依赖中文内容）");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType0Font.load(doc, cnFont), 12);
                cs.newLineAtOffset(50, 780);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -22);
                }
                cs.endText();
            }
            doc.save(file.toFile());
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
