package com.docrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** docrag.* 配置项，路径集中在 application.yml，禁止硬编码 */
@ConfigurationProperties(prefix = "docrag")
public class DocRagProperties {

    /** 上传原文存放目录 */
    private String uploadDir = "../data/upload";

    /** Lucene 索引目录 */
    private String indexDir = "../data/index";

    /** vector-service（bge + ChromaDB）地址 */
    private String vectorServiceUrl = "http://127.0.0.1:8081";

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public String getIndexDir() {
        return indexDir;
    }

    public void setIndexDir(String indexDir) {
        this.indexDir = indexDir;
    }

    public String getVectorServiceUrl() {
        return vectorServiceUrl;
    }

    public void setVectorServiceUrl(String vectorServiceUrl) {
        this.vectorServiceUrl = vectorServiceUrl;
    }
}
