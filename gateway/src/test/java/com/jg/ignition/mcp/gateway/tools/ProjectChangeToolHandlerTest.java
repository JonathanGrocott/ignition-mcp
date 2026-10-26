package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQuery;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.McpAuthService;
import com.jg.ignition.mcp.gateway.ToolCallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectChangeToolHandlerTest extends InspectionTestSupport {
    final ChangePlanStore store = new ChangePlanStore();
    final ProjectManager manager = mock(ProjectManager.class);
    final Map<ResourcePath, Resource> state = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        when(config.maxBatchWriteSize()).thenReturn(50);
        when(config.writesEnabled()).thenReturn(true);
        when(config.allowedProjectScriptWritePatterns()).thenReturn(List.of("*"));
        when(config.allowedNamedQueryWritePatterns()).thenReturn(List.of("*"));
        when(gateway.getProjectManager()).thenReturn(manager);
        when(manager.getNames()).thenReturn(List.of("Plant"));
        when(manager.isMutable("Plant")).thenReturn(true);
        when(manager.getResource(eq("Plant"), any(ResourcePath.class))).thenAnswer(call -> Optional.ofNullable(state.get(call.getArgument(1))));
    }

    Resource script(String path, String code) {
        return Resource.newBuilder().setResourceCollectionName("Plant")
            .setResourcePath(ProjectResourceService.resourcePath("ignition", "script-python", path))
            .setVersion(1).setApplicationScope(7).setOverridable(true).putData("code.py", code).build();
    }

    ObjectNode serialized(Resource resource) {
        return ProjectResourceService.serializeResource(context(), resource, true);
    }

    ObjectNode bundle(Resource... resources) {
        ObjectNode result = mapper.createObjectNode().put("format", ProjectBundle.FORMAT);
        var rows = result.putArray("resources");
        for (var resource : resources) { rows.add(serialized(resource)); }
        return result;
    }

    ToolExecutionResult call(String suffix, ObjectNode args) {
        return new ProjectChangeToolHandler("ignition.projects." + suffix, store).execute(args, context());
    }

    JsonNode preview(Resource... resources) {
        var result = call("changes.preview", mapper.createObjectNode().put("targetProject", "Plant").set("bundle", bundle(resources)));
        assertFalse(result.isError(), result.text() + " " + result.structuredContent());
        return result.structuredContent();
    }

    ObjectNode applyArgs(JsonNode plan) {
        return mapper.createObjectNode().put("planId", plan.path("planId").asText()).put("planHash", plan.path("planHash").asText()).put("commit", true);
    }

    void successfulPush() throws Exception {
        when(manager.push(anyList())).thenAnswer(invocation -> {
            List<ChangeOperation> operations = invocation.getArgument(0);
            for (var operation : operations) {
                Resource resource = ChangeOperation.getResourceFromChange(operation);
                if (resource != null) { state.put(resource.getResourcePath(), resource); }
                else { state.clear(); } // Delete scenarios in this fixture target one explicit resource.
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    @Test
    void offlineInspectionPreservesUnknownAttributesAndNeverTouchesGateway() {
        Resource resource = script("MCP/Lib", "answer = 42").copy(builder -> builder.putAttribute("vendor", "keep"));
        clearInvocations(gateway);
        var result = call("bundle.inspect", mapper.createObjectNode().set("bundle", bundle(resource)));
        assertFalse(result.isError(), result.text());
        assertEquals("keep", result.structuredContent().at("/resources/0/attributes/vendor").asText());
        assertEquals(64, result.structuredContent().at("/resources/0/fingerprint").asText().length());
        verifyNoInteractions(gateway);
    }

    @Test
    void malformedPathsDuplicateIdentitiesEncodingAndIncompleteBundlesAreRejected() {
        for (String path : List.of("../Escape", "/Absolute", "A//B", "A\\B")) {
            var b = bundle(script("MCP/Lib", "answer=1"));
            ((ObjectNode)b.path("resources").get(0)).put("path", path);
            var result = call("bundle.inspect", mapper.createObjectNode().set("bundle", b));
            assertEquals("VALIDATION_ERROR", result.structuredContent().path("code").asText());
        }
        var duplicate = call("bundle.inspect", mapper.createObjectNode().set("bundle", bundle(script("Same", "1"), script("Same", "2"))));
        assertTrue(duplicate.isError());
        var b = bundle(script("MCP/Lib", "answer=1"));
        ((ObjectNode)b.at("/resources/0/data/code.py")).put("encoding", "gzip");
        assertTrue(call("bundle.inspect", mapper.createObjectNode().set("bundle", b)).isError());
        b = bundle(script("MCP/Lib", "answer=1")).put("truncated", true);
        var incomplete = call("changes.preview", mapper.createObjectNode().put("targetProject", "Plant").set("bundle", b));
        assertEquals("INCOMPLETE_BUNDLE", incomplete.structuredContent().path("code").asText());
    }

    @Test
    void previewDoesNotWriteAndHashesExactScriptBytesAndMetadata() throws Exception {
        var plan = preview(script("MCP/Lib", "answer = 42\n").copy(builder -> builder.putAttribute("custom", true)));
        assertEquals("create", plan.at("/changes/0/action").asText());
        assertEquals("answer = 42\n", plan.at("/changes/0/fileChanges/0/afterText").asText());
        assertTrue(plan.at("/changes/0/after/attributes/custom").asBoolean());
        assertTrue(plan.path("dryRun").asBoolean());
        verify(manager, never()).push(anyList());
    }

    @Test
    void applyVerifiesAndReplayDoesNotWriteAgain() throws Exception {
        successfulPush();
        Resource desired = script("MCP/Lib", "answer = 42");
        var plan = preview(desired);
        var result = call("changes.apply", applyArgs(plan));
        assertFalse(result.isError(), result.text());
        assertEquals("verified", result.structuredContent().path("state").asText());
        assertTrue(result.structuredContent().path("committed").asBoolean());
        assertEquals(ProjectBundle.fingerprint(desired, mapper), ProjectBundle.fingerprint(state.get(desired.getResourcePath()), mapper));
        var replay = call("changes.apply", applyArgs(plan));
        assertFalse(replay.isError());
        assertTrue(replay.structuredContent().path("alreadyAttempted").asBoolean());
        verify(manager, times(1)).push(anyList());
        assertEquals("verified", call("changes.status", mapper.createObjectNode().put("planId", plan.path("planId").asText()))
            .structuredContent().path("state").asText());
    }

    @Test
    void wrongHashReplacementPayloadMissingCommitAndDisabledGateCannotApply() throws Exception {
        var plan = preview(script("MCP/Lib", "answer=1"));
        assertEquals("PLAN_HASH_MISMATCH", call("changes.apply", applyArgs(plan).put("planHash", "wrong")).structuredContent().path("code").asText());
        var missingCommit = applyArgs(plan); missingCommit.remove("commit");
        assertEquals("DRY_RUN_REQUIRED", call("changes.apply", missingCommit).structuredContent().path("code").asText());
        assertEquals("VALIDATION_ERROR", call("changes.apply", applyArgs(plan).put("targetProject", "Other")).structuredContent().path("code").asText());
        when(config.writesEnabled()).thenReturn(false);
        assertEquals("WRITES_DISABLED", call("changes.apply", applyArgs(plan)).structuredContent().path("code").asText());
        verify(manager, never()).push(anyList());
    }

    @Test
    void changedTargetPreventsAnyPushAndCannotBeBlindlyRetried() throws Exception {
        Resource before = script("MCP/Lib", "answer=1");
        state.put(before.getResourcePath(), before);
        var plan = preview(script("MCP/Lib", "answer=2"));
        state.put(before.getResourcePath(), script("MCP/Lib", "answer=3"));
        var result = call("changes.apply", applyArgs(plan));
        assertEquals("CONFLICT", result.structuredContent().path("code").asText());
        state.put(before.getResourcePath(), before);
        assertTrue(call("changes.apply", applyArgs(plan)).isError());
        verify(manager, never()).push(anyList());
    }

    @Test
    void verificationFailureIsNotReportedAsCommittedAndPreservesPerResourceResults() throws Exception {
        Resource first = script("MCP/First", "answer=1");
        Resource second = script("MCP/Second", "answer=2");
        when(manager.push(anyList())).thenAnswer(invocation -> {
            state.put(first.getResourcePath(), first);
            return CompletableFuture.completedFuture(null);
        });
        var plan = preview(first, second);
        var result = call("changes.apply", applyArgs(plan));
        assertTrue(result.isError());
        assertEquals("VERIFICATION_FAILED", result.structuredContent().path("code").asText());
        assertFalse(result.structuredContent().has("committed"));
        assertTrue(result.structuredContent().at("/verification/0/verified").asBoolean());
        assertFalse(result.structuredContent().at("/verification/1/verified").asBoolean());
        assertTrue(call("changes.apply", applyArgs(plan)).isError());
        verify(manager, times(1)).push(anyList());
    }

    @Test
    void failedFutureAfterAWriteRemainsUnknownAndNeverAutomaticallyRetries() throws Exception {
        Resource desired = script("MCP/Lib", "answer=1");
        when(manager.push(anyList())).thenAnswer(invocation -> {
            state.put(desired.getResourcePath(), desired);
            return CompletableFuture.failedFuture(new IllegalStateException("Acknowledgement lost"));
        });
        var plan = preview(desired);
        var result = call("changes.apply", applyArgs(plan));
        assertEquals("OUTCOME_UNKNOWN", result.structuredContent().path("code").asText());
        assertTrue(state.containsKey(desired.getResourcePath()));
        assertTrue(call("changes.apply", applyArgs(plan)).isError());
        verify(manager, times(1)).push(anyList());
    }

    @Test
    void deleteIsExplicitAndReadbackChecksAbsence() throws Exception {
        successfulPush();
        Resource before = script("MCP/Lib", "answer=1");
        state.put(before.getResourcePath(), before);
        var b = bundle(before);
        var row = (ObjectNode)b.path("resources").get(0);
        row.put("operation", "delete"); row.remove("data");
        var preview = call("changes.preview", mapper.createObjectNode().put("targetProject", "Plant").set("bundle", b));
        assertFalse(preview.isError(), preview.text());
        var result = call("changes.apply", applyArgs(preview.structuredContent()));
        assertFalse(result.isError(), result.text());
        assertFalse(state.containsKey(before.getResourcePath()));
        assertEquals("absent", result.structuredContent().at("/verification/0/actualHash").asText());
    }

    @Test
    void unchangedPlansDoNotPush() throws Exception {
        Resource before = script("MCP/Lib", "answer=1");
        state.put(before.getResourcePath(), before);
        var plan = preview(before);
        assertEquals(0, plan.path("changeCount").asInt());
        var result = call("changes.apply", applyArgs(plan));
        assertFalse(result.isError());
        assertFalse(result.structuredContent().path("committed").asBoolean());
        verify(manager, never()).push(anyList());
    }

    @Test
    void revokedGrantsInheritedResourcesAndAnotherTokenCannotReuseAPlan() throws Exception {
        Resource desired = script("MCP/Lib", "answer=1");
        var plan = preview(desired);
        ToolCallContext original = context();
        var other = new ToolCallContext(gateway, original.requestContext(), new McpAuthService.AuthContext("other", "other", null),
            original.safetyPolicy(), original.auditLogger(), config, mapper);
        var status = new ProjectChangeToolHandler("ignition.projects.changes.status", store).execute(
            mapper.createObjectNode().put("planId", plan.path("planId").asText()), other);
        assertEquals("NOT_FOUND", status.structuredContent().path("code").asText());
        when(config.allowedProjectScriptWritePatterns()).thenReturn(List.of());
        assertEquals("ALLOWLIST_BLOCKED", call("changes.apply", applyArgs(plan)).structuredContent().path("code").asText());
        when(config.allowedProjectResourceReadPatterns()).thenReturn(List.of());
        assertEquals("ALLOWLIST_BLOCKED", call("changes.status", mapper.createObjectNode().put("planId", plan.path("planId").asText()))
            .structuredContent().path("code").asText());
        when(config.allowedProjectResourceReadPatterns()).thenReturn(List.of("*"));
        when(config.allowedProjectScriptWritePatterns()).thenReturn(List.of("*"));
        state.put(desired.getResourcePath(), desired.copy(builder -> builder.setResourceCollectionName("Parent")));
        var inherited = call("changes.preview", mapper.createObjectNode().put("targetProject", "Plant").set("bundle", bundle(desired)));
        assertEquals("INHERITED_RESOURCE", inherited.structuredContent().path("code").asText());
        verify(manager, never()).push(anyList());
    }

    @Test
    void currentNamedQueryAttributesSurviveExportPreviewApplyAndReadback() throws Exception {
        successfulPush();
        NamedQuery query = new NamedQuery();
        query.setType(NamedQuery.Type.Query); query.setQuery("SELECT :value AS result"); query.setDatabase("SyntheticDB");
        var builder = Resource.newBuilder().setResourceCollectionName("Plant")
            .setResourcePath(NamedQuery.RESOURCE_TYPE.subPath("MCP/Read"))
            .setVersion(NamedQuery.CURRENT_RESOURCE_VERSION).setApplicationScope(1).setOverridable(true);
        NamedQuery.toResource(query).accept(builder);
        Resource desired = builder.build();
        Resource reconstructed = ProjectResourceService.resourceFromSerialized(context(), "Plant", serialized(desired));
        NamedQuery legacyRoundtrip = NamedQuery.fromResource(reconstructed, null);
        assertEquals(query.getDatabase(), legacyRoundtrip.getDatabase());
        assertEquals(query.getQuery(), legacyRoundtrip.getQuery());
        assertEquals(1, reconstructed.getApplicationScope());
        var plan = preview(desired);
        var result = call("changes.apply", applyArgs(plan));
        assertFalse(result.isError(), result.text());
        NamedQuery readback = NamedQuery.fromResource(state.get(desired.getResourcePath()), null);
        assertEquals(query.getQuery(), readback.getQuery());
        assertEquals(query.getDatabase(), readback.getDatabase());
        assertEquals(1, state.get(desired.getResourcePath()).getApplicationScope());
        verify(gateway, never()).createDeserializer();
    }

    @Test
    void unsupportedTypesAndEventScriptsCannotBecomeGenericProjectWrites() {
        Resource view = Resource.newBuilder().setResourceCollectionName("Plant")
            .setResourcePath(ProjectResourceService.resourcePath("com.inductiveautomation.perspective", "views", "Main"))
            .putData("view.json", "{\"root\":{}}").setVersion(1).setApplicationScope(7).build();
        var result = call("changes.preview", mapper.createObjectNode().put("targetProject", "Plant").set("bundle", bundle(view)));
        assertEquals("UNSUPPORTED_TYPE", result.structuredContent().path("code").asText());
    }

    @Test
    void concurrentApplyOnlySubmitsOneWrite() throws Exception {
        Resource desired = script("MCP/Lib", "answer=1");
        var plan = preview(desired);
        CountDownLatch submitted = new CountDownLatch(1);
        CompletableFuture<Void> pending = new CompletableFuture<>();
        when(manager.push(anyList())).thenAnswer(invocation -> { submitted.countDown(); return pending; });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> call("changes.apply", applyArgs(plan)));
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> call("changes.apply", applyArgs(plan)));
            state.put(desired.getResourcePath(), desired); pending.complete(null);
            assertFalse(first.get(2, TimeUnit.SECONDS).isError());
            assertTrue(second.get(2, TimeUnit.SECONDS).structuredContent().path("alreadyAttempted").asBoolean());
            verify(manager, times(1)).push(anyList());
        } finally { pending.complete(null); executor.shutdownNow(); }
    }

    @Test
    void planCapacityAndExpirationAreEnforced() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        ChangePlanStore bounded = new ChangePlanStore(clock, 1, 1_000_000);
        var tool = new ProjectChangeToolHandler("ignition.projects.changes.preview", bounded);
        ObjectNode args = mapper.createObjectNode().put("targetProject", "Plant").set("bundle", bundle(script("MCP/Lib", "answer=1")));
        var first = tool.execute(args, context()); assertFalse(first.isError(), first.text());
        assertEquals("PLAN_CAPACITY", tool.execute(args, context()).structuredContent().path("code").asText());
        when(clock.millis()).thenReturn(ChangePlanStore.TTL_MS + 1);
        var expired = new ProjectChangeToolHandler("ignition.projects.changes.status", bounded).execute(
            mapper.createObjectNode().put("planId", first.structuredContent().path("planId").asText()), context());
        assertEquals("NOT_FOUND", expired.structuredContent().path("code").asText());
        assertFalse(tool.execute(args, context()).isError());
    }

    @Test
    void gateRevokedDuringPreflightCannotReachPush() throws Exception {
        var plan = preview(script("MCP/Lib", "answer=1"));
        when(manager.getResource(eq("Plant"), any(ResourcePath.class))).thenAnswer(invocation -> {
            when(config.writesEnabled()).thenReturn(false);
            return Optional.empty();
        });
        var result = call("changes.apply", applyArgs(plan));
        assertEquals("WRITES_DISABLED", result.structuredContent().path("code").asText());
        verify(manager, never()).push(anyList());
    }

    @Test
    void expiryDuringPreflightCannotReachPush() throws Exception {
        Clock clock = mock(Clock.class);
        var now = new java.util.concurrent.atomic.AtomicLong(0);
        when(clock.millis()).thenAnswer(invocation -> now.get());
        ChangePlanStore bounded = new ChangePlanStore(clock, 32, 1_000_000);
        var previewTool = new ProjectChangeToolHandler("ignition.projects.changes.preview", bounded);
        var preview = previewTool.execute(mapper.createObjectNode().put("targetProject", "Plant")
            .set("bundle", bundle(script("MCP/Lib", "answer=1"))), context());
        assertFalse(preview.isError());
        when(manager.getResource(eq("Plant"), any(ResourcePath.class))).thenAnswer(invocation -> {
            now.set(ChangePlanStore.TTL_MS + 1);
            return Optional.empty();
        });
        var result = new ProjectChangeToolHandler("ignition.projects.changes.apply", bounded).execute(applyArgs(preview.structuredContent()), context());
        assertEquals("PLAN_EXPIRED", result.structuredContent().path("code").asText());
        verify(manager, never()).push(anyList());
    }
}
