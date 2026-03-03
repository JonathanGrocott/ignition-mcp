package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.Path;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.StreamingDatasetWriter;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode;
import com.inductiveautomation.ignition.common.sqltags.history.BasicTagHistoryQueryParams;
import com.inductiveautomation.ignition.common.sqltags.history.ReturnFormat;
import com.inductiveautomation.ignition.common.sqltags.history.TagHistoryQueryFlags;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.common.util.Flags;
import com.inductiveautomation.ignition.gateway.historian.TagHistoryManager;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class HistorianToolHandler implements ToolHandler {

    private static final String TOOL = "ignition.historian.query";

    private final ToolDefinition definition = new ToolDefinition(
        TOOL,
        "Query tag historian data with row limits and provider defaults",
        PermissionRequirement.READ,
        inputSchema(),
        false
    );

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Historian query requires an arguments object");
        }

        String provider = arguments.path("provider").asText(context.safetyPolicy().historianDefaultProvider()).trim();
        int requestedMaxRows = arguments.path("maxRows").asInt(context.safetyPolicy().historianMaxRows());
        int maxRows = Math.max(1, Math.min(requestedMaxRows, context.safetyPolicy().historianMaxRows()));
        Date startDate = parseDate(arguments.get("start"));
        Date endDate = parseDate(arguments.get("end"));
        if (startDate == null || endDate == null) {
            return ToolExecutionResult.error("Historian query requires valid start and end values");
        }
        if (endDate.before(startDate)) {
            return ToolExecutionResult.error("Historian query end must be after start");
        }

        ArrayNode paths = arguments.has("paths") && arguments.get("paths").isArray()
            ? (ArrayNode) arguments.get("paths")
            : context.objectMapper().createArrayNode();
        if (paths.isEmpty()) {
            return ToolExecutionResult.error("Historian query requires at least one path");
        }

        List<Path> queryPaths = new ArrayList<>(paths.size());
        List<String> aliases = new ArrayList<>(paths.size());

        for (JsonNode pathNode : paths) {
            String path = pathNode.asText("").trim();
            if (path.isBlank()) {
                return ToolExecutionResult.error("Historian path cannot be empty");
            }
            if (!context.safetyPolicy().isTagReadAllowed(path)) {
                return ToolExecutionResult.error("Historian path blocked by read allowlist: " + path);
            }
            Path resolvedPath = resolveHistoryPath(path, provider);
            if (resolvedPath == null) {
                return ToolExecutionResult.error("Unable to resolve historian path: " + path);
            }
            queryPaths.add(resolvedPath);
            aliases.add(path);
        }

        AggregationMode aggregationMode = resolveAggregationMode(arguments.path("aggregate").asText(""));
        ReturnFormat returnFormat = resolveReturnFormat(arguments.path("returnFormat").asText(""));
        Flags flags = Flags.none();
        if (arguments.path("ignoreBadQuality").asBoolean(false)) {
            flags = flags.or(TagHistoryQueryFlags.IGNORE_BAD_QUALITY);
        }
        if (arguments.path("noInterpolation").asBoolean(false)) {
            flags = flags.or(TagHistoryQueryFlags.NO_INTERPOLATION);
        }
        if (arguments.path("noSeedValues").asBoolean(false)) {
            flags = flags.or(TagHistoryQueryFlags.NO_SEED_VALUES);
        }

        BasicTagHistoryQueryParams query = BasicTagHistoryQueryParams.newBuilder()
            .paths(queryPaths)
            .aliases(aliases)
            .startDate(startDate)
            .endDate(endDate)
            .returnSize(maxRows)
            .aggregationMode(aggregationMode)
            .returnFormat(returnFormat)
            .queryFlags(flags)
            .build();

        try {
            TagHistoryManager historyManager = context.gatewayContext().getTagHistoryManager();
            CollectingDatasetWriter writer = new CollectingDatasetWriter(maxRows);
            historyManager.queryHistory(query, writer);
            if (writer.error != null) {
                return ToolExecutionResult.error("Historian query failed: " + writer.error.getMessage());
            }

            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("provider", provider);
            result.put("start", startDate.getTime());
            result.put("end", endDate.getTime());
            result.put("maxRows", maxRows);
            result.put("seriesCount", queryPaths.size());
            result.put("rowCount", writer.rows.size());
            result.put("truncated", writer.truncated);
            result.put("returnFormat", returnFormat.name());
            result.put("aggregationMode", aggregationMode.name());
            result.put("qualityIncluded", writer.qualityData);
            result.set("columns", context.objectMapper().valueToTree(Arrays.asList(writer.columnNames)));

            ArrayNode rows = result.putArray("rows");
            for (int rowIndex = 0; rowIndex < writer.rows.size(); rowIndex++) {
                Object[] rowValues = writer.rows.get(rowIndex);
                QualityCode[] rowQuality = rowIndex < writer.qualities.size()
                    ? writer.qualities.get(rowIndex)
                    : new QualityCode[0];

                ObjectNode row = rows.addObject();
                ObjectNode quality = writer.qualityData ? row.putObject("_quality") : null;
                for (int colIndex = 0; colIndex < rowValues.length; colIndex++) {
                    String columnName = colIndex < writer.columnNames.length && writer.columnNames[colIndex] != null
                        ? writer.columnNames[colIndex]
                        : "col_" + colIndex;
                    row.set(columnName, context.objectMapper().valueToTree(rowValues[colIndex]));
                    if (quality != null) {
                        QualityCode cellQuality = colIndex < rowQuality.length ? rowQuality[colIndex] : null;
                        quality.put(columnName, cellQuality == null ? "" : cellQuality.getName());
                    }
                }
            }
            return ToolExecutionResult.ok("Historian query processed", result);
        }
        catch (Exception e) {
            return ToolExecutionResult.error("Historian query failed: " + e.getMessage());
        }
    }

    private static JsonNode inputSchema() {
        ObjectNode schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode paths = props.putObject("paths");
        paths.put("type", "array");
        paths.putObject("items").put("type", "string");

        props.putObject("start").put("type", "string");
        props.putObject("end").put("type", "string");
        props.putObject("provider").put("type", "string");
        props.putObject("maxRows").put("type", "integer");
        props.putObject("aggregate").put("type", "string");
        props.putObject("returnFormat").put("type", "string");
        props.putObject("ignoreBadQuality").put("type", "boolean");
        props.putObject("noInterpolation").put("type", "boolean");
        props.putObject("noSeedValues").put("type", "boolean");
        schema.putArray("required").add("paths").add("start").add("end");

        return schema;
    }

    private static Path resolveHistoryPath(String path, String provider) {
        if (StringUtils.isNotBlank(provider)) {
            return QualifiedPath.of("histprov", provider, "tag", path);
        }

        Path tagPath = TagPathParser.parseSafe(path);
        if (tagPath != null) {
            return tagPath;
        }
        return QualifiedPath.parseSafe(path);
    }

    private static Date parseDate(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return new Date(value.asLong());
        }
        String text = value.asText("").trim();
        if (text.isBlank()) {
            return null;
        }

        try {
            return new Date(Long.parseLong(text));
        }
        catch (Exception ignored) {
            // Continue trying ISO variants.
        }
        try {
            return Date.from(Instant.parse(text));
        }
        catch (Exception ignored) {
            // Continue trying offset timestamp variant.
        }
        try {
            return Date.from(OffsetDateTime.parse(text).toInstant());
        }
        catch (Exception ignored) {
            // Continue trying zoned timestamp variant.
        }
        try {
            return Date.from(ZonedDateTime.parse(text).toInstant());
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static AggregationMode resolveAggregationMode(String value) {
        if (StringUtils.isBlank(value)) {
            return AggregationMode.Average;
        }
        AggregationMode mode = AggregationMode.valueOfCaseInsensitive(value.trim());
        return mode == null ? AggregationMode.Average : mode;
    }

    private static ReturnFormat resolveReturnFormat(String value) {
        if (StringUtils.isBlank(value)) {
            return ReturnFormat.Wide;
        }
        for (ReturnFormat format : ReturnFormat.values()) {
            if (format.name().equalsIgnoreCase(value.trim())) {
                return format;
            }
        }
        return ReturnFormat.Wide;
    }

    private static final class CollectingDatasetWriter implements StreamingDatasetWriter {
        private final int maxRows;
        private String[] columnNames = new String[0];
        private Class<?>[] columnTypes = new Class<?>[0];
        private boolean qualityData;
        private final List<Object[]> rows = new ArrayList<>();
        private final List<QualityCode[]> qualities = new ArrayList<>();
        private Exception error;
        private boolean truncated;

        private CollectingDatasetWriter(int maxRows) {
            this.maxRows = maxRows;
        }

        @Override
        public void initialize(String[] names, Class<?>[] types, boolean supportsQuality, int expectedRows) {
            this.columnNames = names == null ? new String[0] : names.clone();
            this.columnTypes = types == null ? new Class<?>[0] : types.clone();
            this.qualityData = supportsQuality;
        }

        @Override
        public void write(Object[] values, QualityCode[] qualityCodes) {
            if (rows.size() >= maxRows) {
                truncated = true;
                return;
            }
            rows.add(values == null ? new Object[0] : values.clone());
            qualities.add(qualityCodes == null ? new QualityCode[0] : qualityCodes.clone());
        }

        @Override
        public void finish() {
            // No action required.
        }

        @Override
        public void finishWithError(Exception error) {
            this.error = error;
        }
    }
}
