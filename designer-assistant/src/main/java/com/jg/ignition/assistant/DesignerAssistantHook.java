package com.jg.ignition.assistant;

import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnectionManager;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.jidesoft.docking.DockContext;
import com.jidesoft.docking.DockableFrame;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.List;

public final class DesignerAssistantHook extends AbstractDesignerModuleHook {
    private volatile DockableFrame frame;
    private AssistantPanel panel;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) throws Exception {
        Runnable create = () -> {
            var views = new ResourceType(PerspectiveDraft.MODULE, "views");
            panel = new AssistantPanel(context::getProjectName, () -> {
                var connection = GatewayConnectionManager.getInstance();
                var address = connection == null ? null : connection.getGatewayAddress();
                return address == null ? "" : address.toString();
            }, path -> {
                var project = context.getProject();
                return project != null && project.getResource(new ResourcePath(views, path)).isPresent();
            });

            DockableFrame dock = new DockableFrame("IgnitionDesignerAssistant");
            dock.setTitle("Designer Assistant");
            dock.setTabTitle("Assistant");
            dock.setSideTitle("Assistant");
            dock.getContext().setInitMode(DockContext.STATE_FRAMEDOCKED);
            dock.getContext().setInitSide(DockContext.DOCK_SIDE_EAST);
            dock.getContext().setInitIndex(0);
            dock.setPreferredSize(new Dimension(420, 570));
            dock.setDockedWidth(420);
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
