package com.jg.ignition.assistant;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.jg.ignition.assistant.AssistantStyle.*;

final class AssistantPanel extends JPanel {
    private static final int MAX_PROMPT = 4_000;
    private static final int MAX_HISTORY = 18;
    private static final int MAX_CARDS = 60;

    private final AgentProject project;
    private final AssistantAgent agent;
    private final Palette palette;
    private final JButton send;
    private final JButton attach;
    private final JButton removeAttachment;
    private final JLabel attachmentLabel = new JLabel();
    private final JPanel attachmentRow = new JPanel(new BorderLayout(6, 0));
    private final JComboBox<AssistantAgent.Mode> permission = new JComboBox<>(AssistantAgent.Mode.values());
    private final Cards cards = new Cards();
    private final JScrollPane messages = new JScrollPane(cards);
    private final JTextArea prompt = new Composer();
    private final JLabel status = new JLabel();
    private final JProgressBar activity = new JProgressBar();
    private final JPanel statusRow = new JPanel(new BorderLayout(6, 0));
    private final List<ModelProvider.Message> history = new ArrayList<>();

    private final Supplier<AssistantSettings> settings;
    private ProjectTools.Attachment attachment;
    private ProjectTools.Attachment lastJsonAttachment;
    private SwingWorker<?, ?> worker;
    private Pending pending;
    private boolean closed;

    AssistantPanel(AgentProject project, AssistantSettings initialSettings) {
        this(project, () -> initialSettings, null, Palette.current());
    }

