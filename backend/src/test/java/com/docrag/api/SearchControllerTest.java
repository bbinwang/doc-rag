package com.docrag.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.docrag.mode.Mode;
import com.docrag.searcher.ModeSearcher;
import com.docrag.searcher.SearchResponse;

/** 检索入口的分发/钳制/空查询语义：打桩 ModeSearcher（记录调用参数，不建真实索引） */
class SearchControllerTest {

    /** 记录 (q|page|size) 的打桩 searcher：ModeSearcher 构造器纯赋值，null 依赖不触达 */
    static class RecordingSearcher extends ModeSearcher {
        final List<String> calls = new ArrayList<>();
        final long total;

        RecordingSearcher(Mode mode, long total) {
            super(mode, null, null, null, null);
            this.total = total;
        }

        @Override
        public SearchResponse search(String q, int page, int size) {
            calls.add(q + "|" + page + "|" + size);
            return new SearchResponse(total, List.of(), false);
        }
    }

    private RecordingSearcher plain;
    private RecordingSearcher deep;
    private SearchController controller;

    @BeforeEach
    void setUp() {
        plain = new RecordingSearcher(Mode.PLAIN, 3);
        deep = new RecordingSearcher(Mode.DEEP, 7);
        Map<Mode, ModeSearcher> searchers = new EnumMap<>(Mode.class);
        searchers.put(Mode.PLAIN, plain);
        searchers.put(Mode.DEEP, deep);
        controller = new SearchController(searchers);
    }

    @Test
    void blankQueryReturnsEmptyPerModeWithoutSearching() throws Exception {
        Map<String, Object> resp = controller.search("   ", 1, 10, List.of("plain", "deep"));
        Map<?, ?> modes = (Map<?, ?>) resp.get("modes");
        assertEquals(2, modes.size());
        for (Object r : modes.values()) {
            SearchResponse sr = (SearchResponse) r;
            assertEquals(0, sr.total());
            assertTrue(sr.hits().isEmpty());
            assertTrue(!sr.degraded());
        }
        assertTrue(plain.calls.isEmpty(), "空查询不应触达 searcher");
        assertTrue(deep.calls.isEmpty(), "空查询不应触达 searcher");
    }

    @Test
    void pageAndSizeClampedAndQueryTrimmed() throws Exception {
        controller.search("  合同  ", 0, 999, List.of("plain"));
        controller.search("合同", -5, 0, List.of("plain"));
        // page <1 → 1、size 超上限 → 50、size <1 → 1；查询词 trim 后下发
        assertEquals(List.of("合同|1|50", "合同|1|1"), plain.calls);
        assertTrue(deep.calls.isEmpty(), "未选模式不调用");
    }

    @Test
    void modeFanOutPreservesRequestOrderAndTotals() throws Exception {
        Map<String, Object> resp = controller.search("合同", 1, 10, List.of("deep", "plain"));
        Map<?, ?> modes = (Map<?, ?>) resp.get("modes");
        assertEquals(List.of("deep", "plain"), new ArrayList<>(modes.keySet()), "key 顺序=请求顺序");
        assertEquals(7L, ((SearchResponse) modes.get("deep")).total(), "各模式独立返回自己的结果");
        assertEquals(3L, ((SearchResponse) modes.get("plain")).total());
        assertEquals(List.of("合同|1|10"), deep.calls);
        assertEquals(List.of("合同|1|10"), plain.calls);
    }

    @Test
    void invalidModeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.search("合同", 1, 10, List.of("bogus")));
    }
}
