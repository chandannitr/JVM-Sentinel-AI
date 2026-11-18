package dev.jvmsentinel.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON codec for the bounded Ollama boundary and the JDK-only launcher. */
final class Json {
    private Json() { }

    static String write(Object value) {
        var output = new StringBuilder();
        write(value, output);
        return output.toString();
    }

    private static void write(Object value, StringBuilder output) {
        switch (value) {
            case null -> output.append("null");
            case String string -> output.append(JsonStrings.quote(string));
            case Long number -> output.append(number);
            case Integer number -> output.append(number);
            case Boolean bool -> output.append(bool);
            case Map<?, ?> map -> {
                output.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) output.append(',');
                    output.append(JsonStrings.quote((String) entry.getKey())).append(':');
                    write(entry.getValue(), output);
                    first = false;
                }
                output.append('}');
            }
            case Iterable<?> list -> {
                output.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) output.append(',');
                    write(item, output);
                    first = false;
                }
                output.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON value");
        }
    }

    static Map<?, ?> object(String source) {
        var parser = new Parser(source);
        Object value = parser.value(0);
        parser.space();
        if (!(value instanceof Map<?, ?> object) || parser.index != source.length()) {
            throw invalid();
        }
        return object;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Malformed JSON");
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) { this.text = text; }

        Object value(int depth) {
            if (depth > 12) throw invalid();
            space();
            return switch (peek()) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            index++;
            var map = new LinkedHashMap<String, Object>();
            if (take('}')) return map;
            do {
                space();
                String key = string();
                if (map.containsKey(key) || !take(':')) throw invalid();
                map.put(key, value(depth));
            } while (take(','));
            if (!take('}')) throw invalid();
            return map;
        }

        private List<Object> array(int depth) {
            index++;
            var list = new ArrayList<>();
            if (take(']')) return list;
            do {
                list.add(value(depth));
            } while (take(','));
            if (!take(']')) throw invalid();
            return list;
        }

        private String string() {
            if (peek() != '"') throw invalid();
            index++;
            var out = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw invalid();
                out.append(c == '\\' ? escape() : c);
            }
            throw invalid();
        }

        private char escape() {
            if (index == text.length()) throw invalid();
            return switch (text.charAt(index++)) {
                case '"', '\\', '/' -> text.charAt(index - 1);
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> unicode();
                default -> throw invalid();
            };
        }

        private char unicode() {
            int code = 0;
            for (int i = 0; i < 4; i++) {
                char c = peek();
                int digit = c <= 127 ? Character.digit(c, 16) : -1;
                if (digit < 0) throw invalid();
                code = code * 16 + digit;
                index++;
            }
            return (char) code;
        }

        private Object literal(String token, Object value) {
            if (!text.startsWith(token, index)) throw invalid();
            index += token.length();
            return value;
        }

        private BigDecimal number() {
            int start = index;
            if (peek() == '-') index++;
            if (peek() == '0') index++;
            else digits();
            if (peek() == '.') {
                index++;
                digits();
            }
            if (peek() == 'e' || peek() == 'E') {
                index++;
                if (peek() == '+' || peek() == '-') index++;
                digits();
            }
            if (index - start > 64) throw invalid();
            try {
                return new BigDecimal(text.substring(start, index));
            } catch (NumberFormatException failure) {
                throw invalid();
            }
        }

        private void digits() {
            int start = index;
            while (peek() >= '0' && peek() <= '9') index++;
            if (start == index) throw invalid();
        }

        private boolean take(char c) {
            space();
            if (peek() != c) return false;
            index++;
            return true;
        }

        private char peek() {
            return index < text.length() ? text.charAt(index) : 0;
        }

        private void space() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') index++;
        }
    }
}
