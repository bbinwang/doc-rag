package com.docrag.indexer;

import java.io.IOException;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.SearcherManager;
import org.springframework.stereotype.Component;

/**
 * Lucene 写入封装：一个文件 = 一个 Document（全文存 content 字段，不分块）。
 * 写后 commit 并刷新近实时 reader，检索立即可见。
 */
@Component
public class DocumentIndexer {

    private final IndexWriter writer;
    private final SearcherManager searcherManager;

    public DocumentIndexer(IndexWriter writer, SearcherManager searcherManager) {
        this.writer = writer;
        this.searcherManager = searcherManager;
    }

    public void index(String id, String filename, String path, String type, String content)
            throws IOException {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new TextField("filename", filename, Field.Store.YES));
        doc.add(new StringField("path", path, Field.Store.YES));
        doc.add(new StringField("type", type, Field.Store.YES));
        // modified 仅作元数据展示，不参与检索，用 StoredField 即可（Lucene 8.x 无 LongField）
        doc.add(new StoredField("modified", System.currentTimeMillis()));
        doc.add(new TextField("content", content, Field.Store.YES));
        writer.updateDocument(new Term("id", id), doc);
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }

    public void delete(String docId) throws IOException {
        writer.deleteDocuments(new Term("id", docId));
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }
}
