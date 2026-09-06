package com.docrag.vector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.docrag.mode.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.docrag.config.DocRagProperties;

/**
 * vector-service（Python, bge + ChromaDB）HTTP 客户端（per-mode：
 * plain/deep 各自一个 collection，所有请求带 mode）。
 * 入库写路径失败上抛（由调用方回滚倒排保证一致性）；
 * 检索读路径失败由 ModeSearcher 按模式降级为纯 BM25。
 *
 * <p>注意：必须使用 HTTP/1.1，JDK HttpClient 默认协商 HTTP/2，
 * FastAPI/Starlette 对 HTTP/2 的 body 处理不兼容（返回 422）。</p>
 */
@Component
public class VectorClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(Version.HTTP_1_1)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public VectorClient(DocRagProperties props) {
        this.baseUrl = props.getVectorServiceUrl();
    }

    /** 服务是否可用（短超时，用于降级判断/健康检查） */
    public boolean ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** /health 快照：per-mode 向量数与模型名；不可达返回 available=false（不抛异常，状态条用） */
    public VectorStats stats() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return VectorStats.unavailable();
            }
            JsonNode node = mapper.readTree(resp.body());
            Map<String, Integer> parsed = new HashMap<>();
            if (node.path("vectors").isObject()) {
                node.path("vectors").fields().forEachRemaining(e ->
                        parsed.put(e.getKey(), e.getValue().asInt()));
            }
            Map<String, Integer> vectors = parsed.isEmpty() ? null : parsed;
            String model = node.has("model") && !node.path("model").isNull()
                    ? node.path("model").asText() : null;
            return new VectorStats(true, vectors, model);
        } catch (Exception e) {
            return VectorStats.unavailable();
        }
    }

    /** 全清向量库（一键清理用，DELETE /documents?mode=all）；失败上抛由调用方决定整体行为 */
    public void clearAll() throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/documents?mode=all"))
                    .timeout(Duration.ofSeconds(30)).DELETE().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("vector-service HTTP " + resp.statusCode() + ": " + resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("向量库清理被中断", e);
        } catch (IOException e) {
            throw new IOException("向量库清理失败: " + e.getMessage(), e);
        }
    }

    /** 批量编码 chunk 并写入该模式的向量 collection（幂等 upsert） */
    public int upsert(Mode mode, String docId, String filename, String type, List<String> chunks)
            throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("docId", docId);
        body.put("filename", filename);
        body.put("type", type);
        body.put("mode", mode.id());
        body.put("chunks", chunks);
        JsonNode resp = post(baseUrl + "/documents", body, Duration.ofSeconds(120));
        return resp.path("chunkCount").asInt(0);
    }

    /** 该模式向量 collection 语义检索 topK；服务不可用抛 IOException（调用方按模式降级） */
    public List<VectorHit> query(Mode mode, String text, int topK) throws IOException {
        try {
            Map<String, Object> body = Map.of("text", text, "topK", topK, "mode", mode.id());
            JsonNode resp = post(baseUrl + "/query", body, Duration.ofSeconds(15));
            List<VectorHit> hits = new ArrayList<>();
            for (JsonNode h : resp.path("hits")) {
                hits.add(new VectorHit(
                        h.path("docId").asText(),
                        h.path("filename").asText(),
                        h.path("type").asText(),
                        h.path("chunk").asText(),
                        h.path("similarity").asDouble()));
            }
            return hits;
        } catch (HttpTimeoutException e) {
            throw new IOException("向量检索超时", e);
        } catch (IOException | InterruptedException e) {
            throw new IOException("向量服务不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 删除该文档在指定模式的向量；mode 传 null 时删双 collection
     * （级联删除用，vector-service 侧对不存在的 docId 是幂等 no-op）。
     */
    public void delete(String docId, Mode mode) throws IOException {
        String suffix = mode == null ? "all" : mode.id();
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/documents/" + docId + "?mode=" + suffix))
                    .timeout(Duration.ofSeconds(15)).DELETE().build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("向量库删除失败: " + e.getMessage(), e);
        }
    }

    /** 向量库文档列表条目（docId 维度聚合，chunkCount 为向量条数） */
    public record VectorDocSummary(String docId, String filename, String type, int chunkCount) {
    }

    /** 向量 chunk（chunkIndex 为入库顺序号） */
    public record VectorChunk(int chunkIndex, String text) {
    }

    /** 向量库单文档明细：文档元信息 + 全部 chunk（按 chunkIndex 升序） */
    public record VectorDocDetail(String docId, String filename, String type, List<VectorChunk> chunks) {
    }

    /** 列出该模式 collection 的全部文档（向量库明细页用）；服务不可用上抛 */
    public List<VectorDocSummary> listDocs(Mode mode) throws IOException {
        JsonNode resp = get(baseUrl + "/documents?mode=" + mode.id(), Duration.ofSeconds(15));
        List<VectorDocSummary> docs = new ArrayList<>();
        for (JsonNode d : resp.path("docs")) {
            docs.add(new VectorDocSummary(
                    d.path("docId").asText(),
                    d.path("filename").asText(),
                    d.path("type").asText(),
                    d.path("chunkCount").asInt(0)));
        }
        return docs;
    }

    /** 该模式某文档的全部 chunk；向量库中不存在返回 null（调用方转 404） */
    public VectorDocDetail getDoc(Mode mode, String docId) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/documents/" + docId + "?mode=" + mode.id()))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("向量库查询被中断", e);
        }
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new IOException("vector-service HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode node = mapper.readTree(resp.body());
        List<VectorChunk> chunks = new ArrayList<>();
        for (JsonNode c : node.path("chunks")) {
            chunks.add(new VectorChunk(
                    c.path("chunkIndex").asInt(),
                    c.path("text").asText()));
        }
        return new VectorDocDetail(
                node.path("docId").asText(),
                node.path("filename").asText(),
                node.path("type").asText(),
                chunks);
    }

    private JsonNode get(String url, Duration timeout) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout).GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("向量库查询被中断", e);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("vector-service HTTP " + resp.statusCode() + ": " + resp.body());
        }
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new IOException("向量库响应解析失败: " + e.getMessage(), e);
        }
    }

    private JsonNode post(String url, Object body, Duration timeout)
            throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("vector-service HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }
}