    AssistantPanel(AgentProject project, Supplier<AssistantSettings> settings, ProjectTools tools, Palette palette) {
        super(new BorderLayout());
        this.project = project;
        this.agent = new AssistantAgent(project, tools);
        this.settings = settings;
        this.palette = palette;
        this.send = iconButton(palette, "Send", Glyph.SEND, true);
        this.attach = iconButton(palette, "Attach", Glyph.ATTACH, false);
        this.removeAttachment = iconButton(palette, "Remove attachment", Glyph.REMOVE, false);
        setBackground(palette.bg());
        setBorder(new EmptyBorder(3, 0, 0, 0));

        cards.setLayout(new TranscriptLayout());
        cards.setBackground(palette.bg());
        cards.setBorder(new EmptyBorder(4, 0, 2, 4));
        messages.setBorder(null);
        messages.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        messages.getViewport().setBackground(palette.bg());
        messages.getVerticalScrollBar().setUnitIncrement(18);
        messages.getAccessibleContext().setAccessibleName("Designer Assistant conversation");
        messages.setMinimumSize(new Dimension(0, 48));
        var latest = textButton(palette, "Latest reply ↓", false);
        latest.addActionListener(event -> scrollToEnd());
        var latestRow = row(FlowLayout.CENTER, latest);
        latestRow.setVisible(false);
        messages.getVerticalScrollBar().addAdjustmentListener(event -> latestRow.setVisible(!nearEnd()));
        var transcript = new JPanel(new BorderLayout());
        transcript.setOpaque(false);
        transcript.add(messages, BorderLayout.CENTER);
        transcript.add(latestRow, BorderLayout.SOUTH);
        transcript.setMinimumSize(new Dimension(0, 48));

        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.setMargin(new Insets(4, 5, 3, 5));
        prompt.setBackground(palette.panel());
        prompt.setForeground(palette.text());
        prompt.setCaretColor(palette.text());
        prompt.setBorder(new EmptyBorder(4, 5, 3, 5));
        prompt.getAccessibleContext().setAccessibleName("Message for Designer Assistant");
        var keys = prompt.getInputMap();
        keys.put(KeyStroke.getKeyStroke("ENTER"), "sendMessage");
        keys.put(KeyStroke.getKeyStroke("shift ENTER"), DefaultEditorKit.insertBreakAction);
        keys.put(KeyStroke.getKeyStroke("ctrl ENTER"), "sendMessage");
        keys.put(KeyStroke.getKeyStroke("meta ENTER"), "sendMessage");
        prompt.getActionMap().put("sendMessage", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { if (send.isEnabled()) send(); }
        });
        prompt.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateControls(); }
            @Override public void removeUpdate(DocumentEvent event) { updateControls(); }
            @Override public void changedUpdate(DocumentEvent event) { updateControls(); }
        });
        var input = new JScrollPane(prompt);
        input.setMinimumSize(new Dimension(0, 36));
        input.setBorder(null);
        input.setOpaque(false);
        input.getViewport().setOpaque(false);
        var composer = new Surface(palette.panel(), palette.border(), 16);
        composer.setLayout(new BorderLayout(4, 2));
        composer.setBorder(new EmptyBorder(6, 6, 5, 6));
        composer.add(input, BorderLayout.CENTER);
        send.setToolTipText("Send (Enter)");
        permission.getAccessibleContext().setAccessibleName("Change permission");
        permission.setPrototypeDisplayValue(AssistantAgent.Mode.AUTO);
        updatePermissionHint();
        var composerControls = new JPanel(new BorderLayout(6, 0));
        composerControls.setOpaque(false);
        if (tools != null) {
            attach.setToolTipText("Attach a JSON file or Ignition project export ZIP");
            attach.addActionListener(event -> attach());
            removeAttachment.addActionListener(event -> setAttachment(null));
            attachmentLabel.setForeground(palette.muted());
            attachmentRow.setOpaque(false);
            attachmentRow.setBorder(new EmptyBorder(0, 4, 0, 0));
            attachmentRow.add(attachmentLabel, BorderLayout.CENTER);
            attachmentRow.add(removeAttachment, BorderLayout.EAST);
            composer.add(attachmentRow, BorderLayout.NORTH);
            composerControls.add(row(attach, permission), BorderLayout.WEST);
        } else {
            composerControls.add(row(permission), BorderLayout.WEST);
        }
        composerControls.add(send, BorderLayout.EAST);
        composer.add(composerControls, BorderLayout.SOUTH);
        setAttachment(null);

        status.setForeground(palette.muted());
        status.setBorder(new EmptyBorder(0, 4, 2, 4));
        status.getAccessibleContext().setAccessibleName("Assistant status");
        status.setVisible(false);
        activity.setPreferredSize(new Dimension(20, 6));
        activity.setBorderPainted(false);
        activity.setForeground(palette.accent());
        activity.setBackground(palette.bg());
        activity.getAccessibleContext().setAccessibleName("Assistant working");
        statusRow.setOpaque(false);
        statusRow.add(activity, BorderLayout.WEST);
        statusRow.add(status, BorderLayout.CENTER);
        statusRow.setVisible(false);
        var compose = new JPanel(new BorderLayout(0, 2));
        compose.setOpaque(false);
        compose.add(statusRow, BorderLayout.NORTH);
        compose.add(composer, BorderLayout.CENTER);
        add(new ComposerSplitPane(transcript, compose, palette), BorderLayout.CENTER);

        send.addActionListener(event -> send());
        permission.addActionListener(event -> updatePermissionHint());
        updateControls();
    }

    void focusComposer() {
        prompt.requestFocusInWindow();
    }

    boolean isBusy() {
        return worker != null;
    }

    private void updatePermissionHint() {
        permission.setToolTipText(permission.getSelectedItem() == AssistantAgent.Mode.AUTO
            ? "Apply authorized changes immediately in this chat."
            : "Review and approve changes before applying them.");
    }

    private void send() {
        var file = attachment;
        String draft = prompt.getText().strip();
        String question = draft.isEmpty() && file != null ? "Inspect the attached file." : draft;
        if (closed || worker != null) {
            return;
        }
        if (question.isBlank() || question.length() > MAX_PROMPT) {
            setStatus("Enter a message of 1–4,000 characters.");
            prompt.requestFocusInWindow();
            return;
        }
        if (pending != null) {
            resolvePending("Superseded");
        }
        if (project.name() == null || project.name().isBlank()) {
            setStatus("Open an Ignition project first.");
            return;
        }
        var designerContext = project.designerContext().deepCopy();
        var previousFile = lastJsonAttachment;
        if (file != null && file.json() != null) {
            lastJsonAttachment = file;
        }
        prompt.setText("");
        setAttachment(null);
        message(file == null ? question : question + "\n" + file.name(), true);
        scrollToEnd();
        prompt.requestFocusInWindow();
        if (history.isEmpty()) {
            firePropertyChange("firstPrompt", null, draft.isEmpty() && file != null ? file.name() : question);
        }
        remember("user", question);
        var input = List.copyOf(history);
        var model = settings.get().provider();
        var mode = (AssistantAgent.Mode) permission.getSelectedItem();
        run("Thinking…", progress -> () -> agent.run(model, input, mode, file, previousFile, designerContext, progress), turn -> {
            turn.exports().forEach(this::showExport);
            if (!turn.reply().isBlank()) {
                reply(turn.reply());
            }
            if (turn.pending() != null) {
                addReview(turn.pending());
            } else if (turn.applied() != null) {
                showApplied(turn.applied());
            }
        });
    }

    private void addReview(ChangeReview change) {
        JPanel card = card(palette.amberBg());
        line(card, change.title(), palette.amber(), true);
        if (!change.target().isBlank()) {
            line(card, change.target(), palette.text(), false);
        }
        var highlights = change.details().stream().filter(detail -> !detail.startsWith("  ")).limit(3).toList();
        highlights.forEach(detail -> line(card, detail, palette.text(), false));
        JButton approve = textButton(palette, "Approve", true);
        approve.getAccessibleContext().setAccessibleName(change.approveLabel());
        JButton dismiss = textButton(palette, "Dismiss", false);
        JLabel reviewState = new JLabel();
        reviewState.setVisible(false);
        reviewState.setForeground(palette.muted());
        JPanel actions = row(approve, dismiss);
        if (!change.sources().isEmpty() || change.details().size() > highlights.size()) {
            var details = textButton(palette, "Details", false);
            details.setToolTipText("View change details");
            details.addActionListener(event -> showDetails(change));
            actions.add(details);
        }
        actions.add(reviewState);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(actions);
        var reviewed = new Pending(change, approve, dismiss, reviewState);
        pending = reviewed;
        approve.addActionListener(event -> approve(reviewed));
        dismiss.addActionListener(event -> {
            if (pending == reviewed) {
                resolvePending("Dismissed");
                remember("assistant", "The user dismissed " + change.title() + " at " + change.target() + ".");
                updateControls();
            }
        });
        addCard(card);
        remember("assistant", "Proposed " + change.title() + " at " + change.target() + " for approval. "
            + String.join(" ", change.details().subList(0, Math.min(12, change.details().size()))) + sourceContext(change));
    }

    private static String sourceContext(ChangeReview change) {
        var context = new StringBuilder();
        for (var source : change.sources()) {
            if (source.title().startsWith("Proposed") || source.title().endsWith(".py") || source.title().endsWith(".sql")) {
                context.append("\n").append(source.title()).append("\n").append(source.text());
            }
            if (context.length() > 6_000) {
                return context.substring(0, 6_000) + "\n[Excerpt]";
            }
        }
        return context.toString();
    }

    private void showDetails(ChangeReview change) {
        var text = new StringBuilder(change.target()).append("\n\n").append(String.join("\n", change.details()));
        for (var section : change.sources()) {
            text.append("\n\n").append(section.title()).append("\n").append(section.text());
        }
        var body = new JTextArea(text.toString(), 24, 72);
        body.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        body.setEditable(false);
        body.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(body), change.title(), JOptionPane.PLAIN_MESSAGE);
    }

    private void approve(Pending reviewed) {
        if (reviewed == null || reviewed != pending || worker != null || closed) {
            return;
        }
        resolvePending("Approved");
        run(reviewed.change().title() + "…", ignored -> reviewed.change().apply(), this::showApplied);
    }

    private void resolvePending(String state) {
        if (pending != null) {
            pending.approve().setEnabled(false);
            pending.dismiss().setEnabled(false);
            pending.approve().setVisible(false);
            pending.dismiss().setVisible(false);
            pending.state().setText(state);
            pending.state().setVisible(true);
            pending = null;
        }
    }

    private void showApplied(AssistantAgent.Applied result) {
        JPanel card = card(palette.greenBg());
        line(card, result.summary(), palette.text(), false);
        if (result.draft() != null) {
            JButton show = textButton(palette, "Show in Tag Browser", false);
            show.addActionListener(event -> {
                try {
                    project.showUdt(result.draft());
                    setStatus("Selected the UDT in the Tag Browser.");
                } catch (IOException | RuntimeException e) {
                    setStatus("Open UDT Definitions and browse to " + result.draft().path() + ".");
                }
            });
            JPanel actions = row(show);
            actions.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(actions);
        }
        addCard(card);
        remember("assistant", result.summary());
        setStatus("");
        if (result.projectChanged()) {
            try {
                if (project.requestProjectUpdate()) {
                    return;
                }
            } catch (RuntimeException e) {
                System.getLogger(AssistantPanel.class.getName())
                    .log(System.Logger.Level.WARNING, "Could not start the Designer project update", e);
            }
            line(card, "Use File → Update Project to load this change.", palette.muted(), false);
            card.revalidate();
        }
    }

    private void attach() {
        var chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files and Ignition project exports", "json", "zip"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path file = chooser.getSelectedFile().toPath();
            run("Opening attachment…", ignored -> () -> ProjectTools.Attachment.read(file), this::setAttachment);
        }
    }

    private void setAttachment(ProjectTools.Attachment file) {
        attachment = file;
        attachmentLabel.setText(file == null ? "" : file.name());
        attachmentLabel.setToolTipText(file == null ? null : file.name());
        attachmentRow.setVisible(file != null);
        revalidate();
        updateControls();
    }

    private void showExport(ProjectTools.Export export) {
        var card = card(palette.panel());
        line(card, "Project resource export", palette.text(), true);
        int count = export.bundle().path("resourceCount").asInt();
        String summary = count + (count == 1 ? " resource" : " resources");
        line(card, summary + (export.bundle().path("truncated").asBoolean() ? " (partial export)" : ""),
            palette.muted(), false);
        var save = textButton(palette, "Save export…", false);
        save.addActionListener(event -> {
            if (closed || worker != null) {
                return;
            }
            var chooser = new JFileChooser();
            chooser.setSelectedFile(new java.io.File(export.filename()));
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                Path target = chooser.getSelectedFile().toPath();
                boolean replace = Files.exists(target);
                if (replace && JOptionPane.showConfirmDialog(this, "Replace " + target.getFileName() + "?",
                        "Save export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                    return;
                }
                run("Saving export…", ignored -> () -> {
                    String text = AssistantJson.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(export.bundle());
                    return replace ? Files.writeString(target, text) : Files.writeString(target, text, StandardOpenOption.CREATE_NEW);
                }, saved -> setStatus("Saved " + saved.getFileName() + "."));
            }
        });
        var actions = row(save);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(actions);
        addCard(card);
    }

    private void reply(String text) {
        message(text, false);
        remember("assistant", text);
    }

    private void remember(String role, String value) {
        history.add(new ModelProvider.Message(role, value));
        while (history.size() > MAX_HISTORY
            || history.size() > 2 && history.stream().mapToInt(m -> m.text().length()).sum() > 36_000) {
            history.remove(0);
        }
        while (!history.isEmpty() && !"user".equals(history.get(0).role())) {
            history.remove(0);
        }
    }

    private void message(String body, boolean fromUser) {
        JPanel card = surface(fromUser ? palette.accentBg() : palette.bg(), null);
        if (!fromUser) {
            card.getAccessibleContext().setAccessibleName("Assistant message");
            card.setBorder(new EmptyBorder(5, 3, 5, 3));
        }
        if (fromUser) {
            line(card, body, palette.text(), false);
        } else {
            card.add(new MarkdownReply(body, palette));
            var copy = iconButton(palette, "Copy reply", Glyph.COPY, false);
            copy.addActionListener(event -> MarkdownReply.copy(copy, body, "Copy reply"));
            card.add(row(copy));
        }
        if (fromUser) {
            card.getAccessibleContext().setAccessibleName("Your message");
            var inset = new JPanel(new TranscriptLayout());
            inset.setOpaque(false);
            inset.setBorder(new EmptyBorder(0, 25, 0, 0));
            inset.add(card);
            addCard(inset);
        } else {
            addCard(card);
        }
    }

    private JPanel card(Color background) {
        return surface(background, palette.border());
    }

    private static JPanel surface(Color background, Color border) {
        JPanel card = new Surface(background, border, 14);
        card.setLayout(new TranscriptLayout());
        card.setBackground(background);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(new EmptyBorder(6, 8, 6, 8));
        return card;
    }

    private static void line(JPanel parent, String text, Color color, boolean heading) {
        JTextArea label = new JTextArea(text);
        label.setEditable(false);
        label.setLineWrap(true);
        label.setWrapStyleWord(true);
        label.setOpaque(false);
        label.setBackground(parent.getBackground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setForeground(color);
        label.setFont(UIManager.getFont("Label.font").deriveFont(heading ? Font.BOLD : Font.PLAIN));
        label.setBorder(new EmptyBorder(1, 0, 2, 0));
        parent.add(label);
    }

    private void addCard(JPanel card) {
        boolean follow = nearEnd();
        cards.add(card);
        cards.add(Box.createVerticalStrut(8));
        while (cards.getComponentCount() > MAX_CARDS * 2) {
            cards.remove(0);
            cards.remove(0);
        }
        cards.revalidate();
        cards.repaint();
        if (follow) {
            scrollToEnd();
        }
    }

    private boolean nearEnd() {
        var bar = messages.getVerticalScrollBar();
        return bar.getMaximum() - bar.getValue() - bar.getVisibleAmount() < 36;
    }

    private void scrollToEnd() {
        SwingUtilities.invokeLater(() -> {
            var bar = messages.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void setStatus(String text) {
        status.setText(text);
        status.setVisible(!text.isBlank());
        statusRow.setVisible(!text.isBlank());
        activity.setVisible(worker != null && !closed);
        activity.setIndeterminate(worker != null && !closed);
        status.setToolTipText(text.isBlank() ? null : text);
        status.getAccessibleContext().setAccessibleDescription(text);
    }

    private void updateControls() {
        boolean idle = !closed && worker == null;
        send.setEnabled(idle && (attachment != null || !prompt.getText().isBlank()));
        attach.setEnabled(idle);
        removeAttachment.setEnabled(idle);
        prompt.setEditable(!closed);
        permission.setEnabled(idle && pending == null);
        if (pending != null) {
            pending.approve().setEnabled(idle);
            pending.dismiss().setEnabled(idle);
        }
    }

    private <T> void run(String label, Function<Consumer<String>, Callable<T>> action, Consumer<T> success) {
        worker = new SwingWorker<T, String>() {
            @Override protected T doInBackground() throws Exception { return action.apply(this::publish).call(); }
            @Override protected void process(List<String> updates) {
                if (worker == this && !updates.isEmpty()) {
                    setStatus(updates.get(updates.size() - 1));
                }
            }
            @Override protected void done() {
                worker = null;
                if (closed) {
                    return;
                }
                setStatus("");
                try {
                    success.accept(get());
                } catch (ExecutionException e) {
                    String error = e.getCause() instanceof IOException ? e.getCause().getMessage()
                        : "The request failed. Check the Designer connection and model settings.";
                    reply(error);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setStatus("Request interrupted.");
                } finally {
                    updateControls();
                }
            }
        };
        setStatus(label);
        updateControls();
        worker.execute();
    }

    void close() {
        closed = true;
        if (worker != null) {
            worker.cancel(true);
        }
        resolvePending("Closed");
        history.clear();
        lastJsonAttachment = null;
        setAttachment(null);
        updateControls();
    }

    private record Pending(ChangeReview change, JButton approve, JButton dismiss, JLabel state) {}

    private final class Composer extends JTextArea {
        Composer() { super(2, 6); }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getDocument().getLength() == 0) {
                var insets = getInsets();
                graphics.setColor(palette.muted());
                graphics.drawString("Ask about this project…", insets.left + 1,
                    insets.top + graphics.getFontMetrics().getAscent());
            }
        }
    }

    private static final class Cards extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(390, 420); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 18); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

}
