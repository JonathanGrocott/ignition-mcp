package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQuery;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** Staged typed resource changes. Apply never accepts a replacement bundle or target project. */
public final class ProjectChangeToolHandler implements ToolHandler {
    private final String name;
    private final ChangePlanStore plans;

    public static List<ToolHandler> tools() {
        ChangePlanStore store = new ChangePlanStore();
        return List.of(new ProjectChangeToolHandler("ignition.projects.bundle.inspect", store),
            new ProjectChangeToolHandler("ignition.projects.changes.preview", store),
            new ProjectChangeToolHandler("ignition.projects.changes.apply", store),
            new ProjectChangeToolHandler("ignition.projects.changes.status", store));
    }

    ProjectChangeToolHandler(String name, ChangePlanStore plans) { this.name = name; this.plans = plans; }

    @Override
    public ToolDefinition definition() {
        boolean mutating = name.endsWith(".apply");
        return new ToolDefinition(name, switch (name) {
            case "ignition.projects.bundle.inspect" -> "Inspect an uploaded JSON resource bundle without accessing Gateway projects or applying files; reports formats and hashes";
            case "ignition.projects.changes.preview" -> "Prepare a token-owned, expiring plan for explicit native Python-library/named-query resource changes; includes before/after hashes and bounded content previews";
            case "ignition.projects.changes.apply" -> "Apply exactly a stored plan with commit=true and its planHash, operator write gate and current policies; rechecks conflicts and verifies every resource by readback";
            default -> "Read the stored result of a token-owned plan; receipts are observations at application time, not a fresh health check";
        }, mutating ? PermissionRequirement.WRITE : PermissionRequirement.READ, schema(), mutating);
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        try {
            JsonNode args = arguments == null ? context.objectMapper().createObjectNode() : arguments;
            if (!args.isObject()) { throw new IllegalArgumentException("arguments must be an object"); }
            return switch (name) {
                case "ignition.projects.bundle.inspect" -> inspect(args, context);
                case "ignition.projects.changes.preview" -> preview(args, context);
                case "ignition.projects.changes.apply", "ignition.projects.changes.status" -> stored(args, context);
                default -> ToolExecutionResult.error("Unknown change workflow tool");
            };
        } catch (Exception e) { return InspectionSupport.failure(e); }
    }

    private ToolExecutionResult inspect(JsonNode args, ToolCallContext context) {
        var bundle = ProjectBundle.parse(args.path("bundle"), "uploaded", context.objectMapper());
        ObjectNode out = context.objectMapper().createObjectNode();
        ArrayNode rows = out.putArray("resources");
        for (var item : bundle.items()) {
            ObjectNode row = summary(context, item.resource());
            row.put("operation", item.operation()).put("decodedBytes", item.bytes());
            row.put("planTypeSupported", supported(item.resource().getResourcePath()) && !item.resource().isFolder() && !item.resource().isUnary());
            rows.add(row);
        }
        out.put("resourceCount", rows.size()).put("decodedBytes", bundle.bytes()).put("complete", bundle.complete());
        out.put("note", "Uploaded content only; no Gateway reads, writes, compilation, SQL execution or dependency resolution");
        return InspectionSupport.result("Portable bundle inspected", out);
    }

