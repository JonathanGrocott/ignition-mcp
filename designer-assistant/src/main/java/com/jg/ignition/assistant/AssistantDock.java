package com.jg.ignition.assistant;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;

import static com.jg.ignition.assistant.AssistantStyle.*;

final class AssistantDock extends JPanel {
    private final AgentProject project;
    private final ProjectTools tools;
    private final Palette palette = Palette.current();
    private final JLabel modelLabel = new JLabel();
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private AssistantSettings settings;
    private int nextChat = 1;

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
        modelSettings.addActionListener(event -> this.settings.edit(this).ifPresent(value -> {
            this.settings = value;
            showProvider();
        }));
        modelLabel.setForeground(palette.muted());
        modelLabel.setBorder(new EmptyBorder(0, 3, 0, 0));
        var header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.add(modelLabel, BorderLayout.CENTER);
        header.add(row(newChat, modelSettings), BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        showProvider();

        tabs.setOpaque(false);
        tabs.getAccessibleContext().setAccessibleName("Assistant chats");
        add(tabs, BorderLayout.CENTER);
        addChat(false);
    }

    private void showProvider() {
        modelLabel.setText(settings.provider().displayName());
        modelLabel.setToolTipText(settings.provider().connectionDescription());
    }

    private void addChat(boolean focus) {
        var chat = new AssistantPanel(project, () -> settings, tools, palette);
        String title = "Chat " + nextChat++;
        var close = iconButton(palette, "Close " + title, Glyph.REMOVE, false);
        close.setBorder(new EmptyBorder(1, 2, 1, 2));
        close.addActionListener(event -> closeChat(chat));

        tabs.addTab(title, chat);
        tabs.setTabComponentAt(tabs.indexOfComponent(chat), row(new JLabel(title), close));
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

    void close() {
        for (int index = 0; index < tabs.getTabCount(); index++) {
            Component component = tabs.getComponentAt(index);
            ((AssistantPanel) component).close();
        }
        tabs.removeAll();
    }
}
