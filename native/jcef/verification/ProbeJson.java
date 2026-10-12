import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON codec for CDP responses and machine-readable test results; no runtime dependencies. */
final class ProbeJson {
    private final String source;
    private int index;

    private ProbeJson(String source) { this.source = source; }

    static Object parse(String source) {
        ProbeJson parser = new ProbeJson(source);
        Object result = parser.value();
        parser.space();
        if (parser.index != source.length()) throw new IllegalArgumentException("Trailing JSON data");
        return result;
    }

    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder result = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    default -> {
                        if (c < 32) result.append(String.format("\\u%04x", (int) c));
                        else result.append(c);
                    }
                }
            }
            return result.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, item) -> entries.add(encode(key.toString()) + ":" + encode(item)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> values) {
            List<String> entries = new ArrayList<>();
            values.forEach(item -> entries.add(encode(item)));
            return "[" + String.join(",", entries) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private void space() {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
    }

    private char take() {
        if (index >= source.length()) throw new IllegalArgumentException("Incomplete JSON");
        return source.charAt(index++);
    }

    private void expect(char expected) {
        if (take() != expected) throw new IllegalArgumentException("Expected " + expected);
    }

    private Object value() {
        space();
        char c = take();
        if (c == '"') return string();
        if (c == '{') {
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (source.charAt(index) == '}') { index++; return result; }
            do {
                space(); expect('"'); String key = string(); space(); expect(':');
                if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key");
                result.put(key, value()); space(); c = take();
            } while (c == ',');
            if (c != '}') throw new IllegalArgumentException("Expected object end");
            return result;
        }
        if (c == '[') {
            List<Object> result = new ArrayList<>();
            space();
            if (source.charAt(index) == ']') { index++; return result; }
            do { result.add(value()); space(); c = take(); } while (c == ',');
            if (c != ']') throw new IllegalArgumentException("Expected array end");
            return result;
        }
        int start = index - 1;
        while (index < source.length() && ",]} \r\n\t".indexOf(source.charAt(index)) < 0) index++;
        String literal = source.substring(start, index);
        return switch (literal) {
            case "true" -> true;
            case "false" -> false;
            case "null" -> null;
            default -> Double.valueOf(literal);
        };
    }

    private String string() {
        StringBuilder result = new StringBuilder();
        for (;;) {
            char c = take();
            if (c == '"') return result.toString();
            if (c != '\\') { result.append(c); continue; }
            char escaped = take();
            result.append(switch (escaped) {
                case '"', '\\', '/' -> escaped;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    char decoded = (char) Integer.parseInt(source.substring(index, index + 4), 16);
                    index += 4; yield decoded;
                }
                default -> throw new IllegalArgumentException("Invalid JSON escape");
            });
        }
    }
}
