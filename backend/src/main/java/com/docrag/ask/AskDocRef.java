package com.docrag.ask;

/** 问答引用文档（chunks 按 docId 去重，前端调试展示用） */
public record AskDocRef(String docId, String filename, String type, String path) {
}
