package com.kingdomsai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/** Chamado pelos handlers de rede — só existe no cliente. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void onManagerSync(String json, boolean open) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject data;
        try {
            data = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        ClientState.snapshot = data;
        if (mc.screen instanceof ManagerScreen ms) ms.update(data);
        else if (open && mc.screen == null) mc.setScreen(new ManagerScreen(data));
    }

    public static void onManagerState(boolean on) {
        ClientState.managerOn = on;
        Minecraft mc = Minecraft.getInstance();
        if (!on && mc.screen instanceof ManagerScreen) mc.setScreen(null);
    }

    public static void openChat(String prefill) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null || mc.screen instanceof ManagerScreen) mc.setScreen(new ChatScreen(prefill));
    }
}
