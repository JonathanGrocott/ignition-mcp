package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** Reads a native project export into resource entries, without writing archive paths to disk. */
final class ProjectExportZip {
    private static final int MAX_ENTRIES = 4_096;
    private static final String MANIFEST = "/resource.json";
    private static final String UNARY_MANIFEST = "/unary-resource.json";

    private ProjectExportZip() {}

    static ProjectBundle.Parsed parse(String encoded, String project, ObjectMapper mapper) throws IOException {
        if (encoded.length() > 4 * ((ProjectBundle.MAX_INPUT_BYTES + 2) / 3)) {
            throw new IllegalArgumentException("ZIP exceeds the 2 MiB archive limit");
        }
        byte[] archive = Base64.getDecoder().decode(encoded);
        if (archive.length > ProjectBundle.MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("ZIP exceeds the 2 MiB archive limit");
        }
        Map<String, byte[]> files = unzip(archive);
        // The destination is always supplied separately. Project export settings are not resource files.
        files.remove("project.json");
        var bundle = mapper.createObjectNode().put("format", ProjectBundle.FORMAT);
        var rows = bundle.putArray("resources");
        for (String manifestPath : files.keySet().stream()
            .filter(path -> path.endsWith(MANIFEST) || path.endsWith(UNARY_MANIFEST)).sorted().toList()) {
            if (rows.size() >= 100) { throw new IllegalArgumentException("ZIP contains more than 100 resources"); }
            byte[] metadata = files.remove(manifestPath);
            JsonNode manifest = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(metadata);
            String directory = manifestPath.substring(0, manifestPath.lastIndexOf('/'));
            String[] identity = directory.split("/", 3);
            boolean unary = manifestPath.endsWith(UNARY_MANIFEST)
                || directory.equals("com.inductiveautomation.perspective/page-config");
            if (identity.length < 2 || (unary && identity.length != 2)
                || manifest == null || !manifest.isObject() || !manifest.path("files").isArray()
                || !manifest.hasNonNull("scope") || !manifest.path("version").isIntegralNumber()) {
                throw new IllegalArgumentException("Invalid native resource manifest: " + manifestPath);
            }
            ObjectNode row = rows.addObject().put("moduleId", identity[0]).put("resourceType", identity[1])
                .put("path", identity.length == 3 ? identity[2] : "").put("unary", unary);
            row.set("applicationScope", manifest.get("scope"));
            for (String field : new String[]{"version", "documentation", "restricted", "overridable", "attributes"}) {
                if (manifest.has(field)) { row.set(field, manifest.get(field)); }
            }
            ObjectNode data = row.putObject("data");
            for (JsonNode file : manifest.path("files")) {
                if (!file.isTextual()) { throw new IllegalArgumentException("Manifest file names must be strings"); }
                String fileName = file.textValue();
                ProjectBundle.dataFile(fileName);
                byte[] bytes = files.remove(directory + "/" + fileName);
                if (bytes == null) { throw new IllegalArgumentException("Missing or duplicate archive file: " + fileName); }
                data.putObject(fileName).put("encoding", "base64").put("value", Base64.getEncoder().encodeToString(bytes));
            }
        }
        if (!files.isEmpty()) {
            throw new IllegalArgumentException("ZIP has files not declared by a native resource manifest");
        }
        return ProjectBundle.parse(bundle, project, mapper, false);
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        int expectedEntries = centralDirectoryEntryCount(archive);
        if (expectedEntries > MAX_ENTRIES) { throw new IllegalArgumentException("ZIP contains too many entries"); }
        var paths = new HashSet<String>();
        var files = new LinkedHashMap<String, byte[]>();
        int remaining = ProjectBundle.MAX_INPUT_BYTES;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                String path = ProjectBundle.path(entry.isDirectory() ? name.substring(0, name.length() - 1) : name, false);
                if (!paths.add(path) || paths.size() > expectedEntries) {
                    throw new IllegalArgumentException("ZIP contains duplicate paths or an invalid entry count");
                }
                int limit = entry.isDirectory() ? 0 : Math.min(remaining, ProjectBundle.MAX_RESOURCE_BYTES);
                byte[] content = zip.readNBytes(limit + 1);
                if (content.length > limit) { throw new IllegalArgumentException("Expanded ZIP entry or archive exceeds its limit"); }
                remaining -= content.length;
                if (!entry.isDirectory()) { files.put(path, content); }
            }
        }
        if (paths.size() != expectedEntries) { throw new IllegalArgumentException("ZIP is missing entries"); }
        return files;
    }

    private static int centralDirectoryEntryCount(byte[] archive) {
        // Require an end record so a truncated stream of local entries cannot become a partial import.
        for (int end = archive.length - 22; end >= Math.max(0, archive.length - 22 - 65_535); end--) {
            if (word(archive, end) == 0x4b50 && word(archive, end + 2) == 0x0605
                && end + 22 + word(archive, end + 20) == archive.length
                && word(archive, end + 4) == 0 && word(archive, end + 6) == 0
                && word(archive, end + 8) == word(archive, end + 10)) {
                long directorySize = word(archive, end + 12) | (long) word(archive, end + 14) << 16;
                long directoryOffset = word(archive, end + 16) | (long) word(archive, end + 18) << 16;
                if (directorySize + directoryOffset == end) { return word(archive, end + 10); }
            }
        }
        throw new IllegalArgumentException("Invalid, incomplete, or split ZIP archive");
    }

    private static int word(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }
}
