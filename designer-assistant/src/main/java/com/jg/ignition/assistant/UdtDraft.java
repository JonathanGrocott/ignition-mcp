package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.jg.ignition.assistant.AssistantJson.JSON;

record UdtDraft(String provider, String folder, String name, String description, List<Member> members) {
    static final Set<String> DATA_TYPES = Set.of(
        "Boolean", "Int1", "Int2", "Int4", "Int8", "Float4", "Float8", "String", "Text", "DateTime");

    static UdtDraft parse(ObjectNode args, String defaultProvider) throws IOException {
        String provider = optional(args, "provider", 128);
        if (provider.isEmpty()) {
            provider = defaultProvider == null ? "" : defaultProvider;
        }
        requireProvider(provider);
        if (!args.path("folder").isTextual()) {
            throw new IOException("Choose a destination folder, or specify the definition root.");
        }
        String folder = relativeFolder(optional(args, "folder", 360));
        String name = segment(required(args, "name", 128));
        String description = optional(args, "description", 500);
        JsonNode rows = args.path("members");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 24) {
            throw new IOException("Choose between 1 and 24 UDT members.");
        }
        var members = new ArrayList<Member>();
        var names = new HashSet<String>();
        for (JsonNode row : rows) {
            if (!(row instanceof ObjectNode member) || !onlyFields(member, Set.of("name", "dataType", "description"))) {
                throw new IOException("This UDT creator supports named memory members with a data type and description.");
            }
            String memberName = segment(required(member, "name", 128));
            if (!names.add(memberName.toLowerCase(Locale.ROOT))) {
                throw new IOException("Member names must be unique.");
            }
            String dataType = required(member, "dataType", 32);
            if (!DATA_TYPES.contains(dataType)) {
                throw new IOException("Unsupported member data type. Choose " + String.join(", ", DATA_TYPES.stream().sorted().toList()) + ".");
            }
            members.add(new Member(memberName, dataType, optional(member, "description", 300)));
        }
        if (!onlyFields(args, Set.of("provider", "folder", "name", "description", "members"))) {
            throw new IOException("The proposal contains UDT options this version cannot apply.");
        }
        return new UdtDraft(provider, folder, name, description, List.copyOf(members));
    }

    static String relativeFolder(String folder) throws IOException {
        if (folder.isEmpty() || folder.equals("_types_")) {
            return "";
        }
        if (folder.startsWith("_types_/")) {
            folder = folder.substring("_types_/".length());
        }
        for (String value : folder.split("/", -1)) {
            segment(value);
        }
        return folder;
    }

    static String segment(String value) throws IOException {
        if (value.isEmpty() || value.length() > 128 || !value.equals(value.strip())
                || value.equals(".") || value.equals("..")
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || "[]/\\.:*?".indexOf(c) >= 0)) {
            throw new IOException("Enter a valid Ignition tag or folder name.");
        }
        return value;
    }

    static void requireProvider(String provider) throws IOException {
        if (provider.isBlank() || !provider.equals(provider.strip()) || provider.length() > 128
                || provider.equalsIgnoreCase("system") || provider.equalsIgnoreCase("client")
                || provider.codePoints().anyMatch(c -> Character.isISOControl(c) || "[]/\\:".indexOf(c) >= 0)) {
            throw new IOException("Choose a Gateway tag provider from the Tag Browser.");
        }
    }

    static String optional(JsonNode args, String key, int limit) throws IOException {
        return args.hasNonNull(key) ? text(args, key, limit, true) : "";
    }

    static String required(JsonNode args, String key, int limit) throws IOException {
        return text(args, key, limit, false);
    }

    private static boolean onlyFields(ObjectNode args, Set<String> fields) {
        return args.properties().stream().allMatch(field -> fields.contains(field.getKey()));
    }

    private static String text(JsonNode args, String key, int limit, boolean allowEmpty) throws IOException {
        JsonNode value = args.path(key);
        if (!value.isTextual() || value.textValue().length() > limit || (!allowEmpty && value.textValue().isBlank())
                || value.textValue().codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new IOException("Invalid " + key + " in the proposed Designer action.");
        }
        return value.textValue().strip();
    }

    String parentPath() { return "[" + provider + "]_types_" + (folder.isEmpty() ? "" : "/" + folder); }
    String path() { return parentPath() + "/" + name; }

    ObjectNode receipt() {
        var result = JSON.createObjectNode().put("path", path()).put("verified", true);
        var names = result.putArray("members");
        members.forEach(member -> names.addObject().put("name", member.name()).put("dataType", member.dataType()));
        return result;
    }

    record Member(String name, String dataType, String description) {}
}
