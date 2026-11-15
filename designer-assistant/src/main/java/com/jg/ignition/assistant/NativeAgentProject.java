package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy;
import com.inductiveautomation.ignition.common.tags.config.TagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.SecurityContext;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.designer.IgnitionDesigner;
import com.inductiveautomation.ignition.designer.model.DesignerContext;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.jg.ignition.assistant.AssistantJson.JSON;

final class NativeAgentProject implements AgentProject {
    private static final int LIST_LIMIT = 100;
    private static final int TEXT_LIMIT = 6_000;
    private static final int SECONDS = 15;
    private static final Set<String> SCRIPT_TYPES = Set.of(
        "script-python", "script-app-library", "startup", "shutdown", "update", "timer", "message",
        "tag-change", "scheduled", "event-scripts");

    private final DesignerContext context;

    NativeAgentProject(DesignerContext context) {
        this.context = context;
    }

    @Override public String name() { return context.getProjectName(); }
    @Override public String defaultProvider() { return context.getDefaultTagProviderName(); }

    @Override public void checkProjectChanges(Set<String> paths) throws IOException, InterruptedException {
        if (paths.isEmpty()) {
            return;
        }
        var unsaved = onEdt(() -> ChangeOperation.changeOpsToIdSet(context.getProject().getChanges()).stream()
            .map(id -> id.getResourcePath().toString()).filter(paths::contains).findFirst());
        if (unsaved.isPresent()) {
            throw new IOException("Save or discard the unsaved Designer changes to " + unsaved.get()
                + " before editing this resource through the assistant.");
        }
    }

    @Override public JsonNode inspect(String action, ObjectNode args) throws IOException, InterruptedException {
        return switch (action) {
            case "project_overview" -> overview();
            case "list_project_resources" -> listResources(args);
            case "read_project_resource" -> readResource(args);
            case "list_tag_providers" -> providers();
            case "browse_tags" -> browse(tagPath(args, "folder", false, true), false);
            case "browse_udts" -> browse(tagPath(args, "folder", true, true), true);
            case "read_tag" -> readTag(tagPath(args, "path", false, false));
            case "read_udt" -> readUdt(tagPath(args, "path", true, false));
            default -> throw new IOException("Unknown inspection action.");
        };
    }

    private ObjectNode overview() throws IOException, InterruptedException {
        var out = JSON.createObjectNode().put("project", name()).put("ignitionVersion", "8.3.9")
            .put("defaultTagProvider", defaultProvider());
        var resources = onEdt(() -> List.copyOf(context.getProject().getResources()));
        var counts = out.putObject("projectResources");
        for (String kind : List.of("perspective", "scripts", "namedQueries")) {
            counts.put(kind, resources.stream().filter(r -> matches(r, kind)).count());
        }
        var modules = out.putArray("installedModules");
        context.getModules().stream().limit(40).forEach(module ->
            modules.addObject().put("name", module.getName()).put("version", String.valueOf(module.getVersion())));
        return out;
    }

    private ObjectNode listResources(ObjectNode args) throws IOException, InterruptedException {
        String kind = kind(args);
        String folder = UdtDraft.optional(args, "folder", 350);
        var resources = onEdt(() -> context.getProject().getResources().stream()
            .filter(r -> matches(r, kind) && (folder.isEmpty() || path(r).startsWith(folder + "/")))
            .sorted(Comparator.comparing(NativeAgentProject::path))
            .limit(LIST_LIMIT + 1L)
            .map(r -> resourceSummary(r, context.getProject().isChanged(r.getResourcePath())))
            .toList());
        var out = JSON.createObjectNode().put("project", name()).put("kind", kind).put("folder", folder)
            .put("truncated", resources.size() > LIST_LIMIT);
        var rows = out.putArray("resources");
        resources.stream().limit(LIST_LIMIT).forEach(rows::add);
        return out;
    }

    private ObjectNode readResource(ObjectNode args) throws IOException, InterruptedException {
        String kind = kind(args);
        String requested = UdtDraft.required(args, "path", 500);
        String type = UdtDraft.optional(args, "resourceType", 120);
        var matches = onEdt(() -> context.getProject().getResources().stream()
            .filter(r -> matches(r, kind) && path(r).equals(requested)
                && (type.isEmpty() || r.getResourceType().typeId().equals(type)))
            .limit(2).toList());
        if (matches.size() != 1) {
            throw new IOException(matches.isEmpty() ? "No " + kind + " resource found at " + requested + "."
                : "Specify the resource type because more than one resource matches " + requested + ".");
        }
        Resource resource = matches.get(0);
        var out = resourceSummary(resource, onEdt(() -> context.getProject().isChanged(resource.getResourcePath())));
        var files = out.putArray("files");
        int remaining = TEXT_LIMIT;
        for (String key : resource.getDataKeys()) {
            if (files.size() >= 12) {
                out.put("fileListTruncated", true);
                break;
            }
            ObjectNode file = files.addObject().put("name", key);
            var extension = key.toLowerCase(Locale.ROOT);
            if (remaining <= 0 || !(extension.endsWith(".py") || extension.endsWith(".json") || extension.endsWith(".txt"))) {
                continue;
            }
            var reader = resource.getDataReader(key);
            if (reader.isPresent()) {
                try (var input = reader.orElseThrow()) {
                    char[] data = new char[remaining + 1];
                    int length = 0;
                    while (length < data.length) {
                        int read = input.read(data, length, data.length - length);
                        if (read <= 0) {
                            break;
                        }
                        length += read;
                    }
                    int included = Math.min(length, remaining);
                    file.put("text", new String(data, 0, included)).put("truncated", length > remaining);
                    remaining -= included;
                }
            }
        }
        return out;
    }

