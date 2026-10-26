package com.jg.ignition.mcp.gateway.tools;

import com.inductiveautomation.ignition.common.datasource.DatasourceStatus;
import com.inductiveautomation.ignition.common.logging.Level;
import com.inductiveautomation.ignition.common.logging.LogEvent;
import com.inductiveautomation.ignition.common.logging.LogQueryConfig;
import com.inductiveautomation.ignition.common.logging.LogResults;
import com.inductiveautomation.ignition.gateway.ContextState;
import com.inductiveautomation.ignition.gateway.datasource.Datasource;
import com.inductiveautomation.ignition.gateway.datasource.DatasourceManager;
import com.inductiveautomation.ignition.gateway.logging.GatewayLoggingManager;
import com.inductiveautomation.ignition.gateway.model.ModuleManager;
import com.inductiveautomation.ignition.gateway.model.ModuleState;
import com.inductiveautomation.ignition.gateway.model.ModuleFaultCause;
import com.inductiveautomation.ignition.gateway.model.GatewayModule;
import com.inductiveautomation.ignition.common.modules.ModuleInfo;
import com.inductiveautomation.ignition.gateway.opc.OpcConnectionManager;
import com.inductiveautomation.ignition.gateway.redundancy.RedundancyManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GatewayInspectionToolHandlerTest extends InspectionTestSupport {
    void healthy() {
        when(gateway.getState()).thenReturn(ContextState.RUNNING);
        when(gateway.getRedundancyManager()).thenReturn(mock(RedundancyManager.class));
        var modules = mock(ModuleManager.class);
        when(gateway.getModuleManager()).thenReturn(modules);
        when(modules.getModules()).thenReturn(List.of());
        var db = mock(DatasourceManager.class);
        when(gateway.getDatasourceManager()).thenReturn(db);
        when(db.getDatasources()).thenReturn(List.of());
        var opc = mock(OpcConnectionManager.class);
        when(gateway.getOpcManager()).thenReturn(opc);
        when(opc.getConnections()).thenReturn(List.of());
    }

    @Test
    void diagnosisReportsHealthyOnlyWhenAllCoveredChecksSucceed() {
        healthy();
        var result = new GatewayInspectionToolHandler("ignition.gateway.diagnose").execute(mapper.createObjectNode(), context());
        assertFalse(result.isError(), result.text());
        assertEquals("healthy", result.structuredContent().path("verdict").asText());
        assertTrue(result.structuredContent().path("complete").asBoolean());
        assertEquals(3, result.structuredContent().path("steps").size());
        when(gateway.getOpcManager()).thenReturn(null);
        result = new GatewayInspectionToolHandler("ignition.gateway.diagnose").execute(mapper.createObjectNode(), context());
        assertEquals("unknown", result.structuredContent().path("verdict").asText());
        assertFalse(result.structuredContent().path("complete").asBoolean());
    }

    @Test
    void moduleFaultMetadataAndIntentionalInactivityDoNotProduceFalseFaults() {
        healthy();
        var active = mock(GatewayModule.class);
        var inactive = mock(GatewayModule.class);
        var info = mock(ModuleInfo.class);
        when(info.getId()).thenReturn("test.module");
        when(active.getInfo()).thenReturn(info);
        when(inactive.getInfo()).thenReturn(info);
        when(active.getState()).thenReturn(ModuleState.ACTIVE);
        when(inactive.getState()).thenReturn(ModuleState.INACTIVE);
        // The native implementation can retain fault metadata outside the FAULTED lifecycle state.
        when(active.getFaultCause()).thenReturn(ModuleFaultCause.Other);
        when(active.getFaultReason()).thenReturn(new IllegalStateException("Not a current fault"));
        when(gateway.getModuleManager().getModules()).thenReturn(List.of(active, inactive));
        var handler = new GatewayInspectionToolHandler("ignition.gateway.diagnose");
        var result = handler.execute(mapper.createObjectNode(), context());
        assertEquals("healthy", result.structuredContent().path("verdict").asText());
        assertFalse(result.structuredContent().at("/checks/modules/modules/0/faulted").asBoolean());
        when(active.getState()).thenReturn(ModuleState.FAULTED);
        result = handler.execute(mapper.createObjectNode(), context());
        assertEquals("degraded", result.structuredContent().path("verdict").asText());
        assertTrue(result.structuredContent().at("/checks/modules/modules/0/faulted").asBoolean());
    }

    @Test
    void diagnosisCannotBypassChildToolAuthorization() {
        healthy();
        when(config.allowedReadToolPatterns()).thenReturn(List.of("ignition.gateway.diagnose", "ignition.gateway.status"));
        clearInvocations(gateway);
        var result = new GatewayInspectionToolHandler("ignition.gateway.diagnose").execute(mapper.createObjectNode(), context());
        assertEquals("unknown", result.structuredContent().path("verdict").asText());
        assertEquals("TOOL_NOT_AUTHORIZED", result.structuredContent().at("/steps/1/code").asText());
        verify(gateway, never()).getModuleManager();
        verify(gateway, never()).getDatasourceManager();
        verify(gateway, never()).getOpcManager();
    }

    @Test
    void diagnosisKeepsSuccessfulStepsWhenAnotherSdkCallFails() throws Exception {
        healthy();
        when(gateway.getModuleManager()).thenThrow(new IllegalStateException("internal password=secret"));
        var source = mock(Datasource.class);
        when(source.getName()).thenReturn("ProcessDB");
        when(source.getStatus()).thenReturn(DatasourceStatus.FAULTED);
        when(gateway.getDatasourceManager().getDatasources()).thenReturn(List.of(source));
        var result = new GatewayInspectionToolHandler("ignition.gateway.diagnose").execute(mapper.createObjectNode(), context());
        assertEquals("degraded", result.structuredContent().path("verdict").asText());
        assertEquals("SDK_FAILURE", result.structuredContent().at("/steps/1/code").asText());
        assertEquals("ProcessDB", result.structuredContent().at("/checks/connections/databases/0/name").asText());
        assertFalse(result.structuredContent().toString().contains("password"));
        verify(source, never()).getConnection();
    }

    @Test
    void logsBoundQueryWindowResultCountAndMessageLength() {
        var logging = mock(GatewayLoggingManager.class);
        when(gateway.getLoggingManager()).thenReturn(logging);
        var event = new LogEvent();
        event.setMessage("x".repeat(5000));
        event.setLevel(Level.WARN);
        event.setLoggerName("Test");
        when(logging.queryLogEvents(any())).thenReturn(new LogResults(List.of(event, event)));
        var result = new GatewayInspectionToolHandler("ignition.gateway.logs").execute(
            mapper.createObjectNode().put("maxResults", 1).put("lastMinutes", 5).put("minLevel", "WARN"), context());
        assertFalse(result.isError(), result.text());
        assertEquals(1, result.structuredContent().path("count").asInt());
        assertTrue(result.structuredContent().path("truncated").asBoolean());
        assertEquals(4000, result.structuredContent().at("/events/0/message").asText().length());
        assertTrue(result.structuredContent().at("/events/0/messageTruncated").asBoolean());
        var query = ArgumentCaptor.forClass(LogQueryConfig.class);
        verify(logging).queryLogEvents(query.capture());
        assertEquals(2, query.getValue().getLimit());
        assertEquals(300000L, query.getValue().getEndTime() - query.getValue().getStartTime());
        assertEquals(Level.WARN, query.getValue().getMinLevel());
    }

    @Test
    void invalidLogLevelAndUnboundedLimitsAreRejected() {
        when(gateway.getLoggingManager()).thenReturn(mock(GatewayLoggingManager.class));
        var handler = new GatewayInspectionToolHandler("ignition.gateway.logs");
        var result = handler.execute(mapper.createObjectNode().put("minLevel", "everything"), context());
        assertEquals("VALIDATION_ERROR", result.structuredContent().path("code").asText());
        result = handler.execute(mapper.createObjectNode().put("maxResults", 100000), context());
        assertEquals("VALIDATION_ERROR", result.structuredContent().path("code").asText());
        verify(gateway.getLoggingManager(), never()).queryLogEvents(any());
    }
}
