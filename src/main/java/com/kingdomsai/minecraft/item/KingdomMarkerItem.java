package com.kingdomsai.minecraft.item;

import com.kingdomsai.core.kingdom.Marker;
import com.kingdomsai.minecraft.ChatFormat;
import com.kingdomsai.minecraft.ServerRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Bandeira do Reino: clique num bloco para marcar o ponto (em cima dele) — spawn do reino, praça, mina ou bosque.
 * Shift + clique troca o tipo. Segurando a bandeira, os marcos aparecem como colunas de partículas.
 * A validação (território, chão seguro) é do Core: o item só manda "mark <tipo> x y z".
 */
public class KingdomMarkerItem extends Item {
    public KingdomMarkerItem(Properties props) {
        super(props);
    }

    public static Marker mode(ItemStack stack) {
        String m = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("mode");
        Marker parsed = Marker.parse(m);
        return parsed == null ? Marker.SPAWN : parsed;
    }

    private static void setMode(ItemStack stack, Marker m) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString("mode", m.name()));
    }

    private static void cycle(Player player, ItemStack stack) {
        Marker next = mode(stack).next();
        setMode(stack, next);
        player.displayClientMessage(Component.literal("⚑ Bandeira: " + next.display).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("  (clique num bloco para marcar)").withStyle(ChatFormatting.GRAY)), true);
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.4f);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) cycle(player, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        Level level = ctx.getLevel();
        ItemStack stack = ctx.getItemInHand();
        if (player == null) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) cycle(player, stack);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            BlockPos at = ctx.getClickedPos().relative(ctx.getClickedFace()); // em cima (ou na frente) do bloco clicado
            ServerRuntime rt = ServerRuntime.get();
            if (rt != null) {
                Marker m = mode(stack);
                List<String> out = rt.runCommand(sp, "mark " + m.name().toLowerCase() + " " + at.getX() + " " + at.getY() + " " + at.getZ());
                for (String l : out) sp.sendSystemMessage(ChatFormat.line(l));
                if (!out.isEmpty() && out.get(0).startsWith("✓")) {
                    sp.serverLevel().sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                            24, 0.4, 0.8, 0.4, 0.02);
                    level.playSound(null, at, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1f, 0.8f);
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable(getDescriptionId()).append(Component.literal(" — " + mode(stack).display));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Clique num bloco: marca " + mode(stack).display.toLowerCase()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Shift + clique: troca (spawn → praça → mina → bosque)").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Spawn: novos moradores chegam e o rei renasce ali").withStyle(ChatFormatting.DARK_GRAY));
    }
}
