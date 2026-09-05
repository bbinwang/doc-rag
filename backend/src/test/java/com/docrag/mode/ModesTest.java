package com.docrag.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** modes 参数解析：逗号串 / repeated 参数 / 非法值 / 空值 */
class ModesTest {

    @Test
    void parseCommaJoinedKeepsOrderAndDedupes() {
        assertEquals(List.of("plain", "deep"), Modes.ids(Modes.parse("plain,deep")));
        assertEquals(List.of("deep", "plain"), Modes.ids(Modes.parse("deep,plain")));
        // 重复值去重
        assertEquals(List.of("plain", "deep"), Modes.ids(Modes.parse("plain,deep,plain")));
        // 空白容错
        assertEquals(List.of("deep"), Modes.ids(Modes.parse(" deep , ")));
    }

    @Test
    void parseListAcceptsRepeatedParams() {
        assertEquals(List.of("plain", "deep"), Modes.ids(Modes.parseList(List.of("plain", "deep"))));
        assertEquals(List.of("deep"), Modes.ids(Modes.parseList(List.of("deep"))));
    }

    @Test
    void invalidValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> Modes.parse("full"));
        assertThrows(IllegalArgumentException.class, () -> Modes.parse("table"));
        assertThrows(IllegalArgumentException.class, () -> Modes.parseList(List.of("plain", "xx")));
    }

    @Test
    void emptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> Modes.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Modes.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Modes.parseList(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Modes.parseList(List.of("", " ")));
    }

    @Test
    void modeOfAndLabels() {
        assertEquals(Mode.PLAIN, Mode.of("plain"));
        assertEquals(Mode.DEEP, Mode.of("deep"));
        assertEquals("纯文本解析", Mode.PLAIN.label());
        assertEquals("深度解析", Mode.DEEP.label());
        assertTrue(Set.of(Mode.values()).size() == 2);
    }
}
