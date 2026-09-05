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

import com.docrag.mode.Mode;

/**
 * 双模式共用的倒排写入封装（plain / deep 各一个实例，schema 完全同构）：
 * 一个文件 = 一个 Document，content 为该模式入库文本（plain=纯文本 / deep=统一文本）。
 * id 直接存 docId 作级联删除键（updateDocument 按 id upsert，幂等）。
 * 写后 commit 并刷新近实时 reader，检索立即可见。
 * 实例由 LuceneConfig 的 @Bean 工厂产出（qualifier 收敛在 config 一个文件内）。
 */
public class ModeIndexer {

    private final Mode mode;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;

    public ModeIndexer(Mode mode, IndexWriter writer, SearcherManager searcherManager) {
        this.mode = mode;
        this.writer = writer;
        this.searcherManager = searcherManager;
    }

    public Mode mode() {
        return mode;
    }

    public void index(String docId, String filename, String path, String type, String content)
            throws IOException {
        Document doc = new Document();
        doc.add(new StringField("id", docId, Field.Store.YES));
        doc.add(new TextField("filename", filename, Field.Store.YES));
        doc.add(new StringField("path", path, Field.Store.YES));
        doc.add(new StringField("type", type, Field.Store.YES));
        // modified 仅作元数据展示，不参与检索，用 StoredField 即可（Lucene 8.x 无 LongField）
        doc.add(new StoredField("modified", System.currentTimeMillis()));
        doc.add(new TextField("content", content, Field.Store.YES));
        writer.updateDocument(new Term("id", docId), doc);
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }

    public void delete(String docId) throws IOException {
        writer.deleteDocuments(new Term("id", docId));
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }

    /** 当前索引文档数（近实时 reader 视角，供状态展示） */
    public int count() throws IOException {
        org.apache.lucene.search.IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searcherManager.release(searcher);
        }
    }

    /** 清空全部文档（一键清理用）：走存活的单例 writer，不绕过锁直接删目录 */
    public void clearAll() throws IOException {
        writer.deleteAll();
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }
}
