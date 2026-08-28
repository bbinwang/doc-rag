package com.docrag.parser;

import java.io.InputStream;

/** 文档解析器：把二进制文档提取为纯文本 */
public interface DocumentParser {

    /** 是否支持该扩展名（如 "docx"） */
    boolean supports(String ext);

    /** 提取纯文本，失败抛出受检异常（由 API 层转 400） */
    String parse(InputStream in) throws DocumentParseException;
}
