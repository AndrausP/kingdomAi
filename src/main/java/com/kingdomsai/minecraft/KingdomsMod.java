package com.kingdomsai.minecraft;

import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import com.kingdomsai.minecraft.network.Payloads;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import com.kingdomsai.minecraft.item.KingdomMarkerItem;
import org.slf4j.Logger;

/**
 * Ponto de entrada do mod. Aqui só ficam registros; a lógica do reino vive no Core
 * (com.kingdomsai.core), que não sabe que o Minecraft existe.
 */
@Mod(KingdomsMod.MODID)
public final class KingdomsMod {
    public static final String MODID = "kingdomsai";
    public static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<KingdomNpcEntity>> NPC = ENTITIES.register("npc",
            () -> EntityType.Builder.of(KingdomNpcEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(10)
                    .build(MODID + ":npc"));

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    /** Bandeira do Reino: marca spawn, praça, mina e bosque. */
    public static final DeferredItem<KingdomMarkerItem> MARKER = ITEMS.register("kingdom_marker",
            () -> new KingdomMarkerItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public KingdomsMod(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(KingdomsMod::onCreativeTab);
        modBus.addListener(KingdomsMod::onAttributes);
        modBus.addListener(Payloads::register);
        container.registerConfig(ModConfig.Type.COMMON, KingdomsConfig.SPEC);
        NeoForge.EVENT_BUS.register(ServerEvents.class);
        LOG.info("[KingdomsAI] carregado — o jogador começa como rei.");
    }

    private static void onCreativeTab(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(MARKER.get());
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(NPC.get(), KingdomNpcEntity.createAttributes().build());
    }
}
