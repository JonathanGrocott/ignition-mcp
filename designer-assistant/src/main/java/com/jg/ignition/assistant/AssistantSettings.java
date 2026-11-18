package com.jg.ignition.assistant;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
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

    Optional<AssistantSettings> edit(Component owner, ProjectTools tools) {
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
        var detected = new JComboBox<CliExecutable.Match>();
        detected.getAccessibleContext().setAccessibleName("Detected CLI installations");
        detected.setPrototypeDisplayValue(new CliExecutable.Match("", "Choose detected install…"));
        detected.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(index < 0 || !(value instanceof CliExecutable.Match match) ? "Choose detected install…"
                    : match.source() + " · " + match.executable());
                return this;
            }
        });
        var reset = new JButton("Reset to auto-detect");
        JTextArea executableStatus = note(3);
        executableStatus.getAccessibleContext().setAccessibleName("CLI path in use");
        JTextField profile = new JTextField(provider.codexProfile(), 28);
        var drafts = new EnumMap<ModelProvider.Kind, ModelProvider.Settings>(ModelProvider.Kind.class);
        drafts.putAll(sessionSettings);
        var previousKind = new AtomicReference<>(provider.kind());
        JTextArea help = note(3);
        JButton test = new JButton("Test connection");
        JTextArea testStatus = note(1);
        testStatus.getAccessibleContext().setAccessibleName("Model connection result");
        var copy = new JButton("Copy diagnostics");
        copy.setVisible(false);
        var gatewayTest = new JButton("Check Gateway tools");
        JTextArea gatewayStatus = note(2);
        gatewayStatus.getAccessibleContext().setAccessibleName("Gateway tool access result");
        gatewayStatus.setVisible(false);
        var diagnostics = new StringBuilder();

        Supplier<ModelProvider.Settings> current = () -> new ModelProvider.Settings(
            previousKind.get(), endpoint.isVisible() ? endpoint.getText().strip() : "",
            model.getText().strip(),
            key.isVisible() ? secret(key).strip() : "", header.isVisible() ? header.getText().strip() : "",
            previousKind.get().usesExecutable() ? CliExecutable.configuredPath(executable.getText()) : "",
            previousKind.get() == ModelProvider.Kind.CODEX_CLI ? profile.getText().strip() : "");
        var check = new AtomicReference<SwingWorker<Void, String>>();
        var discovery = new AtomicReference<SwingWorker<List<CliExecutable.Match>, Void>>();
        var gatewayCheck = new AtomicReference<SwingWorker<Integer, Void>>();

        JPanel form = new SettingsForm();
        int row = 0;
        field(form, row++, "Provider", kind);
        row++; // Keep the Model or Deployment field directly after Provider for API providers.
        var endpointLabel = field(form, row++, "Model URL", endpoint);
        var keyLabel = field(form, row++, "Model credential", key);
        var headerLabel = field(form, row++, "Credential header", header);
        field(form, row++, "", executableStatus);
        var advancedToggle = new JToggleButton("Advanced");
        field(form, row++, "", advancedToggle);
        var advanced = new JPanel(new GridBagLayout());
        var modelLabel = field(advanced, 0, "Model", model);
        var executableLabel = field(advanced, 1, "CLI executable", executableRow);
        executableLabel.setLabelFor(executable);
        field(advanced, 2, "", detected);
        field(advanced, 3, "", reset);
        var profileLabel = field(advanced, 4, "Trusted Codex profile", profile);
        field(form, row++, "", advanced);
        field(form, row++, "", help);
        field(form, row++, "", test);
        field(form, row++, "", testStatus);
        field(form, row++, "", copy);
        field(form, row++, "", gatewayTest);
        field(form, row, "", gatewayStatus);
        Runnable showPath = () -> {
            String path = CliExecutable.configuredPath(executable.getText());
            if (!path.isBlank()) {
                executableStatus.setText("Manual path\n" + path);
            } else if (detected.getItemCount() > 0) {
                var found = detected.getItemAt(0);
                executableStatus.setText("Auto-detect · " + found.source() + "\n" + found.executable());
            } else {
                executableStatus.setText(discovery.get() != null ? "Looking for installed CLIs…"
                    : "No installation detected. Try Test connection or browse to the executable under Advanced.");
            }
            executableStatus.setCaretPosition(0);
        };
        Runnable scan = () -> {
            cancelCheck(discovery);
            detected.removeAllItems();
            var selected = previousKind.get();
            if (!selected.usesExecutable()) {
                return;
            }
            var worker = new SwingWorker<List<CliExecutable.Match>, Void>() {
                @Override protected List<CliExecutable.Match> doInBackground() {
                    return CliExecutable.findAll(selected.command);
                }

                @Override protected void done() {
                    if (!discovery.compareAndSet(this, null)) {
                        return;
                    }
                    try {
                        detected.setModel(new DefaultComboBoxModel<>(get().toArray(CliExecutable.Match[]::new)));
                        detected.setSelectedIndex(-1);
                    } catch (ExecutionException ignored) {
                        // The user can still supply an executable path.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    detected.setEnabled(detected.getItemCount() > 0);
                    showPath.run();
                }
            };
            discovery.set(worker);
            detected.setEnabled(false);
            showPath.run();
            worker.execute();
        };
        Runnable refresh = () -> {
            var selected = (ModelProvider.Kind) kind.getSelectedItem();
            boolean cli = selected.isCli();
            boolean codex = selected == ModelProvider.Kind.CODEX_CLI;
            boolean entra = selected == ModelProvider.Kind.AZURE_ENTRA;
            boolean azure = entra || selected == ModelProvider.Kind.AZURE_OPENAI;
            modelLabel.setText(azure ? "Deployment" : "Model");
            if (modelLabel.getParent() != (cli ? advanced : form)) {
                modelLabel.getParent().remove(modelLabel);
                model.getParent().remove(model);
                if (cli) {
                    addField(advanced, 0, modelLabel, model);
                } else {
                    addField(form, 1, modelLabel, model);
                }
            }
            endpoint.setVisible(!cli);
            endpointLabel.setVisible(!cli);
            key.setVisible(!cli && !entra);
            keyLabel.setVisible(!cli && !entra);
            header.setVisible(selected == ModelProvider.Kind.OPENAI_COMPATIBLE || selected == ModelProvider.Kind.ANTHROPIC);
            headerLabel.setVisible(header.isVisible());
            executableRow.setVisible(selected.usesExecutable());
            executableLabel.setVisible(selected.usesExecutable());
            executableLabel.setText(entra ? "Azure CLI executable" : "CLI executable");
            executableStatus.setVisible(selected.usesExecutable());
            detected.setVisible(selected.usesExecutable());
            reset.setVisible(selected.usesExecutable());
            advancedToggle.setVisible(selected.usesExecutable());
            advanced.setVisible(selected.usesExecutable() && advancedToggle.isSelected());
            profile.setVisible(codex);
            profileLabel.setVisible(codex);
            help.setText(switch (selected) {
                case CODEX_CLI -> "Uses your installed Codex sign-in. Set a trusted organization profile under Advanced if needed.";
                case CLAUDE_CLI -> "Uses the sign-in configured in your installed Claude Code.";
                case KIRO_CLI -> "Uses the sign-in configured in your installed Kiro CLI.";
                case AZURE_OPENAI -> "Replace YOUR-RESOURCE with your Azure resource name. Enter the deployment name and the resource API key.";
                case AZURE_ENTRA -> "Run az login with the account that can access your deployment. Replace YOUR-RESOURCE and enter the deployment name.";
                case OLLAMA -> "Start Ollama on this workstation. Enter the exact model name shown by ollama list. A local server usually needs no credential.";
                case GEMINI -> "Enter the Gemini model ID and a Gemini API key. The URL is prefilled.";
                case BEDROCK -> "Set the AWS region in the URL. Enter the Chat Completions model ID and a Bedrock API key.";
                case OPENAI_COMPATIBLE, ANTHROPIC -> "Enter the model ID and full URL. Enter a credential or env:VARIABLE. Leave the header blank for the provider default.";
            });
            form.revalidate();
        };
        advancedToggle.addActionListener(event -> refresh.run());
        detected.addActionListener(event -> {
            if (detected.isEnabled() && detected.getSelectedItem() instanceof CliExecutable.Match match) {
                executable.setText(match.executable());
                detected.setSelectedIndex(-1);
            }
        });
        reset.addActionListener(event -> {
            executable.setText("");
            scan.run();
        });
        Runnable clearTest = () -> {
            cancelCheck(check);
            test.setEnabled(true);
            testStatus.setText("");
            testStatus.setRows(1);
            copy.setVisible(false);
            diagnostics.setLength(0);
        };
        var edited = new DocumentListener() {
            private void changed() {
                showPath.run();
                clearTest.run();
            }

            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
        };
        for (JTextField input : List.of(executable, model, profile, endpoint, key, header)) {
            input.getDocument().addDocumentListener(edited);
        }
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
            clearTest.run();
            refresh.run();
            scan.run();
        });
        browse.addActionListener(event -> {
            var chooser = new JFileChooser();
            chooser.setDialogTitle("Select the CLI executable");
            String path = CliExecutable.configuredPath(executable.getText());
            if (!path.isBlank()) {
                chooser.setSelectedFile(new java.io.File(path));
            }
            if (chooser.showOpenDialog(form) == JFileChooser.APPROVE_OPTION) {
                executable.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        test.addActionListener(event -> {
            ModelProvider.Settings candidate = current.get();
            clearTest.run();
            test.setEnabled(false);
            diagnostics.append(candidate.kind().label).append('\n');
            if (candidate.kind().usesExecutable()) {
                diagnostics.append(candidate.cliExecutable().isBlank() ? "Executable selection: automatic\n"
                    : "Executable selection: manual\n");
            }
            testStatus.setRows(candidate.kind().isCli() ? 9 : 4);
            testStatus.setText("Starting connection check…");
            var worker = new SwingWorker<Void, String>() {
                @Override protected Void doInBackground() throws Exception {
                    ModelProvider.testConnection(candidate, this::publish);
                    return null;
                }

                @Override protected void process(List<String> updates) {
                    if (check.get() == this) {
                        updates.forEach(message -> diagnostics.append(message).append('\n'));
                        testStatus.setText(diagnostics.toString());
                    }
                }

                @Override protected void done() {
                    if (!check.compareAndSet(this, null)) {
                        return;
                    }
                    try {
                        get();
                        diagnostics.append("Model connection verified.");
                    } catch (ExecutionException e) {
                        diagnostics.append(e.getCause() instanceof IOException failure
                            && !(failure instanceof java.nio.file.FileSystemException)
                            ? failure.getMessage() : "The connection check failed. Check the provider settings.");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        diagnostics.append("The connection check was interrupted.");
                    } finally {
                        testStatus.setText(diagnostics.toString());
                        testStatus.setCaretPosition(0);
                        copy.setVisible(candidate.kind().isCli());
                        test.setEnabled(true);
                        reveal(copy.isVisible() ? copy : testStatus);
                    }
                }
            };
            check.set(worker);
            worker.execute();
            reveal(testStatus);
        });
        copy.addActionListener(event -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
                    "Designer Assistant · " + (CliExecutable.windows() ? "Windows" : System.getProperty("os.name")) + "\n"
                    + diagnostics), null);
            } catch (IllegalStateException e) {
                JOptionPane.showMessageDialog(form, "The clipboard is busy. Try copying again.");
            }
        });
        gatewayTest.setEnabled(tools != null);
        gatewayTest.addActionListener(event -> {
            gatewayStatus.setVisible(true);
            gatewayStatus.setText("Checking this Designer session’s access to Gateway tools…");
            gatewayTest.setEnabled(false);
            var worker = new SwingWorker<Integer, Void>() {
                @Override protected Integer doInBackground() throws Exception {
                    return tools.catalog().size();
                }

                @Override protected void done() {
                    if (!gatewayCheck.compareAndSet(this, null)) {
                        return;
                    }
                    try {
                        int count = get();
                        gatewayStatus.setText(count == 0 ? "Gateway connected. No tools granted to this Designer user."
                            : "Gateway connected. Tools available: " + count + ".");
                    } catch (ExecutionException e) {
                        gatewayStatus.setText("Gateway tools are unavailable. Check the Ignition MCP module and Designer user grants.");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        gatewayStatus.setText("The Gateway tools check was interrupted.");
                    } finally {
                        gatewayTest.setEnabled(true);
                        reveal(gatewayStatus);
                    }
                }
            };
            gatewayCheck.set(worker);
            worker.execute();
            reveal(gatewayStatus);
        });
        refresh.run();
        scan.run();
        if (!showDialog(owner, form, () -> {
            cancelCheck(check);
            cancelCheck(discovery);
            cancelCheck(gatewayCheck);
        })) {
            return Optional.empty();
        }
        var updated = current.get();
        drafts.put(updated.kind(), updated);
        drafts.values().forEach(AssistantSettings::persist);
        PREFS.put("provider", updated.kind().name());
        return Optional.of(new AssistantSettings(updated, Map.copyOf(drafts)));
    }

    private static <T extends SwingWorker<?, ?>> void cancelCheck(AtomicReference<T> check) {
        T worker = check.getAndSet(null);
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
        JLabel label = new JLabel(caption);
        label.setLabelFor(input);
        addField(panel, row, label, input);
        return label;
    }

    private static void addField(JPanel panel, int row, JLabel label, JComponent input) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.insets = new Insets(4, 6, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridx = 0;
        if (label.getText().isEmpty()) {
            constraints.gridwidth = 2;
        } else {
            panel.add(label, constraints);
            constraints.gridx = 1;
        }
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        panel.add(input, constraints);
    }

    private static JTextArea note(int rows) {
        var note = new JTextArea(rows, 28);
        note.setEditable(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setOpaque(false);
        note.setFont(UIManager.getFont("Label.font"));
        return note;
    }

    private static void reveal(JComponent component) {
        SwingUtilities.invokeLater(() -> component.scrollRectToVisible(new Rectangle(component.getSize())));
    }

    private static boolean showDialog(Component owner, JPanel form, Runnable onClose) {
        var screen = owner.getGraphicsConfiguration();
        var bounds = screen.getBounds();
        var insets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        var usable = new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
            bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
        var scroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        int contentHeight = Math.max(320, form.getPreferredSize().height);
        scroll.setPreferredSize(new Dimension(Math.min(610, usable.width * 9 / 10),
            Math.min(contentHeight, usable.height * 7 / 10)));
        var pane = new JOptionPane(scroll, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        var dialog = pane.createDialog(owner, "Designer Assistant Settings");
        dialog.setResizable(true);
        dialog.setMinimumSize(new Dimension(Math.min(475, usable.width), Math.min(235, usable.height)));
        dialog.setSize(Math.min(dialog.getWidth(), usable.width), Math.min(dialog.getHeight(), usable.height));
        dialog.setLocation(
            Math.max(usable.x, Math.min(dialog.getX(), usable.x + usable.width - dialog.getWidth())),
            Math.max(usable.y, Math.min(dialog.getY(), usable.y + usable.height - dialog.getHeight())));
        try {
            dialog.setVisible(true);
            return Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue());
        } finally {
            onClose.run();
            dialog.dispose();
        }
    }

    private static final class SettingsForm extends JPanel implements Scrollable {
        SettingsForm() {
            super(new GridBagLayout());
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(18, visible.height - 18);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
