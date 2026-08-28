package com.docrag.debug;

import java.util.List;

/** debug 解析出的结构化表格：docx 为「表格 N」，xlsx 为 sheet 名 */
public record TableInfo(String title, List<List<String>> rows) {
}
