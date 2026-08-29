package com.docrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** docrag.* 配置项，路径集中在 application.yml，禁止硬编码 */
@ConfigurationProperties(prefix = "docrag")
public class DocRagProperties {

    /** 上传原文存放目录 */
    private String uploadDir = "../data/upload";

    /** Lucene 全文索引目录 */
    private String indexDir = "../data/index";

    /** Lucene 表格 markdown 索引目录 */
    private String tableIndexDir = "../data/index-table";

    /** vector-service（bge + ChromaDB）地址 */
    private String vectorServiceUrl = "http://127.0.0.1:8081";

    /** LLM 问答配置（OpenAI 兼容） */
    private Llm llm = new Llm();

    public static class Llm {

        /** OpenAI 兼容服务地址（不含 /chat/completions） */
        private String baseUrl = "https://api.openai.com/v1";

        /** 为空视为未配置，/api/ask 返回 400 */
        private String apiKey = "";

        private String model = "gpt-4o-mini";

        private double temperature = 0.2;

        private int timeoutSeconds = 60;

        /** 送 LLM 的上下文块数上限（full=chunk 数，table=片段数） */
        private int maxContextChunks = 8;

        /** 送 LLM 的上下文总字符预算 */
        private int contextCharBudget = 6000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxContextChunks() {
            return maxContextChunks;
        }

        public void setMaxContextChunks(int maxContextChunks) {
            this.maxContextChunks = maxContextChunks;
        }

        public int getContextCharBudget() {
            return contextCharBudget;
        }

        public void setContextCharBudget(int contextCharBudget) {
            this.contextCharBudget = contextCharBudget;
        }
    }

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

    public String getTableIndexDir() {
        return tableIndexDir;
    }

    public void setTableIndexDir(String tableIndexDir) {
        this.tableIndexDir = tableIndexDir;
    }

    public String getVectorServiceUrl() {
        return vectorServiceUrl;
    }

    public void setVectorServiceUrl(String vectorServiceUrl) {
        this.vectorServiceUrl = vectorServiceUrl;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }
}
