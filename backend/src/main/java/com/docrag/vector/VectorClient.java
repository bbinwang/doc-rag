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
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.docrag.config.DocRagProperties;

/**
 * vector-service（Python, bge + ChromaDB）HTTP 客户端。
 * 入库写路径失败上抛（由调用方回滚倒排保证一期构建一致性）；
 * 检索读路径失败由 DocumentSearcher 降级为纯 BM25。
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

    /** 批量编码 chunk 并写入向量库（幂等 upsert） */
    public int upsert(String docId, String filename, String type, List<String> chunks)
            throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "docId", docId, "filename", filename, "type", type, "chunks", chunks);
        JsonNode resp = post(baseUrl + "/documents", body, Duration.ofSeconds(120));
        return resp.path("chunkCount").asInt(0);
    }

    /** 语义检索 topK；服务不可用抛 IOException（调用方降级） */
    public List<VectorHit> query(String text, int topK) throws IOException {
        try {
            Map<String, Object> body = Map.of("text", text, "topK", topK);
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

    /** 删除该文档全部 chunk 向量 */
    public void delete(String docId) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/documents/" + docId))
                    .timeout(Duration.ofSeconds(15)).DELETE().build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("向量库删除失败: " + e.getMessage(), e);
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
