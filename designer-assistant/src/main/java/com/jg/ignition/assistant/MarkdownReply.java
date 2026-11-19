package com.jg.ignition.assistant;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Element;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.InlineView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jg.ignition.assistant.AssistantStyle.*;

/** Renders model Markdown while keeping code selectable and preserving indentation when copied. */
final class MarkdownReply extends JPanel {
    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS)
        .escapeHtml(true).sanitizeUrls(true).nodeRendererFactory(LinkRenderer::new).build();

    MarkdownReply(String markdown, Palette palette) {
        super(new TranscriptLayout());
        setOpaque(false);
        getAccessibleContext().setAccessibleName("Formatted assistant response");
        var prose = new StringBuilder();
        for (Node node = PARSER.parse(markdown).getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof FencedCodeBlock code) {
                addProse(prose, palette);
                addCode(code.getLiteral(), code.getInfo(), palette);
            } else if (node instanceof IndentedCodeBlock code) {
                addProse(prose, palette);
                addCode(code.getLiteral(), "", palette);
            } else {
                prose.append(RENDERER.render(node));
            }
        }
        addProse(prose, palette);
    }

    static String renderHtml(String markdown) {
        return RENDERER.render(PARSER.parse(markdown));
    }

    static boolean webLink(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void addProse(StringBuilder html, Palette palette) {
        if (html.isEmpty()) {
            return;
        }
        var kit = new HTMLEditorKit() {
            private final ViewFactory factory = new HTMLFactory() {
                @Override public View create(Element element) {
                    View view = super.create(element);
                    return view instanceof InlineView ? new InlineView(element) {
                        @Override public float getMinimumSpan(int axis) {
                            return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                        }
                    } : view;
                }
            };
            @Override public ViewFactory getViewFactory() { return factory; }
        };
        Font font = UIManager.getFont("Label.font");
        kit.getStyleSheet().addRule("body { font-family: '" + font.getFamily()
            + "'; font-size: " + (font.getSize() + 1) + "pt; font-weight: normal; font-style: normal; color: "
            + hex(palette.text()) + "; margin: 0; }");
        kit.getStyleSheet().addRule("p { margin-top: 3px; margin-bottom: 9px; }");
        kit.getStyleSheet().addRule("h1 { font-size: 155%; margin-top: 12px; margin-bottom: 7px; }");
        kit.getStyleSheet().addRule("h2 { font-size: 130%; margin-top: 11px; margin-bottom: 6px; }");
        kit.getStyleSheet().addRule("h3, h4, h5, h6 { font-size: 110%; margin-top: 9px; margin-bottom: 5px; }");
        kit.getStyleSheet().addRule("ul, ol { margin-left: 20px; margin-top: 3px; margin-bottom: 9px; }");
        kit.getStyleSheet().addRule("li { margin-top: 2px; margin-bottom: 3px; }");
        kit.getStyleSheet().addRule("code, pre { font-family: monospace; font-size: 95%; background-color: "
            + hex(palette.accentBg()) + "; }");
        kit.getStyleSheet().addRule("a { color: " + hex(palette.accent()) + "; text-decoration: underline; }");
        kit.getStyleSheet().addRule("blockquote { margin-left: 12px; margin-right: 6px; color: "
            + hex(palette.muted()) + "; }");
        kit.getStyleSheet().addRule("table { border-spacing: 0; margin-top: 5px; margin-bottom: 10px; }");
        kit.getStyleSheet().addRule("td, th { border: 1px solid " + hex(palette.border()) + "; padding: 5px; }");
        kit.getStyleSheet().addRule("th { background-color: " + hex(palette.accentBg()) + "; text-align: left; }");
        var body = new JEditorPane();
        body.setEditorKit(kit);
        body.setFont(font.deriveFont(Font.PLAIN, font.getSize() + 1f));
        body.setEditable(false);
        body.setOpaque(false);
        body.setBackground(palette.bg());
        body.setForeground(palette.text());
        body.setCaretColor(palette.text());
        body.setBorder(new EmptyBorder(1, 0, 1, 0));
        body.getAccessibleContext().setAccessibleName("Assistant response text");
        body.setText("<html><body>" + html + "</body></html>");
        body.setCaretPosition(0);
        body.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && webLink(event.getDescription())) {
                try {
                    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        throw new IOException("Browser unavailable");
                    }
                    Desktop.getDesktop().browse(URI.create(event.getDescription()));
                } catch (IOException | SecurityException e) {
                    JOptionPane.showMessageDialog(body, "Could not open this link. Copy its address into your browser.");
                }
            }
        });
        add(body);
        html.setLength(0);
    }

    private void addCode(String source, String info, Palette palette) {
        String language = info.strip().split("\\s+", 2)[0];
        var panel = new Surface(palette.panel(), palette.border(), 10);
        panel.setLayout(new BorderLayout(0, 5));
        panel.setBorder(new EmptyBorder(6, 8, 8, 8));
        var label = new JLabel(language);
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setForeground(palette.muted());
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN));
        label.setToolTipText(language.isBlank() ? null : language);
        var copy = textButton(palette, "Copy code", false);
        copy.addActionListener(event -> copy(copy, source, "Copy code"));
        var toolbar = new JPanel(new BorderLayout(4, 0));
        toolbar.setOpaque(false);
        toolbar.add(label, BorderLayout.CENTER);
        toolbar.add(copy, BorderLayout.EAST);
        panel.add(toolbar, BorderLayout.NORTH);

        var code = new JTextArea(source);
        code.setEditable(false);
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
        code.setForeground(palette.text());
        code.setBackground(palette.panel());
        code.setCaretColor(palette.text());
        code.setTabSize(4);
        code.setRows(Math.min(12, code.getLineCount()));
        code.setCaretPosition(0);
        code.getAccessibleContext().setAccessibleName(language.isBlank() ? "Code block" : language + " code block");
        var scroll = new JScrollPane(code);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(palette.panel());
        scroll.getHorizontalScrollBar().setUnitIncrement(18);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        panel.add(scroll, BorderLayout.CENTER);
        add(panel);
        add(Box.createVerticalStrut(9));
    }

    static void copy(JButton button, String source, String label) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(source), null);
            button.setText("Copied");
            button.setEnabled(false);
            var reset = new Timer(1500, event -> {
                button.setText(label);
                button.setEnabled(true);
            });
            reset.setRepeats(false);
            reset.start();
        } catch (IllegalStateException | SecurityException e) {
            JOptionPane.showMessageDialog(button, "The clipboard is unavailable. Select the text and copy it.");
        }
    }

    private static String hex(Color color) {
        return String.format("#%06x", color.getRGB() & 0xffffff);
    }

    private static final class LinkRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;

        LinkRenderer(HtmlNodeRendererContext context) {
            this.context = context;
        }

        @Override public Set<Class<? extends Node>> getNodeTypes() { return Set.of(Link.class, Image.class); }

        @Override public void render(Node node) {
            var writer = context.getWriter();
            boolean link = node instanceof Link value && webLink(value.getDestination());
            if (link) {
                writer.tag("a", Map.of("href", ((Link) node).getDestination()));
            }
            // Image alt text is rendered inline. Rendering a message never fetches image URLs.
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
            if (link) {
                writer.tag("/a");
            }
        }
    }
}
