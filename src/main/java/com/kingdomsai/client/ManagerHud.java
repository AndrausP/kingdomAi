package com.kingdomsai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kingdomsai.minecraft.KingdomsConfig;
import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;

import java.util.Locale;

import static com.kingdomsai.client.UiKit.*;

/**
 * HUD do Manager Mode enquanto o rei voa com a câmera livre:
 * barra de recursos no topo, eventos à direita, obras com prazo à esquerda, ficha do que a mira aponta embaixo.
 * Com o Xaero's Minimap instalado, o canto superior esquerdo fica livre para o minimapa.
 */
public final class ManagerHud {
    private ManagerHud() {}

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientState.managerOn || mc.screen != null || mc.options.hideGui || mc.player == null) return;
        JsonObject d = ClientState.snapshot;
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        if (d == null || !d.has("kingdom")) {
            panel(g, w / 2 - 110, 4, w / 2 + 110, 20);
            g.drawCenteredString(font, "MANAGER MODE — carregando reino...", w / 2, 8, ACCENT);
            hints(g, font, w, h);
            return;
        }
        JsonObject k = d.getAsJsonObject("kingdom");
        int reserve = Math.min(minimapReserve(), w / 3);

        // --- barra superior
        panel(g, reserve, 0, w, 20);
        String title = "👑 " + k.get("name").getAsString() + "  ·  Dia " + k.get("day").getAsLong();
        g.drawString(font, title, reserve + 6, 6, ACCENT);
        int x = reserve + 12 + font.width(title);
        x = chip(g, font, x, "Pop", k.get("pop").getAsInt() + "/" + k.get("cap").getAsInt(), k.get("pop").getAsInt() > k.get("cap").getAsInt() ? WARN : TEXT);
        for (JsonElement e : d.getAsJsonArray("res")) {
            JsonObject r = e.getAsJsonObject();
            double v = r.get("value").getAsDouble(), dl = r.get("delta").getAsDouble();
            String val = String.format(Locale.ROOT, "%.0f", v) + (Math.abs(dl) < 0.05 ? "" : String.format(Locale.ROOT, " %+.1f", dl));
            x = chip(g, font, x, r.get("name").getAsString(), val, dl < -0.05 ? WARN : TEXT);
            if (x > w - 140) break;
        }
        x = chip(g, font, x, "Moral", String.valueOf((int) k.get("morale").getAsDouble()), levelColor(k.get("morale").getAsDouble()));
        chip(g, font, x, "Estab.", String.valueOf((int) k.get("stability").getAsDouble()), levelColor(k.get("stability").getAsDouble()));

        // --- obras (esquerda)
        JsonArray projects = d.has("projects") ? d.getAsJsonArray("projects") : new JsonArray();
        int y = Math.max(26, reserve + 4);
        if (!projects.isEmpty()) {
            int pw = 170;
            int rows = Math.min(5, projects.size());
            panel(g, 4, y, 4 + pw, y + 14 + rows * 22);
            g.drawString(font, "OBRAS", 9, y + 4, ACCENT);
            y += 15;
            for (int i = 0; i < rows; i++) {
                JsonObject p = projects.get(i).getAsJsonObject();
                boolean late = p.get("late").getAsBoolean();
                g.drawString(font, font.plainSubstrByWidth("#" + p.get("n").getAsInt() + " " + p.get("name").getAsString(), pw - 40), 9, y, TEXT);
                String pct = (int) p.get("pct").getAsDouble() + "%";
                g.drawString(font, pct, 4 + pw - 5 - font.width(pct), y, DIM);
                bar(g, 9, y + 10, pw - 14, 3, p.get("pct").getAsDouble() / 100.0, late ? BAD : ACCENT);
                String sub = "ETA " + p.get("eta").getAsString() + (p.get("deadline").getAsString().isEmpty() ? "" : " · prazo " + p.get("deadline").getAsString());
                g.drawString(font, font.plainSubstrByWidth(sub, pw - 12), 9, y + 13, late ? BAD : MUTED);
                y += 22;
            }
        }

        // --- súdito selecionado
        String sel = d.has("selected") ? d.get("selected").getAsString() : "";
        JsonObject sn = sel.isEmpty() ? null : npc(d, sel);
        if (sn != null) {
            y += 6;
            int pw = 170;
            panel(g, 4, y, 4 + pw, y + 44);
            g.drawString(font, font.plainSubstrByWidth(sn.get("name").getAsString() + " — " + sn.get("title").getAsString(), pw - 10), 9, y + 4, ACCENT);
            g.drawString(font, font.plainSubstrByWidth(sn.get("act").getAsString() + ": " + sn.get("task").getAsString(), pw - 10), 9, y + 16, DIM);
            g.drawString(font, "Lealdade " + sn.get("loyalty").getAsInt() + " · fama " + sn.get("fame").getAsInt(), 9, y + 28, levelColor(sn.get("loyalty").getAsInt()));
        }

        // --- rotinas / cadeias de trabalho (esquerda, abaixo das obras)
        JsonArray chains = d.has("chains") ? d.getAsJsonArray("chains") : new JsonArray();
        if (!chains.isEmpty()) {
            y += sn != null ? 50 : 6;
            int pw = 170;
            int rows = Math.min(3, chains.size());
            int lines = 0;
            for (int i = 0; i < rows; i++) {
                JsonObject c = chains.get(i).getAsJsonObject();
                lines += 1 + (c.get("broken").getAsBoolean() ? 1 : Math.min(2, c.getAsJsonArray("roles").size()));
            }
            panel(g, 4, y, 4 + pw, y + 14 + lines * 10 + rows * 2);
            g.drawString(font, "ROTINAS", 9, y + 4, ACCENT);
            int ly = y + 15;
            for (int i = 0; i < rows; i++) {
                JsonObject c = chains.get(i).getAsJsonObject();
                boolean broken = c.get("broken").getAsBoolean();
                String head = (broken ? "⚠ " : "⛓ ") + "#" + c.get("n").getAsInt() + " " + c.get("name").getAsString()
                        + (c.get("cycles").getAsInt() > 0 ? " ×" + c.get("cycles").getAsInt() : "");
                g.drawString(font, font.plainSubstrByWidth(head, pw - 10), 9, ly, broken ? BAD : GOOD);
                ly += 10;
                JsonArray roles = c.getAsJsonArray("roles");
                if (broken) {
                    g.drawString(font, font.plainSubstrByWidth("quebrou: " + c.get("reason").getAsString(), pw - 14), 13, ly, WARN);
                    ly += 10;
                } else for (int j = 0; j < Math.min(2, roles.size()); j++) {
                    g.drawString(font, font.plainSubstrByWidth(roles.get(j).getAsString(), pw - 14), 13, ly, DIM);
                    ly += 10;
                }
                ly += 2;
            }
        }

        // --- eventos (direita)
        JsonArray ev = d.getAsJsonArray("events");
        int ew = 180, ex = w - ew - 4;
        int ey = 26;
        int shown = 0;
        for (int i = ev.size() - 1; i >= 0 && shown < 6; i--, shown++) {
            JsonObject e = ev.get(i).getAsJsonObject();
            int c = switch (e.get("sev").getAsString()) {
                case "GOOD" -> GOOD;
                case "WARN" -> WARN;
                case "DANGER" -> BAD;
                default -> DIM;
            };
            var lines = font.split(Component.literal(e.get("icon").getAsString() + " " + e.get("msg").getAsString()), ew - 10);
            int bh = lines.size() * 10 + 6;
            g.fill(ex, ey, ex + ew, ey + bh, PANEL);
            g.fill(ex, ey, ex + 2, ey + bh, c);
            int ly = ey + 3;
            for (FormattedCharSequence l : lines) {
                g.drawString(font, l, ex + 6, ly, c == DIM ? TEXT : c);
                ly += 10;
            }
            ey += bh + 2;
        }

        // --- o que a mira aponta
        target(g, font, mc, d, w, h);
        hints(g, font, w, h);
    }

    /** Largura/altura livre no canto superior esquerdo para o minimapa (config compat.minimap_reserve). */
    private static int minimapReserve() {
        int r = KingdomsConfig.MINIMAP_RESERVE.get();
        if (r >= 0) return r;
        if (xaero == null) xaero = ModList.get().isLoaded("xaerominimap") || ModList.get().isLoaded("xaerominimapfair");
        return xaero ? 140 : 0;
    }

    private static Boolean xaero;

    private static int chip(GuiGraphics g, Font font, int x, String label, String value, int color) {
        g.drawString(font, label, x, 6, MUTED);
        x += font.width(label) + 3;
        g.drawString(font, value, x, 6, color);
        return x + font.width(value) + 10;
    }

    private static void hints(GuiGraphics g, Font font, int w, int h) {
        String s = "[WASD/Espaço/Shift] voar   [Roda] velocidade   [Alt] interface   [Clique] selecionar   [Botão direito] falar   [M] voltar ao corpo";
        int tw = Math.min(w - 8, font.width(s) + 12);
        panel(g, w / 2 - tw / 2, h - 16, w / 2 + tw / 2, h - 2);
        g.drawCenteredString(font, font.plainSubstrByWidth(s, tw - 8), w / 2, h - 13, DIM);
    }

    private static void target(GuiGraphics g, Font font, Minecraft mc, JsonObject d, int w, int h) {
        HitResult hit = mc.hitResult;
        String title = null, line1 = null, line2 = null;
        double pct = -1;
        if (hit instanceof EntityHitResult eh && eh.getEntity() instanceof KingdomNpcEntity e && e.getCustomName() != null) {
            String name = e.getCustomName().getString().split(" \\[")[0];
            JsonObject n = npc(d, name);
            title = e.getCustomName().getString();
            if (n != null) {
                line1 = n.get("act").getAsString() + (n.get("task").getAsString().isBlank() ? "" : ": " + n.get("task").getAsString());
                line2 = "Lealdade " + n.get("loyalty").getAsInt() + (n.get("mine").getAsBoolean() ? "" : " · estrangeiro") + " · clique = selecionar · direito = falar";
            }
        } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = bh.getBlockPos();
            for (JsonElement el : d.getAsJsonArray("buildings")) {
                JsonObject b = el.getAsJsonObject();
                int bx = b.get("x").getAsInt(), bz = b.get("z").getAsInt(), by = b.has("y") ? b.get("y").getAsInt() : p.getY();
                if (p.getX() >= bx - 1 && p.getX() <= bx + b.get("w").getAsInt() && p.getZ() >= bz - 1 && p.getZ() <= bz + b.get("d").getAsInt()
                        && p.getY() >= by - 2 && p.getY() <= by + (b.has("h") ? b.get("h").getAsInt() : 12)) {
                    title = b.get("name").getAsString();
                    boolean done = b.get("done").getAsBoolean();
                    pct = done ? 1 : b.get("pct").getAsDouble() / 100.0;
                    line1 = done ? "Concluída" : String.format(Locale.ROOT, "Em obra: %.0f%% · ETA %s", b.get("pct").getAsDouble(), b.has("eta") ? b.get("eta").getAsString() : "?");
                    break;
                }
            }
        }
        if (title == null) return;
        int cw = 220, cx = w / 2 - cw / 2, cy = h - 64;
        panel(g, cx, cy, cx + cw, cy + 42);
        g.drawCenteredString(font, font.plainSubstrByWidth(title, cw - 10), w / 2, cy + 4, ACCENT);
        if (line1 != null) g.drawCenteredString(font, font.plainSubstrByWidth(line1, cw - 10), w / 2, cy + 16, TEXT);
        if (pct >= 0) bar(g, cx + 8, cy + 30, cw - 16, 4, pct, pct >= 1 ? GOOD : ACCENT);
        else if (line2 != null) g.drawCenteredString(font, font.plainSubstrByWidth(line2, cw - 10), w / 2, cy + 28, MUTED);
    }

    static JsonObject npc(JsonObject d, String name) {
        if (d == null || !d.has("npcs")) return null;
        for (JsonElement e : d.getAsJsonArray("npcs")) {
            JsonObject n = e.getAsJsonObject();
            if (n.get("name").getAsString().equals(name)) return n;
        }
        return null;
    }
}
