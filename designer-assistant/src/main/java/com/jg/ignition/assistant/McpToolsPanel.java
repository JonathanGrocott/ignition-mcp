package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import static com.jg.ignition.assistant.AssistantJson.JSON;

final class McpToolsPanel extends JPanel {
    private static final int MAX_ARGUMENTS = 4_194_304;
    private static final int MAX_CHAT_RESULT = 8_000;

    private final AssistantPanel owner;
    private final Supplier<String> project;
    private final Supplier<String> gateway;
    private final Supplier<AssistantSettings> settings;
    private final JComboBox<McpTool> selector = new JComboBox<>();
    private final JTextArea arguments = AssistantPanel.textArea(true);
    private final JTextArea result = AssistantPanel.textArea(false);
    private final JButton discover = new JButton("Load tools");
    private final JButton prepare = new JButton("Run read");
    private final JButton apply = new JButton("Apply reviewed call");
    private final JButton share = new JButton("Use result in chat");

    private List<McpTool> catalog = List.of();
    private McpReview.Commit pending;
    private String reviewedGateway;
    private String chatResult;
    private boolean installing;
    private boolean idle = true;

    McpToolsPanel(AssistantPanel owner, Supplier<String> project, Supplier<String> gateway,
                  Supplier<AssistantSettings> settings) {
        super(new BorderLayout(4, 6));
        this.owner = owner;
        this.project = project;
        this.gateway = gateway;
        this.settings = settings;

        selector.getAccessibleContext().setAccessibleName("Ignition MCP tool");
        arguments.getAccessibleContext().setAccessibleName("Tool arguments JSON");
        result.getAccessibleContext().setAccessibleName("Tool result or input schema");
        selector.setPreferredSize(new Dimension(270, selector.getPreferredSize().height));
        arguments.setLineWrap(false);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(arguments), new JScrollPane(result));
        split.setResizeWeight(0.40);
        split.setDividerLocation(165);
        JPanel choose = new JPanel(new BorderLayout(4, 0));
        choose.add(selector, BorderLayout.CENTER);
        choose.add(discover, BorderLayout.EAST);
        add(choose, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.GridLayout(2, 1, 0, 3));
        actions.add(AssistantPanel.buttons(prepare, apply));
        actions.add(AssistantPanel.buttons(share));
        add(actions, BorderLayout.SOUTH);

