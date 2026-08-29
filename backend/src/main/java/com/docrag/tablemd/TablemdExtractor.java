package com.docrag.tablemd;

import java.io.InputStream;
import java.util.List;

import com.docrag.parser.DocumentParseException;

/**
 * 表格 markdown 提取器（第二索引格式的片段来源）：
 * 文档中的表格转 markdown 片段，其余正文照常提取为文本片段。
 */
public interface TablemdExtractor {

    /** 表格 markdown 索引中正文片段的块上限（大于 embedding 的 128，兼顾检索粒度与片段数） */
    int BODY_CHUNK_CHARS = 512;

    boolean supports(String ext);

    List<TableFragment> extract(InputStream in) throws DocumentParseException;
}
