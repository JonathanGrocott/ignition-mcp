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

final class AssistantPanel extends JPanel {
    private static final int MAX_PROMPT = 4_000;
    private static final int MAX_HISTORY = 18;
    private static final int MAX_CARDS = 60;

    private final AgentProject project;
    private final AssistantAgent agent;
    private final Palette palette = Palette.current();
    private final JButton settingsButton = iconButton("Model settings", Glyph.SETTINGS, false);
    private final JButton newChat = iconButton("New chat", Glyph.PLUS, false);
    private final JButton send = iconButton("Send", Glyph.SEND, true);
    private final JButton attach = iconButton("Attach", Glyph.ATTACH, false);
    private final JButton removeAttachment = iconButton("Remove attachment", Glyph.REMOVE, false);
    private final JLabel attachmentLabel = new JLabel();
    private final JPanel attachmentRow = new JPanel(new BorderLayout(6, 0));
    private final JLabel modelLabel = new JLabel();
    private final JComboBox<AssistantAgent.Mode> permission = new JComboBox<>(AssistantAgent.Mode.values());
    private final Cards cards = new Cards();
    private final JScrollPane messages = new JScrollPane(cards);
    private final JTextArea prompt = new Composer();
    private final JLabel status = new JLabel();
    private final List<ModelProvider.Message> history = new ArrayList<>();

    private AssistantSettings settings;
    private ProjectTools.Attachment attachment;
    private SwingWorker<?, ?> worker;
    private Pending pending;
    private boolean closed;

    AssistantPanel(AgentProject project, AssistantSettings initialSettings) {
        this(project, initialSettings, null);
    }

