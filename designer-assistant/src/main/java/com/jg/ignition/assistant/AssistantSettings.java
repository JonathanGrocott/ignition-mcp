package com.jg.ignition.assistant;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

record AssistantSettings(ModelProvider.Settings provider, Map<ModelProvider.Kind, ModelProvider.Settings> sessionSettings) {
    private static final Preferences PREFS = Preferences.userRoot().node("com/jg/ignition/designer-assistant");

    AssistantSettings(ModelProvider.Settings provider) {
        this(provider, Map.of());
    }

    static AssistantSettings saved() {
        ModelProvider.Kind kind;
        try {
            kind = ModelProvider.Kind.valueOf(PREFS.get("provider", "CODEX_CLI"));
        } catch (IllegalArgumentException e) {
            kind = ModelProvider.Kind.CODEX_CLI;
        }
        return new AssistantSettings(load(kind));
    }

    private static ModelProvider.Settings load(ModelProvider.Kind kind) {
        var preferences = providerPreferences(kind);
        boolean legacySelected = kind.name().equals(PREFS.get("provider", "CODEX_CLI"));
        return new ModelProvider.Settings(kind,
            preferences.get("endpoint", legacySelected && !kind.isCli()
                ? PREFS.get("modelEndpoint", kind.defaultEndpoint) : kind.defaultEndpoint),
            preferences.get("model", legacySelected ? PREFS.get("model", "") : ""),
            preferences.get("credentialReference", ""),
            preferences.get("credentialHeader", legacySelected ? PREFS.get("credentialHeader", "") : ""),
            preferences.get("executable", kind.usesExecutable() ? PREFS.get(kind.command, "") : ""),
            preferences.get("profile", kind == ModelProvider.Kind.CODEX_CLI ? PREFS.get("profile", "") : ""));
    }

    private static Preferences providerPreferences(ModelProvider.Kind kind) {
        return PREFS.node("providers").node(kind.name());
    }

    private static void persist(ModelProvider.Settings settings) {
        var preferences = providerPreferences(settings.kind());
        var endpoint = settings.kind().defaultEndpoint;
        try {
            var uri = HttpJson.endpoint(settings.endpoint());
            if (uri.getRawQuery() == null && !settings.kind().isCli()) {
                endpoint = uri.toString();
            }
        } catch (IOException ignored) {
            // A blank or invalid URL should reopen with the provider's default.
        }
        preferences.put("endpoint", endpoint);
        preferences.put("model", settings.model());
        preferences.put("credentialHeader", settings.credentialHeader());
        preferences.put("executable", settings.cliExecutable());
        preferences.put("profile", settings.codexProfile());
        if (ModelProvider.environmentReference(settings.credential())) {
            preferences.put("credentialReference", settings.credential());
        } else {
            preferences.remove("credentialReference");
        }
    }

