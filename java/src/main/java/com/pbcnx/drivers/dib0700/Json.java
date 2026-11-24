/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the captured session files: an array of flat objects whose values are
 * numbers, strings, booleans or null. Deliberately not org.json, so the core has no dependency and
 * runs on both a plain JDK and Android.
 */
final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        Object value = parser.value();
        parser.space();
        if (parser.at != text.length()) throw parser.error("trailing data");
        return value;
    }

    private Object value() {
        space();
        if (at >= text.length()) throw error("unexpected end");
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        space();
        if (peek() == '}') {
            at++;
            return map;
        }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            map.put(key, value());
            space();
            char c = peek();
            at++;
            if (c == '}') return map;
            if (c != ',') throw error("expected , or }");
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        space();
        if (peek() == ']') {
            at++;
            return list;
        }
        while (true) {
            list.add(value());
            space();
            char c = peek();
            at++;
            if (c == ']') return list;
            if (c != ',') throw error("expected , or ]");
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw error("unterminated string");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) throw error("unterminated escape");
            char escape = text.charAt(at++);
            switch (escape) {
                case '"':
                case '\\':
                case '/':
                    out.append(escape);
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (at + 4 > text.length()) throw error("short unicode escape");
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default:
                    throw error("bad escape \\" + escape);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        if (at == start) throw error("expected a value");
        return Double.valueOf(text.substring(start, at));
    }

    private void literal(String word) {
        if (!text.startsWith(word, at)) throw error("expected " + word);
        at += word.length();
    }

    private void expect(char expected) {
        space();
        if (at >= text.length() || text.charAt(at) != expected) {
            throw error("expected " + expected);
        }
        at++;
    }

    private char peek() {
        space();
        if (at >= text.length()) throw error("unexpected end");
        return text.charAt(at);
    }

    private void space() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            at++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON at " + at + ": " + message);
    }
}
