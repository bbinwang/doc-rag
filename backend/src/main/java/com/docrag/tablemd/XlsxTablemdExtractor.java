package com.docrag.tablemd;

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
 * xlsx → 表格 markdown 片段：每个 sheet 一个片段（首行作表头）；
 * 超过 {@link #MAX_ROWS_PER_FRAGMENT} 行的 sheet 分片，后续分片重复表头行，标题标注 (i/n)。
 */
@Component
public class XlsxTablemdExtractor implements TablemdExtractor {

    /** 单片段行数上限（含表头）：控制片段与送 LLM 上下文的体积 */
    static final int MAX_ROWS_PER_FRAGMENT = 60;

    @Override
    public boolean supports(String ext) {
        return "xlsx".equalsIgnoreCase(ext);
    }

    @Override
    public List<TableFragment> extract(InputStream in) throws DocumentParseException {
        List<TableFragment> fragments = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Iterator<Sheet> sheets = workbook.sheetIterator();
            while (sheets.hasNext()) {
                Sheet sheet = sheets.next();
                List<List<String>> rows = readRows(sheet, formatter);
                if (rows.isEmpty()) {
                    continue;
                }
                appendSheetFragments(fragments, sheet.getSheetName(), rows);
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("xlsx 表格提取失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
        return fragments;
    }

    private static void appendSheetFragments(List<TableFragment> fragments, String sheetName,
                                             List<List<String>> rows) {
        int pages = (rows.size() + MAX_ROWS_PER_FRAGMENT - 1) / MAX_ROWS_PER_FRAGMENT;
        for (int p = 0; p < pages; p++) {
            List<List<String>> page = new ArrayList<>(rows.subList(p * MAX_ROWS_PER_FRAGMENT,
                    Math.min(rows.size(), (p + 1) * MAX_ROWS_PER_FRAGMENT)));
            if (p > 0) {
                page.add(0, rows.get(0)); // 后续分片重复表头，保证片段自含可读
            }
            String title = pages == 1 ? sheetName : sheetName + " (" + (p + 1) + "/" + pages + ")";
            fragments.add(TableFragment.table(title, MarkdownTable.toMarkdown(page).stripTrailing()));
        }
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
