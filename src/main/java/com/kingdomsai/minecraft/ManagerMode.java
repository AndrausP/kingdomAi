package com.kingdomsai.minecraft;

import com.kingdomsai.minecraft.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * MANAGER MODE com câmera livre: o rei "sai do corpo" e voa pelo reino (modo espectador — atravessa paredes,
 * não interage com blocos), com a interface do Manager por cima. O corpo fica marcado por um suporte de armadura
 * com coroa. Ao sair, o jogador volta exatamente para onde estava, no modo de jogo que estava.
 *
 * O estado é guardado nos dados persistentes do jogador: se o jogo fechar no meio, ele é restaurado no próximo login.
 */
public final class ManagerMode {
    private static final String TAG = "kingdomsai_manager";

    private ManagerMode() {}

    public static boolean isOn(ServerPlayer p) {
        return p.getPersistentData().contains(TAG);
    }

    public static void toggle(ServerPlayer p) {
        if (isOn(p)) exit(p);
        else enter(p);
    }

    public static void enter(ServerPlayer p) {
        if (isOn(p)) return;
        CompoundTag t = new CompoundTag();
        t.putString("dim", p.level().dimension().location().toString());
        t.putDouble("x", p.getX());
        t.putDouble("y", p.getY());
        t.putDouble("z", p.getZ());
        t.putFloat("yRot", p.getYRot());
        t.putFloat("xRot", p.getXRot());
        t.putString("mode", p.gameMode.getGameModeForPlayer().getName());
        t.putBoolean("flying", p.getAbilities().flying);
        ArmorStand body = new ArmorStand(p.level(), p.getX(), p.getY(), p.getZ());
        body.setYRot(p.getYRot());
        body.setCustomName(Component.literal("👑 " + p.getGameProfile().getName() + " (governando)").withStyle(ChatFormatting.GOLD));
        body.setCustomNameVisible(true);
        body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        body.setItemSlot(EquipmentSlot.CHEST, p.getItemBySlot(EquipmentSlot.CHEST).copy());
        body.setShowArms(true);
        body.setNoBasePlate(true);
        body.setInvulnerable(true);
        body.addTag(TAG);
        if (p.level().addFreshEntity(body)) t.putUUID("body", body.getUUID());
        p.getPersistentData().put(TAG, t);

        p.setGameMode(GameType.SPECTATOR);
        p.getAbilities().setFlyingSpeed(0.08f);
        p.onUpdateAbilities();
        p.teleportTo((ServerLevel) p.level(), p.getX(), p.getY() + 14, p.getZ(), p.getYRot(), 55f);
        PacketDistributor.sendToPlayer(p, new Payloads.ManagerState(true));
        p.sendSystemMessage(Component.literal("Manager Mode: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("voe com WASD/espaço/shift · roda = velocidade · Alt = interface · clique num súdito = selecionar · M = voltar ao corpo")
                        .withStyle(ChatFormatting.GRAY)));
        ServerRuntime rt = ServerRuntime.get();
        if (rt != null) rt.sendManager(p, false, null);
    }

    public static void exit(ServerPlayer p) {
        CompoundTag t = p.getPersistentData().getCompound(TAG);
        if (!p.getPersistentData().contains(TAG)) return;
        p.getPersistentData().remove(TAG);
        ServerLevel level = p.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("dim"))));
        if (level == null) level = p.server.overworld();
        if (t.hasUUID("body")) {
            Entity body = level.getEntity(t.getUUID("body"));
            if (body != null) body.discard();
        }
        p.getAbilities().setFlyingSpeed(0.05f);
        p.setGameMode(GameType.byName(t.getString("mode"), GameType.SURVIVAL));
        p.teleportTo(level, t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getFloat("yRot"), t.getFloat("xRot"));
        if (t.getBoolean("flying") && p.getAbilities().mayfly) {
            p.getAbilities().flying = true;
        }
        p.onUpdateAbilities();
        PacketDistributor.sendToPlayer(p, new Payloads.ManagerState(false));
    }

    /** No login: se o jogo fechou com o jogador no Manager, devolve o corpo. */
    public static void onLogin(ServerPlayer p) {
        if (isOn(p)) exit(p);
        else PacketDistributor.sendToPlayer(p, new Payloads.ManagerState(false));
    }

    public static void exitAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (isOn(p)) exit(p);
    }

    public static boolean isManagerBody(Entity e) {
        return e.getTags().contains(TAG);
    }

    @SuppressWarnings("unused")
    private static ResourceKey<Level> overworld() {
        return Level.OVERWORLD;
    }

    public static UUID bodyOf(ServerPlayer p) {
        CompoundTag t = p.getPersistentData().getCompound(TAG);
        return t.hasUUID("body") ? t.getUUID("body") : null;
    }
}
