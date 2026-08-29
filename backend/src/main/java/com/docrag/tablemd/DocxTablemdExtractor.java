package com.docrag.tablemd;

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

import com.docrag.indexer.Chunker;
import com.docrag.parser.DocumentParseException;

/**
 * docx → 表格 markdown 片段：按 body 元素顺序遍历，
 * 段落累积为正文片段（kind=text），表格转 markdown 片段（kind=table，首行作表头）。
 */
@Component
public class DocxTablemdExtractor implements TablemdExtractor {

    @Override
    public boolean supports(String ext) {
        return "docx".equalsIgnoreCase(ext);
    }

    @Override
    public List<TableFragment> extract(InputStream in) throws DocumentParseException {
        List<TableFragment> fragments = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            int tableNo = 0;
            for (IBodyElement element : doc.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    String text = ((XWPFParagraph) element).getText();
                    if (!text.isBlank()) {
                        body.append(text).append('\n');
                    }
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    flushBody(fragments, body);
                    tableNo++;
                    String md = MarkdownTable.toMarkdown(tableRows((XWPFTable) element));
                    if (!md.isEmpty()) {
                        fragments.add(TableFragment.table("表格 " + tableNo, md.stripTrailing()));
                    }
                }
            }
            flushBody(fragments, body);
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("docx 表格提取失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
        return fragments;
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

    /** 表格前积累的正文切块入列，保证表格片段与正文片段不混排 */
    private static void flushBody(List<TableFragment> fragments, StringBuilder body) {
        if (body.isEmpty()) {
            return;
        }
        for (String chunk : Chunker.chunk(body.toString(), BODY_CHUNK_CHARS)) {
            fragments.add(TableFragment.text("正文", chunk));
        }
        body.setLength(0);
    }
}
