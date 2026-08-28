package com.docrag.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** xlsx 解析：逐 Sheet 逐行拼接，单元格间制表符、行间换行 */
@Component
public class XlsxParser implements DocumentParser {

    @Override
    public boolean supports(String ext) {
        return "xlsx".equalsIgnoreCase(ext);
    }

    @Override
    public String parse(InputStream in) throws DocumentParseException {
        DataFormatter formatter = new DataFormatter();
        StringBuilder text = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Iterator<Sheet> sheets = workbook.sheetIterator();
            while (sheets.hasNext()) {
                Sheet sheet = sheets.next();
                text.append("## ").append(sheet.getSheetName()).append('\n');
                Iterator<Row> rows = sheet.rowIterator();
                while (rows.hasNext()) {
                    Row row = rows.next();
                    StringBuilder line = new StringBuilder();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        if (c > 0) {
                            line.append('\t');
                        }
                        line.append(formatter.formatCellValue(row.getCell(c)));
                    }
                    if (!line.isEmpty()) {
                        text.append(line).append('\n');
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("xlsx 解析失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
        return text.toString();
    }
}
