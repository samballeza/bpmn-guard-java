package bpmnguard.report;

import java.util.Collection;
import java.util.Map;

/**
 * Minimal JSON writer for report output (strings, numbers, booleans, null, maps, collections,
 * arrays of doubles). Keeps the tool free of runtime dependencies.
 */
public final class Json {

    private Json() {}

    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { quote(sb, s); return; }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) { sb.append(v); return; }
        if (v instanceof Double d) { sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString()); return; }
        if (v instanceof Number n) { sb.append(n); return; }
        if (v instanceof Enum<?> e) { quote(sb, e.name()); return; }
        if (v instanceof double[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
            sb.append(']');
            return;
        }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, pretty, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append('}');
            return;
        }
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, pretty, depth + 1);
                write(sb, o, pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append(']');
            return;
        }
        quote(sb, String.valueOf(v));
    }

    private static void newline(StringBuilder sb, boolean pretty, int depth) {
        if (!pretty) return;
        sb.append('\n');
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }
}
