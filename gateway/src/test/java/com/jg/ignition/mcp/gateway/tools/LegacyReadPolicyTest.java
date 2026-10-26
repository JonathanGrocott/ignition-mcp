package com.jg.ignition.mcp.gateway.tools;

import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyReadPolicyTest extends InspectionTestSupport {
    @Test
    void scriptReadAndInventoryApplyTheSameResourcePolicyAsGenericReads() {
        var visible = resource("ignition", "script-python", "Public", "code.py", "answer=1");
        var hidden = resource("ignition", "script-python", "Private", "code.py", "private_secret=2");
        var manager = project(visible, hidden);
        when(config.allowedProjectResourceReadPatterns()).thenReturn(List.of("Plant/ignition/script-python/Public"));
        var listed = new ProjectScriptToolHandler("ignition.scripts.project.list").execute(
            mapper.createObjectNode().put("includeCode", true), context());
        assertFalse(listed.isError(), listed.text());
        assertEquals(1, listed.structuredContent().path("count").asInt());
        assertFalse(listed.structuredContent().toString().contains("Private"));
        verify(hidden, never()).getData(anyString());
        clearInvocations(manager);
        var denied = new ProjectScriptToolHandler("ignition.scripts.project.read").execute(
            mapper.createObjectNode().put("project", "Plant").put("scriptType", "python").put("path", "Private"), context());
        assertEquals("ALLOWLIST_BLOCKED", denied.structuredContent().path("code").asText());
        verifyNoInteractions(manager);
    }

    @Test
    void namedQuerySourceReadAndInventoryCannotBypassResourcePolicy() {
        var visible = resource("ignition", "named-query", "Public", "query.sql", "SELECT 1");
        var hidden = resource("ignition", "named-query", "Private", "query.sql", "SELECT private_column");
        project(visible, hidden);
        when(config.allowedProjectResourceReadPatterns()).thenReturn(List.of("Plant/ignition/named-query/Public"));
        var listed = new ProjectToolHandler("ignition.namedqueries.list").execute(mapper.createObjectNode(), context());
        assertFalse(listed.isError(), listed.text());
        assertEquals(1, listed.structuredContent().path("count").asInt());
        assertFalse(listed.structuredContent().toString().contains("Private"));
        var denied = new ProjectToolHandler("ignition.namedqueries.read").execute(
            mapper.createObjectNode().put("project", "Plant").put("path", "Private"), context());
        assertEquals("ALLOWLIST_BLOCKED", denied.structuredContent().path("code").asText());
        verify(gateway, never()).getNamedQueryManager();
    }

    @Test
    void tagBrowseFiltersDeniedChildrenAndProviderDiscovery() {
        var tags = mock(GatewayTagManager.class);
        var provider = mock(TagProvider.class);
        when(gateway.getTagManager()).thenReturn(tags);
        when(provider.getName()).thenReturn("default");
        when(tags.getTagProviders()).thenReturn(List.of(provider));
        when(config.allowedTagReadPatterns()).thenReturn(List.of("[default]Root", "[default]Root/Public"));
        var visible = mock(NodeDescription.class);
        var hidden = mock(NodeDescription.class);
        when(visible.getName()).thenReturn("Public"); when(visible.getFullPath()).thenReturn(TagPathParser.parseSafe("[default]Root/Public"));
        when(hidden.getName()).thenReturn("Private"); when(hidden.getFullPath()).thenReturn(TagPathParser.parseSafe("[default]Root/Private"));
        when(tags.browseAsync(any(), any(BrowseFilter.class))).thenReturn(CompletableFuture.completedFuture(Results.of(List.of(visible, hidden))));
        var result = new TagToolHandler("ignition.tags.browse").execute(mapper.createObjectNode().put("path", "[default]Root"), context());
        assertFalse(result.isError(), result.text());
        assertEquals(1, result.structuredContent().path("nodeCount").asInt());
        assertEquals(0, result.structuredContent().path("providers").size());
        assertFalse(result.structuredContent().toString().contains("Private"));
    }

    private TagConfigurationModel model(String path, TagObjectType type) {
        var config = BasicTagConfiguration.createEdit(TagPathParser.parseSafe(path));
        var model = mock(TagConfigurationModel.class);
        when(model.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
        when(model.getPath()).thenReturn(TagPathParser.parseSafe(path));
        when(model.getType()).thenReturn(type);
        when(model.getTagProperties()).thenReturn(config.getTagProperties());
        when(model.getLocalConfiguration()).thenReturn(config);
        when(model.getChildren()).thenReturn(List.of());
        return model;
    }

    @Test
    void recursiveTagDefinitionReadFiltersEachChild() {
        var tags = mock(GatewayTagManager.class); var provider = mock(TagProvider.class);
        when(gateway.getTagManager()).thenReturn(tags); when(tags.getTagProvider("default")).thenReturn(provider);
        var root = model("[default]Root", TagObjectType.Folder);
        var visible = model("[default]Root/Public", TagObjectType.AtomicTag);
        var hidden = model("[default]Root/Private", TagObjectType.AtomicTag);
        when(root.getChildren()).thenReturn(List.of(visible, hidden));
        when(config.allowedTagReadPatterns()).thenReturn(List.of("[default]Root", "[default]Root/Public"));
        when(provider.getTagConfigsAsync(anyList(), eq(true), eq(false))).thenReturn(CompletableFuture.completedFuture(List.of(root)));
        var args = mapper.createObjectNode().put("recursive", true); args.putArray("paths").add("[default]Root");
        var result = new TagDefinitionToolHandler("ignition.tags.definition.read").execute(args, context());
        assertFalse(result.isError(), result.text());
        assertEquals(1, result.structuredContent().at("/definitions/0/children").size());
        assertFalse(result.structuredContent().toString().contains("Private"));
    }

    @Test
    void nestedDefinitionWritesCannotEscapeParentOrChildPolicy() {
        var tags = mock(GatewayTagManager.class); var provider = mock(TagProvider.class);
        when(gateway.getTagManager()).thenReturn(tags); when(tags.getTagProvider("default")).thenReturn(provider);
        when(config.allowedTagWritePatterns()).thenReturn(List.of("[default]Root"));
        var args = mapper.createObjectNode().put("path", "[default]Root").put("tagObjectType", "Folder").put("commit", true);
        var child = args.putArray("children").addObject().put("name", "Private").put("tagObjectType", "AtomicTag");
        var handler = new TagDefinitionToolHandler("ignition.tags.definition.write");
        var result = handler.execute(args, context());
        assertEquals("ALLOWLIST_BLOCKED", result.structuredContent().path("code").asText());
        when(config.allowedTagWritePatterns()).thenReturn(List.of("*"));
        child.remove("name"); child.put("path", "[default]Other/Private");
        result = handler.execute(args, context());
        assertEquals("VALIDATION_ERROR", result.structuredContent().path("code").asText());
        verify(provider, never()).saveTagConfigsAsync(anyList(), any());
    }
}
