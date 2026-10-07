package com.kingdomsai.client;

import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/** NPCs usam o modelo de jogador com as 9 skins padrão do Minecraft (sem texturas novas). */
public class KingdomNpcRenderer extends HumanoidMobRenderer<KingdomNpcEntity, PlayerModel<KingdomNpcEntity>> {
    private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[SKINS.length];

    static {
        for (int i = 0; i < SKINS.length; i++)
            TEXTURES[i] = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/" + SKINS[i] + ".png");
    }

    public KingdomNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(KingdomNpcEntity e) {
        return TEXTURES[Math.floorMod(e.skin(), TEXTURES.length)];
    }
}
