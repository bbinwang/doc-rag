package com.docrag.tablemd;

import java.util.List;

/** 行列数据 → markdown 表格：首行作表头，单元格内换行转空格、管道符转义 */
public final class MarkdownTable {

    private MarkdownTable() {
    }

    public static String toMarkdown(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        appendRow(sb, rows.get(0), cols);
        sb.append('|');
        for (int c = 0; c < cols; c++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        for (int r = 1; r < rows.size(); r++) {
            appendRow(sb, rows.get(r), cols);
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> cells, int cols) {
        sb.append('|');
        for (int c = 0; c < cols; c++) {
            sb.append(' ').append(escape(c < cells.size() ? cells.get(c) : "")).append(" |");
        }
        sb.append('\n');
    }

    /** markdown 表格单元格内不允许换行与裸管道符 */
    static String escape(String cell) {
        if (cell == null) {
            return "";
        }
        return cell.replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
    }
}
