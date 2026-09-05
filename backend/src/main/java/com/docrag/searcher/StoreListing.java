package com.docrag.searcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;

/** 索引明细列表：全量遍历存储字段，modified 倒序（同毫秒按 docId 字典序）。
 *  本地工具数据量小，Java 侧排序可接受（modified 为 StoredField，无 DocValues）。 */
final class StoreListing {

    private StoreListing() {
    }

    static List<StoreListItem> listAll(SearcherManager searcherManager) {
        List<StoreListItem> items = new ArrayList<>();
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            TopDocs top = searcher.search(new MatchAllDocsQuery(), searcher.getIndexReader().numDocs());
            for (var sd : top.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                long modified = 0L;
                if (doc.getField("modified") != null && doc.getField("modified").numericValue() != null) {
                    modified = doc.getField("modified").numericValue().longValue();
                }
                items.add(new StoreListItem(doc.get("id"), doc.get("filename"), doc.get("path"),
                        doc.get("type"), modified));
            }
        } catch (Exception e) {
            // 明细列表尽力而为：读取异常返回已收集部分
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    // 释放异常不影响已收集结果
                }
            }
        }
        items.sort(Comparator.comparingLong(StoreListItem::modified).reversed()
                .thenComparing(StoreListItem::docId));
        return items;
    }
}
