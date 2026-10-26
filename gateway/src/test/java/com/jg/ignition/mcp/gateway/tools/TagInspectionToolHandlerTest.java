package com.jg.ignition.mcp.gateway.tools;

import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.config.BasicProperty;
import com.inductiveautomation.ignition.common.model.values.BasicQualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagInspectionToolHandlerTest extends InspectionTestSupport {
    final GatewayTagManager tags = mock(GatewayTagManager.class);
    final TagProvider provider = mock(TagProvider.class);

    @BeforeEach
    void setup() {
        when(gateway.getTagManager()).thenReturn(tags);
        when(tags.getTagProvider("default")).thenReturn(provider);
    }

    TagPath path(String path) { return TagPathParser.parseSafe("[default]" + path); }

    TagConfigurationModel model(String path, TagObjectType type) {
        var configuration = BasicTagConfiguration.createEdit(path(path));
        var model = mock(TagConfigurationModel.class);
        when(model.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
        when(model.getPath()).thenReturn(path(path));
        when(model.getType()).thenReturn(type);
        when(model.getTagProperties()).thenReturn(configuration.getTagProperties());
        when(model.getLocalConfiguration()).thenReturn(configuration);
        when(provider.getTagConfigsAsync(eq(List.of(path(path))), eq(false), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(List.of(model)));
        return model;
    }

    NodeDescription node(String path) {
        var node = mock(NodeDescription.class);
        when(node.getFullPath()).thenReturn(path(path));
        return node;
    }

    @Test
    void snapshotPreservesBadQualityAndTimestamps() {
        model("MCP/Speed", TagObjectType.AtomicTag);
        when(tags.readAsync(List.of(path("MCP/Speed")))).thenReturn(CompletableFuture.completedFuture(
            List.of(new BasicQualifiedValue(12.5, QualityCode.Bad_Stale, new Date(1700000000000L)))));
        var args = mapper.createObjectNode().put("path", "[default]MCP/Speed");
        var result = new TagInspectionToolHandler("ignition.tags.snapshot").execute(args, context());
        assertFalse(result.isError(), result.text());
        var row = result.structuredContent().path("tags").get(0);
        assertEquals(12.5, row.path("value").asDouble());
        assertFalse(row.path("good").asBoolean());
        assertEquals(1700000000000L, row.path("timestamp").asLong());
        assertTrue(result.structuredContent().path("complete").asBoolean());
    }

    @Test
    void deniedChildNeverGetsConfigurationOrValueRead() {
        model("MCP", TagObjectType.Folder);
        model("MCP/Allowed", TagObjectType.AtomicTag);
        when(config.allowedTagReadPatterns()).thenReturn(List.of("[default]MCP", "[default]MCP/Allowed"));
        var children = Results.of(List.of(node("MCP/Allowed"), node("MCP/Hidden")));
        when(tags.browseAsync(eq(path("MCP")), any(BrowseFilter.class))).thenReturn(CompletableFuture.completedFuture(children));
        clearInvocations(provider);
        var result = new TagInspectionToolHandler("ignition.tags.snapshot").execute(
            mapper.createObjectNode().put("path", "[default]MCP").put("includeValues", false), context());
        assertFalse(result.isError(), result.text());
        assertFalse(result.structuredContent().toString().contains("Hidden"));
        verify(provider, never()).getTagConfigsAsync(eq(List.of(path("MCP/Hidden"))), anyBoolean(), anyBoolean());
        verify(tags, never()).readAsync(anyList());
    }

    @Test
    void maximumTagsBoundsSdkBrowseAndPendingWork() {
        model("MCP", TagObjectType.Folder);
        model("MCP/One", TagObjectType.AtomicTag);
        model("MCP/Two", TagObjectType.AtomicTag);
        var browse = Results.of(List.of(node("MCP/One"), node("MCP/Two")));
        browse.setTotalAvailableResults(100);
        browse.setContinuationPoint("next-page");
        when(tags.browseAsync(eq(path("MCP")), any(BrowseFilter.class))).thenReturn(CompletableFuture.completedFuture(browse));
        var result = new TagInspectionToolHandler("ignition.tags.snapshot").execute(
            mapper.createObjectNode().put("path", "[default]MCP").put("maxTags", 2).put("includeValues", false), context());
        assertFalse(result.isError(), result.text());
        assertEquals(2, result.structuredContent().path("tagsExamined").asInt());
        assertTrue(result.structuredContent().path("truncated").asBoolean());
        assertTrue(result.structuredContent().path("truncationReasons").toString().contains("browseContinuation"));
        ArgumentCaptor<BrowseFilter> filter = ArgumentCaptor.forClass(BrowseFilter.class);
        verify(tags).browseAsync(eq(path("MCP")), filter.capture());
        assertEquals(1, filter.getValue().getMaxResults());
        verify(provider, never()).getTagConfigsAsync(eq(List.of(path("MCP/Two"))), anyBoolean(), anyBoolean());
    }

    @Test
    void timeBudgetCancelsPendingSdkReadAndDoesNotClaimComplete() {
        CompletableFuture<List<TagConfigurationModel>> pending = new CompletableFuture<>();
        when(provider.getTagConfigsAsync(anyList(), eq(false), eq(false))).thenReturn(pending);
        var result = new TagInspectionToolHandler("ignition.tags.snapshot").execute(
            mapper.createObjectNode().put("path", "[default]MCP").put("maxDurationMs", 20), context());
        assertFalse(result.isError(), result.text());
        assertTrue(pending.isCancelled());
        assertFalse(result.structuredContent().path("complete").asBoolean());
        assertEquals("TIMEOUT", result.structuredContent().at("/errors/0/code").asText());
    }

    @Test
    void udtDiscoveryTraversesFoldersWithoutReadingMembers() {
        model("_types_", TagObjectType.Folder);
        model("_types_/Motor", TagObjectType.UdtType);
        var children = Results.of(List.of(node("_types_/Motor")));
        when(tags.browseAsync(eq(path("_types_")), any(BrowseFilter.class))).thenReturn(
            CompletableFuture.completedFuture(children));
        var result = new TagInspectionToolHandler("ignition.udts.list").execute(mapper.createObjectNode(), context());
        assertFalse(result.isError(), result.text());
        assertEquals(1, result.structuredContent().path("count").asInt());
        assertEquals("UdtType", result.structuredContent().at("/definitions/0/type").asText());
        verify(tags, never()).browseAsync(eq(path("_types_/Motor")), any());
    }

    @Test
    void opcSearchReturnsMappingsAndFiltersWithoutClaimingPlcLogic() {
        var model = model("MCP/Speed", TagObjectType.AtomicTag);
        model.getTagProperties().set(new BasicProperty<>("opcItemPath", String.class), "ns=1;s=[PLC]Motor.Speed");
        model.getTagProperties().set(new BasicProperty<>("opcServer", String.class), "Ignition OPC UA Server");
        var result = new TagInspectionToolHandler("ignition.tags.opc.search").execute(
            mapper.createObjectNode().put("path", "[default]MCP/Speed").put("query", "motor.speed"), context());
        assertFalse(result.isError(), result.text());
        assertEquals(1, result.structuredContent().path("count").asInt());
        assertEquals("ns=1;s=[PLC]Motor.Speed", result.structuredContent().at("/tags/0/opcItemPath").asText());
        verify(tags, never()).readAsync(anyList());
    }

    @Test
    void blockedRootAndInvalidProviderDoNotTouchSdk() {
        when(config.allowedTagReadPatterns()).thenReturn(List.of());
        clearInvocations(gateway, tags, provider);
        var blocked = new TagInspectionToolHandler("ignition.tags.snapshot").execute(mapper.createObjectNode().put("path", "[default]Private"), context());
        assertEquals("ALLOWLIST_BLOCKED", blocked.structuredContent().path("code").asText());
        var invalid = new TagInspectionToolHandler("ignition.udts.list").execute(mapper.createObjectNode().put("provider", "default]x"), context());
        assertEquals("VALIDATION_ERROR", invalid.structuredContent().path("code").asText());
        verifyNoInteractions(gateway, tags, provider);
    }
}
