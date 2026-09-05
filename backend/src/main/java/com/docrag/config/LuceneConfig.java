package com.docrag.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

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

import com.docrag.indexer.ModeIndexer;
import com.docrag.mode.Mode;
import com.docrag.searcher.ModeSearcher;
import com.docrag.vector.VectorClient;

/**
 * Lucene 核心对象生命周期统一管理（双模式：plain / deep 结构完全对称）。
 * 全项目的 @Qualifier 只允许出现在本文件内——ModeIndexer/ModeSearcher 走
 * @Bean 工厂产出，Controller/Service 直接按类型或 Map<Mode, …> 注入。
 *
 * IK 分词双 analyzer 策略（经典做法，两模式共用同一对单例）：
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

    /** plain 倒排的进程内单例 IndexWriter（data/index-plain/） */
    @Bean(name = "plainIndexWriter", destroyMethod = "close")
    public IndexWriter plainIndexWriter(DocRagProperties props,
                                        @Qualifier("indexAnalyzer") Analyzer analyzer) throws IOException {
        return openWriter(props.getPlainIndexDir(), analyzer);
    }

    @Bean(name = "plainSearcherManager", destroyMethod = "close")
    public SearcherManager plainSearcherManager(@Qualifier("plainIndexWriter") IndexWriter writer)
            throws IOException {
        return new SearcherManager(writer, new SearcherFactory());
    }

    /** deep 倒排的进程内单例 IndexWriter（data/index-deep/，独立目录可单独重建） */
    @Bean(name = "deepIndexWriter", destroyMethod = "close")
    public IndexWriter deepIndexWriter(DocRagProperties props,
                                       @Qualifier("indexAnalyzer") Analyzer analyzer) throws IOException {
        return openWriter(props.getDeepIndexDir(), analyzer);
    }

    @Bean(name = "deepSearcherManager", destroyMethod = "close")
    public SearcherManager deepSearcherManager(@Qualifier("deepIndexWriter") IndexWriter writer)
            throws IOException {
        return new SearcherManager(writer, new SearcherFactory());
    }

    @Bean
    public ModeIndexer plainIndexer(@Qualifier("plainIndexWriter") IndexWriter writer,
                                    @Qualifier("plainSearcherManager") SearcherManager searcherManager) {
        return new ModeIndexer(Mode.PLAIN, writer, searcherManager);
    }

    @Bean
    public ModeIndexer deepIndexer(@Qualifier("deepIndexWriter") IndexWriter writer,
                                   @Qualifier("deepSearcherManager") SearcherManager searcherManager) {
        return new ModeIndexer(Mode.DEEP, writer, searcherManager);
    }

    @Bean
    public ModeSearcher plainSearcher(@Qualifier("plainSearcherManager") SearcherManager searcherManager,
                                      @Qualifier("queryAnalyzer") Analyzer queryAnalyzer,
                                      @Qualifier("indexAnalyzer") Analyzer indexAnalyzer,
                                      VectorClient vectorClient) {
        return new ModeSearcher(Mode.PLAIN, searcherManager, queryAnalyzer, indexAnalyzer, vectorClient);
    }

    @Bean
    public ModeSearcher deepSearcher(@Qualifier("deepSearcherManager") SearcherManager searcherManager,
                                     @Qualifier("queryAnalyzer") Analyzer queryAnalyzer,
                                     @Qualifier("indexAnalyzer") Analyzer indexAnalyzer,
                                     VectorClient vectorClient) {
        return new ModeSearcher(Mode.DEEP, searcherManager, queryAnalyzer, indexAnalyzer, vectorClient);
    }

    /** 按模式取用的索引器/检索器映射（Controller 与 AskService 循环分派用） */
    @Bean
    public Map<Mode, ModeIndexer> modeIndexers(ModeIndexer plainIndexer, ModeIndexer deepIndexer) {
        Map<Mode, ModeIndexer> out = new EnumMap<>(Mode.class);
        out.put(Mode.PLAIN, plainIndexer);
        out.put(Mode.DEEP, deepIndexer);
        return out;
    }

    @Bean
    public Map<Mode, ModeSearcher> modeSearchers(ModeSearcher plainSearcher, ModeSearcher deepSearcher) {
        Map<Mode, ModeSearcher> out = new EnumMap<>(Mode.class);
        out.put(Mode.PLAIN, plainSearcher);
        out.put(Mode.DEEP, deepSearcher);
        return out;
    }

    private static IndexWriter openWriter(String dir, Analyzer analyzer) throws IOException {
        Path indexDir = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(indexDir);
        return new IndexWriter(FSDirectory.open(indexDir), new IndexWriterConfig(analyzer));
    }
}
