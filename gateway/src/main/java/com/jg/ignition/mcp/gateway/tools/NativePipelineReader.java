package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Passive inspection of Ignition binary serialization v1/v2. Never loads stored classes,
 * invokes constructors/methods, resolves scripts, or starts notification jobs. */
final class NativePipelineReader {
    private static final UUID MAGIC = UUID.fromString("98298faa-43f7-4a4f-b28d-ca3c4896afc9");
    private static final String MODEL = "com.inductiveautomation.ignition.alarming.common.pipelines.PipelineDescriptor";
    private static final int MAX_EXPANDED = 8_388_608;
    private static final int MAX_NODES = 10000;
    private final ObjectMapper mapper;
    private final DataInputStream in;
    private final Map<Integer, String> strings = new HashMap<>();
    private final Map<Integer, ObjectNode> objects = new HashMap<>();
    private int nodes;
    private int textBudget = InspectionSupport.MAX_RESULT_BYTES;
    private int normalizedNodes;

    private NativePipelineReader(ObjectMapper mapper, byte[] bytes) {
        this.mapper = mapper;
        in = new DataInputStream(new ByteArrayInputStream(bytes));
    }

    static ObjectNode read(ObjectMapper mapper, byte[] bytes) {
        try {
            if (bytes.length > InspectionSupport.MAX_FILE_BYTES) { throw invalid("Pipeline exceeds file byte limit"); }
            boolean compressed = bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b;
            byte[] expanded = bytes;
            if (compressed) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                    expanded = gzip.readNBytes(MAX_EXPANDED + 1);
                    if (expanded.length > MAX_EXPANDED) { throw invalid("Pipeline exceeds expanded byte limit"); }
                }
            }
            var reader = new NativePipelineReader(mapper, expanded);
            if (!MAGIC.equals(new UUID(reader.in.readLong(), reader.in.readLong()))) { throw invalid("Unknown pipeline binary header"); }
            int version = reader.in.readInt();
            if (version != 1 && version != 2) { throw invalid("Unsupported pipeline binary version"); }
            long timestamp = reader.in.readLong();
            reader.in.readFully(new byte[20]);
            reader.dictionary(version);
            ObjectNode tree = reader.element(0);
            if (reader.in.read() != -1) { throw invalid("Trailing pipeline binary data"); }
            if (!tree.path("tag").asText().equals("objects") || tree.path("children").size() != 1) {
                throw invalid("Expected one pipeline descriptor");
            }
            JsonNode descriptor = tree.path("children").get(0);
            if (!MODEL.equals(descriptor.at("/attributes/cls").asText())) { throw invalid("Resource is not a pipeline descriptor"); }
            ObjectNode out = mapper.createObjectNode();
            out.put("format", "ignition-binary");
            out.put("binaryVersion", version);
            out.put("gzip", compressed);
            out.put("serializedAtEpochMillis", timestamp);
            out.set("properties", reader.value(descriptor, new HashSet<>(), 0));
            // Keep unknown fields, class names, constructor arguments and reference IDs inspectable.
            out.set("serialization", tree);
            if (mapper.writeValueAsBytes(out).length > InspectionSupport.MAX_RESULT_BYTES) {
                throw new InspectionSupport.InspectionException("RESULT_TOO_LARGE", "Pipeline inspection exceeds result byte limit");
            }
            return out;
        } catch (IOException e) { throw invalid("Malformed or truncated pipeline binary data"); }
    }

    private void dictionary(int version) throws IOException {
        int count = bounded(in.readInt(), MAX_NODES, "string count");
        for (int i = 0; i < count; i++) {
            int id = in.readInt();
            int kind = version == 2 ? in.readUnsignedByte() : 0;
            String text;
            if (kind == 0) { text = in.readUTF(); }
            else if (kind == 1) {
                int length = bounded(in.readInt(), InspectionSupport.MAX_FILE_BYTES, "string bytes");
                byte[] data = new byte[length];
                in.readFully(data);
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
            } else { throw invalid("Unknown string encoding"); }
            charge(text);
            if (id < 0 || strings.putIfAbsent(id, text) != null) { throw invalid("Invalid string ID"); }
        }
    }

    private ObjectNode element(int depth) throws IOException {
        if (depth > 64 || ++nodes > MAX_NODES) { throw invalid("Pipeline nesting/node limit exceeded"); }
        ObjectNode node = mapper.createObjectNode();
        int flags = in.readUnsignedByte();
        if (flags == 1) {
            int id = in.readInt();
            if (!objects.containsKey(id)) { throw invalid("Unknown pipeline object reference"); }
            return node.put("ref", id);
        }
        if ((flags & ~62) != 0) { throw invalid("Unknown pipeline element flags"); }
        String tag = string(false);
        node.put("tag", tag);
        if ((flags & 2) != 0) { node.put("method", string(false)); }
        if ((flags & 4) != 0) {
            int id = in.readInt();
            if (id < 0 || objects.putIfAbsent(id, node) != null) { throw invalid("Duplicate pipeline object ID"); }
            node.put("id", id);
        }
        if ((flags & 8) != 0) {
            ObjectNode attrs = node.putObject("attributes");
            int count = in.readUnsignedByte();
            for (int i = 0; i < count; i++) {
                String key = string(false);
                if (attrs.has(key)) { throw invalid("Duplicate pipeline attribute"); }
                attrs.set(key, scalar(in.readUnsignedByte()));
            }
        }
        if ((flags & 16) != 0) {
            int count = bounded(in.readInt(), MAX_NODES - nodes, "child count");
            ArrayNode children = node.putArray("children");
            for (int i = 0; i < count; i++) { children.add(element(depth + 1)); }
        }
        if ((flags & 32) != 0) {
            // Body framing precedes the codec value; -1 represents an absent body.
            int bodyLength = in.readInt();
            if (bodyLength == -1) { return node; }
            bounded(bodyLength, MAX_EXPANDED, "body length");
            int before = in.available();
            if (tag.equals("point") || tag.equals("dim")) {
                ObjectNode pair = mapper.createObjectNode();
                pair.put(tag.equals("point") ? "x" : "width", in.readInt());
                pair.put(tag.equals("point") ? "y" : "height", in.readInt());
                node.set("value", pair);
                if (before - in.available() != bodyLength) { throw invalid("Invalid pipeline body length"); }
                return node;
            }
            int codec = switch (tag) {
                case "byt" -> 0; case "shrt" -> 1; case "int" -> 2; case "lng" -> 3;
                case "flt" -> 4; case "dbl" -> 5; case "bool" -> 6; case "date" -> 7;
                case "str" -> 8; case "class" -> 9; case "color" -> 11; case "uuid" -> 12;
                case "qcode" -> 13;
                default -> throw invalid("Unsupported pipeline body tag: " + tag);
            };
            node.set("value", scalar(codec));
            if (before - in.available() != bodyLength) { throw invalid("Invalid pipeline body length"); }
        }
        return node;
    }

    private JsonNode scalar(int codec) throws IOException {
        if (codec >= 0 && codec <= 7 && !present()) { return mapper.nullNode(); }
        Object value = switch (codec) {
            case 0 -> in.readByte(); case 1 -> in.readShort(); case 2 -> in.readInt(); case 3 -> in.readLong();
            case 4 -> finite(in.readFloat()); case 5 -> finite(in.readDouble()); case 6 -> in.readBoolean();
            case 7 -> in.readLong();
            // Class/signature codecs deliberately retain names; no class loading or reflection.
            case 8, 9 -> string(true);
            case 10 -> {
                int count = bounded(in.readByte(), 127, "signature count");
                ArrayNode names = mapper.createArrayNode();
                for (int i = 0; i < count; i++) { names.add(string(false)); }
                yield names;
            }
            case 11, 13 -> present() ? in.readInt() : null;
            case 12 -> present() ? new UUID(in.readLong(), in.readLong()).toString() : null;
            default -> throw invalid("Unsupported pipeline codec");
        };
        return mapper.valueToTree(value);
    }

    private boolean present() throws IOException {
        int flag = in.readUnsignedByte();
        if (flag != 0 && flag != 255) { throw invalid("Invalid nullable value marker"); }
        return flag == 255;
    }

    private double finite(double number) {
        if (!Double.isFinite(number)) { throw invalid("Non-finite pipeline numeric value"); }
        return number;
    }

    private String string(boolean nullable) throws IOException {
        int id = in.readInt();
        if (id == -1 && nullable) { return null; }
        String text = strings.get(id);
        if (text == null) { throw invalid("Unknown pipeline string reference"); }
        charge(text);
        return text;
    }

    private void charge(String text) {
        textBudget -= text.length();
        if (textBudget < 0) { throw invalid("Pipeline text budget exceeded"); }
    }

    private JsonNode value(JsonNode original, Set<Integer> active, int depth) {
        if (depth > 64 || ++normalizedNodes > MAX_NODES) { throw invalid("Pipeline property expansion limit exceeded"); }
        JsonNode node = original.has("ref") ? objects.get(original.path("ref").asInt()) : original;
        int id = node.path("id").asInt(-1);
        if (id >= 0 && !active.add(id)) { return mapper.createObjectNode().put("ref", id); }
        try {
            if (node.has("value")) { return node.get("value"); }
            String tag = node.path("tag").asText();
            if (tag.equals("true") || tag.equals("false")) { return mapper.getNodeFactory().booleanNode(tag.equals("true")); }
            if (tag.equals("null")) { return mapper.nullNode(); }
            if (tag.equals("array")) {
                ArrayNode array = mapper.createArrayNode();
                for (JsonNode child : node.path("children")) { array.add(value(child, active, depth + 1)); }
                return array;
            }
            String cls = node.at("/attributes/cls").asText();
            if (cls.equals(MODEL) || cls.equals("com.inductiveautomation.ignition.common.config.BasicPropertySet")) {
                JsonNode children = node.path("children");
                if (children.size() == 1 && children.get(0).path("children").size() == 1) {
                    return value(children.get(0).path("children").get(0), active, depth + 1);
                }
            }
            if (cls.equals("java.util.HashMap")) {
                ObjectNode properties = mapper.createObjectNode();
                for (JsonNode entry : node.path("children")) {
                    JsonNode pair = entry.path("children");
                    if (!entry.at("/attributes/m").asText().equals("put") || pair.size() != 2) { return node; }
                    JsonNode key = pair.get(0);
                    if (key.has("ref")) { key = objects.get(key.path("ref").asInt()); }
                    JsonNode args = key.path("children").path(0).path("children");
                    if (!key.at("/attributes/cls").asText().equals("com.inductiveautomation.ignition.common.config.BasicProperty")
                        || args.size() < 1 || !args.get(0).path("value").isTextual()) { return node; }
                    String name = args.get(0).path("value").asText();
                    if (properties.has(name)) { throw invalid("Duplicate pipeline property"); }
                    properties.set(name, value(pair.get(1), active, depth + 1));
                }
                return properties;
            }
            return node;
        } finally { if (id >= 0) { active.remove(id); } }
    }

    private static int bounded(int value, int maximum, String name) {
        if (value < 0 || value > maximum) { throw invalid("Pipeline " + name + " exceeds bounds"); }
        return value;
    }

    private static InspectionSupport.InspectionException invalid(String message) {
        return new InspectionSupport.InspectionException("UNSUPPORTED_FORMAT", message);
    }
}
