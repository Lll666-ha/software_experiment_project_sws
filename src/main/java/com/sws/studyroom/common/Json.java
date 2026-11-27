package com.sws.studyroom.common;

import java.util.*;

/** JSON encoding and strict parsing of the flat scalar request objects used by this API. */
public final class Json {
    private Json() {}
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var join = new StringJoiner(",", "{", "}");
            map.forEach((k, v) -> join.add(encode(k.toString()) + ":" + encode(v)));
            return join.toString();
        }
        if (value instanceof Iterable<?> list) {
            var join = new StringJoiner(",", "[", "]");
            list.forEach(v -> join.add(encode(v))); return join.toString();
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
    public static Map<String, String> parse(String body) {
        return new Parser(body).object();
    }
    private static final class Parser {
        private final String text; private int at;
        Parser(String text) { this.text = text; }
        private void whitespace() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        private boolean take(char c) { whitespace(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        private void expect(char c) { if (!take(c)) throw invalid(); }
        private IllegalArgumentException invalid() { return new IllegalArgumentException("JSON格式错误或包含不支持的嵌套字段"); }
        Map<String, String> object() {
            var result = new LinkedHashMap<String, String>(); expect('{');
            if (!take('}')) {
                do {
                    String key = string(); expect(':'); whitespace(); String value;
                    if (at < text.length() && text.charAt(at) == '"') value = string();
                    else {
                        int start = at;
                        while (at < text.length() && ",} \r\n\t".indexOf(text.charAt(at)) < 0) at++;
                        String literal = text.substring(start, at);
                        if (!literal.matches("null|true|false|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
                        value = literal.equals("null") ? null : literal;
                    }
                    if (result.containsKey(key)) throw invalid(); result.put(key, value);
                } while (take(','));
                expect('}');
            }
            whitespace(); if (at != text.length()) throw invalid(); return result;
        }
        private String string() {
            expect('"'); var out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (at == text.length()) throw invalid();
                    c = text.charAt(at++);
                    switch (c) {
                        case '"', '\\', '/' -> out.append(c);
                        case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                        case 'u' -> {
                            if (at + 4 > text.length() || !text.substring(at, at + 4).matches("[0-9a-fA-F]{4}")) throw invalid();
                            out.append((char)Integer.parseInt(text.substring(at, at + 4), 16)); at += 4;
                        }
                        default -> throw invalid();
                    }
                } else out.append(c);
            }
            throw invalid();
        }
    }
}
