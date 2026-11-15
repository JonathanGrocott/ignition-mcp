package com.jg.ignition.assistant;

import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.text.View;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.LayoutManager;

/** Measures transcript entries at the viewport width before allocating their height. */
final class TranscriptLayout implements LayoutManager {
    @Override public void addLayoutComponent(String name, Component child) {}
    @Override public void removeLayoutComponent(Component child) {}
    @Override public Dimension minimumLayoutSize(Container parent) { return new Dimension(); }

    @Override public Dimension preferredLayoutSize(Container parent) {
        int width = parent.getParent() instanceof JViewport viewport ? viewport.getExtentSize().width : parent.getWidth();
        int measuredWidth = width > 0 ? width : 320;
        return new Dimension(measuredWidth, height(parent, measuredWidth));
    }

    @Override public void layoutContainer(Container parent) {
        var insets = parent.getInsets();
        int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
        int y = insets.top;
        for (var child : parent.getComponents()) {
            if (child.isVisible()) {
                int height = heightOf(child, width);
                child.setBounds(insets.left, y, width, height);
                y += height;
            }
        }
    }

    private int height(Container parent, int width) {
        var insets = parent.getInsets();
        int inner = Math.max(0, width - insets.left - insets.right);
        int height = insets.top + insets.bottom;
        for (var child : parent.getComponents()) {
            if (child.isVisible()) {
                height += heightOf(child, inner);
            }
        }
        return height;
    }

    private int heightOf(Component child, int width) {
        if (child instanceof JTextArea text && text.getLineWrap()) {
            var insets = text.getInsets();
            var view = text.getUI().getRootView(text);
            view.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
            return (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom;
        }
        if (child instanceof Container container) {
            if (container.getLayout() instanceof TranscriptLayout) {
                return height(container, width);
            }
            if (container.getLayout() instanceof FlowLayout flow) {
                return flowHeight(container, width, flow);
            }
        }
        return child.getPreferredSize().height;
    }

    private static int flowHeight(Container parent, int width, FlowLayout layout) {
        var insets = parent.getInsets();
        int available = width - insets.left - insets.right - 2 * layout.getHgap();
        int rowWidth = 0;
        int rowHeight = 0;
        int height = insets.top + insets.bottom + 2 * layout.getVgap();
        for (var child : parent.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            var size = child.getPreferredSize();
            if (rowWidth > 0 && rowWidth + size.width > available) {
                height += rowHeight + layout.getVgap();
                rowHeight = 0;
                rowWidth = 0;
            }
            rowWidth += size.width + layout.getHgap();
            rowHeight = Math.max(rowHeight, size.height);
        }
        return height + rowHeight;
    }
}
