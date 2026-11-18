package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static com.jg.ignition.assistant.AssistantStyle.*;

final class AssistantDock extends JPanel {
    private final AgentProject project;
    private final ProjectTools tools;
    private final Palette palette = Palette.current();
    private final JLabel modelLabel = new JLabel();
    private final JLabel contextLabel = new JLabel();
    private final Timer contextTimer = new Timer(500, event -> showContext());
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private AssistantSettings settings;

    AssistantDock(AgentProject project, AssistantSettings settings, ProjectTools tools) {
        super(new BorderLayout(0, 3));
        this.project = project;
        this.settings = settings;
        this.tools = tools;
        setBackground(palette.bg());
        setBorder(new EmptyBorder(7, 10, 9, 10));

        var newChat = iconButton(palette, "New chat", Glyph.PLUS, false);
        newChat.addActionListener(event -> addChat(true));
        var modelSettings = iconButton(palette, "Model settings", Glyph.SETTINGS, false);
        modelSettings.addActionListener(event -> this.settings.edit(this, tools).ifPresent(value -> {
            this.settings = value;
            showProvider();
        }));
        modelLabel.setForeground(palette.muted());
        modelLabel.setBorder(new EmptyBorder(0, 3, 0, 0));
        var header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.add(modelLabel, BorderLayout.CENTER);
        header.add(row(newChat, modelSettings), BorderLayout.EAST);
        contextLabel.setForeground(palette.muted());
        contextLabel.setFont(contextLabel.getFont().deriveFont(contextLabel.getFont().getSize2D() - 1));
        contextLabel.setBorder(new EmptyBorder(1, 3, 2, 0));
        contextLabel.getAccessibleContext().setAccessibleName("Current Designer context");
        contextLabel.setVisible(false);
        header.add(contextLabel, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        showProvider();

        tabs.setOpaque(false);
        tabs.getAccessibleContext().setAccessibleName("Assistant chats");
        tabs.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke("F2"), "renameChat");
        tabs.getActionMap().put("renameChat", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                int index = tabs.getSelectedIndex();
                if (index >= 0 && tabs.getTabComponentAt(index) instanceof ChatTab tab) {
                    tab.rename();
                }
            }
        });
        add(tabs, BorderLayout.CENTER);
        addChat(false);
    }

    private void showProvider() {
        modelLabel.setText(settings.provider().displayName());
        modelLabel.setToolTipText(settings.provider().connectionDescription());
    }

    @Override public void addNotify() {
        super.addNotify();
        contextTimer.start();
    }

    @Override public void removeNotify() {
        contextTimer.stop();
        super.removeNotify();
    }

    private void showContext() {
        if (!isShowing()) {
            return;
        }
        String label = contextLabel(project.designerContext());
        contextLabel.setText(label);
        contextLabel.setToolTipText(label.isEmpty() ? null : label);
        contextLabel.setVisible(!label.isEmpty());
    }

    static String contextLabel(ObjectNode context) {
        var active = context.path("activeResource");
        if (active.isObject()) {
            String label = switch (active.path("kind").asText()) {
                case "perspective" -> "Perspective";
                case "scripts" -> "Script";
                case "namedQueries" -> "Named Query";
                default -> "Editor";
            };
            var selection = context.path("perspectiveSelection");
            var component = selection.path("components").path(0);
            int count = selection.path("count").asInt();
            String selected = count > 1 ? count + " components" : component.path("name").asText(component.path("type").asText());
            return label + "  ·  " + resourceLabel(active) + (selected.isEmpty() ? "" : "  ·  " + selected);
        }
        var tags = context.path("tagBrowserSelection");
        if (tags.size() > 0) {
            var tag = tags.path(0);
            String label = "udt".equals(tag.path("kind").asText()) ? "UDT Definitions" : "Tag Browser";
            return label + "  ·  " + (tags.size() > 1 ? tags.size() + " selected" : tag.path("path").asText());
        }
        var resources = context.path("projectBrowserSelection");
        return resources.size() == 0 ? "" : "Project Browser  ·  "
            + (resources.size() > 1 ? resources.size() + " selected" : resourceLabel(resources.path(0)));
    }

    private static String resourceLabel(JsonNode resource) {
        String path = resource.path("path").asText();
        return "_unary".equals(path) ? resource.path("title").asText(path) : path;
    }

    private void addChat(boolean focus) {
        var chat = new AssistantPanel(project, () -> settings, tools, palette);
        tabs.addTab("New chat", chat);
        var tab = new ChatTab(chat);
        tabs.setTabComponentAt(tabs.indexOfComponent(chat), tab);
        chat.addPropertyChangeListener("firstPrompt", event -> tab.nameFromPrompt((String) event.getNewValue()));
        tabs.setSelectedComponent(chat);
        if (focus) {
            SwingUtilities.invokeLater(chat::focusComposer);
        }
    }

    private void closeChat(AssistantPanel chat) {
        if (chat.isBusy()) {
            tabs.setSelectedComponent(chat);
            return;
        }
        chat.close();
        tabs.remove(chat);
        if (tabs.getTabCount() == 0) {
            addChat(true);
        }
    }

    private final class ChatTab extends JPanel {
        private final AssistantPanel chat;
        private final JLabel label = new JLabel();
        private final JButton close = iconButton(palette, "Close chat", Glyph.REMOVE, false);
        private String name = "New chat";
        private boolean renamed;

        ChatTab(AssistantPanel chat) {
            super(new BorderLayout(3, 0));
            this.chat = chat;
            setOpaque(false);
            add(label, BorderLayout.CENTER);
            close.setBorder(new EmptyBorder(1, 2, 1, 2));
            close.addActionListener(event -> closeChat(chat));
            add(close, BorderLayout.EAST);

            var menu = new JPopupMenu();
            var rename = new JMenuItem("Rename chat…");
            rename.addActionListener(event -> rename());
            menu.add(rename);
            setComponentPopupMenu(menu);
            label.setInheritsPopupMenu(true);
            var select = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        tabs.setSelectedComponent(chat);
                        if (event.getClickCount() == 2) {
                            rename();
                        }
                    }
                }
            };
            addMouseListener(select);
            label.addMouseListener(select);
            updateName(name);
        }

        void nameFromPrompt(String prompt) {
            if (!renamed) {
                updateName(limit(prompt.replaceAll("\\s+", " ").strip(), 64));
            }
        }

        void rename() {
            String value = JOptionPane.showInputDialog(AssistantDock.this, "Chat name", name);
            if (value != null && !value.isBlank()) {
                renamed = true;
                updateName(limit(value.strip().replaceAll("\\s+", " "), 64));
            }
        }

        private void updateName(String title) {
            name = title;
            label.setText(limit(title, 19));
            label.setToolTipText(title + " · Double-click to rename");
            close.setToolTipText("Close " + title);
            close.getAccessibleContext().setAccessibleName("Close " + title);
            tabs.setTitleAt(tabs.indexOfComponent(chat), title);
        }

        private String limit(String text, int maxLength) {
            int count = text.codePointCount(0, text.length());
            return count <= maxLength ? text : text.substring(0, text.offsetByCodePoints(0, maxLength - 1)).stripTrailing() + "…";
        }
    }

    void close() {
        contextTimer.stop();
        for (int index = 0; index < tabs.getTabCount(); index++) {
            Component component = tabs.getComponentAt(index);
            ((AssistantPanel) component).close();
        }
        tabs.removeAll();
    }
}