    private ToolExecutionResult preview(JsonNode args, ToolCallContext context) throws Exception {
        String project = ProjectBundle.path(InspectionSupport.required(args, "targetProject"), false);
        if (project.contains("/")) { throw new IllegalArgumentException("targetProject must be a single project name"); }
        var bundle = ProjectBundle.parse(args.path("bundle"), project, context.objectMapper());
        if (!bundle.complete()) { return ToolExecutionResult.error("INCOMPLETE_BUNDLE", "Cannot plan from a truncated or count-mismatched bundle"); }
        if (bundle.items().size() > Math.min(100, context.safetyPolicy().maxBatchWriteSize())) {
            throw new IllegalArgumentException("Plan exceeds configured maxBatchWriteSize");
        }
        var manager = InspectionSupport.manager(context);
        var mutable = ProjectResourceService.requireMutable(manager, project);
        if (mutable != null) { return mutable; }
        ObjectNode out = context.objectMapper().createObjectNode().put("targetProject", project);
        ArrayNode rows = out.putArray("changes");
        List<ChangePlanStore.Change> changes = new ArrayList<>();
        for (var item : bundle.items()) {
            Resource desired = item.resource();
            ResourcePath path = desired.getResourcePath();
            authorize(context, project, path, true);
            if (desired.isFolder() || desired.isUnary() || path.getPath().toString().isEmpty()) {
                throw new IllegalArgumentException("Plans cannot change folders, unary resources or project configuration");
            }
            Resource before = manager.getResource(project, path).orElse(null);
            rejectInherited(before, project);
            if (item.operation().equals("create") && before != null || item.operation().equals("edit") && before == null
                || item.operation().equals("delete") && before == null) {
                return ToolExecutionResult.error("CONFLICT", "Requested operation conflicts with current resource existence");
            }
            boolean delete = item.operation().equals("delete");
            if (!delete) { validateTyped(desired, context); }
            String beforeHash = ProjectBundle.fingerprint(before, context.objectMapper());
            String afterHash = delete ? "absent" : ProjectBundle.fingerprint(desired, context.objectMapper());
            String action = beforeHash.equals(afterHash) ? "unchanged" : delete ? "delete" : before == null ? "create" : "modify";
            changes.add(new ChangePlanStore.Change(path, delete ? null : desired, beforeHash, afterHash,
                before == null ? null : before.getResourceSignature(), action));
            ObjectNode row = rows.addObject().put("moduleId", path.getModuleId()).put("resourceType", path.getType())
                .put("path", path.getPath().toString()).put("action", action).put("beforeHash", beforeHash).put("afterHash", afterHash);
            if (before != null) { row.set("before", summary(context, before)); }
            if (!delete) { row.set("after", summary(context, desired)); }
            row.set("fileChanges", fileChanges(context, before, delete ? null : desired));
        }
        out.put("resourceCount", changes.size());
        out.put("changeCount", changes.stream().filter(change -> !change.action().equals("unchanged")).count());
        out.put("dryRun", true).put("writesEnabledAtPreview", context.safetyPolicy().writesEnabled());
        out.put("note", "Preview is not human approval. Apply requires explicit commit=true, the returned planHash, operator write enablement and current grants. Restart/expiry invalidates the plan.");
        ToolExecutionResult bounded = InspectionSupport.result("Project change preview prepared", out);
        if (bounded.isError()) { return bounded; }
        if (out.toString().getBytes(StandardCharsets.UTF_8).length > InspectionSupport.MAX_RESULT_BYTES - 512) {
            return ToolExecutionResult.error("RESULT_TOO_LARGE", "Preview leaves insufficient room for plan identity; narrow the bundle");
        }
        String hash = ProjectBundle.sha256(out.toString().getBytes(StandardCharsets.UTF_8));
        var plan = plans.create(context.authContext().tokenName(), project, hash, changes, out,
            bundle.bytes() + out.toString().getBytes(StandardCharsets.UTF_8).length);
        return InspectionSupport.result("Project change plan staged", plan.snapshot());
    }

