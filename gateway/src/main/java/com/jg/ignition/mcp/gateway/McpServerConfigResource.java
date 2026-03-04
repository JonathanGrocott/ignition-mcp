package com.jg.ignition.mcp.gateway;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.ValidationErrors;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;
import com.jg.ignition.mcp.common.McpConstants;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public record McpServerConfigResource(
    @FormCategory("General")
    @Label("Enabled")
    @FormField(FormFieldType.CHECKBOX)
    @DefaultValue("true")
    @Description("Enable MCP route handling")
    Boolean enabled,

    @FormCategory("General")
    @Label("Mount Alias")
    @FormField(FormFieldType.TEXT)
    @DefaultValue("ignition-mcp")
    String mountAlias,

    @FormCategory("Transport")
    @Label("Allowed Origins")
    @FormField(FormFieldType.TEXT)
    @Description("Allowed Origin header patterns")
    List<String> allowedOrigins,

    @FormCategory("Transport")
    @Label("Allowed Hosts")
    @FormField(FormFieldType.TEXT)
    @Description("Allowed Host header patterns")
    List<String> allowedHosts,

    @FormCategory("Transport")
    @Label("Streamable HTTP Enabled")
    @FormField(FormFieldType.CHECKBOX)
    @DefaultValue("true")
    Boolean streamableEnabled,

    @FormCategory("Transport")
    @Label("SSE Fallback Enabled")
    @FormField(FormFieldType.CHECKBOX)
    @DefaultValue("true")
    Boolean sseFallbackEnabled,

    @FormCategory("Limits")
    @Label("Max Concurrent Sessions")
    @FormField(FormFieldType.NUMBER)
    @DefaultValue("200")
    Integer maxConcurrentSessions,

    @FormCategory("Limits")
    @Label("Max Requests Per Minute Per Token")
    @FormField(FormFieldType.NUMBER)
    @DefaultValue("300")
    Integer maxRequestsPerMinutePerToken,

    @FormCategory("Limits")
    @Label("Max Write Ops Per Minute Per Token")
    @FormField(FormFieldType.NUMBER)
    @DefaultValue("60")
    Integer maxWriteOpsPerMinutePerToken,

    @FormCategory("Safety")
    @Label("Default Dry Run")
    @FormField(FormFieldType.CHECKBOX)
    @DefaultValue("true")
    Boolean defaultDryRun,

    @FormCategory("Safety")
    @Label("Max Batch Write Size")
    @FormField(FormFieldType.NUMBER)
    @DefaultValue("50")
    Integer maxBatchWriteSize,

    @FormCategory("Safety")
    @Label("Allowed Tag Read Patterns")
    @FormField(FormFieldType.TEXT)
    List<String> allowedTagReadPatterns,

    @FormCategory("Safety")
    @Label("Allowed Tag Write Patterns")
    @FormField(FormFieldType.TEXT)
    List<String> allowedTagWritePatterns,

    @FormCategory("Safety")
    @Label("Allowed Alarm Ack Sources")
    @FormField(FormFieldType.TEXT)
    List<String> allowedAlarmAckSources,

    @FormCategory("Safety")
    @Label("Allowed Named Query Execute Patterns")
    @FormField(FormFieldType.TEXT)
    List<String> allowedNamedQueryExecutePatterns,

    @FormCategory("Historian")
    @Label("Historian Default Provider")
    @FormField(FormFieldType.TEXT)
    @DefaultValue("")
    String historianDefaultProvider,

    @FormCategory("Historian")
    @Label("Historian Max Rows")
    @FormField(FormFieldType.NUMBER)
    @DefaultValue("5000")
    Integer historianMaxRows,

    @FormCategory("Limits")
    @Label("Named Query Max Rows")
    @FormField(FormFieldType.NUMBER)
    @DefaultValue("1000")
    Integer namedQueryMaxRows
) {

    private static final boolean DEFAULT_ENABLED = true;
    private static final String DEFAULT_MOUNT_ALIAS = McpConstants.MOUNT_ALIAS_DEFAULT;
    private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of();
    private static final List<String> DEFAULT_ALLOWED_HOSTS = List.of();
    private static final boolean DEFAULT_STREAMABLE_ENABLED = true;
    private static final boolean DEFAULT_SSE_FALLBACK_ENABLED = true;
    private static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 200;
    private static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 300;
    private static final int DEFAULT_MAX_WRITE_OPS_PER_MINUTE = 60;
    private static final boolean DEFAULT_DRY_RUN = true;
    private static final int DEFAULT_MAX_BATCH_WRITE_SIZE = 50;
    private static final List<String> DEFAULT_ALLOWED_TAG_READ_PATTERNS = List.of("*");
    private static final List<String> DEFAULT_ALLOWED_TAG_WRITE_PATTERNS = List.of("[default]MCP/*");
    private static final List<String> DEFAULT_ALLOWED_ALARM_ACK_SOURCES = List.of("*");
    private static final List<String> DEFAULT_ALLOWED_NAMED_QUERY_EXECUTE_PATTERNS = List.of("*");
    private static final String DEFAULT_HISTORIAN_PROVIDER = "";
    private static final int DEFAULT_HISTORIAN_MAX_ROWS = 5000;
    private static final int DEFAULT_NAMED_QUERY_MAX_ROWS = 1000;

    public static final ResourceType RESOURCE_TYPE = new ResourceType(McpConstants.MODULE_ID, "mcp-config");

    public static final McpServerConfigResource DEFAULT = new McpServerConfigResource(
        DEFAULT_ENABLED,
        DEFAULT_MOUNT_ALIAS,
        DEFAULT_ALLOWED_ORIGINS,
        DEFAULT_ALLOWED_HOSTS,
        DEFAULT_STREAMABLE_ENABLED,
        DEFAULT_SSE_FALLBACK_ENABLED,
        DEFAULT_MAX_CONCURRENT_SESSIONS,
        DEFAULT_MAX_REQUESTS_PER_MINUTE,
        DEFAULT_MAX_WRITE_OPS_PER_MINUTE,
        DEFAULT_DRY_RUN,
        DEFAULT_MAX_BATCH_WRITE_SIZE,
        DEFAULT_ALLOWED_TAG_READ_PATTERNS,
        DEFAULT_ALLOWED_TAG_WRITE_PATTERNS,
        DEFAULT_ALLOWED_ALARM_ACK_SOURCES,
        DEFAULT_ALLOWED_NAMED_QUERY_EXECUTE_PATTERNS,
        DEFAULT_HISTORIAN_PROVIDER,
        DEFAULT_HISTORIAN_MAX_ROWS,
        DEFAULT_NAMED_QUERY_MAX_ROWS
    );

    public static final ResourceTypeMeta<McpServerConfigResource> META = ResourceTypeMeta
        .newBuilder(McpServerConfigResource.class)
        .resourceType(RESOURCE_TYPE)
        .categoryName("Ignition MCP")
        .defaultConfig(DEFAULT)
        .buildValidator((resource, validator) -> resource.validate(validator))
        .build();

    public McpServerConfigResource {
        enabled = enabled == null ? DEFAULT_ENABLED : enabled;
        mountAlias = StringUtils.isBlank(mountAlias) ? DEFAULT_MOUNT_ALIAS : mountAlias;
        allowedOrigins = allowedOrigins == null ? DEFAULT_ALLOWED_ORIGINS : List.copyOf(allowedOrigins);
        allowedHosts = allowedHosts == null ? DEFAULT_ALLOWED_HOSTS : List.copyOf(allowedHosts);
        streamableEnabled = streamableEnabled == null ? DEFAULT_STREAMABLE_ENABLED : streamableEnabled;
        sseFallbackEnabled = sseFallbackEnabled == null ? DEFAULT_SSE_FALLBACK_ENABLED : sseFallbackEnabled;
        maxConcurrentSessions = positiveOrDefault(maxConcurrentSessions, DEFAULT_MAX_CONCURRENT_SESSIONS);
        maxRequestsPerMinutePerToken = positiveOrDefault(
            maxRequestsPerMinutePerToken,
            DEFAULT_MAX_REQUESTS_PER_MINUTE
        );
        maxWriteOpsPerMinutePerToken = positiveOrDefault(
            maxWriteOpsPerMinutePerToken,
            DEFAULT_MAX_WRITE_OPS_PER_MINUTE
        );
        defaultDryRun = defaultDryRun == null ? DEFAULT_DRY_RUN : defaultDryRun;
        maxBatchWriteSize = positiveOrDefault(maxBatchWriteSize, DEFAULT_MAX_BATCH_WRITE_SIZE);
        allowedTagReadPatterns = allowedTagReadPatterns == null
            ? DEFAULT_ALLOWED_TAG_READ_PATTERNS
            : List.copyOf(allowedTagReadPatterns);
        allowedTagWritePatterns = allowedTagWritePatterns == null
            ? DEFAULT_ALLOWED_TAG_WRITE_PATTERNS
            : List.copyOf(allowedTagWritePatterns);
        allowedAlarmAckSources = allowedAlarmAckSources == null
            ? DEFAULT_ALLOWED_ALARM_ACK_SOURCES
            : List.copyOf(allowedAlarmAckSources);
        allowedNamedQueryExecutePatterns = allowedNamedQueryExecutePatterns == null
            ? DEFAULT_ALLOWED_NAMED_QUERY_EXECUTE_PATTERNS
            : List.copyOf(allowedNamedQueryExecutePatterns);
        historianDefaultProvider = historianDefaultProvider == null ? DEFAULT_HISTORIAN_PROVIDER : historianDefaultProvider;
        historianMaxRows = positiveOrDefault(historianMaxRows, DEFAULT_HISTORIAN_MAX_ROWS);
        namedQueryMaxRows = positiveOrDefault(namedQueryMaxRows, DEFAULT_NAMED_QUERY_MAX_ROWS);
    }

    private static int positiveOrDefault(Integer value, int defaultValue) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return value;
    }

    public void validate(ValidationErrors.Builder errors) {
        if (maxConcurrentSessions <= 0) {
            errors.addFieldMessage("maxConcurrentSessions", "must be greater than 0");
        }
        if (maxRequestsPerMinutePerToken <= 0) {
            errors.addFieldMessage("maxRequestsPerMinutePerToken", "must be greater than 0");
        }
        if (maxWriteOpsPerMinutePerToken <= 0) {
            errors.addFieldMessage("maxWriteOpsPerMinutePerToken", "must be greater than 0");
        }
        if (maxBatchWriteSize <= 0) {
            errors.addFieldMessage("maxBatchWriteSize", "must be greater than 0");
        }
        if (historianMaxRows <= 0) {
            errors.addFieldMessage("historianMaxRows", "must be greater than 0");
        }
        if (namedQueryMaxRows <= 0) {
            errors.addFieldMessage("namedQueryMaxRows", "must be greater than 0");
        }
    }
}
