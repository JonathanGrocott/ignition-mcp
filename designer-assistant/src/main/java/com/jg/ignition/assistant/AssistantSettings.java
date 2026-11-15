package com.jg.ignition.assistant;

import javax.swing.*;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.prefs.Preferences;

record AssistantSettings(ModelProvider.Settings provider) {
    private static final String OPENAI = "https://api.openai.com/v1/chat/completions";
    private static final String ANTHROPIC = "https://api.anthropic.com/v1/messages";
    private static final Preferences PREFS = Preferences.userRoot().node("com/jg/ignition/designer-assistant");

    static AssistantSettings saved() {
        ModelProvider.Kind kind;
        try {
            kind = ModelProvider.Kind.valueOf(PREFS.get("provider", "CODEX_CLI"));
        } catch (IllegalArgumentException e) {
            kind = ModelProvider.Kind.CODEX_CLI;
        }
        return new AssistantSettings(new ModelProvider.Settings(kind,
            PREFS.get("modelEndpoint", defaultEndpoint(kind)), PREFS.get("model", ""), "",
            PREFS.get("credentialHeader", ""), PREFS.get("codex", ""), PREFS.get("profile", "")));
    }

    private static String defaultEndpoint(ModelProvider.Kind kind) {
        return kind == ModelProvider.Kind.ANTHROPIC ? ANTHROPIC : OPENAI;
    }

    Optional<AssistantSettings> edit(Component owner) {
        JComboBox<ModelProvider.Kind> kind = new JComboBox<>(ModelProvider.Kind.values());
        kind.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof ModelProvider.Kind item) {
                    setText(switch (item) {
                        case CODEX_CLI -> "Codex CLI (existing sign-in)";
                        case OPENAI_COMPATIBLE -> "OpenAI-compatible HTTP";
                        case ANTHROPIC -> "Anthropic Messages";
                    });
                }
                return this;
            }
        });
        kind.setSelectedItem(provider.kind());
        JTextField endpoint = new JTextField(provider.endpoint(), 28);
        JTextField model = new JTextField(provider.model(), 28);
        JPasswordField key = new JPasswordField(provider.credential(), 28);
        JTextField header = new JTextField(provider.credentialHeader(), 28);
        JTextField executable = new JTextField(provider.codexExecutable(), 28);
        JTextField profile = new JTextField(provider.codexProfile(), 28);
        JTextArea help = new JTextArea(3, 38);
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setOpaque(false);
        help.setFont(UIManager.getFont("Label.font"));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        field(form, row++, "Provider", kind);
        field(form, row++, "Model", model);
        field(form, row++, "Model URL", endpoint);
        field(form, row++, "Model credential", key);
        field(form, row++, "Credential header", header);
        field(form, row++, "Codex executable", executable);
        field(form, row++, "Trusted Codex profile", profile);
        field(form, row, "", help);
        Runnable refresh = () -> {
            boolean cli = kind.getSelectedItem() == ModelProvider.Kind.CODEX_CLI;
            endpoint.setEnabled(!cli);
            key.setEnabled(!cli);
            header.setEnabled(!cli);
            executable.setEnabled(cli);
            profile.setEnabled(cli);
            help.setText(cli
                ? "Use the installed CLI's sign-in. Leave profile blank to use default settings, or enter a trusted profile for your organization."
                : "Use the full model URL. Credentials can be entered here or as env:VARIABLE. Leave the header blank for the provider default.");
        };
        kind.addActionListener(event -> {
            String current = endpoint.getText().strip();
            if (current.isEmpty() || current.equals(OPENAI) || current.equals(ANTHROPIC)) {
                endpoint.setText(defaultEndpoint((ModelProvider.Kind) kind.getSelectedItem()));
            }
            refresh.run();
        });
        refresh.run();
        if (JOptionPane.showConfirmDialog(owner, form, "Designer Assistant Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }
        ModelProvider.Kind selected = (ModelProvider.Kind) kind.getSelectedItem();
        var updated = new ModelProvider.Settings(selected, endpoint.getText().strip(), model.getText().strip(),
            secret(key), header.getText().strip(), executable.getText().strip(), profile.getText().strip());
        try {
            PREFS.put("provider", selected.name());
            // URLs may contain query credentials. Persist only endpoints without a query or embedded user info.
            var uri = HttpJson.endpoint(updated.endpoint());
            PREFS.put("modelEndpoint", uri.getQuery() == null ? uri.toString() : defaultEndpoint(selected));
        } catch (IOException | IllegalArgumentException e) {
            PREFS.remove("modelEndpoint");
        }
        PREFS.put("model", updated.model());
        PREFS.put("credentialHeader", updated.credentialHeader());
        PREFS.put("codex", updated.codexExecutable());
        PREFS.put("profile", updated.codexProfile());
        return Optional.of(new AssistantSettings(updated));
    }

    private static String secret(JPasswordField field) {
        char[] value = field.getPassword();
        try {
            return new String(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    @Override public String toString() {
        return "AssistantSettings[provider=" + provider.kind() + "]";
    }

    private static void field(JPanel panel, int row, String caption, JComponent input) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.insets = new Insets(4, 6, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;
        JLabel label = new JLabel(caption);
        label.setLabelFor(input);
        panel.add(label, constraints);
        constraints.gridx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        panel.add(input, constraints);
    }
}
