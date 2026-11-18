package dev.jvmsentinel.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small JSON string utility for Ollama's single-response generate endpoint. */
final class JsonStrings {
    private JsonStrings() { }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    // Minimal extraction for a known top-level Ollama response field; NOT a general JSON parser.
    static String extractStringField(String json, String field) {
        Pattern pattern = Pattern.compile(Pattern.quote(quote(field)) + "\\s*:\\s*\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Missing response field: " + field);
        int i = matcher.end();
        StringBuilder value = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '"') return value.toString();
            if (c != '\\') { value.append(c); continue; }
            if (i >= json.length()) break;
            char escaped = json.charAt(i++);
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    if (i + 4 > json.length()) throw new IllegalArgumentException("Invalid JSON unicode escape");
                    value.append((char)Integer.parseInt(json.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("Invalid JSON escape: " + escaped);
            }
        }
        throw new IllegalArgumentException("Incomplete JSON response");
    }
}
