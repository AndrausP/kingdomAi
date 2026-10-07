package com.kingdomsai.client;

import com.kingdomsai.minecraft.KingdomsMod;
import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import com.kingdomsai.minecraft.network.Payloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Cliente: renderizador dos NPCs, teclas e HUD do Manager.
 * M = entra/sai da câmera livre do Manager · Alt = abre/fecha a interface com cursor.
 */
public final class ClientSetup {
    public static final KeyMapping MANAGER_KEY = new KeyMapping("key.kingdomsai.manager", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M, "key.categories.kingdomsai");
    public static final KeyMapping UI_KEY = new KeyMapping("key.kingdomsai.interface", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT, "key.categories.kingdomsai");

    private static int refresh;

    private ClientSetup() {}

    @EventBusSubscriber(modid = KingdomsMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
            e.registerEntityRenderer(KingdomsMod.NPC.get(), KingdomNpcRenderer::new);
        }

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) {
            e.register(MANAGER_KEY);
            e.register(UI_KEY);
        }

        @SubscribeEvent
        public static void layers(RegisterGuiLayersEvent e) {
            e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(KingdomsMod.MODID, "manager_hud"), ManagerHud::render);
        }
    }

    @EventBusSubscriber(modid = KingdomsMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static final class GameBus {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) {
                ClientState.managerOn = false;
                return;
            }
            while (MANAGER_KEY.consumeClick()) {
                if (mc.screen == null) PacketDistributor.sendToServer(new Payloads.ManagerToggle(!ClientState.managerOn));
            }
            while (UI_KEY.consumeClick()) {
                if (mc.screen == null && ClientState.managerOn) {
                    if (ClientState.snapshot != null) mc.setScreen(new ManagerScreen(ClientState.snapshot));
                    PacketDistributor.sendToServer(new Payloads.ManagerRequest(ClientState.snapshot == null));
                }
            }
            if (ClientState.managerOn && mc.screen == null && ++refresh % 40 == 0)
                PacketDistributor.sendToServer(new Payloads.ManagerRequest(false));
        }

        /** No Manager, clique esquerdo seleciona súdito e clique direito abre conversa — nada de bater/espectar. */
        @SubscribeEvent
        public static void click(InputEvent.InteractionKeyMappingTriggered e) {
            if (!ClientState.managerOn) return;
            Minecraft mc = Minecraft.getInstance();
            e.setCanceled(true);
            e.setSwingHand(false);
            if (mc.hitResult instanceof EntityHitResult eh && eh.getEntity() instanceof KingdomNpcEntity npc) {
                if (e.isAttack()) PacketDistributor.sendToServer(new Payloads.ManagerAction("select " + npc.getId()));
                else if (e.isUseItem() && npc.getCustomName() != null) {
                    PacketDistributor.sendToServer(new Payloads.ManagerAction("select " + npc.getId()));
                    ClientHooks.openChat("/k say ");
                }
            }
        }
    }
}
