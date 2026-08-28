package com.docrag.api;

import com.docrag.parser.DocumentParseException;

/** 文件名工具：去路径、取小写扩展名 */
public final class Filenames {

    private Filenames() {
    }

    /** 只保留文件名部分，去掉客户端可能携带的路径 */
    public static String sanitize(String name) throws DocumentParseException {
        if (name == null || name.isBlank()) {
            throw new DocumentParseException("文件名为空");
        }
        return java.nio.file.Paths.get(name.replace('\\', '/')).getFileName().toString();
    }

    /** 取小写扩展名（不含点）；无扩展名返回空串 */
    public static String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
