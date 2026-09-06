package com.docrag.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.docrag.ask.AskParams;
import com.docrag.ask.AskRequest;
import com.docrag.ask.AskResponse;
import com.docrag.ask.AskService;
import com.docrag.ask.LlmClient;
import com.docrag.config.DocRagProperties;
import com.docrag.mode.Mode;
import com.docrag.parser.DocumentParseException;

/** AskController 参数校验与透传：直构 controller + 打桩 AskService / LlmClient */
class AskControllerTest {

    /** 可切换可用性的 LLM 打桩（apiKey 默认为空 = 未配置） */
    static class StubLlm extends LlmClient {
        final boolean enabled;

        StubLlm(boolean enabled) {
            super(new DocRagProperties());
            this.enabled = enabled;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }

    /** 捕获入参的 AskService 打桩 */
    static class StubAskService extends AskService {
        int calls;
        String question;
        Set<Mode> modes;
        Integer bm25;
        Integer vector;
        Integer context;

        StubAskService() {
            super(null, null, new DocRagProperties());
        }

        @Override
        public AskResponse ask(String question, Set<Mode> modes,
                               Integer bm25Chunks, Integer vectorChunks, Integer contextChunks) {
            calls++;
            this.question = question;
            this.modes = modes;
            this.bm25 = bm25Chunks;
            this.vector = vectorChunks;
            this.context = contextChunks;
            return new AskResponse("stub-model", java.util.Map.of(), new AskParams(5, 5, 8));
        }
    }

    private AskController controller(StubAskService service, boolean llmEnabled) {
        return new AskController(service, new StubLlm(llmEnabled), new DocRagProperties());
    }

    @Test
    void blankQuestionRejected() {
        StubAskService service = new StubAskService();
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> controller(service, true).ask(new AskRequest("  ", List.of("plain"), null, null, null)));
        assertEquals("问题不能为空", e.getMessage());
        assertEquals(0, service.calls);
    }

    @Test
    void llmNotConfiguredRejected() {
        StubAskService service = new StubAskService();
        DocumentParseException e = assertThrows(DocumentParseException.class,
                () -> controller(service, false).ask(new AskRequest("问题", List.of("plain"), null, null, null)));
        assertTrue(e.getMessage().contains("LLM 未配置"));
        assertEquals(0, service.calls);
    }

    @Test
    void invalidModeRejected() {
        StubAskService service = new StubAskService();
        assertThrows(DocumentParseException.class,
                () -> controller(service, true).ask(new AskRequest("问题", List.of("plain", "x"), null, null, null)));
        assertEquals(0, service.calls);
    }

    @Test
    void defaultsAndPassthrough() throws Exception {
        StubAskService service = new StubAskService();
        // modes 缺省 → plain；问题 trim；三个 Integer 原样透传（null = 用配置默认）
        controller(service, true).ask(new AskRequest("  预算是多少  ", null, null, null, null));
        assertEquals("预算是多少", service.question);
        assertEquals(Set.of(Mode.PLAIN), service.modes);

        controller(service, true).ask(new AskRequest("问题", List.of("deep", "plain"), 3, 7, 2));
        assertEquals(Set.of(Mode.PLAIN, Mode.DEEP), service.modes);
        assertEquals(3, service.bm25);
        assertEquals(7, service.vector);
        assertEquals(2, service.context);
        assertEquals(2, service.calls);
    }

    @Test
    void paramsEndpointReturnsConfigDefaults() {
        DocRagProperties props = new DocRagProperties();
        props.getAsk().setBm25Chunks(3);
        props.getAsk().setVectorChunks(4);
        props.getAsk().setContextChunks(5);
        AskController c = new AskController(new StubAskService(), new StubLlm(true), props);
        assertEquals(new AskParams(3, 4, 5), c.params());
    }
}
