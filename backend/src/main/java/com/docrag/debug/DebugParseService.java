package com.docrag.debug;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import com.docrag.parser.DocumentParseException;
import com.docrag.parser.DocxParser;
import com.docrag.parser.XlsxParser;

/**
 * debug 解析：返回结构化明细（表格行列、图片计数），不入库、不落盘。
 * 关键约束：indexedText 直接调用生产 DocxParser/XlsxParser，
 * 保证 debug 页对比的就是「真实写入索引 content 字段的文本」。
 */
@Service
public class DebugParseService {

    /** 表格行数 / 索引文本展示上限，防止超大文档撑爆 JSON 与页面 */
    static final int MAX_TABLE_ROWS = 100;
    static final int MAX_TEXT_CHARS = 50_000;

    private final DocxParser docxParser;
    private final XlsxParser xlsxParser;

    public DebugParseService(DocxParser docxParser, XlsxParser xlsxParser) {
        this.docxParser = docxParser;
        this.xlsxParser = xlsxParser;
    }

    public DebugParseResult parse(String filename, byte[] data) throws DocumentParseException {
        String ext = com.docrag.api.Filenames.extOf(filename);
        return switch (ext) {
            case "docx" -> parseDocx(filename, ext, data);
            case "xlsx" -> parseXlsx(filename, ext, data);
            case "pdf" -> throw new DocumentParseException("PDF 的 debug 解析暂未实现（第一期支持 docx/xlsx）");
            default -> throw new DocumentParseException("不支持的文件类型: " + ext + "（debug 解析支持 docx/xlsx）");
        };
    }

    private DebugParseResult parseDocx(String filename, String ext, byte[] data)
            throws DocumentParseException {
        String indexedText = docxParser.parse(new ByteArrayInputStream(data));

        List<TableInfo> tables = new ArrayList<>();
        boolean truncated = false;
        int imageCount;
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            imageCount = doc.getAllPictures().size();
            List<XWPFTable> docTables = doc.getTables();
            for (int i = 0; i < docTables.size(); i++) {
                XWPFTable table = docTables.get(i);
                List<XWPFTableRow> allRows = table.getRows();
                if (allRows.size() > MAX_TABLE_ROWS) {
                    truncated = true;
                }
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r < Math.min(allRows.size(), MAX_TABLE_ROWS); r++) {
                    rows.add(allRows.get(r).getTableCells().stream()
                            .map(XWPFTableCell::getText)
                            .toList());
                }
                tables.add(new TableInfo("表格 " + (i + 1), rows));
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("docx 结构化解析失败: " + e.getMessage(), e);
        }
        return buildResult(filename, ext, imageCount, tables, truncated, indexedText);
    }

    private DebugParseResult parseXlsx(String filename, String ext, byte[] data)
            throws DocumentParseException {
        String indexedText = xlsxParser.parse(new ByteArrayInputStream(data));

        DataFormatter formatter = new DataFormatter();
        List<TableInfo> tables = new ArrayList<>();
        boolean truncated = false;
        int imageCount;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            imageCount = workbook.getAllPictures().size();
            Iterator<Sheet> sheets = workbook.sheetIterator();
            while (sheets.hasNext()) {
                Sheet sheet = sheets.next();
                List<List<String>> rows = new ArrayList<>();
                Iterator<Row> rowIterator = sheet.rowIterator();
                while (rowIterator.hasNext()) {
                    if (rows.size() >= MAX_TABLE_ROWS) {
                        truncated = true;
                        break;
                    }
                    Row row = rowIterator.next();
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        cells.add(formatter.formatCellValue(row.getCell(c)));
                    }
                    rows.add(cells);
                }
                tables.add(new TableInfo(sheet.getSheetName(), rows));
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("xlsx 结构化解析失败: " + e.getMessage(), e);
        }
        return buildResult(filename, ext, imageCount, tables, truncated, indexedText);
    }

    private static DebugParseResult buildResult(String filename, String type, int imageCount,
                                                List<TableInfo> tables, boolean tablesTruncated,
                                                String indexedText) {
        boolean textTruncated = indexedText.length() > MAX_TEXT_CHARS;
        String shown = textTruncated ? indexedText.substring(0, MAX_TEXT_CHARS) : indexedText;
        return new DebugParseResult(filename, type, imageCount, tables.size(),
                tablesTruncated, textTruncated, shown, tables);
    }
}
