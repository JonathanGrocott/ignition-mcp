package com.jg.ignition.assistant;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownReplyTest {
    @Test
    void rendersMarkdownAndTablesWhileKeepingUntrustedHtmlAndImageUrlsInert() {
        String html = MarkdownReply.renderHtml("""
            ## Project overview

            **Tags** and *views* use `Utilities/Cooling`.

            1. Inspect the current project.
            2. Review the change.

            | Resource | Scope |
            | --- | --- |
            | UDT | Gateway |

            [Manual](https://www.docs.inductiveautomation.com/docs/8.3)
            [Unsafe](javascript:alert%281%29)
            ![Image description](https://example.invalid/image.png)

            <img src="file:///private/example">
            <script>ignored()</script>
            """);
        assertTrue(html.contains("<h2>Project overview</h2>"));
        assertTrue(html.contains("<strong>Tags</strong>"));
        assertTrue(html.contains("<em>views</em>"));
        assertTrue(html.contains("<code>Utilities/Cooling</code>"));
        assertTrue(html.contains("<ol>"));
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("href=\"https://www.docs.inductiveautomation.com/docs/8.3\""));
        assertFalse(html.contains("href=\"javascript:"));
        assertTrue(html.contains("Image description"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("<script>"));
    }

    @Test
    void onlyActivatesValidHttpLinks() {
        assertTrue(MarkdownReply.webLink("https://www.docs.inductiveautomation.com/docs/8.3?q=tags#udts"));
        assertTrue(MarkdownReply.webLink("http://localhost:8088/"));
        for (String link : List.of("file:///tmp/script.py", "javascript:alert(1)", "data:text/html,test",
                "/relative", "https://user:pass@example.com", "https://example.com/invalid space")) {
            assertFalse(MarkdownReply.webLink(link), link);
        }
    }

    @Test
    void codeBlocksPreserveContentAndOfferCopyActions() throws Exception {
        edt(() -> {
            String source = "def read_status():\n\treturn '<Motor & Pump>'\n";
            var reply = new MarkdownReply("## Example\n\n```python\n" + source + "```\n\nRead `Running`.",
                AssistantStyle.Palette.current());
            var code = children(reply).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                .findFirst().orElseThrow();
            assertEquals(source, code.getText());
            assertEquals(4, code.getTabSize());
            assertFalse(code.getLineWrap());
            assertFalse(code.isEditable());
            assertTrue(children(reply).filter(JButton.class::isInstance).map(JButton.class::cast)
                .anyMatch(button -> "Copy code".equals(button.getText())));
            var prose = children(reply).filter(JEditorPane.class::isInstance).map(JEditorPane.class::cast).toList();
            assertEquals(2, prose.size());
            assertFalse(prose.get(0).isEditable());
            assertEquals("Example", prose.get(0).getDocument()
                .getText(0, prose.get(0).getDocument().getLength()).strip());
            return null;
        });
    }

    @Test
    void formattedRepliesWrapAsTheDockNarrows() throws Exception {
        edt(() -> {
            var reply = new MarkdownReply("""
                ## Current project

                Inspect the saved Perspective view and its existing tag bindings before preparing a change.
                Keep the current layout, navigation, parameters, and component event scripts.

                - Review the current bindings and tag paths.
                - Check the scripts and Named Queries used by the view.

                ```python
                tagPath = "[default]Utilities/Cooling/Pump/Running"
                ```
                """, AssistantStyle.Palette.current());
            var transcript = new JPanel(new TranscriptLayout());
            transcript.add(reply);
            transcript.setSize(500, 2000);
            transcript.doLayout();
            int wideHeight = reply.getHeight();
            transcript.setSize(220, 2000);
            transcript.doLayout();
            assertTrue(reply.getHeight() > wideHeight, "Narrow replies need more height");
            assertEquals(220, reply.getWidth());
            reply.doLayout();
            var body = children(reply).filter(JEditorPane.class::isInstance).map(JEditorPane.class::cast)
                .findFirst().orElseThrow();
            assertTrue(body.getHeight() > 0);
            assertTrue(body.getWidth() <= reply.getWidth());
            return null;
        });
    }

    @Test
    void longRepliesHaveABoundedInitialLayoutBeforeTheViewportGetsItsWidth() throws Exception {
        edt(() -> {
            String text = "The pump’s state → inspect the saved resource and review its tag bindings. ".repeat(140);
            var reply = new MarkdownReply("## Project overview\n\n" + text, AssistantStyle.Palette.current());
            var body = children(reply).filter(JEditorPane.class::isInstance).map(JEditorPane.class::cast)
                .findFirst().orElseThrow();
            body.getDocument().putProperty("i18n", Boolean.TRUE);
            body.setText(body.getText());
            var transcript = new JPanel(new TranscriptLayout());
            transcript.add(reply);
            transcript.setSize(0, 1000);
            transcript.doLayout();
            assertTrue(reply.getHeight() < 20_000, "Initial measurement must use a usable line width");
            transcript.setSize(340, 1000);
            transcript.doLayout();
            assertTrue(reply.getHeight() > 0);
            return null;
        });
    }

    private static Stream<Component> children(Component component) {
        return component instanceof Container parent
            ? Stream.concat(Stream.of(component), Stream.of(parent.getComponents()).flatMap(MarkdownReplyTest::children))
            : Stream.of(component);
    }

    private static <T> T edt(Callable<T> body) throws Exception {
        var task = new FutureTask<>(body);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
