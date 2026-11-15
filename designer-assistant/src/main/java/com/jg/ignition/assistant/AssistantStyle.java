package com.jg.ignition.assistant;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

final class AssistantStyle {
    private AssistantStyle() {}

    static JPanel row(JComponent... components) {
        return row(FlowLayout.LEADING, components);
    }

    static JPanel row(int alignment, JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(alignment, 4, 0));
        row.setOpaque(false);
        for (JComponent component : components) {
            row.add(component);
        }
        return row;
    }

    static JButton iconButton(Palette palette, String name, Glyph glyph, boolean primary) {
        var button = new DockButton(palette, "", primary);
        button.setIcon(glyph);
        button.setDisabledIcon(glyph);
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
        return button;
    }

    static JButton textButton(Palette palette, String label, boolean primary) {
        return new DockButton(palette, label, primary);
    }

    private static final class DockButton extends JButton {
        private final Palette palette;
        private final boolean primary;

        DockButton(Palette palette, String label, boolean primary) {
            super(label);
            this.palette = palette;
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

    enum Glyph implements Icon {
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

    static final class Surface extends JPanel {
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

    record Palette(Color bg, Color panel, Color text, Color muted, Color border,
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
