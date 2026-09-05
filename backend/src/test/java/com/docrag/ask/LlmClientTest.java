package com.docrag.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import com.docrag.config.DocRagProperties;

/**
 * LlmClient 单测：本地 HTTP 桩验证请求拼装与响应解析（不访问真实 LLM 服务），
 * 另有一个用例直接加载主配置 application.yml，验证配置文件维护的 LLM 配置生效。
 */
class LlmClientTest {

    private HttpServer server;
    private String capturedPath;
    private String capturedAuth;
    private String capturedBody;
    private int status = 200;
    private String responseBody = "{\"choices\":[{\"message\":{\"content\":\"答案[1]\"}}]}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            capturedPath = ex.getRequestURI().getPath();
            capturedAuth = ex.getRequestHeaders().getFirst("Authorization");
            capturedBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private DocRagProperties props(String baseUrl) {
        DocRagProperties props = new DocRagProperties();
        props.getLlm().setBaseUrl(baseUrl);
        props.getLlm().setApiKey("sk-test-key");
        props.getLlm().setModel("mac-uni-model");
        return props;
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void chatPostsToConfiguredEndpointWithKeyAndModel() throws Exception {
        // base-url 带尾斜杠，验证拼接 /chat/completions 前先裁掉
        String reply = new LlmClient(props(base() + "/")).chat("你是助手", "问题");
        assertEquals("答案[1]", reply);
        assertEquals("/v1/chat/completions", capturedPath);
        assertEquals("Bearer sk-test-key", capturedAuth);
        JsonNode body = new ObjectMapper().readTree(capturedBody);
        assertEquals("mac-uni-model", body.path("model").asText());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("你是助手", body.path("messages").get(0).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("问题", body.path("messages").get(1).path("content").asText());
    }

    @Test
    void non200SurfacesStatusAndBody() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"invalid key\"}}";
        IOException e = assertThrows(IOException.class,
                () -> new LlmClient(props(base())).chat("s", "u"));
        assertTrue(e.getMessage().contains("LLM HTTP 401"), e.getMessage());
        assertTrue(e.getMessage().contains("invalid key"), e.getMessage());
    }

    @Test
    void missingContentThrows() {
        responseBody = "{\"choices\":[]}";
        IOException e = assertThrows(IOException.class,
                () -> new LlmClient(props(base())).chat("s", "u"));
        assertTrue(e.getMessage().contains("choices[0].message.content"), e.getMessage());
    }

    @Test
    void enabledRequiresApiKey() {
        DocRagProperties blank = props(base());
        blank.getLlm().setApiKey("");
        assertFalse(new LlmClient(blank).isEnabled());
        assertTrue(new LlmClient(props(base())).isEnabled());
    }

    /** 加载主配置 application.yml 绑定 DocRagProperties，验证配置文件维护的 base-url/api-key/model 生效 */
    @Test
    void applicationYmlDrivesLlmConfig() throws Exception {
        // MockEnvironment 不含系统环境变量，保证断言的是配置文件里的默认值而非 shell 覆盖
        MockEnvironment env = new MockEnvironment();
        new YamlPropertySourceLoader()
                .load("application.yml", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(env.getPropertySources()::addFirst);
        DocRagProperties props = Binder.get(env).bind("docrag", DocRagProperties.class).get();

        assertEquals("http://192.168.2.122:3000/v1", props.getLlm().getBaseUrl());
        assertEquals("sk-S23ssEGQap8cE4WDUqaXQn5orhvxzUNJ2ZY9YqRTOSdHXvbl", props.getLlm().getApiKey());
        assertEquals("mac-uni-model", props.getLlm().getModel());

        LlmClient client = new LlmClient(props);
        assertTrue(client.isEnabled(), "配置文件中的 api-key 应使 /api/ask 可用");
        assertEquals("mac-uni-model", client.model());
    }
}
