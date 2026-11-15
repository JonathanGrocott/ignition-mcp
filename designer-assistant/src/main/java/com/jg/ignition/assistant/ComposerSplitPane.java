package com.jg.ignition.assistant;

import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

final class ComposerSplitPane extends JSplitPane {
    ComposerSplitPane(JComponent transcript, JComponent composer, AssistantStyle.Palette palette) {
        super(VERTICAL_SPLIT, true, transcript, composer);
        setUI(new BasicSplitPaneUI() {
            @Override public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics graphics) {
                        var g = (Graphics2D) graphics.create();
                        try {
                            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            g.setColor(palette.bg());
                            g.fillRect(0, 0, getWidth(), getHeight());
                            g.setColor(isMouseOver() || splitPane.hasFocus() ? palette.accent() : palette.muted());
                            g.fillRoundRect((getWidth() - 24) / 2, (getHeight() - 3) / 2, 24, 3, 3, 3);
                        } finally {
                            g.dispose();
                        }
                    }
                };
            }
        });
        setBorder(null);
        setDividerSize(13);
        setResizeWeight(1.0);
        getAccessibleContext().setAccessibleName("Resize message input");
    }
}
