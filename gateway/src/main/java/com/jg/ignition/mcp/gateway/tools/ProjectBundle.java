package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.ImmutableBytes;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Parses the module's portable JSON bundle, never a Gateway filesystem path or an executable archive. */
final class ProjectBundle {
    static final String FORMAT = "ignition-mcp.project-resource-bundle.v1";
    static final int MAX_INPUT_BYTES = 2_097_152;
    static final int MAX_RESOURCE_BYTES = 524_288;
    private static final Set<String> CALCULATED_ATTRIBUTES = Set.of("lastModification", "lastModificationSignature");

    record Item(Resource resource, String operation, int bytes) {}
    record Parsed(List<Item> items, int bytes, boolean complete) {}

    private ProjectBundle() {}

    static Parsed parse(JsonNode bundle, String target, ObjectMapper mapper) {
        if (!bundle.isObject() || !FORMAT.equals(bundle.path("format").asText())) {
            throw new IllegalArgumentException("bundle.format must be " + FORMAT);
        }
        if (bundle.toString().getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Bundle exceeds the 2 MiB input limit");
        }
        canonical(bundle, mapper, 0); // Reject deep metadata before passing it to the platform JSON codec.
        JsonNode resources = bundle.path("resources");
        if (!resources.isArray() || resources.isEmpty() || resources.size() > 100) {
            throw new IllegalArgumentException("bundle.resources must contain 1 to 100 resources");
        }
        List<Item> items = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        int bytes = 0;
        for (JsonNode node : resources) {
            if (!node.isObject()) { throw new IllegalArgumentException("Each resource must be an object"); }
            String module = identifier(node, "moduleId");
            String type = identifier(node, "resourceType");
            String path = path(node.path("path").asText(""), true);
            String operation = node.path("operation").asText("upsert");
            if (!Set.of("upsert", "create", "edit", "delete").contains(operation)) {
                throw new IllegalArgumentException("Resource operation must be upsert, create, edit or delete");
            }
            if (!identities.add(module + "/" + type + "/" + path)) {
                throw new IllegalArgumentException("Duplicate resource identity in bundle");
            }
            int scope = scope(node.path("applicationScope"));
            int version = node.path("version").asInt(1);
            if (scope < 1 || scope > 7 || version < 1 || version > 1000) {
                throw new IllegalArgumentException("Invalid resource scope or version");
            }
            ResourceBuilder builder = Resource.newBuilder().setResourceCollectionName(target)
                .setResourcePath(ProjectResourceService.resourcePath(module, type, path))
                .setVersion(version).setApplicationScope(scope)
                .setDocumentation(node.path("documentation").asText(""))
                .setRestricted(node.path("restricted").asBoolean(false))
                .setOverridable(node.path("overridable").asBoolean(true))
                .setFolder(node.path("folder").asBoolean(false))
                .setUnary(node.path("unary").asBoolean(false));
            JsonNode attributes = node.path("attributes");
            if (!attributes.isMissingNode() && !attributes.isObject()) { throw new IllegalArgumentException("attributes must be an object"); }
            if (attributes.size() > 256) { throw new IllegalArgumentException("Too many resource attributes"); }
            attributes.fields().forEachRemaining(field -> {
                if (!CALCULATED_ATTRIBUTES.contains(field.getKey())) {
                    builder.putAttribute(field.getKey(), JsonParser.parseString(field.getValue().toString()));
                }
            });
            JsonNode data = node.path("data");
            if (!data.isMissingNode() && !data.isObject()) { throw new IllegalArgumentException("data must be an object"); }
            if (data.size() > 32 || operation.equals("delete") && !data.isEmpty()) {
                throw new IllegalArgumentException("Too many files, or delete operation contains unused data");
            }
            int resourceBytes = attributes.toString().getBytes(StandardCharsets.UTF_8).length;
            var fields = data.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                path(field.getKey(), false);
                JsonNode file = field.getValue();
                if (!file.isObject() || !file.has("value")) { throw new IllegalArgumentException("Each file requires encoding and value"); }
                String encoding = file.path("encoding").asText("utf-8");
                byte[] raw;
                if (encoding.equals("base64")) {
                    if (!file.get("value").isTextual()) { throw new IllegalArgumentException("Base64 value must be a string"); }
                    raw = Base64.getDecoder().decode(file.get("value").asText());
                } else if (encoding.equals("utf-8")) {
                    raw = (file.get("value").isTextual() ? file.get("value").asText() : file.get("value").toString())
                        .getBytes(StandardCharsets.UTF_8);
                } else { throw new IllegalArgumentException("Unsupported file encoding"); }
                resourceBytes += raw.length;
                if (resourceBytes > MAX_RESOURCE_BYTES) { throw new IllegalArgumentException("Resource exceeds 512 KiB"); }
                builder.putData(field.getKey(), raw);
            }
            if (resourceBytes > MAX_RESOURCE_BYTES) { throw new IllegalArgumentException("Resource exceeds 512 KiB"); }
            bytes += resourceBytes;
            if (bytes > MAX_INPUT_BYTES) { throw new IllegalArgumentException("Decoded bundle exceeds 2 MiB"); }
            items.add(new Item(builder.build(), operation, resourceBytes));
        }
        items.sort(Comparator.comparing(item -> item.resource.getResourcePath().toString()));
        boolean complete = !bundle.path("truncated").asBoolean(false);
        if (bundle.has("resourceCount") && bundle.path("resourceCount").asInt(-1) != resources.size()) { complete = false; }
        if (bundle.has("matchedBeforeLimit") && bundle.path("matchedBeforeLimit").asInt(-1) != resources.size()) { complete = false; }
        return new Parsed(List.copyOf(items), bytes, complete);
    }

    private static String identifier(JsonNode node, String key) {
        String value = InspectionSupport.required(node, key);
        if (value.length() > 160 || !value.matches("[A-Za-z0-9_.-]+") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return value;
    }

    static int scope(JsonNode value) {
        if (value.isMissingNode()) { return 7; }
        if (value.isIntegralNumber() && value.canConvertToInt()) { return value.intValue(); }
        if (value.isTextual()) {
            for (int scope = 1; scope <= 7; scope++) {
                if (com.inductiveautomation.ignition.common.model.ApplicationScope.toCode(scope).equals(value.asText())) { return scope; }
            }
        }
        throw new IllegalArgumentException("applicationScope must be a valid Ignition scope code or bitmask");
    }

    static String path(String value, boolean allowEmpty) {
        if (allowEmpty && value.isEmpty()) { return value; }
        if (value.isEmpty() || value.length() > 512 || !value.equals(value.trim()) || value.contains("\\")
            || value.chars().anyMatch(Character::isISOControl)) { throw new IllegalArgumentException("Invalid resource/file path"); }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Resource/file paths must be canonical relative paths");
            }
        }
        return value;
    }

    static String fingerprint(Resource resource, ObjectMapper mapper) {
        if (resource == null) { return "absent"; }
        if (resource.getDataKeys().size() > 32 || resource.getAttributes().size() > 256) {
            throw new IllegalArgumentException("Resource exceeds supported file/attribute counts");
        }
        ObjectNode semantic = mapper.createObjectNode();
        semantic.put("scope", resource.getApplicationScope()).put("version", resource.getVersion())
            .put("restricted", resource.isRestricted()).put("overridable", resource.isOverridable())
            .put("folder", resource.isFolder()).put("unary", resource.isUnary())
            .put("documentation", resource.getDocumentation() == null ? "" : resource.getDocumentation());
        ObjectNode attributes = semantic.putObject("attributes");
        for (var entry : new TreeMap<>(resource.getAttributes()).entrySet()) {
            if (!CALCULATED_ATTRIBUTES.contains(entry.getKey())) {
                try { attributes.set(entry.getKey(), canonical(mapper.readTree(entry.getValue().toString()), mapper, 0)); }
                catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid resource attribute JSON"); }
            }
        }
        ObjectNode files = semantic.putObject("files");
        int bytes = attributes.toString().getBytes(StandardCharsets.UTF_8).length;
        for (String key : resource.getDataKeys().stream().sorted().toList()) {
            ImmutableBytes raw = resource.getData(key).orElseThrow(() -> new IllegalArgumentException("Missing resource data"));
            bytes += raw.length();
            if (bytes > MAX_RESOURCE_BYTES) { throw new IllegalArgumentException("Target resource exceeds 512 KiB"); }
            byte[] content = raw.getBytes();
            if (key.endsWith(".json")) {
                try { content = canonical(mapper.readTree(content), mapper, 0).toString().getBytes(StandardCharsets.UTF_8); }
                catch (java.io.IOException ignored) { /* Opaque data retains byte identity. */ }
            }
            files.put(key, sha256(content));
        }
        return sha256(semantic.toString().getBytes(StandardCharsets.UTF_8));
    }

    static JsonNode canonical(JsonNode node, ObjectMapper mapper, int depth) {
        if (node == null || depth > 64) { throw new IllegalArgumentException("JSON exceeds supported nesting depth"); }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            var fields = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(field -> fields.put(field.getKey(), field.getValue()));
            fields.forEach((key, value) -> result.set(key, canonical(value, mapper, depth + 1)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(value -> result.add(canonical(value, mapper, depth + 1)));
            return result;
        }
        return node;
    }

    static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
