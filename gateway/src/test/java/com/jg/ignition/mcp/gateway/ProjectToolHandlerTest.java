package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQuery;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQueryManager;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionManifest;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceId;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.resourcecollection.RuntimeResourceCollection;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.tools.ProjectToolHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectToolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void projectsListIncludesNamedQueryCounts() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        when(gatewayContext.getProjectManager()).thenReturn(projectManager);

        when(projectManager.getNames()).thenReturn(List.of("samplequickstart", "emptyproj"));
        when(projectManager.getManifests()).thenReturn(
            Map.of(
                "samplequickstart",
                new ResourceCollectionManifest("Sample", "", true, true, ""),
                "emptyproj",
                new ResourceCollectionManifest("Empty", "", false, false, "")
            )
        );
        when(projectManager.isMutable("samplequickstart")).thenReturn(true);
        when(projectManager.isMutable("emptyproj")).thenReturn(false);

        RuntimeResourceCollection sampleCollection = mock(RuntimeResourceCollection.class);
        RuntimeResourceCollection emptyCollection = mock(RuntimeResourceCollection.class);
        when(projectManager.find("samplequickstart")).thenReturn(Optional.of(sampleCollection));
        when(projectManager.find("emptyproj")).thenReturn(Optional.of(emptyCollection));

        Resource sampleNamedQuery1 = resource("ignition", "named-query", "Reports/Audit Log");
        Resource sampleNamedQuery2 = resource("ignition", "named-query", "Ignition 101/Data Entry/Tank List");
        Resource sampleOtherResource = resource("perspective", "view", "Main/View");
        when(sampleCollection.getAllResources()).thenReturn(
            Map.of(
                mock(ResourceId.class), sampleNamedQuery1,
                mock(ResourceId.class), sampleNamedQuery2,
                mock(ResourceId.class), sampleOtherResource
            )
        );
        when(emptyCollection.getAllResources()).thenReturn(Map.of());

        ToolExecutionResult result = new ProjectToolHandler("ignition.projects.list")
            .execute(mapper.createObjectNode(), toolContext(gatewayContext));

        assertFalse(result.isError(), result.text());
        JsonNode body = result.structuredContent();
        assertEquals(2, body.path("count").asInt());
        assertEquals(1, body.path("enabledCount").asInt());
        assertEquals(2, body.path("namedQueryCount").asInt());

        ArrayNode projects = (ArrayNode) body.path("projects");
        JsonNode sampleRow = findByName(projects, "samplequickstart");
        assertEquals(2, sampleRow.path("namedQueryCount").asInt());
        assertTrue(sampleRow.path("mutable").asBoolean());
    }

    @Test
    void namedQueriesListSupportsProjectAndPrefixFilters() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        when(gatewayContext.getProjectManager()).thenReturn(projectManager);

        when(projectManager.getNames()).thenReturn(List.of("samplequickstart"));
        when(projectManager.getManifests()).thenReturn(
            Map.of("samplequickstart", new ResourceCollectionManifest("Sample", "", true, true, ""))
        );
        when(projectManager.isMutable("samplequickstart")).thenReturn(true);

        RuntimeResourceCollection collection = mock(RuntimeResourceCollection.class);
        when(projectManager.find("samplequickstart")).thenReturn(Optional.of(collection));
        Resource reportQuery = resource("ignition", "named-query", "Reports/Audit Log");
        Resource tankListQuery = resource("ignition", "named-query", "Ignition 101/Data Entry/Tank List");
        when(collection.getAllResources()).thenReturn(
            Map.of(
                mock(ResourceId.class), reportQuery,
                mock(ResourceId.class), tankListQuery
            )
        );

        ObjectNode args = mapper.createObjectNode();
        args.put("project", "samplequickstart");
        args.put("pathPrefix", "Ignition 101/Data Entry/");

        ToolExecutionResult result = new ProjectToolHandler("ignition.namedqueries.list")
            .execute(args, toolContext(gatewayContext));

        assertFalse(result.isError(), result.text());
        JsonNode body = result.structuredContent();
        assertEquals(1, body.path("count").asInt());
        assertEquals("samplequickstart", body.path("project").asText());
        assertEquals("Ignition 101/Data Entry/", body.path("pathPrefix").asText());
        assertEquals(
            "Ignition 101/Data Entry/Tank List",
            body.path("queries").path(0).path("path").asText()
        );
    }

    @Test
    void namedQueriesListRejectsUnknownProject() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        when(gatewayContext.getProjectManager()).thenReturn(projectManager);
        when(projectManager.getNames()).thenReturn(List.of("samplequickstart"));

        ObjectNode args = mapper.createObjectNode();
        args.put("project", "does-not-exist");

        ToolExecutionResult result = new ProjectToolHandler("ignition.namedqueries.list")
            .execute(args, toolContext(gatewayContext));

        assertTrue(result.isError());
        assertTrue(result.text().contains("Project not found"));
    }

    @Test
    void namedQueriesReadReturnsSqlAndParameters() throws Exception {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        NamedQueryManager namedQueryManager = mock(NamedQueryManager.class);
        when(gatewayContext.getProjectManager()).thenReturn(projectManager);
        when(gatewayContext.getNamedQueryManager()).thenReturn(namedQueryManager);
        when(projectManager.getNames()).thenReturn(List.of("samplequickstart"));

        NamedQuery query = new NamedQuery();
        query.setType(NamedQuery.Type.Query);
        query.setDatabase("Sample_SQLite_Database");
        query.setDescription("Audit report query");
        query.setEnabled(true);
        query.setReadOnly(true);
        query.setCachingEnabled(true);
        query.setCacheAmount(30);
        query.setUseMaxReturnSize(true);
        query.setMaxReturnSize(5000);
        query.setQuery("SELECT * FROM audit_events WHERE eventtime >= :startDate");
        query.setParameters(
            List.of(
                new NamedQuery.Parameter(NamedQuery.ParameterType.Parameter, "startDate", DataType.DateTime)
            )
        );

        when(namedQueryManager.getQueryFromPath("samplequickstart", "Reports/Audit Log")).thenReturn(query);

        ObjectNode args = mapper.createObjectNode();
        args.put("project", "samplequickstart");
        args.put("path", "Reports/Audit Log");

        ToolExecutionResult result = new ProjectToolHandler("ignition.namedqueries.read")
            .execute(args, toolContext(gatewayContext));

        assertFalse(result.isError(), result.text());
        JsonNode body = result.structuredContent();
        assertEquals("samplequickstart", body.path("project").asText());
        assertEquals("Reports/Audit Log", body.path("path").asText());
        assertEquals("Query", body.path("type").asText());
        assertEquals("Sample_SQLite_Database", body.path("database").asText());
        assertTrue(body.path("query").asText().startsWith("SELECT"));
        assertEquals(1, body.path("parameterCount").asInt());
        assertEquals("startDate", body.path("parameters").path(0).path("name").asText());
        assertEquals("Parameter", body.path("parameters").path(0).path("type").asText());
        assertEquals("DateTime", body.path("parameters").path(0).path("sqlType").asText());
    }

    @Test
    void namedQueriesReadRequiresProjectAndPath() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        when(gatewayContext.getProjectManager()).thenReturn(mock(ProjectManager.class));
        when(gatewayContext.getNamedQueryManager()).thenReturn(mock(NamedQueryManager.class));

        ToolExecutionResult missingProject = new ProjectToolHandler("ignition.namedqueries.read")
            .execute(mapper.createObjectNode().put("path", "Reports/Audit Log"), toolContext(gatewayContext));
        assertTrue(missingProject.isError());
        assertTrue(missingProject.text().contains("requires project"));

        ToolExecutionResult missingPath = new ProjectToolHandler("ignition.namedqueries.read")
            .execute(mapper.createObjectNode().put("project", "samplequickstart"), toolContext(gatewayContext));
        assertTrue(missingPath.isError());
        assertTrue(missingPath.text().contains("requires path"));
    }

    @Test
    void namedQueriesExecuteUsesDryRunByDefault() throws Exception {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        NamedQueryManager namedQueryManager = mock(NamedQueryManager.class);
        when(gatewayContext.getProjectManager()).thenReturn(projectManager);
        when(gatewayContext.getNamedQueryManager()).thenReturn(namedQueryManager);
        when(projectManager.getNames()).thenReturn(List.of("samplequickstart"));

        NamedQuery query = new NamedQuery();
        query.setType(NamedQuery.Type.UpdateQuery);
        query.setReadOnly(false);
        when(namedQueryManager.getQueryFromPath("samplequickstart", "Ignition 101/Data Entry/Add Tank")).thenReturn(query);

        ObjectNode args = mapper.createObjectNode();
        args.put("project", "samplequickstart");
        args.put("path", "Ignition 101/Data Entry/Add Tank");
        args.set("parameters", mapper.createObjectNode().put("tankNo", 42));

        ToolExecutionResult result = new ProjectToolHandler("ignition.namedqueries.execute")
            .execute(args, toolContext(gatewayContext));

        assertFalse(result.isError(), result.text());
        assertTrue(result.structuredContent().path("dryRun").asBoolean());
        verify(namedQueryManager, never()).execute(eq("samplequickstart"), eq("Ignition 101/Data Entry/Add Tank"), anyMap(), eq(false), eq(false), eq(""), eq(false));
    }

    @Test
    void namedQueriesExecuteCommitsWhenRequested() throws Exception {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        NamedQueryManager namedQueryManager = mock(NamedQueryManager.class);
        when(gatewayContext.getProjectManager()).thenReturn(projectManager);
        when(gatewayContext.getNamedQueryManager()).thenReturn(namedQueryManager);
        when(projectManager.getNames()).thenReturn(List.of("samplequickstart"));

        NamedQuery query = new NamedQuery();
        query.setType(NamedQuery.Type.UpdateQuery);
        query.setReadOnly(false);
        when(namedQueryManager.getQueryFromPath("samplequickstart", "Ignition 101/Data Entry/Add Tank")).thenReturn(query);
        when(namedQueryManager.execute(
            eq("samplequickstart"),
            eq("Ignition 101/Data Entry/Add Tank"),
            anyMap(),
            eq(false),
            eq(false),
            eq(""),
            eq(false)
        )).thenReturn(1);

        ObjectNode args = mapper.createObjectNode();
        args.put("project", "samplequickstart");
        args.put("path", "Ignition 101/Data Entry/Add Tank");
        args.put("commit", true);
        args.set("parameters", mapper.createObjectNode().put("tankNo", 42));

        ToolExecutionResult result = new ProjectToolHandler("ignition.namedqueries.execute")
            .execute(args, toolContext(gatewayContext));

        assertFalse(result.isError(), result.text());
        assertTrue(result.structuredContent().path("executed").asBoolean());
        assertEquals(1, result.structuredContent().path("result").asInt());
    }

    @Test
    void namedQueriesExecuteAllowsOmittedParameters() throws Exception {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        NamedQueryManager namedQueryManager = mock(NamedQueryManager.class);
        when(gatewayContext.getProjectManager()).thenReturn(projectManager);
        when(gatewayContext.getNamedQueryManager()).thenReturn(namedQueryManager);
        when(projectManager.getNames()).thenReturn(List.of("samplequickstart"));

        NamedQuery query = new NamedQuery();
        query.setType(NamedQuery.Type.Query);
        query.setReadOnly(true);
        when(namedQueryManager.getQueryFromPath("samplequickstart", "Ignition 101/Data Entry/Tank List")).thenReturn(query);
        when(namedQueryManager.execute(
            eq("samplequickstart"),
            eq("Ignition 101/Data Entry/Tank List"),
            anyMap(),
            eq(false),
            eq(false),
            eq(""),
            eq(false)
        )).thenReturn(3);

        ObjectNode args = mapper.createObjectNode();
        args.put("project", "samplequickstart");
        args.put("path", "Ignition 101/Data Entry/Tank List");
        args.put("commit", true);

        ToolExecutionResult result = new ProjectToolHandler("ignition.namedqueries.execute")
            .execute(args, toolContext(gatewayContext));

        assertFalse(result.isError(), result.text());
        assertTrue(result.structuredContent().path("executed").asBoolean());
        assertEquals(3, result.structuredContent().path("result").asInt());
    }

    @Test
    void namedQueriesExecuteBlockedByAllowlist() throws Exception {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        NamedQueryManager namedQueryManager = mock(NamedQueryManager.class);
        when(gatewayContext.getProjectManager()).thenReturn(projectManager);
        when(gatewayContext.getNamedQueryManager()).thenReturn(namedQueryManager);
        when(projectManager.getNames()).thenReturn(List.of("samplequickstart"));

        NamedQuery query = new NamedQuery();
        query.setType(NamedQuery.Type.Query);
        when(namedQueryManager.getQueryFromPath("samplequickstart", "Reports/Audit Log")).thenReturn(query);

        McpServerConfigResource restrictedConfig = new McpServerConfigResource(
            true,
            "ignition-mcp",
            List.of(),
            List.of(),
            true,
            true,
            100,
            300,
            60,
            true,
            50,
            List.of("*"),
            List.of("[default]MCP/*"),
            List.of("*"),
            List.of("samplequickstart/Ignition 101/Data Entry/*"),
            "",
            5000,
            1000
        );

        ObjectNode args = mapper.createObjectNode();
        args.put("project", "samplequickstart");
        args.put("path", "Reports/Audit Log");
        args.put("commit", true);

        ToolExecutionResult result = new ProjectToolHandler("ignition.namedqueries.execute")
            .execute(args, toolContext(gatewayContext, restrictedConfig));

        assertTrue(result.isError());
        assertTrue(result.text().contains("allowlist"));
    }

    private ToolCallContext toolContext(GatewayContext gatewayContext) {
        return toolContext(gatewayContext, McpServerConfigResource.DEFAULT);
    }

    private ToolCallContext toolContext(GatewayContext gatewayContext, McpServerConfigResource config) {
        return new ToolCallContext(
            gatewayContext,
            mock(RequestContext.class),
            new McpAuthService.AuthContext("tok-project", "fp-project", null),
            new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(),
            config,
            mapper
        );
    }

    private static Resource resource(String moduleId, String typeId, String path) {
        Resource resource = mock(Resource.class);
        when(resource.isFolder()).thenReturn(false);
        when(resource.getResourcePath()).thenReturn(new ResourcePath(new ResourceType(moduleId, typeId), path));
        return resource;
    }

    private static JsonNode findByName(ArrayNode rows, String name) {
        for (JsonNode row : rows) {
            if (name.equals(row.path("name").asText())) {
                return row;
            }
        }
        return new ObjectMapper().createObjectNode();
    }
}
