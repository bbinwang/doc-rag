package com.docrag.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.wltea.analyzer.lucene.IKAnalyzer;

/**
 * Lucene 核心对象生命周期统一管理。
 *
 * IK 分词双 analyzer 策略（经典做法）：
 * - 索引侧细粒度切分（useSmart=false）：同一文本切出尽可能多的词，保证召回
 *   （否则「合同条款」被智能模式切成整词，查「合同」会漏）
 * - 查询侧智能切分（useSmart=true）：贴近用户输入意图
 */
@Configuration
public class LuceneConfig {

    @Bean(name = "indexAnalyzer", destroyMethod = "close")
    public Analyzer indexAnalyzer() {
        return new IKAnalyzer(false);
    }

    @Bean(name = "queryAnalyzer", destroyMethod = "close")
    public Analyzer queryAnalyzer() {
        return new IKAnalyzer(true);
    }

    /** 进程内唯一的 IndexWriter，全链路唯一写入点 */
    @Bean(destroyMethod = "close")
    public IndexWriter indexWriter(DocRagProperties props,
                                   @Qualifier("indexAnalyzer") Analyzer analyzer) throws IOException {
        Path indexDir = Paths.get(props.getIndexDir()).toAbsolutePath().normalize();
        Files.createDirectories(indexDir);
        return new IndexWriter(FSDirectory.open(indexDir), new IndexWriterConfig(analyzer));
    }

    /** 近实时检索：写入 commit 后 maybeRefresh 即可读到最新数据 */
    @Bean(destroyMethod = "close")
    public SearcherManager searcherManager(IndexWriter indexWriter) throws IOException {
        return new SearcherManager(indexWriter, new SearcherFactory());
    }
}
