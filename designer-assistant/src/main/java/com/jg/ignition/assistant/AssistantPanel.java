package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.jg.ignition.assistant.AssistantJson.JSON;

final class AssistantPanel extends JPanel {
    private static final int MAX_PROMPT = 4000;
    private static final int HISTORY_MESSAGES = 12;
    private final Supplier<String> projectName;
    private final Supplier<String> gatewayUrl;
    private final Predicate<String> designerViewExists;
    private final List<ModelProvider.Message> history = new ArrayList<>();
    private final JTextArea transcript = textArea(false);
    private final JTextArea prompt = textArea(true);
    private final JTextArea preview = textArea(false);
    private final JTextField path = new JTextField("Assistant/NewView");
    private final JLabel status = new JLabel("Choose a model in Settings or use your installed Codex CLI.");
    private final JButton settingsButton = new JButton("Settings");
    private final JButton clear = new JButton("New chat");
    private final JButton send = new JButton("Send");
    private final JButton stage = new JButton("Preview view");
    private final JButton apply = new JButton("Apply new view");
    private final JButton receipt = new JButton("Check status");
    private final JTabbedPane tabs = new JTabbedPane();
    private final Timer expiration = new Timer(1000, event -> expirePlan());

    private AssistantSettings settings = AssistantSettings.saved();
    private JsonNode proposedView;
    private IgnitionMcpClient client;
    private IgnitionMcpClient.Plan plan;
    private String previewGateway;
    private SwingWorker<?, ?> worker;
    private boolean applyAttempted;
    private boolean closed;