        arguments.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { invalidateReview(); }
            @Override public void removeUpdate(DocumentEvent e) { invalidateReview(); }
            @Override public void changedUpdate(DocumentEvent e) { invalidateReview(); }
        });
        selector.addActionListener(event -> {
            if (!installing) {
                showTool(selected());
            }
        });
        discover.addActionListener(event -> refresh());
        prepare.addActionListener(event -> prepare());
        apply.addActionListener(event -> apply());
        share.addActionListener(event -> share());
        showTool(null);
    }

    List<McpTool> catalog() { return catalog; }

    void showCatalog(List<McpTool> tools) {
        McpTool old = selected();
        catalog = tools;
        installing = true;
        try {
            selector.setModel(new DefaultComboBoxModel<>(tools.toArray(McpTool[]::new)));
            if (old != null) {
                tools.stream().filter(tool -> tool.name().equals(old.name())).findFirst()
                    .ifPresent(selector::setSelectedItem);
            }
        } finally {
            installing = false;
        }
        showTool(selected());
    }

    void propose(McpTool tool, ObjectNode input) {
        selector.setSelectedItem(tool);
        arguments.setText(input.toPrettyString());
        arguments.setCaretPosition(0);
        showOutput("Proposed by the model. Review the tool and arguments.\n\n" + schemaText(tool));
    }

    void setIdle(boolean idle) {
        this.idle = idle;
        McpTool tool = selected();
        discover.setEnabled(idle);
        selector.setEnabled(idle && !catalog.isEmpty());
        arguments.setEditable(idle && tool != null);
        prepare.setText(tool != null && tool.mode() != McpTool.Mode.READ
            || tool != null && IgnitionMcpClient.PREVIEW.equals(tool.name()) ? "Preview call" : "Run read");
        prepare.setEnabled(idle && tool != null && tool.mode() != McpTool.Mode.UNKNOWN);
        apply.setEnabled(idle && pending != null && !pending.expired());
        share.setEnabled(idle && chatResult != null);
    }

    void invalidateReview() {
        pending = null;
        reviewedGateway = null;
        chatResult = null;
        setIdle(idle);
    }

    private McpTool selected() { return (McpTool) selector.getSelectedItem(); }

    private void showTool(McpTool tool) {
        arguments.setText(tool == null ? "{}" : tool.initialArguments(project.get()).toPrettyString());
        arguments.setCaretPosition(0);
        invalidateReview();
        showOutput(tool == null ? "Load the tools from the connected Gateway." : schemaText(tool));
    }

    private static String schemaText(McpTool tool) {
        return tool.name() + "\n" + tool.description() + "\n\nInput schema\n" + tool.schema().toPrettyString();
    }

    private void refresh() {
        try {
            var client = client(gateway.get());
            invalidateReview();
            owner.run("Loading Ignition MCP tools…", client::catalog, tools -> {
                showCatalog(tools);
                owner.setStatus("Loaded " + tools.size() + " Ignition MCP tools.");
            }, this::failure);
        } catch (IOException e) {
            failure(e.getMessage());
        }
    }

    private void prepare() {
        McpTool tool = selected();
        if (tool == null) {
            return;
        }
        invalidateReview();
        try {
            String address = gateway.get();
            var client = client(address);
            String text = arguments.getText();
            if (text.length() > MAX_ARGUMENTS || !(JSON.readTree(text) instanceof ObjectNode input)) {
                throw new IOException("Enter a JSON object within the request size limit.");
            }
            owner.run("Requesting the Gateway…", () -> McpReview.prepare(client, tool, input),
                review -> showReview(tool, address, review), this::failure);
        } catch (IOException e) {
            failure(e instanceof com.fasterxml.jackson.core.JsonProcessingException
                ? "Enter valid JSON tool arguments." : e.getMessage());
        }
    }

    private void showReview(McpTool tool, String address, McpReview.Review review) {
        var reply = review.reply();
        String output = format(reply);
        pending = review.commit();
        if (pending != null) {
            reviewedGateway = address;
            output = "Call sent on Apply\n" + pending.name() + "\n" + pending.arguments().toPrettyString()
                + "\n\nGateway preview\n" + output;
        } else if (!reply.error() && tool.mode() == McpTool.Mode.READ && !IgnitionMcpClient.PREVIEW.equals(tool.name())) {
            String body = reply.data().isMissingNode() ? reply.message() : reply.data().toString();
            if (body.length() <= MAX_CHAT_RESULT) {
                chatResult = "Read result from " + tool.name() + "\n" + body;
            }
        }
        showOutput(output);
        owner.setStatus(reply.error() ? "Gateway rejected the tool call. See the result."
            : pending == null ? "Gateway response received." : "Preview ready. Review before applying.");
    }

    private void apply() {
        var reviewed = pending;
        String address = gateway.get();
        if (reviewed == null || reviewed.expired() || !java.util.Objects.equals(reviewedGateway, address)) {
            failure("The Gateway or preview changed. Preview the call again.");
            invalidateReview();
            return;
        }
        invalidateReview();
        try {
            var client = client(address);
            owner.run("Applying the reviewed call…", () -> reviewed.apply(client), reply -> {
                showOutput(format(reply));
                owner.setStatus(reply.error() ? "Gateway reported an error. Check the result and target."
                    : "Gateway returned a receipt. Update Project to see project changes.");
            }, message -> failure(message + "\nCheck the target or stored plan status before retrying."));
        } catch (IOException e) {
            failure(e.getMessage());
        }
    }

    private IgnitionMcpClient client(String address) throws IOException {
        AssistantSettings current = settings.get();
        return new IgnitionMcpClient(current.mcpEndpoint(address), current.gatewayCredential());
    }

    private void share() {
        if (chatResult != null) {
            owner.addToolContext(chatResult);
            chatResult = null;
            setIdle(idle);
        }
    }

    private void failure(String message) {
        showOutput(message);
        owner.setStatus(message);
    }

    private static String format(IgnitionMcpClient.ToolReply reply) {
        return reply.message() + (reply.data().isMissingNode() ? "" : "\n\n" + reply.data().toPrettyString());
    }

    private void showOutput(String value) {
        result.setText(value);
        result.setCaretPosition(0);
    }
}