    private ToolExecutionResult stored(JsonNode args, ToolCallContext context) {
        var plan = plans.owned(InspectionSupport.required(args, "planId"), context.authContext().tokenName());
        synchronized (plan) {
            for (var change : plan.changes) { authorize(context, plan.project, change.path(), name.endsWith(".apply")); }
            if (name.endsWith(".status")) { return InspectionSupport.result("Stored plan receipt retrieved", plan.snapshot()); }
            for (String forbidden : List.of("bundle", "resources", "targetProject", "project")) {
                if (args.has(forbidden)) { throw new IllegalArgumentException("Apply cannot replace stored content or target"); }
            }
            if (!args.path("commit").isBoolean() || !args.path("commit").booleanValue()) {
                return ToolExecutionResult.error("DRY_RUN_REQUIRED", "Applying a stored plan requires explicit commit=true");
            }
            if (!plan.hash.equals(InspectionSupport.required(args, "planHash"))) {
                return ToolExecutionResult.error("PLAN_HASH_MISMATCH", "planHash does not match the stored preview");
            }
            if (!context.safetyPolicy().writesEnabled()) {
                return ToolExecutionResult.error("WRITES_DISABLED", "Gateway operator has not enabled MCP writes");
            }
            if (!plan.state.equals("pending")) {
                ObjectNode receipt = plan.snapshot().put("alreadyAttempted", true);
                return plan.state.equals("verified") ? InspectionSupport.result("Plan was already verified; no writes repeated", receipt)
                    : ToolExecutionResult.error("Plan was already attempted; inspect its receipt and create a new preview", receipt);
            }
            if (plan.changes.size() > context.safetyPolicy().maxBatchWriteSize()) {
                throw new IllegalArgumentException("Plan exceeds current maxBatchWriteSize");
            }
            var manager = InspectionSupport.manager(context);
            var mutable = ProjectResourceService.requireMutable(manager, plan.project);
            if (mutable != null) { return mutable; }
            List<ChangeOperation> operations = new ArrayList<>();
            for (var change : plan.changes) {
                Resource current = manager.getResource(plan.project, change.path()).orElse(null);
                rejectInherited(current, plan.project);
                if (!ProjectBundle.fingerprint(current, context.objectMapper()).equals(change.beforeHash())) {
                    return terminal(plan, "conflict", "CONFLICT", "Target changed since preview; prepare a new plan", context, false);
                }
                switch (change.action()) {
                    case "create" -> operations.add(ChangeOperation.newCreateOp(change.desired()));
                    case "modify" -> operations.add(ChangeOperation.newModifyOp(change.desired(), change.signature()));
                    case "delete" -> operations.add(ChangeOperation.newDeleteOp(change.signature()));
                    default -> { /* Unchanged resources are still checked for conflicts and readback. */ }
                }
            }
            if (plans.expired(plan)) {
                return terminal(plan, "expired", "PLAN_EXPIRED", "Plan expired during preflight; prepare a new preview", context, false);
            }
            // Long previews/preflights must not turn an earlier policy snapshot into a commit permit.
            for (var change : plan.changes) { authorize(context, plan.project, change.path(), true); }
            if (!context.safetyPolicy().writesEnabled()) {
                return ToolExecutionResult.error("WRITES_DISABLED", "Gateway operator disabled MCP writes before submission");
            }
            if (plan.changes.size() > context.safetyPolicy().maxBatchWriteSize()) {
                throw new IllegalArgumentException("Plan exceeds current maxBatchWriteSize");
            }
            var stillMutable = ProjectResourceService.requireMutable(manager, plan.project);
            if (stillMutable != null) { return stillMutable; }
            plan.state = "applying";
            if (!operations.isEmpty()) {
                try {
                    manager.push(operations).get(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return terminal(plan, "outcome-unknown", "OUTCOME_UNKNOWN", "Apply was interrupted; changes may have landed. Do not blindly retry.", context, true);
                } catch (Exception e) {
                    // A failed/timed-out future does not establish that none of a batch was written.
                    return terminal(plan, "outcome-unknown", "OUTCOME_UNKNOWN", "Apply did not complete normally; inspect target state before preparing another plan", context, true);
                }
            }
            ObjectNode receipt = context.objectMapper().createObjectNode().put("targetProject", plan.project)
                .put("writeAttempted", !operations.isEmpty()).put("pushCompleted", true).put("changeCount", operations.size());
            ArrayNode verification = receipt.putArray("verification");
            boolean verified = true;
            for (var change : plan.changes) {
                ObjectNode row = verification.addObject().put("resourcePath", change.path().toString());
                try {
                    Resource actual = manager.getResource(plan.project, change.path()).orElse(null);
                    String actualHash = ProjectBundle.fingerprint(actual, context.objectMapper());
                    boolean matches = actualHash.equals(change.afterHash());
                    row.put("verified", matches).put("expectedHash", change.afterHash()).put("actualHash", actualHash);
                    verified &= matches;
                } catch (Exception e) { row.put("verified", false).put("code", "READBACK_FAILED"); verified = false; }
            }
            plan.state = verified ? "verified" : "verification-failed";
            receipt.put("verified", verified).put("observedAt", System.currentTimeMillis());
            if (verified) { receipt.put("committed", !operations.isEmpty()); }
            if (!verified) { receipt.put("code", "VERIFICATION_FAILED").put("message", "Writes may have landed; at least one resource did not verify"); }
            plan.receipt = receipt;
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), name, !operations.isEmpty(), verified, plan.project);
            return verified ? InspectionSupport.result("Plan applied and every target verified", plan.snapshot())
                : ToolExecutionResult.error("Post-apply verification failed", plan.snapshot());
        }
    }

    private ToolExecutionResult terminal(ChangePlanStore.Plan plan, String state, String code, String message,
                                         ToolCallContext context, boolean attempted) {
        plan.state = state;
        plan.receipt = plan.summary.deepCopy().put("targetProject", plan.project).put("dryRun", !attempted)
            .put("code", code).put("message", message).put("verified", false).put("writeAttempted", attempted)
            .put("observedAt", System.currentTimeMillis());
        context.auditLogger().logWriteAttempt(context.authContext().tokenName(), name, attempted, false, plan.project);
        return ToolExecutionResult.error(message, plan.snapshot());
    }

    private static void authorize(ToolCallContext context, String project, ResourcePath path, boolean write) {
        if (!supported(path)) { throw new InspectionSupport.InspectionException("UNSUPPORTED_TYPE", "Plans support native Python library and named-query resources only"); }
        String actor = context.authContext().tokenName();
        if (!context.safetyPolicy().isProjectResourceReadAllowed(actor, project, path.getModuleId(), path.getType(), path.getPath().toString())) {
            throw new InspectionSupport.InspectionException("ALLOWLIST_BLOCKED", "Plan target read blocked by allowlist");
        }
        boolean allowed = path.getType().equals("named-query")
            ? context.safetyPolicy().isNamedQueryWriteAllowed(actor, project, path.getPath().toString())
            : context.safetyPolicy().isProjectScriptWriteAllowed(actor, project, "library/" + path.getPath());
        if (write && !allowed) { throw new InspectionSupport.InspectionException("ALLOWLIST_BLOCKED", "Plan target write blocked by allowlist"); }
    }

    private static boolean supported(ResourcePath path) {
        return "ignition".equals(path.getModuleId()) && Set.of("script-python", "named-query").contains(path.getType());
    }

    private void validateTyped(Resource desired, ToolCallContext context) throws Exception {
        if (desired.getResourcePath().getType().equals("named-query")) {
            if (desired.getVersion() != NamedQuery.CURRENT_RESOURCE_VERSION) {
                throw new InspectionSupport.InspectionException("UNSUPPORTED_FORMAT", "Named query requires the SDK's current resource version");
            }
            for (String key : desired.getDataKeys()) {
                if ((!key.endsWith(".json") && !key.endsWith(".sql"))
                    || InspectionSupport.text(desired.getData(key).orElseThrow()).isEmpty()) {
                    throw new InspectionSupport.InspectionException("UNSUPPORTED_FORMAT", "Named query plans accept current text/JSON resources, not legacy binary data");
                }
            }
            // Current-version decoding must not fall back to an XML/object deserializer on client input.
            NamedQuery query = NamedQuery.fromResource(desired, null);
            if (query.getType() == null || query.getQuery() == null || query.getQuery().isBlank()) {
                throw new IllegalArgumentException("Named query resource has no type or SQL");
            }
        } else {
            if (desired.getVersion() != 1 || !desired.getDataKeys().equals(Set.of("code.py"))
                || InspectionSupport.text(desired.getData("code.py").orElseThrow()).isEmpty()) {
                throw new InspectionSupport.InspectionException("UNSUPPORTED_FORMAT", "Python library requires version 1 and one UTF-8 code.py file");
            }
        }
    }

    private void rejectInherited(Resource resource, String project) {
        if (resource != null && !project.equals(resource.getDefiningCollectionName())) {
            throw new InspectionSupport.InspectionException("INHERITED_RESOURCE", "Plan cannot override or delete an inherited resource; target its defining project explicitly");
        }
    }

    private ObjectNode summary(ToolCallContext context, Resource resource) {
        ObjectNode row = InspectionSupport.identity(context, resource.getCollectionName(), resource);
        row.put("version", resource.getVersion()).put("applicationScope", resource.getApplicationScope())
            .put("restricted", resource.isRestricted()).put("overridable", resource.isOverridable());
        ObjectNode attributes = row.putObject("attributes");
        for (var entry : resource.getAttributes().entrySet()) {
            if (!Set.of("lastModification", "lastModificationSignature").contains(entry.getKey())) {
                try { attributes.set(entry.getKey(), context.objectMapper().readTree(entry.getValue().toString())); }
                catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid resource metadata"); }
            }
        }
        row.put("fingerprint", ProjectBundle.fingerprint(resource, context.objectMapper()));
        row.set("files", context.objectMapper().valueToTree(resource.getDataKeys().stream().sorted().toList()));
        return row;
    }

    private ArrayNode fileChanges(ToolCallContext context, Resource before, Resource after) {
        TreeSet<String> keys = new TreeSet<>();
        if (before != null) { keys.addAll(before.getDataKeys()); }
        if (after != null) { keys.addAll(after.getDataKeys()); }
        ArrayNode rows = context.objectMapper().createArrayNode();
        for (String key : keys) {
            var oldBytes = before == null ? java.util.Optional.<com.inductiveautomation.ignition.common.ImmutableBytes>empty() : before.getData(key);
            var newBytes = after == null ? java.util.Optional.<com.inductiveautomation.ignition.common.ImmutableBytes>empty() : after.getData(key);
            if (oldBytes.equals(newBytes)) { continue; }
            ObjectNode row = rows.addObject().put("file", key);
            for (boolean old : List.of(true, false)) {
                var bytes = old ? oldBytes : newBytes;
                if (bytes.isEmpty()) { continue; }
                String prefix = old ? "before" : "after";
                row.put(prefix + "Hash", ProjectBundle.sha256(bytes.get().getBytes()));
                row.put(prefix + "Bytes", bytes.get().length());
                InspectionSupport.text(bytes.get()).ifPresent(text -> {
                    row.put(prefix + "Text", text.substring(0, Math.min(4000, text.length())));
                    row.put(prefix + "TextTruncated", text.length() > 4000);
                });
            }
        }
        return rows;
    }

    private ObjectNode schema() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        if (name.endsWith(".inspect") || name.endsWith(".preview")) {
            properties.putObject("bundle").put("type", "object"); required.add("bundle");
        }
        if (name.endsWith(".preview")) {
            properties.putObject("targetProject").put("type", "string"); required.add("targetProject");
        }
        if (name.endsWith(".apply") || name.endsWith(".status")) {
            properties.putObject("planId").put("type", "string"); required.add("planId");
        }
        if (name.endsWith(".apply")) {
            properties.putObject("planHash").put("type", "string"); required.add("planHash");
            properties.putObject("commit").put("type", "boolean"); required.add("commit");
        }
        schema.put("additionalProperties", false);
        return schema;
    }
}
