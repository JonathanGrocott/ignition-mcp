package com.jg.ignition.assistant;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.io.IOException;
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
    private final JButton settingsButton = new JButton("Settings");
    private final JButton newChat = new JButton("New chat");
    private final JButton send = new JButton("Send");
    private final JLabel modelLabel = new JLabel();
    private final JComboBox<AssistantAgent.Mode> permission = new JComboBox<>(AssistantAgent.Mode.values());
    private final Cards cards = new Cards();
    private final JScrollPane messages = new JScrollPane(cards);
    private final JTextArea prompt = new Composer();
    private final JLabel status = new JLabel(" ");
    private final JLabel activity = new JLabel();
    private final JPanel activityRow = row(activity);
    private final List<ModelProvider.Message> history = new ArrayList<>();

    private AssistantSettings settings;
    private SwingWorker<?, ?> worker;
    private Pending pending;
    private boolean closed;

    AssistantPanel(AgentProject project) {
        this(project, AssistantSettings.saved());
    }

    AssistantPanel(AgentProject project, AssistantSettings initialSettings) {
        super(new BorderLayout(0, 8));
        this.project = project;
        this.agent = new AssistantAgent(project);
        this.settings = initialSettings;
        setBackground(palette.bg);
        setBorder(new EmptyBorder(12, 12, 10, 12));

        var title = new JLabel(project.name());
        title.setForeground(palette.text);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setToolTipText(project.name());
        JLabel modeLabel = new JLabel("Changes");
        modeLabel.setLabelFor(permission);
        modeLabel.setForeground(palette.muted);
        permission.getAccessibleContext().setAccessibleName("Change permission");
        permission.setToolTipText("Auto-apply creates authorized UDTs without a separate approval.");
        var top = new JPanel(new BorderLayout(4, 0));
        top.setOpaque(false);
        top.add(title, BorderLayout.CENTER);
        top.add(row(newChat, settingsButton), BorderLayout.EAST);
        var header = new JPanel(new BorderLayout(4, 4));
        header.setOpaque(false);
        header.add(top, BorderLayout.CENTER);
        var context = new JPanel(new BorderLayout(4, 0));
        context.setOpaque(false);
        modelLabel.setForeground(palette.muted);
        modelLabel.setBorder(new EmptyBorder(0, 4, 0, 0));
        context.add(modelLabel, BorderLayout.CENTER);
        context.add(row(modeLabel, permission), BorderLayout.EAST);
        header.add(context, BorderLayout.SOUTH);
        styleButton(newChat);
        styleButton(settingsButton);
        showProvider();
        add(header, BorderLayout.NORTH);

        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        cards.setBackground(palette.bg);
        cards.setBorder(new EmptyBorder(8, 0, 4, 8));
        messages.setBorder(null);
        messages.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        messages.getViewport().setBackground(palette.bg);
        messages.getVerticalScrollBar().setUnitIncrement(18);
        messages.getAccessibleContext().setAccessibleName("Designer Assistant conversation");
        activity.setForeground(palette.muted);
        activityRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        activityRow.setBorder(new EmptyBorder(1, 3, 8, 3));
        activityRow.setVisible(false);
        cards.add(activityRow);
        add(messages, BorderLayout.CENTER);

        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.setMargin(new Insets(7, 9, 7, 9));
        prompt.setBackground(palette.panel);
        prompt.setForeground(palette.text);
        prompt.setCaretColor(palette.text);
        prompt.setBorder(BorderFactory.createEmptyBorder());
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
        styleButton(send);
        composer.add(row(FlowLayout.TRAILING, send), BorderLayout.SOUTH);
        status.setForeground(palette.muted);
        status.getAccessibleContext().setAccessibleName("Assistant status");
        status.setBorder(new EmptyBorder(1, 5, 0, 5));
        var compose = new JPanel(new BorderLayout(4, 3));
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
        permission.addActionListener(event -> {
            if (permission.getSelectedItem() == AssistantAgent.Mode.AUTO) {
                setStatus("Authorized UDT creations apply immediately in this chat.");
            } else {
                setStatus("UDT creations require review.");
            }
        });
        greet();
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

    private void greet() {
        message("Assistant", "Ask about tags, UDTs, Perspective views, or scripts in this project. "
            + "To create a UDT, tell me which folder to use and what members it needs.", false);
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
            resolvePending("Superseded by a new message.");
        }
        if (project.name() == null || project.name().isBlank()) {
            setStatus("Open an Ignition project first.");
            return;
        }
        prompt.setText("");
        message("You", question, true);
        scrollToEnd();
        prompt.requestFocusInWindow();
        remember("user", question);
        var input = List.copyOf(history);
        var model = settings.provider();
        var mode = (AssistantAgent.Mode) permission.getSelectedItem();
        run("Thinking…", progress -> () -> agent.run(model, input, mode, progress), turn -> {
            if (turn.pending() != null) {
                addReview(turn.pending());
            } else if (turn.applied() != null) {
                showApplied(turn.applied());
            } else {
                reply(turn.reply());
                setStatus(" ");
            }
        });
    }

    private void addReview(UdtDraft draft) {
        JPanel card = card(palette.amberBg);
        line(card, "Create UDT", palette.amber, true);
        line(card, draft.path(), palette.text, false);
        if (!draft.description().isEmpty()) {
            line(card, draft.description(), palette.muted, false);
        }
        line(card, "Members", palette.muted, true);
        for (var member : draft.members()) {
            line(card, member.name() + "   " + member.dataType() + " · Memory"
                + (member.description().isEmpty() ? "" : " — " + member.description()), palette.text, false);
        }
        JButton approve = new JButton("Approve creation");
        JButton dismiss = new JButton("Dismiss");
        styleButton(approve);
        styleButton(dismiss);
        JLabel reviewState = new JLabel(" ");
        reviewState.setForeground(palette.muted);
        JPanel actions = row(approve, dismiss);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(actions);
        reviewState.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(reviewState);
        pending = new Pending(draft, approve, dismiss, reviewState);
        approve.addActionListener(event -> approve(pending));
        dismiss.addActionListener(event -> {
            resolvePending("Dismissed.");
            remember("assistant", "The user dismissed the UDT draft at " + draft.path() + ".");
            setStatus("Draft dismissed.");
            updateControls();
        });
        addCard(card);
        remember("assistant", "Proposed UDT " + draft.path() + " with members "
            + draft.members().stream().map(m -> m.name() + " (" + m.dataType() + ")").toList() + " for approval.");
        setStatus("Review the change or ask for a revision.");
    }

    private void approve(Pending reviewed) {
        if (reviewed == null || reviewed != pending || worker != null || closed) {
            return;
        }
        resolvePending("Approved.");
        run("Creating " + reviewed.draft().path() + "…", ignored -> () -> agent.apply(reviewed.draft()), this::showApplied);
    }

    private void resolvePending(String state) {
        if (pending != null) {
            pending.approve().setEnabled(false);
            pending.dismiss().setEnabled(false);
            pending.state().setText(state);
            pending = null;
        }
    }

    private void showApplied(AssistantAgent.Applied result) {
        JPanel card = card(palette.greenBg);
        line(card, "UDT created", palette.green, true);
        line(card, result.summary(), palette.text, false);
        JButton show = new JButton("Show in Tag Browser");
        styleButton(show);
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
        addCard(card);
        remember("assistant", result.summary());
        setStatus("Gateway readback verified.");
    }

    private void reply(String text) {
        message("Assistant", text, false);
        remember("assistant", text);
    }

    private void clear() {
        history.clear();
        resolvePending("Dismissed.");
        cards.removeAll();
        cards.add(activityRow);
        permission.setSelectedItem(AssistantAgent.Mode.REVIEW);
        greet();
        setStatus(" ");
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

    private void message(String heading, String body, boolean fromUser) {
        JPanel card = surface(fromUser ? palette.accentBg : palette.bg, null);
        if (!fromUser) {
            line(card, heading, palette.muted, true);
        }
        line(card, body, palette.text, false);
        if (fromUser) {
            card.getAccessibleContext().setAccessibleName("Your message");
            var inset = new JPanel(new BorderLayout());
            inset.setOpaque(false);
            inset.setBorder(new EmptyBorder(0, 34, 0, 0));
            inset.setAlignmentX(Component.LEFT_ALIGNMENT);
            inset.add(card, BorderLayout.CENTER);
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
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(background);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(new EmptyBorder(7, 11, 7, 11));
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
        label.setBorder(new EmptyBorder(2, 0, 4, 0));
        parent.add(label);
    }

    private void addCard(JPanel card) {
        boolean follow = nearEnd();
        int last = cards.getComponentCount() - 1;
        cards.add(card, last);
        cards.add(Box.createVerticalStrut(8), last + 1);
        while (cards.getComponentCount() > MAX_CARDS * 2 + 1) {
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

    private static void styleButton(JButton button) {
        button.setMargin(new Insets(3, 9, 3, 9));
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

    private void showActivity(String value) {
        boolean follow = nearEnd();
        activity.setText(value);
        activityRow.setVisible(!value.isBlank());
        cards.revalidate();
        if (follow) {
            scrollToEnd();
        }
    }

    private void setStatus(String text) {
        status.setText(text.length() > 59 ? text.substring(0, 56) + "…" : text);
        status.setToolTipText(text);
        status.getAccessibleContext().setAccessibleDescription(text);
    }

    private void updateControls() {
        boolean idle = !closed && worker == null;
        settingsButton.setEnabled(idle);
        newChat.setEnabled(idle);
        send.setEnabled(idle && !prompt.getText().isBlank());
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
                    showActivity(updates.get(updates.size() - 1));
                }
            }
            @Override protected void done() {
                worker = null;
                if (closed) {
                    return;
                }
                showActivity("");
                try {
                    success.accept(get());
                } catch (ExecutionException e) {
                    String error = e.getCause() instanceof IOException ? e.getCause().getMessage()
                        : "The request failed. Check the Designer connection and model settings.";
                    reply(error);
                    setStatus("Request failed.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setStatus("Request interrupted.");
                } finally {
                    updateControls();
                }
            }
        };
        setStatus(" ");
        showActivity(label);
        updateControls();
        worker.execute();
    }

    void close() {
        closed = true;
        if (worker != null) {
            worker.cancel(true);
        }
        resolvePending("Designer closed.");
        history.clear();
        settings = null;
        updateControls();
    }

    private record Pending(UdtDraft draft, JButton approve, JButton dismiss, JLabel state) {}

    private final class Composer extends JTextArea {
        Composer() { super(2, 10); }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getDocument().getLength() == 0) {
                var insets = getInsets();
                graphics.setColor(palette.muted);
                graphics.drawString("Message Designer Assistant", insets.left + 1,
                    insets.top + graphics.getFontMetrics().getAscent());
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
                           Color accent, Color accentBg, Color amber, Color amberBg, Color green, Color greenBg) {
        static Palette current() {
            var background = UIManager.getColor("Panel.background");
            boolean dark = background != null && background.getRed() + background.getGreen() + background.getBlue() < 384;
            return dark
                ? colors(0x111922, 0x19232e, 0xe9eff5, 0xa6b7c8, 0x344454, 0x8ab9ff, 0x203c5b, 0xf2c982, 0x3d301e, 0x8dd8b3, 0x16382f)
                : colors(0xf4f6f9, 0xffffff, 0x202f40, 0x53667b, 0xd8e0e9, 0x215fa1, 0xeaf2fd, 0x795010, 0xfff3dd, 0x226348, 0xe9f5ed);
        }

        private static Palette colors(int bg, int panel, int text, int muted, int border, int accent,
                                      int accentBg, int amber, int amberBg, int green, int greenBg) {
            return new Palette(new Color(bg), new Color(panel), new Color(text), new Color(muted), new Color(border),
                new Color(accent), new Color(accentBg), new Color(amber), new Color(amberBg), new Color(green), new Color(greenBg));
        }
    }
}
