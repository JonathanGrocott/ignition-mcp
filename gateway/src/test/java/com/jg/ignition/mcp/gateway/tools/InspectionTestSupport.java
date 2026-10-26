package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.inductiveautomation.ignition.common.ImmutableBytes;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceId;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.resourcecollection.RuntimeResourceCollection;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.jg.ignition.mcp.gateway.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;

abstract class InspectionTestSupport {
    final ObjectMapper mapper = new ObjectMapper();
    final GatewayContext gateway = mock(GatewayContext.class);
    final McpServerConfigResource config = mock(McpServerConfigResource.class);

    InspectionTestSupport() {
        when(config.allowedProjectResourceReadPatterns()).thenReturn(List.of("*"));
        when(config.allowedTagReadPatterns()).thenReturn(List.of("*"));
        when(config.allowedReadToolPatterns()).thenReturn(List.of("*"));
        when(config.maxRequestsPerMinutePerToken()).thenReturn(300);
    }

    ToolCallContext context() {
        return new ToolCallContext(gateway, mock(RequestContext.class),
            new McpAuthService.AuthContext("test-reader", "test-fingerprint", null),
            new SafetyPolicyEngine(() -> config), new McpAuditLogger(), config, mapper);
    }

    Resource resource(String module, String type, String path, String file, String text) {
        Resource resource = mock(Resource.class);
        ResourcePath resourcePath = new ResourcePath(new ResourceType(module, type), path);
        when(resource.getResourcePath()).thenReturn(resourcePath);
        when(resource.getDefiningCollectionName()).thenReturn("parent");
        when(resource.getDataKeys()).thenReturn(ImmutableSet.of(file));
        when(resource.getData(file)).thenReturn(Optional.of(ImmutableBytes.ofString(text)));
        return resource;
    }

    ProjectManager project(Resource... resources) {
        ProjectManager manager = mock(ProjectManager.class);
        when(gateway.getProjectManager()).thenReturn(manager);
        when(manager.getNames()).thenReturn(List.of("Plant"));
        RuntimeResourceCollection collection = mock(RuntimeResourceCollection.class);
        when(manager.find("Plant")).thenReturn(Optional.of(collection));
        Map<ResourceId, Resource> map = new LinkedHashMap<>();
        for (Resource resource : resources) {
            map.put(mock(ResourceId.class), resource);
            when(manager.getResource("Plant", resource.getResourcePath())).thenReturn(Optional.of(resource));
        }
        when(collection.getAllResources()).thenReturn(map);
        return manager;
    }
}
