package com.docrag.parser;

/** 文档解析失败（损坏 / 加密 / 不支持的类型），API 层统一转为 400 */
public class DocumentParseException extends Exception {

    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
