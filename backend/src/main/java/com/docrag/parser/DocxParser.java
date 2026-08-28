package com.docrag.parser;

import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

/** docx 解析：XWPFWordExtractor 提取段落与表格文本 */
@Component
public class DocxParser implements DocumentParser {

    @Override
    public boolean supports(String ext) {
        return "docx".equalsIgnoreCase(ext);
    }

    @Override
    public String parse(InputStream in) throws DocumentParseException {
        try (XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("docx 解析失败（文件可能已损坏或加密）: " + e.getMessage(), e);
        }
    }
}
