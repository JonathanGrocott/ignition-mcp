package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.LinkedHashMap;

import static com.jg.ignition.assistant.AssistantJson.JSON;

/** Keeps complete tool results available within a turn while bounding each model message. */
final class ToolResultPages {
    private static final int INLINE_CHARS = 30_000;
    private static final int PAGE_CHARS = 24_000;
    private static final int CACHE_CHARS = 8_000_000;
    private static final int MAX_ENTRIES = 80;

    private final LinkedHashMap<String, Stored> cache = new LinkedHashMap<>();
    private int cachedChars;
    private int nextId;

    Output add(JsonNode value) throws IOException {
        String full = value.toString();
        if (full.length() > CACHE_CHARS) {
            throw new IOException("The read exceeds the Designer result storage limit. Read a specific folder, resource file, or smaller range.");
        }
        while (cachedChars + full.length() > CACHE_CHARS) {
            var oldest = cache.entrySet().iterator().next();
            cachedChars -= oldest.getValue().length();
            cache.remove(oldest.getKey());
        }
        String id = "result-" + ++nextId;
        cache.put(id, new Stored(value, full.length()));
        cachedChars += full.length();
        return new Output(id, full.length() <= INLINE_CHARS ? full : page(id, "", value, 0, MAX_ENTRIES).toString());
    }

    Output read(ObjectNode args) throws IOException {
        String id = UdtDraft.required(args, "resultId", 40);
        Stored stored = cache.get(id);
        if (stored == null) {
            throw new IOException("That result is unavailable in this turn. Repeat the original read to obtain a new result.");
        }
        String pointer = args.path("pointer").asText("");
        if (pointer.length() > 4096 || !pointer.isEmpty() && !pointer.startsWith("/")) {
            throw new IOException("Supply a JSON Pointer such as /details/resources, or an empty pointer for the whole result.");
        }
        JsonNode value = stored.value().at(pointer);
        if (value.isMissingNode()) {
            throw new IOException("The result has no value at that path. Inspect its parent to see available fields.");
        }
        int offset = offset(args, "offset", 0);
        int limit = Math.min(MAX_ENTRIES, Math.max(1, offset(args, "limit", MAX_ENTRIES)));
        return new Output(id, page(id, pointer, value, offset, limit).toString());
    }

    static int offset(JsonNode args, String key, int defaultValue) throws IOException {
        JsonNode number = args.get(key);
        if (number == null) {
            return defaultValue;
        }
        if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 0) {
            throw new IOException(key + " must be a non-negative integer.");
        }
        return number.intValue();
    }

    private static ObjectNode page(String id, String pointer, JsonNode value, int offset, int limit) throws IOException {
        var page = JSON.createObjectNode().put("resultId", id).put("pointer", pointer)
            .put("type", value.getNodeType().name().toLowerCase(java.util.Locale.ROOT));
        if (offset == 0 && value.toString().length() + page.toString().length() < PAGE_CHARS - 256
                && (!value.isContainerNode() || value.size() <= limit)) {
            return page.set("value", value);
        }
        if (value.isTextual()) {
            return textPage(page, value.textValue(), offset);
        }
        if (!value.isContainerNode()) {
            throw new IOException("The requested value has no pages at that offset.");
        }

        int total = value.size();
        if (offset > total) {
            throw new IOException("The page offset is beyond the end of the requested value.");
        }
        var entries = page.put("offset", offset).put("totalEntries", total).putArray("entries");
        int remaining = PAGE_CHARS - page.toString().length() - 256;
        var fields = value.fields();
        for (int index = 0; value.isObject() && index < offset; index++) {
            fields.next();
        }
        int index = offset;
        while (index < total && index - offset < limit) {
            String key;
            JsonNode item;
            if (value.isObject()) {
                var field = fields.next();
                key = field.getKey();
                item = field.getValue();
            } else {
                key = Integer.toString(index);
                item = value.get(index);
            }
            var row = JSON.createObjectNode().put("key", key);
            if (item.toString().length() > PAGE_CHARS / 4) {
                row.put("readPointer", pointer + "/" + key.replace("~", "~0").replace("/", "~1"))
                    .put("type", item.getNodeType().name().toLowerCase(java.util.Locale.ROOT));
                if (item.isContainerNode()) {
                    row.put("totalEntries", item.size());
                } else if (item.isTextual()) {
                    row.put("totalChars", item.textValue().length());
                }
            } else {
                row.set("value", item);
            }
            int cost = row.toString().length() + 1;
            if (cost > remaining) {
                if (entries.isEmpty()) {
                    throw new IOException("The selected field name is too large to page. Inspect a more specific path.");
                }
                break;
            }
            entries.add(row);
            remaining -= cost;
            index++;
        }
        if (index < total) {
            page.put("nextOffset", index);
        }
        return page;
    }

    private static ObjectNode textPage(ObjectNode page, String text, int offset) throws IOException {
        if (offset > text.length() || offset < text.length() && Character.isLowSurrogate(text.charAt(offset))) {
            throw new IOException("Use the returned nextOffset to continue this text value.");
        }
        page.put("offset", offset).put("totalChars", text.length());
        // A UTF-16 character can use six JSON characters when escaped.
        int end = (int) Math.min(text.length(), (long) offset + (PAGE_CHARS - page.toString().length() - 256) / 6);
        if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        page.put("text", text.substring(offset, end));
        if (end < text.length()) {
            page.put("nextOffset", end);
        }
        return page;
    }

    private record Stored(JsonNode value, int length) {}

    record Output(String id, String body) {
        String reference() {
            return JSON.createObjectNode().put("resultId", id)
                .put("note", "Still available through read_tool_result.").toString();
        }
    }
}
