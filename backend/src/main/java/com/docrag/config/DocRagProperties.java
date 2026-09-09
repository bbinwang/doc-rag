package com.docrag.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** docrag.* 配置项，路径集中在 application.yml，禁止硬编码 */
@ConfigurationProperties(prefix = "docrag")
public class DocRagProperties {

    /** 上传原文存放目录 */
    private String uploadDir = "../data/upload";

    /** Lucene plain（纯文本解析）倒排目录 */
    private String plainIndexDir = "../data/index-plain";

    /** Lucene deep（深度解析）倒排目录 */
    private String deepIndexDir = "../data/index-deep";

    /** vector-service（bge + ChromaDB）地址 */
    private String vectorServiceUrl = "http://127.0.0.1:8081";

    /** LLM 问答配置（OpenAI 兼容，只管「怎么连 LLM / 怎么生成」） */
    private Llm llm = new Llm();

    /** 问答召回/上下文参数（「召回多少、送多少」，请求级可覆盖，钳制见 AskService） */
    private Ask ask = new Ask();

    /**
     * 多 provider LLM 配置：active 选出唯一启用的 provider，其余仅存配置不生效。
     * 未配置（active 空 / 指向不存在的 provider）视为 LLM 未启用，/api/ask 返回 400。
     */
    public static class Llm {

        /** 当前启用的 provider 名（对应 providers 的 key） */
        private String active = "";

        /** 全部可用 LLM provider（key = provider 名，如 glm / mac-uni） */
        private Map<String, Provider> providers = new LinkedHashMap<>();

        /** 解析 active 指向的 provider；未配置或指向不存在的 provider 返回 null */
        public Provider resolve() {
            if (active == null || active.isBlank()) {
                return null;
            }
            return providers.get(active);
        }

        public String getActive() {
            return active;
        }

        public void setActive(String active) {
            this.active = active;
        }

        public Map<String, Provider> getProviders() {
            return providers;
        }

        public void setProviders(Map<String, Provider> providers) {
            this.providers = providers;
        }
    }

    /** 单个 LLM provider（OpenAI 兼容） */
    public static class Provider {

        /** OpenAI 兼容服务地址（不含 /chat/completions） */
        private String baseUrl = "https://api.openai.com/v1";

        /** 为空视为该 provider 未配置 */
        private String apiKey = "";

        private String model = "gpt-4o-mini";

        private double temperature = 0.2;

        private int timeoutSeconds = 60;

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
    }

    public static class Ask {

        /** 倒排（BM25）路 chunk 召回上限 */
        private int bm25Chunks = 5;

        /** 向量路 chunk 召回上限（vector-service topK） */
        private int vectorChunks = 5;

        /** 重排（chunk 级 RRF）后每模式送 LLM 的 chunk 上限 */
        private int contextChunks = 8;

        /** 每模式送 LLM 的上下文总字符预算 */
        private int contextCharBudget = 6000;

        public int getBm25Chunks() {
            return bm25Chunks;
        }

        public void setBm25Chunks(int bm25Chunks) {
            this.bm25Chunks = bm25Chunks;
        }

        public int getVectorChunks() {
            return vectorChunks;
        }

        public void setVectorChunks(int vectorChunks) {
            this.vectorChunks = vectorChunks;
        }

        public int getContextChunks() {
            return contextChunks;
        }

        public void setContextChunks(int contextChunks) {
            this.contextChunks = contextChunks;
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

    public String getPlainIndexDir() {
        return plainIndexDir;
    }

    public void setPlainIndexDir(String plainIndexDir) {
        this.plainIndexDir = plainIndexDir;
    }

    public String getDeepIndexDir() {
        return deepIndexDir;
    }

    public void setDeepIndexDir(String deepIndexDir) {
        this.deepIndexDir = deepIndexDir;
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

    public Ask getAsk() {
        return ask;
    }

    public void setAsk(Ask ask) {
        this.ask = ask;
    }
}
