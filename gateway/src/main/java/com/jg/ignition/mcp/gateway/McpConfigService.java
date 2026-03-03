package com.jg.ignition.mcp.gateway;

import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.common.resourcecollection.PushException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class McpConfigService {

    private static final String DEFAULT_RESOURCE_NAME = "default";

    private final Logger logger = LoggerFactory.getLogger(McpConfigService.class);

    private final ConfigHandler handler;
    private final AtomicReference<McpServerConfigResource> current = new AtomicReference<>(McpServerConfigResource.DEFAULT);

    public McpConfigService(GatewayContext gatewayContext) {
        this.handler = new ConfigHandler(gatewayContext);
    }

    public void startup() {
        handler.startup();
        ensureDefaultResource();
    }

    public void shutdown() {
        handler.shutdown();
    }

    public McpServerConfigResource getConfig() {
        return current.get();
    }

    public CompletableFuture<Void> upsert(McpServerConfigResource resource, String actor) {
        try {
            Optional<DecodedResource<McpServerConfigResource>> existing = handler.findResource(DEFAULT_RESOURCE_NAME);
            if (existing.isPresent()) {
                return handler.modify(DEFAULT_RESOURCE_NAME, resource, actor);
            }
            return handler.create(DEFAULT_RESOURCE_NAME, resource, actor);
        }
        catch (PushException e) {
            logger.error("Failed to upsert MCP config resource", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private void ensureDefaultResource() {
        List<DecodedResource<McpServerConfigResource>> resources = handler.getResources();
        if (resources.isEmpty()) {
            logger.info("No MCP config resource found, creating default config");
            try {
                handler.create(DEFAULT_RESOURCE_NAME, McpServerConfigResource.DEFAULT, "system").join();
            }
            catch (PushException e) {
                throw new IllegalStateException("Unable to create default MCP config resource", e);
            }
        }
    }

    private void refreshCurrent(List<DecodedResource<McpServerConfigResource>> resources) {
        resources.stream()
            .min(Comparator.comparing(DecodedResource::name))
            .map(DecodedResource::config)
            .ifPresentOrElse(current::set, () -> current.set(McpServerConfigResource.DEFAULT));
    }

    private final class ConfigHandler extends NamedResourceHandler<McpServerConfigResource> {

        private ConfigHandler(GatewayContext context) {
            super(context, McpServerConfigResource.META);
        }

        @Override
        protected void onInitialResources(List<DecodedResource<McpServerConfigResource>> resources) {
            refreshCurrent(resources);
        }

        @Override
        protected void onResourcesUpdated(List<DecodedResource<McpServerConfigResource>> resources) {
            refreshCurrent(resources);
        }
    }
}
