package com.kingdomsai.minecraft.network;

import com.kingdomsai.minecraft.KingdomsMod;
import com.kingdomsai.minecraft.ServerRuntime;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Pacotes do Manager Mode. O cliente nunca altera o mundo: ele só envia comandos da CLI
 * (os mesmos de /kingdom) e o servidor valida tudo.
 */
public final class Payloads {
    private static final int MAX = 1 << 20;

    private Payloads() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(KingdomsMod.MODID, path);
    }

    /** Cliente pede um snapshot (open = abrir a tela). */
    public record ManagerRequest(boolean open) implements CustomPacketPayload {
        public static final Type<ManagerRequest> TYPE = new Type<>(id("manager_request"));
        public static final StreamCodec<ByteBuf, ManagerRequest> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.open), buf -> new ManagerRequest(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente executa um comando da CLI pelo Manager. */
    public record ManagerAction(String command) implements CustomPacketPayload {
        public static final Type<ManagerAction> TYPE = new Type<>(id("manager_action"));
        public static final StreamCodec<FriendlyByteBuf, ManagerAction> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.command, 1024), buf -> new ManagerAction(buf.readUtf(1024)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Servidor envia o estado do reino (JSON) para o HUD do Manager. */
    public record ManagerSync(String json, boolean open) implements CustomPacketPayload {
        public static final Type<ManagerSync> TYPE = new Type<>(id("manager_sync"));
        public static final StreamCodec<FriendlyByteBuf, ManagerSync> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.json, MAX);
                    buf.writeBoolean(p.open);
                }, buf -> new ManagerSync(buf.readUtf(MAX), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Abre o chat já preenchido (ex.: "/k say ") ao clicar em um NPC. */
    public record OpenChat(String prefill) implements CustomPacketPayload {
        public static final Type<OpenChat> TYPE = new Type<>(id("open_chat"));
        public static final StreamCodec<FriendlyByteBuf, OpenChat> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.prefill, 256), buf -> new OpenChat(buf.readUtf(256)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Cliente pede para entrar/sair do Manager Mode (câmera livre). */
    public record ManagerToggle(boolean on) implements CustomPacketPayload {
        public static final Type<ManagerToggle> TYPE = new Type<>(id("manager_toggle"));
        public static final StreamCodec<ByteBuf, ManagerToggle> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.on), buf -> new ManagerToggle(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Servidor informa se o jogador está no Manager Mode. */
    public record ManagerState(boolean on) implements CustomPacketPayload {
        public static final Type<ManagerState> TYPE = new Type<>(id("manager_state"));
        public static final StreamCodec<ByteBuf, ManagerState> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.on), buf -> new ManagerState(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1").optional();
        r.playToServer(ManagerRequest.TYPE, ManagerRequest.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            ServerRuntime rt = ServerRuntime.get();
            if (rt != null && ctx.player() instanceof ServerPlayer sp) rt.sendManager(sp, p.open(), null);
        }));
        r.playToServer(ManagerAction.TYPE, ManagerAction.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            ServerRuntime rt = ServerRuntime.get();
            if (rt != null && ctx.player() instanceof ServerPlayer sp) rt.managerAction(sp, p.command());
        }));
        r.playToServer(ManagerToggle.TYPE, ManagerToggle.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                if (p.on()) com.kingdomsai.minecraft.ManagerMode.enter(sp);
                else com.kingdomsai.minecraft.ManagerMode.exit(sp);
            }
        }));
        r.playToClient(ManagerState.TYPE, ManagerState.CODEC, (p, ctx) -> ctx.enqueueWork(() ->
                com.kingdomsai.client.ClientHooks.onManagerState(p.on())));
        r.playToClient(ManagerSync.TYPE, ManagerSync.CODEC, (p, ctx) -> ctx.enqueueWork(() ->
                com.kingdomsai.client.ClientHooks.onManagerSync(p.json(), p.open())));
        r.playToClient(OpenChat.TYPE, OpenChat.CODEC, (p, ctx) -> ctx.enqueueWork(() ->
                com.kingdomsai.client.ClientHooks.openChat(p.prefill())));
    }
}