    private static String kind(ObjectNode args) throws IOException {
        String kind = UdtDraft.required(args, "kind", 40);
        if (!Set.of("perspective", "scripts", "namedQueries").contains(kind)) {
            throw new IOException("Choose Perspective, scripts, or named queries.");
        }
        return kind;
    }

    private static boolean matches(Resource resource, String kind) {
        if (resource.isFolder()) {
            return false;
        }
        var type = resource.getResourceType();
        return switch (kind) {
            case "perspective" -> "com.inductiveautomation.perspective".equals(type.moduleId());
            case "scripts" -> "ignition".equals(type.moduleId()) && SCRIPT_TYPES.contains(type.typeId());
            case "namedQueries" -> "ignition".equals(type.moduleId()) && "named-query".equals(type.typeId());
            default -> false;
        };
    }

    private static String path(Resource resource) {
        String path = resource.getResourcePath().getPath().toString();
        return path.isEmpty() ? "_unary" : path;
    }

    private static ObjectNode resourceSummary(Resource resource, boolean locallyChanged) {
        return JSON.createObjectNode().put("path", path(resource)).put("type", resource.getResourceType().typeId())
            .put("definingProject", resource.getDefiningCollectionName()).put("changedInDesigner", locallyChanged);
    }

    private ObjectNode providers() {
        var out = JSON.createObjectNode().put("defaultTagProvider", defaultProvider());
        var rows = out.putArray("providers");
        context.getTagManager().getProviderProperties().stream().limit(LIST_LIMIT)
            .forEach(provider -> rows.add(provider.getName()));
        return out;
    }

    private TagPath tagPath(ObjectNode args, String key, boolean definition, boolean allowRoot) throws IOException {
        String provider = UdtDraft.optional(args, "provider", 128);
        if (provider.isEmpty()) {
            provider = java.util.Objects.toString(defaultProvider(), "");
        }
        UdtDraft.requireProvider(provider);
        String raw = allowRoot ? UdtDraft.optional(args, key, 500) : UdtDraft.required(args, key, 500);
        String path = definition ? UdtDraft.relativeFolder(raw) : raw;
        if (!path.isEmpty()) {
            for (String part : path.split("/", -1)) {
                UdtDraft.segment(part);
            }
        }
        if (definition) {
            path = "_types_" + (path.isEmpty() ? "" : "/" + path);
        }
        return TagPathParser.parse("[" + provider + "]" + path);
    }

    private ObjectNode browse(TagPath path, boolean includeDefinitions) throws IOException, InterruptedException {
        var results = await(context.getTagManager().browseAsync(path,
            new BrowseFilter().setMaxResults(LIST_LIMIT).setAvoidCache(true)), "Tag browse");
        if (!results.getResultQuality().isGood()) {
            throw new IOException("Designer could not browse " + path.toStringFull() + " (" + results.getResultQuality().getName() + ").");
        }
        var out = JSON.createObjectNode().put("path", path.toStringFull())
            .put("truncated", results.getContinuationPoint() != null || results.getTotalAvailableSize() > LIST_LIMIT);
        var rows = out.putArray("items");
        results.getResults().stream().filter(node -> includeDefinitions || !"_types_".equals(node.getName()))
            .limit(LIST_LIMIT).forEach(node -> {
            var item = rows.addObject().put("name", node.getName()).put("path", node.getFullPath().toStringFull())
                .put("type", node.getObjectType().name()).put("hasChildren", node.hasChildren());
            if (node.getDataType() != null) {
                item.put("dataType", node.getDataType().name());
            }
        });
        return out;
    }

    private ObjectNode readTag(TagPath path) throws IOException, InterruptedException {
        var values = await(context.getTagManager().readAsync(List.of(path)), "Tag read");
        if (values.isEmpty()) {
            throw new IOException("Designer returned no value for " + path.toStringFull() + ".");
        }
        var value = values.get(0);
        Object raw = value.getValue();
        var out = JSON.createObjectNode().put("path", path.toStringFull()).put("quality", value.getQuality().getName());
        if (value.getTimestamp() != null) {
            out.put("timestamp", value.getTimestamp().toInstant().toString());
        }
        if (raw == null || raw instanceof Number || raw instanceof Boolean) {
            out.set("value", JSON.valueToTree(raw));
        } else if (raw instanceof String text) {
            out.put("value", text.substring(0, Math.min(1000, text.length()))).put("truncated", text.length() > 1000);
        } else {
            out.put("valueType", raw.getClass().getSimpleName());
        }
        return out;
    }

