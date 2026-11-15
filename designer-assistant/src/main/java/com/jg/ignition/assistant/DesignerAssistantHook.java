package com.jg.ignition.assistant;

import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnection;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.jidesoft.docking.DockContext;
import com.jidesoft.docking.DockableFrame;
import com.jg.ignition.mcp.bridge.DesignerMcpRpc;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.List;

public final class DesignerAssistantHook extends AbstractDesignerModuleHook {
    private volatile DockableFrame frame;
    private AssistantDock panel;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) throws Exception {
        Runnable create = () -> {
            var rpc = GatewayConnection.getRpcInterface(ProtoRpcSerializer.DEFAULT_INSTANCE, DesignerMcpRpc.MODULE_ID,
                DesignerMcpRpc.class);
            panel = new AssistantDock(new NativeAgentProject(context), AssistantSettings.saved(), new ProjectTools(rpc));

            DockableFrame dock = new DockableFrame("IgnitionDesignerAssistant");
            dock.setTitle("Designer Assistant");
            dock.setTabTitle("Assistant");
            dock.setSideTitle("Assistant");
            dock.getContext().setInitMode(DockContext.STATE_FRAMEDOCKED);
            dock.getContext().setInitSide(DockContext.DOCK_SIDE_EAST);
            dock.getContext().setInitIndex(0);
            dock.setPreferredSize(new Dimension(340, 520));
            dock.setDockedWidth(340);
            dock.setContentPane(panel);
            frame = dock;
        };
        if (SwingUtilities.isEventDispatchThread()) {
            create.run();
        } else {
            SwingUtilities.invokeAndWait(create);
        }
    }

    @Override
    public List<DockableFrame> getFrames() {
        DockableFrame current = frame;
        return current == null ? List.of() : List.of(current);
    }

    @Override
    public void shutdown() {
        Runnable dispose = () -> {
            if (panel != null) {
                panel.close();
                panel = null;
            }
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            dispose.run();
        } else {
            SwingUtilities.invokeLater(dispose);
        }
    }
}
