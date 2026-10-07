package com.kingdomsai.minecraft;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Converte as linhas de texto puro do Core em mensagens coloridas no chat. */
public final class ChatFormat {
    private ChatFormat() {}

    public static MutableComponent line(String s) {
        if (s.startsWith("# ")) return Component.literal("━ " + s.substring(2) + " ━").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (s.startsWith("✓")) return Component.literal(s).withStyle(ChatFormatting.GREEN);
        if (s.startsWith("✗")) return Component.literal(s).withStyle(ChatFormatting.RED);
        if (s.startsWith("⚠")) return Component.literal(s).withStyle(ChatFormatting.YELLOW);
        if (s.startsWith("‼")) return Component.literal(s).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        if (s.startsWith("‹")) return Component.literal(s).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        if (s.startsWith("(")) return Component.literal(s).withStyle(ChatFormatting.DARK_GRAY);
        if (s.startsWith("«")) {
            int end = s.indexOf('»');
            if (end > 0)
                return Component.literal(s.substring(0, end + 1)).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                        .append(Component.literal(s.substring(end + 1)).withStyle(ChatFormatting.WHITE));
        }
        return Component.literal(s).withStyle(ChatFormatting.WHITE);
    }

    public static MutableComponent prefixed(String s) {
        return Component.literal("[Reino] ").withStyle(ChatFormatting.DARK_AQUA).append(line(s));
    }
}
