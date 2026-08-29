package com.docrag.ask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.docrag.config.DocRagProperties;

/**
 * OpenAI 兼容 chat/completions 客户端（java.net.http，非流式）。
 * 配置见 docrag.llm.*；api-key 为空视为未配置（isEnabled=false，/api/ask 返回 400）。
 *
 * <p>与 VectorClient 同理强制 HTTP/1.1，避免部分兼容服务对 HTTP/2 body 的兼容问题。</p>
 */
@Component
public class LlmClient {

    /** 错误信息里响应体截断长度，防止把超长报错打满日志 */
    private static final int ERROR_BODY_CHARS = 300;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocRagProperties.Llm llm;

    public LlmClient(DocRagProperties props) {
        this.llm = props.getLlm();
    }

    public boolean isEnabled() {
        return llm.getApiKey() != null && !llm.getApiKey().isBlank();
    }

    public String model() {
        return llm.getModel();
    }

    /** system + user 两条消息，返回首个 choice 的 content；失败抛 IOException（上层转 5xx 提示） */
    public String chat(String system, String user) throws IOException {
        Map<String, Object> body = Map.of(
                "model", llm.getModel(),
                "temperature", llm.getTemperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IOException("LLM 请求序列化失败", e);
        }
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(trimTrailingSlash(llm.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + llm.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.getBytes(StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM 调用被中断", e);
        } catch (IOException e) {
            throw new IOException("LLM 服务不可用: " + e.getMessage(), e);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("LLM HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
        }
        try {
            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IOException("LLM 返回缺少 choices[0].message.content: " + abbreviate(resp.body()));
            }
            return content;
        } catch (JsonProcessingException e) {
            throw new IOException("LLM 返回非 JSON: " + abbreviate(resp.body()), e);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ERROR_BODY_CHARS ? body : body.substring(0, ERROR_BODY_CHARS) + "…";
    }
}
