package com.kingdomsai.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Visual do Manager: painéis translúcidos, cores e botões planos (sem a textura de pedra do Minecraft). */
public final class UiKit {
    public static final int PANEL = 0xB8101820, PANEL_STRONG = 0xE0121A22, BORDER = 0x803E5568, ACCENT = 0xFFE8C15A,
            TEXT = 0xFFE8ECEF, DIM = 0xFF9AA6B0, MUTED = 0xFF6A7682, GOOD = 0xFF6BD06B, WARN = 0xFFE6C24A, BAD = 0xFFE05A5A,
            BLUE = 0xFF4FA3FF;

    private UiKit() {}

    public static void panel(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, PANEL);
        g.fill(x0, y0, x1, y0 + 1, BORDER);
        g.fill(x0, y1 - 1, x1, y1, BORDER);
        g.fill(x0, y0, x0 + 1, y1, BORDER);
        g.fill(x1 - 1, y0, x1, y1, BORDER);
    }

    public static void bar(GuiGraphics g, int x, int y, int w, int h, double pct, int color) {
        g.fill(x, y, x + w, y + h, 0x60000000);
        g.fill(x, y, x + (int) Math.round(w * Math.max(0, Math.min(1, pct))), y + h, color);
    }

    public static int levelColor(double v) {
        return v >= 60 ? GOOD : v >= 35 ? WARN : BAD;
    }

    /** Botão plano com cor de destaque; "selected" pinta de dourado (abas). */
    public static class FlatButton extends Button {
        private final int accent;
        private boolean selected;

        public FlatButton(int x, int y, int w, int h, Component label, OnPress onPress, int accent) {
            super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
            this.accent = accent;
        }

        public FlatButton selected(boolean s) {
            this.selected = s;
            return this;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            boolean hover = isHoveredOrFocused() && active;
            int bg = !active ? 0x80202830 : selected ? 0xE0806020 : hover ? 0xE0304658 : 0xD01A2632;
            g.fill(getX(), getY(), getX() + width, getY() + height, bg);
            int edge = selected ? ACCENT : hover ? accent : 0x80405868;
            g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, edge);
            g.fill(getX(), getY(), getX() + 2, getY() + height, (accent & 0x00FFFFFF) | (hover || selected ? 0xFF000000 : 0x90000000));
            Font font = Minecraft.getInstance().font;
            String text = font.plainSubstrByWidth(getMessage().getString(), width - 8);
            g.drawCenteredString(font, text, getX() + width / 2 + 1, getY() + (height - 8) / 2, active ? (selected ? 0xFFFFF3D0 : TEXT) : MUTED);
        }
    }
}
