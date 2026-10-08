package com.kingdomsai.minecraft;

import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Blueprint.Facing;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.construction.Palette;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Uma casa significa esses blocos." Tradução de material abstrato → bloco do Minecraft.
 * Plantas paramétricas trazem seus próprios materiais; plantas salvas/.nbt trazem blocos explícitos.
 */
public final class MaterialPalette {
    private MaterialPalette() {}


    /** Blocos que uma planta nunca pode colocar (mesmo vinda de .nbt ou salva do mundo). */
    private static final Set<String> FORBIDDEN = Set.of("command_block", "chain_command_block", "repeating_command_block",
            "structure_block", "jigsaw", "barrier", "bedrock", "tnt", "spawner", "trial_spawner", "end_portal", "end_portal_frame",
            "nether_portal", "end_gateway", "light", "reinforced_deepslate", "lava", "fire", "soul_fire", "respawn_anchor");

    private static final Map<String, BlockState> PARSED = new ConcurrentHashMap<>();

    /**
     * Bloco que a colocação põe no mundo. O texto vem do {@link Palette} do Core (a mesma fonte da lista de materiais:
     * o que se cobra do estoque é exatamente o que aparece) e aqui só vira BlockState com direção/metade/parte.
     */
    public static BlockState state(Blueprint.Placement p, Blueprint bp, int variant, Random rng) {
        Material m = p.material();
        if (m == Material.AIR) return Blocks.AIR.defaultBlockState();
        if (m == Material.RAW) return parse(Palette.resolve(p.block(), variant));
        BlockState s = parse(Palette.block(p, bp, variant));
        if (s.isAir()) return s;
        if (m == Material.FARMLAND && s.hasProperty(FarmBlock.MOISTURE)) s = s.setValue(FarmBlock.MOISTURE, 7);
        if (m == Material.CROP && s.getBlock() instanceof CropBlock crop) s = crop.getStateForAge(rng.nextInt(crop.getMaxAge() + 1));
        return orient(s, m, dir(p.facing()));
    }

    private static Direction dir(Facing f) {
        return switch (f) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            default -> Direction.SOUTH;
        };
    }

    /** Aplica direção/metade/parte quando o bloco tem essas propriedades. */
    private static BlockState orient(BlockState s, Material m, Direction dir) {
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, dir);
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            s = s.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, m == Material.DOOR_UPPER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        if (s.hasProperty(BlockStateProperties.BED_PART))
            s = s.setValue(BlockStateProperties.BED_PART, m == Material.BED_HEAD ? BedPart.HEAD : BedPart.FOOT);
        return s;
    }

    /** "minecraft:oak_stairs[facing=north]" → BlockState (cacheado). Proibidos/inválidos viram ar. */
    public static BlockState parse(String text) {
        if (text == null || text.isBlank()) return Blocks.AIR.defaultBlockState();
        return PARSED.computeIfAbsent(text, t -> {
            try {
                BlockState s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), t, false).blockState();
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
                if (FORBIDDEN.contains(id.getPath())) return Blocks.AIR.defaultBlockState();
                return s;
            } catch (Exception e) {
                KingdomsMod.LOG.warn("[KingdomsAI] bloco inválido na planta: {}", t);
                return Blocks.AIR.defaultBlockState();
            }
        });
    }

    public static boolean isForbidden(BlockState s) {
        return FORBIDDEN.contains(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath());
    }

    /** Aterro sob a fundação: terra cavada ali mesmo (não sai do estoque, e não é pedra do nada). */
    public static BlockState support(Material m, int variant) {
        return Blocks.DIRT.defaultBlockState();
    }
}
