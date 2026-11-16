package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.client.tags.tree.node.BrowseTreeNode;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.designer.IgnitionDesigner;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.ignition.designer.navtree.model.AbstractResourceNavTreeNode;
import com.inductiveautomation.ignition.designer.tabbedworkspace.ResourceEditor;
import com.inductiveautomation.ignition.designer.tabbedworkspace.TabbedResourceWorkspace;

import javax.swing.JTree;
import javax.swing.tree.TreePath;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.jg.ignition.assistant.AssistantJson.JSON;

final class DesignerSelection {
    private static final int SELECTION_LIMIT = 8;

    private DesignerSelection() {}

    // Read Swing state only on the event dispatch thread. Nothing here opens or commits an editor.
    static ObjectNode capture(DesignerContext context) {
        var result = JSON.createObjectNode();
        if (!(context.getFrame() instanceof IgnitionDesigner designer) || context.getProject() == null) {
            return result;
        }

        var workspace = designer.getWorkspace().getSelectedWorkspace();
        if (workspace != null) {
            result.put("workspace", workspace.getKey());
        }
        if (workspace instanceof TabbedResourceWorkspace tabs) {
            var editor = tabs.getSelectedEditor();
            ResourcePath path = editor == null ? null : editor.getResourcePath();
            if (path != null) {
                var active = resource(path).put("locallyChanged", context.getProject().isChanged(path));
                if (path.getPath().toString().isEmpty()) {
                    active.put("title", editor.getTabTitle());
                }
                result.set("activeResource", active);
                if ("perspective".equals(active.path("kind").asText())) {
                    var selection = perspectiveSelection(editor);
                    if (!selection.isEmpty()) {
                        result.set("perspectiveSelection", selection);
                    }
                }
            }
        }

        var navigation = designer.getNavTreePanel();
        if (navigation != null && navigation.isShowing()) {
            var selected = selectedNodes(navigation.getTree()).stream()
                .filter(AbstractResourceNavTreeNode.class::isInstance)
                .map(AbstractResourceNavTreeNode.class::cast)
                .filter(node -> node.getResourcePath() != null).limit(SELECTION_LIMIT)
                .map(node -> resource(node.getResourcePath()).put("title", node.getText())).toList();
            if (!selected.isEmpty()) {
                result.putArray("projectBrowserSelection").addAll(selected);
            }
        }

        var tagBrowser = context.getTagBrowser();
        if (tagBrowser != null && tagBrowser.isShowing()) {
            var panels = tagBrowser.getTabbedPanel();
            boolean udt = panels.getUdtPanel().isShowing();
            var panel = udt ? panels.getUdtPanel() : panels.getTagPanel();
            var selected = selectedNodes(panel.getTree()).stream()
                .filter(BrowseTreeNode.class::isInstance).map(BrowseTreeNode.class::cast)
                .filter(node -> !node.isMetaNode())
                .map(BrowseTreeNode::getTagPath).filter(Objects::nonNull)
                .limit(SELECTION_LIMIT).map(path -> JSON.createObjectNode()
                    .put("path", path.toStringFull())
                    .put("providerRelativePath", path.toStringPartial())
                    .put("provider", path.getSource())
                    .put("kind", udt ? "udt" : "tag"))
                .toList();
            if (!selected.isEmpty()) {
                result.putArray("tagBrowserSelection").addAll(selected);
            }
        }
        return result;
    }

    private static List<Object> selectedNodes(JTree tree) {
        TreePath[] paths = tree.getSelectionPaths();
        return paths == null ? List.of() : Arrays.stream(paths).map(TreePath::getLastPathComponent).toList();
    }

    private static ObjectNode resource(ResourcePath path) {
        String relativePath = path.getPath().toString();
        var result = JSON.createObjectNode().put("moduleId", path.getModuleId()).put("resourceType", path.getType())
            .put("path", relativePath.isEmpty() ? "_unary" : relativePath);
        String kind = NativeAgentProject.resourceKind(path.getResourceType());
        if (kind != null) {
            result.put("kind", kind);
        }
        return result;
    }

    private static ObjectNode perspectiveSelection(ResourceEditor<?> editor) {
        // Perspective is optional and its editor types are loaded by Perspective's module classloader.
        try {
            Object selection = editor.getClass().getMethod("getSelection").invoke(editor);
            if (selection == null) {
                return JSON.createObjectNode();
            }
            Class<?> api = selection.getClass();
            if ((boolean) api.getMethod("isView").invoke(selection)) {
                return JSON.createObjectNode().put("kind", "view");
            }
            if (!(boolean) api.getMethod("isComponents").invoke(selection)) {
                return JSON.createObjectNode();
            }
            var details = (List<?>) api.getMethod("getComponentDetails").invoke(selection);
            var result = JSON.createObjectNode().put("kind", "components")
                .put("count", (int) api.getMethod("getSelectionCount").invoke(selection));
            var components = result.putArray("components");
            Object structure = editor.getClass().getMethod("getStructure").invoke(editor);
            Object root = structure == null ? null : structure.getClass().getField("root").get(structure);
            for (Object detail : details.stream().limit(SELECTION_LIMIT).toList()) {
                Class<?> type = detail.getClass();
                String path = (String) type.getField("path").get(detail);
                var component = components.addObject()
                    .put("designerPath", path)
                    .put("type", (String) type.getField("componentType").get(detail));
                if (root != null && path != null) {
                    locateComponent(root, path, "/root", component);
                }
            }
            return result;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return JSON.createObjectNode();
        }
    }

    private static boolean locateComponent(Object node, String target, String pointer, ObjectNode component)
            throws ReflectiveOperationException {
        Class<?> type = node.getClass();
        String path = (String) type.getField("path").get(node);
        if (target.equals(path)) {
            component.put("name", (String) type.getField("name").get(node)).put("editorJsonPointer", pointer);
            return true;
        }
        // The Designer uses a dot after the view prefix and colons between child indices.
        if (path != null && !target.startsWith(path + ".") && !target.startsWith(path + ":")) {
            return false;
        }
        Object[] children = (Object[]) type.getField("children").get(node);
        if (children != null) {
            for (int index = 0; index < children.length; index++) {
                if (locateComponent(children[index], target, pointer + "/children/" + index, component)) {
                    return true;
                }
            }
        }
        return false;
    }
}
