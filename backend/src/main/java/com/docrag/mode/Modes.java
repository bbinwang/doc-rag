package com.docrag.mode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** modes 参数解析工具：兼容逗号串（"plain,deep"）与 repeated 参数（plain&modes=deep） */
public final class Modes {

    private Modes() {
    }

    /** 解析逗号串，保序去重；空串或含非法值抛 IllegalArgumentException */
    public static Set<Mode> parse(String joined) {
        if (joined == null || joined.isBlank()) {
            throw new IllegalArgumentException("modes 不能为空（可选 plain / deep，至少选一个）");
        }
        return parseList(Arrays.asList(joined.split(",")));
    }

    /** 解析 repeated 参数列表，保序去重；空列表或含非法值抛 IllegalArgumentException */
    public static Set<Mode> parseList(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("modes 不能为空（可选 plain / deep，至少选一个）");
        }
        Set<Mode> out = new LinkedHashSet<>();
        for (String v : values) {
            String trimmed = v == null ? "" : v.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.add(Mode.of(trimmed));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("modes 不能为空（可选 plain / deep，至少选一个）");
        }
        return out;
    }

    /** Mode 集合 → 有序 id 列表（API 响应用） */
    public static List<String> ids(Set<Mode> modes) {
        List<String> out = new ArrayList<>();
        for (Mode m : modes) {
            out.add(m.id());
        }
        return out;
    }
}