    AssistantPanel(AgentProject project, AssistantSettings initialSettings, ProjectTools tools) {
        super(new BorderLayout(0, 8));
        this.project = project;
        this.agent = new AssistantAgent(project, tools);
        this.settings = initialSettings;
        setBackground(palette.bg);
        setBorder(new EmptyBorder(7, 10, 9, 10));

        modelLabel.setForeground(palette.muted);
        modelLabel.setBorder(new EmptyBorder(0, 3, 0, 0));
        var header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.add(modelLabel, BorderLayout.CENTER);
        header.add(row(newChat, settingsButton), BorderLayout.EAST);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.border));
        showProvider();
        add(header, BorderLayout.NORTH);

        cards.setLayout(new TranscriptLayout());
        cards.setBackground(palette.bg);
        cards.setBorder(new EmptyBorder(4, 0, 2, 4));
        messages.setBorder(null);
        messages.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        messages.getViewport().setBackground(palette.bg);
        messages.getVerticalScrollBar().setUnitIncrement(18);
        messages.getAccessibleContext().setAccessibleName("Designer Assistant conversation");
        add(messages, BorderLayout.CENTER);

        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.setMargin(new Insets(4, 5, 3, 5));
        prompt.setBackground(palette.panel);
        prompt.setForeground(palette.text);
        prompt.setCaretColor(palette.text);
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
            @Override public void insertUpdate(DocumentEvent event) { composerChanged(); }
            @Override public void removeUpdate(DocumentEvent event) { composerChanged(); }
            @Override public void changedUpdate(DocumentEvent event) { composerChanged(); }
        });
        var input = new JScrollPane(prompt);
        input.setBorder(null);
        input.setOpaque(false);
        input.getViewport().setOpaque(false);
        var composer = new Surface(palette.panel, palette.border, 16);
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
            attach.setToolTipText("Attach an Ignition project export or resource bundle");
            attach.addActionListener(event -> attach());
            removeAttachment.addActionListener(event -> setAttachment(null));
            attachmentLabel.setForeground(palette.muted);
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

        status.setForeground(palette.muted);
        status.setBorder(new EmptyBorder(0, 4, 2, 4));
        status.getAccessibleContext().setAccessibleName("Assistant status");
        status.setVisible(false);
        var compose = new JPanel(new BorderLayout(0, 2));
        compose.setOpaque(false);
        compose.add(status, BorderLayout.NORTH);
        compose.add(composer, BorderLayout.CENTER);
        add(compose, BorderLayout.SOUTH);

        send.addActionListener(event -> send());
        newChat.addActionListener(event -> clear());
        settingsButton.addActionListener(event -> settings.edit(this).ifPresent(value -> {
            if (!settings.provider().equals(value.provider())) {
                clear();
            }
            settings = value;
            showProvider();
            setStatus("Model settings saved.");
        }));
        permission.addActionListener(event -> updatePermissionHint());
        updateControls();
    }

    private void composerChanged() {
        int rows = Math.min(5, Math.max(2, prompt.getLineCount()));
        if (prompt.getRows() != rows) {
            prompt.setRows(rows);
            revalidate();
        }
        updateControls();
    }

    private void showProvider() {
        modelLabel.setText(settings.provider().displayName());
        modelLabel.setToolTipText(settings.provider().connectionDescription());
    }

    private void updatePermissionHint() {
        permission.setToolTipText(permission.getSelectedItem() == AssistantAgent.Mode.AUTO
            ? "Apply authorized changes immediately in this chat."
            : "Review and approve changes before applying them.");
    }

    private void send() {
        String question = prompt.getText().strip();
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
        prompt.setText("");
        var file = attachment;
        message(file == null ? question : question + "\n" + file.name(), true);
        scrollToEnd();
        prompt.requestFocusInWindow();
        remember("user", question);
        var input = List.copyOf(history);
        var model = settings.provider();
        var mode = (AssistantAgent.Mode) permission.getSelectedItem();
        run("Thinking…", progress -> () -> agent.run(model, input, mode, file, progress), turn -> {
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
        JPanel card = card(palette.amberBg);
        line(card, change.title(), palette.amber, true);
        if (!change.target().isBlank()) {
            line(card, change.target(), palette.text, false);
        }
        var highlights = change.details().stream().filter(detail -> !detail.startsWith("  ")).limit(3).toList();
        highlights.forEach(detail -> line(card, detail, palette.text, false));
        JButton approve = textButton("Approve", true);
        approve.getAccessibleContext().setAccessibleName(change.approveLabel());
        JButton dismiss = textButton("Dismiss", false);
        JLabel reviewState = new JLabel();
        reviewState.setVisible(false);
        reviewState.setForeground(palette.muted);
        JPanel actions = row(approve, dismiss);
        if (!change.sources().isEmpty() || change.details().size() > highlights.size()) {
            var details = textButton("Details", false);
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
        JPanel card = card(palette.greenBg);
        line(card, result.summary(), palette.text, false);
        if (result.draft() != null) {
            JButton show = textButton("Show in Tag Browser", false);
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
            line(card, "Use File → Update Project to load this change.", palette.muted, false);
            card.revalidate();
        }
    }

    private void attach() {
        var chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Ignition exports and bundles", "zip", "json"));
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
    }

    private void showExport(ProjectTools.Export export) {
        var card = card(palette.panel);
        line(card, "Project resource export", palette.text, true);
        int count = export.bundle().path("resourceCount").asInt();
        String summary = count + (count == 1 ? " resource" : " resources");
        line(card, summary + (export.bundle().path("truncated").asBoolean() ? " (partial export)" : ""),
            palette.muted, false);
        var save = textButton("Save export…", false);
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

    private void clear() {
        history.clear();
        setAttachment(null);
        resolvePending("Dismissed");
        cards.removeAll();
        cards.revalidate();
        cards.repaint();
        permission.setSelectedItem(AssistantAgent.Mode.REVIEW);
        setStatus("");
        updateControls();
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
        JPanel card = surface(fromUser ? palette.accentBg : palette.bg, null);
        if (!fromUser) {
            card.getAccessibleContext().setAccessibleName("Assistant message");
            card.setBorder(new EmptyBorder(5, 3, 5, 3));
        }
        line(card, body, palette.text, false);
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
        return surface(background, palette.border);
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

    private static JPanel row(JComponent... components) {
        return row(FlowLayout.LEADING, components);
    }

    private static JPanel row(int alignment, JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(alignment, 4, 0));
        row.setOpaque(false);
        for (JComponent component : components) {
            row.add(component);
        }
        return row;
    }

    private JButton iconButton(String name, Glyph glyph, boolean primary) {
        var button = new DockButton("", primary);
        button.setIcon(glyph);
        button.setDisabledIcon(glyph);
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
        return button;
    }

    private JButton textButton(String label, boolean primary) {
        return new DockButton(label, primary);
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
        status.setToolTipText(text.isBlank() ? null : text);
        status.getAccessibleContext().setAccessibleDescription(text);
    }

    private void updateControls() {
        boolean idle = !closed && worker == null;
        settingsButton.setEnabled(idle);
        newChat.setEnabled(idle);
        send.setEnabled(idle && !prompt.getText().isBlank());
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
        setAttachment(null);
        settings = null;
        updateControls();
    }

    private record Pending(ChangeReview change, JButton approve, JButton dismiss, JLabel state) {}

    private final class Composer extends JTextArea {
        Composer() { super(2, 6); }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getDocument().getLength() == 0) {
                var insets = getInsets();
                graphics.setColor(palette.muted);
                graphics.drawString("Ask about this project…", insets.left + 1,
                    insets.top + graphics.getFontMetrics().getAscent());
            }
        }
    }

    private final class DockButton extends JButton {
        private final boolean primary;

        DockButton(String label, boolean primary) {
            super(label);
            this.primary = primary;
            setOpaque(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setBorder(new EmptyBorder(5, 6, 5, 6));
            setForeground(primary ? palette.onAccent : palette.text);
        }

        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (primary || getModel().isRollover() || getModel().isArmed()) {
                    g.setColor(!isEnabled() ? palette.border : primary ? palette.accent : palette.accentBg);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                }
                if (hasFocus()) {
                    g.setColor(primary ? palette.onAccent : palette.accent);
                    g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 8, 8);
                }
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private enum Glyph implements Icon {
        PLUS, SETTINGS, ATTACH, SEND, REMOVE;

        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            var g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(component.getForeground());
                if (!component.isEnabled()) {
                    g.setComposite(AlphaComposite.SrcOver.derive(.45f));
                }
                switch (this) {
                    case PLUS -> {
                        g.drawLine(3, 8, 13, 8);
                        g.drawLine(8, 3, 8, 13);
                    }
                    case SETTINGS -> {
                        g.drawLine(2, 5, 14, 5);
                        g.drawLine(2, 11, 14, 11);
                        g.drawLine(6, 3, 6, 7);
                        g.drawLine(10, 9, 10, 13);
                    }
                    case ATTACH -> {
                        var clip = new java.awt.geom.Path2D.Double();
                        clip.moveTo(11, 5);
                        clip.lineTo(5.5, 10.5);
                        clip.curveTo(4.2, 11.8, 6, 13.4, 7.3, 12.1);
                        clip.lineTo(13.3, 6.1);
                        clip.curveTo(15.8, 3.6, 12.4, .7, 10, 3.1);
                        clip.lineTo(3.1, 10);
                        clip.curveTo(.5, 12.6, 3.5, 16.5, 6.6, 13.5);
                        g.draw(clip);
                    }
                    case SEND -> {
                        g.drawLine(8, 3, 8, 13);
                        g.drawLine(4, 7, 8, 3);
                        g.drawLine(8, 3, 12, 7);
                    }
                    case REMOVE -> {
                        g.drawLine(4, 4, 12, 12);
                        g.drawLine(12, 4, 4, 12);
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static final class Surface extends JPanel {
        private final Color fill;
        private final Color stroke;
        private final int radius;

        Surface(Color fill, Color stroke, int radius) {
            this.fill = fill;
            this.stroke = stroke;
            this.radius = radius;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
                if (stroke != null) {
                    g.setColor(stroke);
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
                }
            } finally {
                g.dispose();
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

    private record Palette(Color bg, Color panel, Color text, Color muted, Color border,
                           Color accent, Color accentBg, Color amber, Color amberBg, Color greenBg, Color onAccent) {
        static Palette current() {
            var background = UIManager.getColor("Panel.background");
            boolean dark = background != null && background.getRed() + background.getGreen() + background.getBlue() < 384;
            return dark
                ? colors(0x111922, 0x19232e, 0xe9eff5, 0xa6b7c8, 0x344454, 0x8ab9ff, 0x203c5b, 0xf2c982, 0x29271f, 0x1b2c26, 0x111922)
                : colors(0xf4f6f9, 0xffffff, 0x202f40, 0x53667b, 0xd8e0e9, 0x215fa1, 0xeaf2fd, 0x795010, 0xfff7ea, 0xeef6f1, 0xffffff);
        }

        private static Palette colors(int bg, int panel, int text, int muted, int border, int accent,
                                      int accentBg, int amber, int amberBg, int greenBg, int onAccent) {
            return new Palette(new Color(bg), new Color(panel), new Color(text), new Color(muted), new Color(border),
                new Color(accent), new Color(accentBg), new Color(amber), new Color(amberBg), new Color(greenBg), new Color(onAccent));
        }
    }
}