    AssistantPanel(Supplier<String> projectName, Supplier<String> gatewayUrl,
                   Predicate<String> designerViewExists) {
        super(new BorderLayout(0, 6));
        this.projectName = projectName;
        this.gatewayUrl = gatewayUrl;
        this.designerViewExists = designerViewExists;
        setBorder(new EmptyBorder(8, 8, 8, 8));
        add(buttons(settingsButton, clear), BorderLayout.NORTH);

        JPanel chat = new JPanel(new BorderLayout(0, 6));
        chat.add(new JScrollPane(transcript), BorderLayout.CENTER);
        JPanel compose = new JPanel(new BorderLayout(4, 4));
        prompt.setRows(4);
        prompt.getAccessibleContext().setAccessibleName("Message for Designer Assistant");
        compose.add(new JScrollPane(prompt), BorderLayout.CENTER);
        compose.add(buttons(send), BorderLayout.SOUTH);
        chat.add(compose, BorderLayout.SOUTH);

        JPanel review = new JPanel(new BorderLayout(4, 6));
        JLabel pathLabel = new JLabel("New view path");
        pathLabel.setLabelFor(path);
        JPanel target = new JPanel(new BorderLayout(6, 0));
        target.add(pathLabel, BorderLayout.WEST);
        target.add(path, BorderLayout.CENTER);
        review.add(target, BorderLayout.NORTH);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        review.add(new JScrollPane(preview), BorderLayout.CENTER);
        review.add(buttons(stage, apply, receipt), BorderLayout.SOUTH);
        tabs.addTab("Chat", chat);
        tabs.addTab("View preview", review);
        add(tabs, BorderLayout.CENTER);
        status.setPreferredSize(new Dimension(390, 23));
        add(status, BorderLayout.SOUTH);

        settingsButton.addActionListener(event -> settings.edit(this).ifPresent(value -> {
            if (!settings.provider().equals(value.provider())) {
                history.clear();
                transcript.setText("");
                proposedView = null;
            }
            settings = value;
            resetReview();
            setStatus("Settings updated.");
        }));
        clear.addActionListener(event -> {
            history.clear();
            transcript.setText("");
            proposedView = null;
            resetReview();
            setStatus("New chat started.");
        });
        send.addActionListener(event -> sendMessage());
        stage.addActionListener(event -> preparePreview());
        apply.addActionListener(event -> applyPreview());
        receipt.addActionListener(event -> checkReceipt());
        path.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { resetReview(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { resetReview(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { resetReview(); }
        });
        append("Assistant", "Ask a question or describe a new Perspective view. For example, “Create a pump overview with sections for status and maintenance.”");
        expiration.start();
        updateButtons();
    }

    private void sendMessage() {
        String question = prompt.getText().strip();
        if (question.isEmpty() || question.length() > MAX_PROMPT) {
            setStatus("Enter a message of 1–4,000 characters.");
            return;
        }
        String project = projectName.get();
        if (project == null || project.isBlank()) {
            setStatus("Open an Ignition project first.");
            return;
        }
        List<ModelProvider.Message> input = new ArrayList<>(history);
        input.add(new ModelProvider.Message("user", question));
        var provider = settings.provider();
        var system = "You are an assistant for Ignition Designer 8.3.9. The active project is "
            + JSON.getNodeFactory().textNode(project) + ".\n" + PerspectiveDraft.SPEC;
        append("You", question);
        prompt.setText("");
        run("Waiting for the model…", () -> parseReply(ModelProvider.complete(provider, input, system)), result -> {
            remember(input, result);
            append("Assistant", result.text());
            if (result.view() != null) {
                proposedView = result.view();
                resetReview();
                tabs.setSelectedIndex(1);
                setStatus("Choose a new view path and click Preview view.");
            } else {
                setStatus("Reply received.");
            }
        });
    }

    private static ModelReply parseReply(String raw) throws IOException {
        if (raw == null || raw.length() > 30_000) {
            throw new IOException("The model response exceeded the draft limit.");
        }
        String value = raw.strip();
        if (value.startsWith("```") && value.endsWith("```")) {
            int newline = value.indexOf('\n');
            if (newline > 0 && newline < value.length() - 3) {
                value = value.substring(newline + 1, value.length() - 3).strip();
            }
        }
        JsonNode body = JSON.readTree(value);
        if (body == null || !body.isObject() || !body.has("view")) {
            throw new IOException("The model did not return the expected reply and view fields.");
        }
        String answer = PerspectiveDraft.text(body, "reply", 2000, false);
        JsonNode view = body.get("view");
        if (!view.isNull()) {
            PerspectiveDraft.create("Draft", view);
        }
        return new ModelReply(answer, view.isNull() ? null : view, value);
    }

    private void remember(List<ModelProvider.Message> input, ModelReply result) {
        history.clear();
        history.addAll(input);
        history.add(new ModelProvider.Message("assistant", result.raw()));
        if (history.size() > HISTORY_MESSAGES) {
            history.subList(0, history.size() - HISTORY_MESSAGES).clear();
        }
    }

    private void preparePreview() {
        String project = projectName.get();
        if (project == null || project.isBlank()) {
            setStatus("Open an Ignition project first.");
            return;
        }
        String gateway = gatewayUrl.get();
        resetReview();
        try {
            var draft = PerspectiveDraft.create(path.getText().strip(), proposedView);
            if (designerViewExists.test(draft.path())) {
                setStatus("That view path already exists in the Designer. Choose a new path.");
                return;
            }
            var newClient = new IgnitionMcpClient(settings.mcpEndpoint(gateway), settings.gatewayCredential());
            preview.setText(draft.viewJson());
            run("Requesting the Gateway preview…", () -> newClient.preview(project, draft), staged -> {
                client = newClient;
                plan = staged;
                previewGateway = gateway;
                var time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(staged.expiresAt()));
                showPreview("Create " + staged.project() + "/" + staged.draft().path()
                    + "\nPreview expires at " + time + ".\n\n", staged.response(), staged.draft());
                setStatus(staged.writesEnabled() ? "Preview ready. Review the view JSON before applying."
                    : "Preview ready. Gateway MCP writes are currently disabled.");
            });
        } catch (IOException e) {
            setStatus(e.getMessage());
        }
    }

    private void applyPreview() {
        var active = plan;
        if (active == null || active.expired() || !active.project().equals(projectName.get())
                || !java.util.Objects.equals(previewGateway, gatewayUrl.get())) {
            resetReview();
            setStatus("The project, Gateway, or preview changed. Preview again.");
            return;
        }
        if (designerViewExists.test(active.draft().path())) {
            resetReview();
            setStatus("That view path now exists in the Designer. Choose a new path.");
            return;
        }
        IgnitionMcpClient mcp = client;
        applyAttempted = true;
        run("Applying the reviewed view…", () -> mcp.apply(active), response -> {
            showPreview("Gateway receipt\n\n", response, active.draft());
            if (response.path("verified").asBoolean(false) && response.path("committed").asBoolean(false)) {
                setStatus("View created and verified. Refresh the project tree to open it.");
            } else {
                setStatus("Check the Gateway receipt for the apply result.");
            }
        }, message -> setStatus(message + " Check status before preparing another preview."));
    }

    private void checkReceipt() {
        var active = plan;
        var mcp = client;
        if (active == null || mcp == null) {
            return;
        }
        run("Reading the Gateway receipt…", () -> mcp.status(active), response -> {
            showPreview("Gateway receipt\n\n", response, active.draft());
            setStatus("Gateway status: " + response.path("state").asText("unknown"));
        });
    }

    private void showPreview(String heading, JsonNode gatewayResponse, PerspectiveDraft draft) {
        preview.setText(heading + "View JSON\n" + draft.viewJson() + "\n\nGateway response\n"
            + gatewayResponse.toPrettyString());
        preview.setCaretPosition(0);
    }

    private void resetReview() {
        plan = null;
        client = null;
        previewGateway = null;
        applyAttempted = false;
        preview.setText(proposedView == null ? "Ask for a new view in Chat."
            : "Draft ready. Preview checks the selected path against the connected Gateway.");
        updateButtons();
    }

    private void expirePlan() {
        if (worker == null && plan != null && plan.expired()) {
            updateButtons();
            setStatus("The preview expired. Preview again to obtain a new plan.");
        }
    }

    private void append(String speaker, String text) {
        transcript.append(speaker + "\n" + text + "\n\n");
        int excess = transcript.getDocument().getLength() - 48_000;
        if (excess > 0) {
            transcript.replaceRange("", 0, excess);
        }
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    private void updateButtons() {
        boolean idle = !closed && worker == null;
        settingsButton.setEnabled(idle);
        clear.setEnabled(idle);
        send.setEnabled(idle);
        path.setEnabled(idle);
        stage.setEnabled(idle && proposedView != null);
        boolean valid = plan != null && !plan.expired();
        apply.setEnabled(idle && valid && plan.writesEnabled() && !applyAttempted);
        receipt.setEnabled(idle && valid && applyAttempted);
    }

    private void setStatus(String text) {
        status.setText(text.length() > 63 ? text.substring(0, 60) + "…" : text);
        status.setToolTipText(text);
        status.getAccessibleContext().setAccessibleDescription(text);
    }

    private <T> void run(String activity, Callable<T> job, Consumer<T> success) {
        run(activity, job, success, message -> {
            append("Assistant", message);
            setStatus(message);
        });
    }

    private <T> void run(String activity, Callable<T> job, Consumer<T> success, Consumer<String> failure) {
        if (worker != null || closed) {
            return;
        }
        worker = new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return job.call(); }
            @Override protected void done() {
                worker = null;
                if (closed) {
                    return;
                }
                try {
                    success.accept(get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    failure.accept(cause instanceof IOException && cause.getMessage() != null
                        ? cause.getMessage() : "Request failed. Check the provider or Gateway settings.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.accept("Request interrupted.");
                } finally {
                    updateButtons();
                }
            }
        };
        setStatus(activity);
        updateButtons();
        worker.execute();
    }

    void close() {
        closed = true;
        expiration.stop();
        if (worker != null) {
            worker.cancel(true);
        }
        history.clear();
        settings = null;
        client = null;
        updateButtons();
    }

    private static JTextArea textArea(boolean editable) {
        JTextArea area = new JTextArea();
        area.setEditable(editable);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setMargin(new java.awt.Insets(6, 6, 6, 6));
        return area;
    }

    private static JPanel buttons(JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 5, 0));
        for (JButton button : buttons) {
            row.add(button);
        }
        return row;
    }

    private record ModelReply(String text, JsonNode view, String raw) {}
}
