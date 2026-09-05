package com.docrag.deepmd;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import com.docrag.parser.DocumentParseException;

/**
 * xlsx → 统一文本：每非空 sheet = sheet 名标题行 + 完整 markdown 表格（首行作表头，不分片），
 * sheet 间空行分隔。tableCount = 非空 sheet 数。
 */
@Component
public class XlsxDeepmdExtractor implements DeepmdExtractor {

    @Override
    public boolean supports(String ext) {
        return "xlsx".equalsIgnoreCase(ext);
    }

    @Override
    public DeepDocument extract(InputStream in) throws DocumentParseException {
        StringBuilder sb = new StringBuilder();
        int tableCount = 0;
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Iterator<Sheet> sheets = workbook.sheetIterator();
            while (sheets.hasNext()) {
                Sheet sheet = sheets.next();
                List<List<String>> rows = readRows(sheet, formatter);
                if (rows.isEmpty()) {
                    continue;
                }
                tableCount++;
                if (sb.length() > 0) {
                    sb.append('\n'); // sheet 间空行分隔
                }
                sb.append(sheet.getSheetName()).append('\n');
                sb.append(MarkdownTable.toMarkdown(rows));
                sb.append('\n');
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("xlsx 表格提取失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
        return new DeepDocument(sb.toString().stripTrailing(), tableCount);
    }

    /** 逐行读取（空行剔除），与生产 XlsxParser 的取数方式一致（DataFormatter 保持显示格式） */
    private static List<List<String>> readRows(Sheet sheet, DataFormatter formatter) {
        List<List<String>> rows = new ArrayList<>();
        Iterator<Row> rowIterator = sheet.rowIterator();
        while (rowIterator.hasNext()) {
            Row row = rowIterator.next();
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.getLastCellNum(); c++) {
                cells.add(formatter.formatCellValue(row.getCell(c)));
            }
            if (cells.stream().anyMatch(s -> s != null && !s.isBlank())) {
                rows.add(cells);
            }
        }
        return rows;
    }
}
