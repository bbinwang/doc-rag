package com.docrag.api;

import java.io.IOException;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.docrag.debug.DebugParseResult;
import com.docrag.debug.DebugParseService;
import com.docrag.parser.DocumentParseException;

/** debug 解析：上传→解析明细返回，不写索引、不落盘 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final DebugParseService service;

    public DebugController(DebugParseService service) {
        this.service = service;
    }

    @PostMapping("/parse")
    public DebugParseResult parse(@RequestParam("file") MultipartFile file)
            throws DocumentParseException, IOException {
        if (file == null || file.isEmpty()) {
            throw new DocumentParseException("上传文件为空");
        }
        String filename = Filenames.sanitize(file.getOriginalFilename());
        return service.parse(filename, file.getBytes());
    }
}