    private ObjectNode readUdt(TagPath path) throws IOException, InterruptedException {
        var definition = lookup(path, false);
        if (definition == null || definition.getType() != TagObjectType.UdtType) {
            throw new IOException("No UDT definition found at " + path.toStringFull() + ".");
        }
        return browse(path, true).put("description", definition.get(WellKnownTagProps.Documentation));
    }

    @Override public void checkDestination(UdtDraft draft) throws IOException, InterruptedException {
        if (context.getTagManager().getProviderProperties().stream().noneMatch(p -> draft.provider().equals(p.getName()))) {
            throw new IOException("Designer cannot find tag provider " + draft.provider() + ".");
        }
        TagPath parent = TagPathParser.parse(draft.parentPath());
        if (draft.folder().isEmpty()) {
            browse(parent, true);
        } else {
            var folder = lookup(parent, false);
            if (folder == null || folder.getType() != TagObjectType.Folder) {
                throw new IOException("Choose an existing folder under UDT Definitions. Designer could not find " + draft.parentPath() + ".");
            }
        }
        if (lookup(TagPathParser.parse(draft.path()), false) != null) {
            throw new IOException("A definition already exists at " + draft.path() + ". Choose a different name.");
        }
    }

    @Override public ObjectNode createUdt(UdtDraft draft) throws IOException, InterruptedException {
        TagPath path = TagPathParser.parse(draft.path());
        TagConfiguration definition = BasicTagConfiguration.createNew(path);
        definition.setType(TagObjectType.UdtType);
        if (!draft.description().isEmpty()) {
            definition.set(WellKnownTagProps.Documentation, draft.description());
        }
        for (var member : draft.members()) {
            TagConfiguration tag = BasicTagConfiguration.createNew(path.getChildPath(member.name()));
            tag.setType(TagObjectType.AtomicTag);
            tag.set(WellKnownTagProps.DataType, DataType.valueOf(member.dataType()));
            tag.set(WellKnownTagProps.ValueSource, WellKnownTagProps.MEMORY_TAG_TYPE);
            if (!member.description().isEmpty()) {
                tag.set(WellKnownTagProps.Documentation, member.description());
            }
            definition.addChild(tag);
        }

        // Match the native Tag Editor's client call. The connection belongs to the signed-in Designer.
        var results = await(context.getTagManager().saveTagConfigsAsync(
            List.of(definition), CollisionPolicy.Abort, SecurityContext.emptyContext()), "UDT save");
        if (results.isEmpty() || results.stream().anyMatch(quality -> quality == null || !quality.isGood())) {
            throw new IOException("Ignition did not accept " + draft.path() + ". Check folder access and name availability.");
        }
        TagConfigurationModel stored = lookup(path, true);
        if (stored == null || stored.getType() != TagObjectType.UdtType
                || stored.getChildren().size() != draft.members().size()
                || draft.members().stream().anyMatch(member -> stored.getChildren().stream().noneMatch(tag ->
                    member.name().equals(tag.getName()) && tag.getType() == TagObjectType.AtomicTag
                        && DataType.valueOf(member.dataType()) == tag.get(WellKnownTagProps.DataType)
                        && WellKnownTagProps.MEMORY_TAG_TYPE.equals(tag.get(WellKnownTagProps.ValueSource))))) {
            throw new IOException("Ignition accepted the save but readback did not match. Check " + draft.path() + " before retrying.");
        }
        return draft.receipt();
    }

    @Override public boolean requestProjectUpdate() {
        if (context.getFrame() instanceof IgnitionDesigner designer && context.getProject() != null) {
            designer.updateProject();
            return true;
        }
        return false;
    }

    @Override public void showUdt(UdtDraft draft) throws IOException {
        TagPath path = TagPathParser.parse(draft.path());
        context.getTagBrowser().refresh(List.of(path.getParentPath()));
        context.getTagBrowser().selectNodeAtPath(path);
    }

    private TagConfigurationModel lookup(TagPath path, boolean children) throws IOException, InterruptedException {
        return await(context.getTagManager().getTagConfigsAsync(List.of(path), children, false), "Tag configuration read")
            .stream().filter(config -> config != null && config.getType() != TagObjectType.Unknown)
            .filter(config -> config.getPath() != null && path.toStringFull().equals(config.getPath().toStringFull()))
            .findFirst().orElse(null);
    }

    private static <T> T await(CompletableFuture<T> future, String action) throws IOException, InterruptedException {
        try {
            return future.get(SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IOException(action + " failed. Check the Designer connection and permissions.", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(action + " timed out. Refresh Designer and check the target before retrying.", e);
        }
    }

    private static <T> T onEdt(Callable<T> operation) throws IOException, InterruptedException {
        var future = new FutureTask<>(operation);
        SwingUtilities.invokeLater(future);
        try {
            return future.get(SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Designer could not inspect the current project.", e);
        }
    }
}
