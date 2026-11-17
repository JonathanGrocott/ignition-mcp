package com.jg.ignition.assistant;

import javax.swing.*;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
            PREFS.get("credentialHeader", ""), executablePath(kind), PREFS.get("profile", "")));
    }

    private static String defaultEndpoint(ModelProvider.Kind kind) {
        return switch (kind) {
            case ANTHROPIC -> ANTHROPIC;
            case OPENAI_COMPATIBLE -> OPENAI;
            case CODEX_CLI, CLAUDE_CLI -> "";
        };
    }

    private static String executablePath(ModelProvider.Kind kind) {
        return kind.isCli() ? PREFS.get(executableKey(kind), "") : "";
    }

    private static String executableKey(ModelProvider.Kind kind) {
        return kind == ModelProvider.Kind.CLAUDE_CLI ? "claude" : "codex";
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
                        case CLAUDE_CLI -> "Claude Code CLI (installed)";
                        case OPENAI_COMPATIBLE -> "OpenAI / compatible API";
                        case ANTHROPIC -> "Anthropic API";
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
        JTextField executable = new JTextField(provider.cliExecutable(), 28);
        JTextField profile = new JTextField(provider.codexProfile(), 28);
        var executableDrafts = new java.util.EnumMap<ModelProvider.Kind, String>(ModelProvider.Kind.class);
        var previousKind = new AtomicReference<>(provider.kind());
        JTextArea help = new JTextArea(3, 38);
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setOpaque(false);
        help.setFont(UIManager.getFont("Label.font"));
        JButton test = new JButton("Test connection");
        JTextArea testStatus = new JTextArea(2, 29);
        testStatus.setEditable(false);
        testStatus.setLineWrap(true);
        testStatus.setWrapStyleWord(true);
        testStatus.setOpaque(false);
        testStatus.setFont(UIManager.getFont("Label.font"));
        testStatus.getAccessibleContext().setAccessibleName("Model connection result");

        Supplier<ModelProvider.Settings> current = () -> new ModelProvider.Settings(
            (ModelProvider.Kind) kind.getSelectedItem(), endpoint.getText().strip(), model.getText().strip(),
            secret(key), header.getText().strip(), executable.getText().strip(), profile.getText().strip());
        var check = new AtomicReference<SwingWorker<Void, Void>>();

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        field(form, row++, "Provider", kind);
        field(form, row++, "Model", model);
        var endpointLabel = field(form, row++, "Model URL", endpoint);
        var keyLabel = field(form, row++, "Model credential", key);
        var headerLabel = field(form, row++, "Credential header", header);
        var executableLabel = field(form, row++, "CLI executable", executable);
        var profileLabel = field(form, row++, "Trusted Codex profile", profile);
        field(form, row++, "", help);
        field(form, row++, "", test);
        field(form, row, "", testStatus);
        Runnable refresh = () -> {
            var selected = (ModelProvider.Kind) kind.getSelectedItem();
            boolean cli = selected.isCli();
            boolean codex = selected == ModelProvider.Kind.CODEX_CLI;
            endpoint.setVisible(!cli);
            endpointLabel.setVisible(!cli);
            key.setVisible(!cli);
            keyLabel.setVisible(!cli);
            header.setVisible(!cli);
            headerLabel.setVisible(!cli);
            executable.setVisible(cli);
            executableLabel.setVisible(cli);
            profile.setVisible(codex);
            profileLabel.setVisible(codex);
            help.setText(switch (selected) {
                case CODEX_CLI -> "Use the installed Codex sign-in. Leave the model and executable blank to use the defaults. Enter a trusted organization profile if needed.";
                case CLAUDE_CLI -> "Use the authentication configured in the installed Claude Code. Leave the model and executable blank to use the defaults.";
                case OPENAI_COMPATIBLE, ANTHROPIC -> "Enter the model ID and full URL. Enter a credential or env:VARIABLE. Leave the header blank for the provider default.";
            });
        };
        kind.addActionListener(event -> {
            var selected = (ModelProvider.Kind) kind.getSelectedItem();
            executableDrafts.put(previousKind.getAndSet(selected), executable.getText());
            executable.setText(executableDrafts.getOrDefault(selected, executablePath(selected)));
            endpoint.setText(defaultEndpoint(selected));
            model.setText("");
            key.setText("");
            header.setText("");
            testStatus.setText("");
            refresh.run();
        });
        test.addActionListener(event -> {
            ModelProvider.Settings candidate = current.get();
            test.setEnabled(false);
            testStatus.setText("Sending a connection check with no project data…");
            var worker = new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    ModelProvider.checkConnection(candidate);
                    return null;
                }

                @Override protected void done() {
                    if (isCancelled()) {
                        return;
                    }
                    try {
                        get();
                        testStatus.setText("Connection verified with " + candidate.displayName() + ".");
                    } catch (ExecutionException e) {
                        testStatus.setText(e.getCause() instanceof IOException
                            ? e.getCause().getMessage() : "The connection check failed.");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        testStatus.setText("The connection check was interrupted.");
                    } finally {
                        test.setEnabled(true);
                    }
                }
            };
            check.set(worker);
            worker.execute();
        });
        refresh.run();
        int result = JOptionPane.showConfirmDialog(owner, form, "Designer Assistant Settings",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (check.get() != null && !check.get().isDone()) {
            check.get().cancel(true);
        }
        if (result != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }
        var updated = current.get();
        ModelProvider.Kind selected = updated.kind();
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
        if (selected.isCli()) {
            PREFS.put(executableKey(selected), updated.cliExecutable());
        }
        if (selected == ModelProvider.Kind.CODEX_CLI) {
            PREFS.put("profile", updated.codexProfile());
        }
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

    private static JLabel field(JPanel panel, int row, String caption, JComponent input) {
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
        return label;
    }
}