    Optional<AssistantSettings> edit(Component owner) {
        JComboBox<ModelProvider.Kind> kind = new JComboBox<>(ModelProvider.Kind.values());
        kind.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof ModelProvider.Kind item) {
                    setText(item.label);
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
        executable.getAccessibleContext().setAccessibleName("CLI executable");
        JButton browse = new JButton("Browse…");
        var executableRow = new JPanel(new BorderLayout(5, 0));
        executableRow.add(executable, BorderLayout.CENTER);
        executableRow.add(browse, BorderLayout.EAST);
        JTextField profile = new JTextField(provider.codexProfile(), 28);
        var drafts = new EnumMap<ModelProvider.Kind, ModelProvider.Settings>(ModelProvider.Kind.class);
        drafts.putAll(sessionSettings);
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
            previousKind.get(), endpoint.isVisible() ? endpoint.getText().strip() : "", model.getText().strip(),
            key.isVisible() ? secret(key).strip() : "", header.isVisible() ? header.getText().strip() : "",
            executableRow.isVisible() ? executable.getText().strip() : "", profile.isVisible() ? profile.getText().strip() : "");
        var check = new AtomicReference<SwingWorker<Void, Void>>();

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        field(form, row++, "Provider", kind);
        var modelLabel = field(form, row++, "Model", model);
        var endpointLabel = field(form, row++, "Model URL", endpoint);
        var keyLabel = field(form, row++, "Model credential", key);
        var headerLabel = field(form, row++, "Credential header", header);
        var executableLabel = field(form, row++, "CLI executable", executableRow);
        executableLabel.setLabelFor(executable);
        var profileLabel = field(form, row++, "Trusted Codex profile", profile);
        field(form, row++, "", help);
        field(form, row++, "", test);
        field(form, row, "", testStatus);
        Runnable refresh = () -> {
            var selected = (ModelProvider.Kind) kind.getSelectedItem();
            boolean cli = selected.isCli();
            boolean codex = selected == ModelProvider.Kind.CODEX_CLI;
            boolean entra = selected == ModelProvider.Kind.AZURE_ENTRA;
            boolean azure = entra || selected == ModelProvider.Kind.AZURE_OPENAI;
            modelLabel.setText(azure ? "Deployment" : "Model");
            endpoint.setVisible(!cli);
            endpointLabel.setVisible(!cli);
            key.setVisible(!cli && !entra);
            keyLabel.setVisible(!cli && !entra);
            header.setVisible(selected == ModelProvider.Kind.OPENAI_COMPATIBLE || selected == ModelProvider.Kind.ANTHROPIC);
            headerLabel.setVisible(header.isVisible());
            executableRow.setVisible(selected.usesExecutable());
            executableLabel.setVisible(selected.usesExecutable());
            executableLabel.setText(entra ? "Azure CLI executable" : "CLI executable");
            if (selected.usesExecutable()) {
                executable.setToolTipText("Leave blank to use automatic lookup. Default: "
                    + CliExecutable.resolve("", selected.command));
            }
            profile.setVisible(codex);
            profileLabel.setVisible(codex);
            help.setText(switch (selected) {
                case CODEX_CLI -> "Use the installed Codex sign-in. Leave the model and executable blank to use the defaults. Enter a trusted organization profile if needed.";
                case CLAUDE_CLI -> "Use the authentication configured in the installed Claude Code. Leave the model and executable blank to use the defaults.";
                case KIRO_CLI -> "Run kiro-cli whoami to check your sign-in. Leave the model and executable blank to use the defaults.";
                case AZURE_OPENAI -> "Replace YOUR-RESOURCE with your Azure resource name. Enter the deployment name and the resource API key.";
                case AZURE_ENTRA -> "Run az login with the account that can access your deployment. Replace YOUR-RESOURCE and enter the deployment name.";
                case OLLAMA -> "Start Ollama on this workstation. Enter the exact model name shown by ollama list. A local server usually needs no credential.";
                case GEMINI -> "Enter the Gemini model ID and a Gemini API key. The URL is prefilled.";
                case BEDROCK -> "Set the AWS region in the URL. Enter the Chat Completions model ID and a Bedrock API key.";
                case OPENAI_COMPATIBLE, ANTHROPIC -> "Enter the model ID and full URL. Enter a credential or env:VARIABLE. Leave the header blank for the provider default.";
            });
        };
        kind.addActionListener(event -> {
            var selected = (ModelProvider.Kind) kind.getSelectedItem();
            drafts.put(previousKind.get(), current.get());
            previousKind.set(selected);
            var settings = drafts.computeIfAbsent(selected, AssistantSettings::load);
            executable.setText(settings.cliExecutable());
            endpoint.setText(settings.endpoint());
            model.setText(settings.model());
            key.setText(settings.credential());
            header.setText(settings.credentialHeader());
            profile.setText(settings.codexProfile());
            cancelCheck(check);
            test.setEnabled(true);
            testStatus.setText("");
            refresh.run();
        });
        browse.addActionListener(event -> {
            var chooser = new JFileChooser();
            chooser.setDialogTitle("Select the CLI executable");
            if (!executable.getText().isBlank()) {
                chooser.setSelectedFile(new java.io.File(executable.getText()));
            }
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                executable.setText(chooser.getSelectedFile().getAbsolutePath());
            }
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
        cancelCheck(check);
        if (result != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }
        var updated = current.get();
        drafts.put(updated.kind(), updated);
        drafts.values().forEach(AssistantSettings::persist);
        PREFS.put("provider", updated.kind().name());
        return Optional.of(new AssistantSettings(updated, Map.copyOf(drafts)));
    }

    private static void cancelCheck(AtomicReference<SwingWorker<Void, Void>> check) {
        SwingWorker<Void, Void> worker = check.getAndSet(null);
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
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
