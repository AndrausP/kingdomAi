package com.kingdomsai.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Eventos do servidor (NeoForge.EVENT_BUS). */
public final class ServerEvents {
    private ServerEvents() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) {
        ServerRuntime.start(e.getServer());
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        ManagerMode.exitAll(e.getServer());
        ServerRuntime.stop();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp && ManagerMode.isOn(sp)) ManagerMode.exit(sp);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        ServerRuntime rt = ServerRuntime.get();
        if (rt != null) rt.tick();
    }

    @SubscribeEvent
    public static void onSave(LevelEvent.Save e) {
        ServerRuntime rt = ServerRuntime.get();
        if (rt != null && e.getLevel() instanceof ServerLevel sl && sl.dimension() == Level.OVERWORLD) rt.save();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        ServerRuntime rt = ServerRuntime.get();
        if (e.getEntity() instanceof ServerPlayer sp) {
            ManagerMode.onLogin(sp);
            if (rt != null) rt.onPlayerJoin(sp);
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        KingdomCommands.register(e.getDispatcher());
    }
}
