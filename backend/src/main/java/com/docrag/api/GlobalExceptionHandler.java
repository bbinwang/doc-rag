package com.docrag.api;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.queryparser.classic.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.docrag.parser.DocumentParseException;

/** 统一 JSON 错误返回：解析/参数错误 4xx，不向前端抛 500 堆栈 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DocumentParseException.class)
    public ResponseEntity<Map<String, String>> badRequest(DocumentParseException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ParseException.class)
    public ResponseEntity<Map<String, String>> badQuery(ParseException e) {
        return body(HttpStatus.BAD_REQUEST, "查询语法错误: " + e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "文件超出大小限制（50MB）");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> ioError(IOException e) {
        log.error("服务端 IO 错误", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务端错误: " + e.getMessage());
    }

    /** 兜底：任何未预期异常也返回 JSON，不向前端抛堆栈 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("未预期错误", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务端错误: " + e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
