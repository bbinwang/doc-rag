package com.docrag.indexer;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.SearcherManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.docrag.tablemd.TableFragment;

/**
 * 表格 markdown 索引写入封装：一个片段 = 一个 Document（content 为 markdown 表格或正文片段）。
 * 按 docId 整体 upsert（先删后加，幂等），删除按 docId 级联清掉该文档全部片段。
 */
@Component
public class TableIndexer {

    private final IndexWriter writer;
    private final SearcherManager searcherManager;

    public TableIndexer(@Qualifier("tableIndexWriter") IndexWriter writer,
                        @Qualifier("tableSearcherManager") SearcherManager searcherManager) {
        this.writer = writer;
        this.searcherManager = searcherManager;
    }

    /** 该文档的片段整体重写（同 docId 重复入库安全） */
    public void index(String docId, String filename, String path, String type,
                      List<TableFragment> fragments) throws IOException {
        long modified = System.currentTimeMillis();
        writer.deleteDocuments(new Term("docId", docId));
        for (TableFragment fragment : fragments) {
            Document doc = new Document();
            doc.add(new StringField("id", UUID.randomUUID().toString(), Field.Store.YES));
            doc.add(new StringField("docId", docId, Field.Store.YES));
            doc.add(new TextField("filename", filename, Field.Store.YES));
            doc.add(new StringField("path", path, Field.Store.YES));
            doc.add(new StringField("type", type, Field.Store.YES));
            doc.add(new StringField("kind", fragment.kind(), Field.Store.YES));
            doc.add(new TextField("title", fragment.title(), Field.Store.YES));
            doc.add(new StoredField("modified", modified));
            doc.add(new TextField("content", fragment.content(), Field.Store.YES));
            writer.addDocument(doc);
        }
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }

    public void delete(String docId) throws IOException {
        writer.deleteDocuments(new Term("docId", docId));
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }
}
