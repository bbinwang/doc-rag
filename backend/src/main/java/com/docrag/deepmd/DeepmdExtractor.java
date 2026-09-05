package com.docrag.deepmd;

import java.io.InputStream;

import com.docrag.parser.DocumentParseException;

/**
 * 表格→markdown + 统一文本提取器（表格索引的文本来源）：
 * 正文（纯文本）与表格（markdown）按文档原始顺序拼成一个统一文本，一文一 Document。
 */
public interface DeepmdExtractor {

    boolean supports(String ext);

    DeepDocument extract(InputStream in) throws DocumentParseException;
}
