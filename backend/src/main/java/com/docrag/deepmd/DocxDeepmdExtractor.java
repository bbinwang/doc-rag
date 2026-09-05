package com.docrag.deepmd;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import com.docrag.parser.DocumentParseException;

/**
 * docx → 统一文本：按 body 元素顺序遍历，段落逐行累积，
 * 表格 = 空行分隔 + 「表格 N」标题行 + markdown 表格（首行作表头）+ 空行分隔。
 * 块边界约定（空行分隔、标题行紧贴表格）是 Chunker.chunkKeepingTables 的切块前提。
 */
@Component
public class DocxDeepmdExtractor implements DeepmdExtractor {

    @Override
    public boolean supports(String ext) {
        return "docx".equalsIgnoreCase(ext);
    }

    @Override
    public DeepDocument extract(InputStream in) throws DocumentParseException {
        StringBuilder sb = new StringBuilder();
        int tableNo = 0;
        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    String text = ((XWPFParagraph) element).getText();
                    if (!text.isBlank()) {
                        sb.append(text).append('\n');
                    }
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    tableNo++;
                    String md = MarkdownTable.toMarkdown(tableRows((XWPFTable) element));
                    if (md.isEmpty()) {
                        continue; // 空表跳过，但编号仍递增（保持与文档表格序号一致）
                    }
                    if (sb.length() > 0) {
                        sb.append('\n'); // 与前面正文隔一个空行
                    }
                    sb.append("表格 ").append(tableNo).append('\n');
                    sb.append(md);
                    sb.append('\n'); // 表格后空行，保证下一个块边界清晰
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("docx 表格提取失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
        return new DeepDocument(sb.toString().stripTrailing(), tableNo);
    }

    private static List<List<String>> tableRows(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            rows.add(row.getTableCells().stream()
                    .map(XWPFTableCell::getText)
                    .toList());
        }
        return rows;
    }
}
