package com.docrag.tablemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarkdownTableTest {

    @Test
    void emptyRowsProduceEmptyMarkdown() {
        assertEquals("", MarkdownTable.toMarkdown(List.of()));
    }

    @Test
    void firstRowBecomesHeaderWithSeparator() {
        String md = MarkdownTable.toMarkdown(List.of(
                List.of("姓名", "年龄"),
                List.of("张三", "25")));
        assertEquals("| 姓名 | 年龄 |\n| --- | --- |\n| 张三 | 25 |\n", md);
    }

    @Test
    void raggedRowsArePaddedToMaxColumns() {
        String md = MarkdownTable.toMarkdown(List.of(
                List.of("a", "b", "c"),
                List.of("x")));
        assertTrue(md.contains("| x |  |  |"));
        assertTrue(md.contains("| --- | --- | --- |"));
    }

    @Test
    void escapeReplacesPipeAndNewline() {
        assertEquals("a\\|b c", MarkdownTable.escape("a|b\nc"));
        assertEquals("", MarkdownTable.escape(null));
    }
}
