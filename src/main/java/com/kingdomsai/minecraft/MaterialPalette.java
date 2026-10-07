package com.kingdomsai.minecraft;

import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Blueprint.Facing;
import com.kingdomsai.core.construction.Material;
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

    private static final Block[] WALLS = {Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS};
    private static final Block[] PILLARS = {Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.STRIPPED_DARK_OAK_LOG};
    private static final Block[] ROOFS = {Blocks.DARK_OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BRICKS};
    private static final Block[] ROOF_STAIRS = {Blocks.DARK_OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BRICK_STAIRS};
    private static final Block[] FOUNDATIONS = {Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE};
    private static final Block[] DOORS = {Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.BIRCH_DOOR};
    private static final Block[] BEDS = {Blocks.RED_BED, Blocks.BLUE_BED, Blocks.GREEN_BED};

    /** Blocos que uma planta nunca pode colocar (mesmo vinda de .nbt ou salva do mundo). */
    private static final Set<String> FORBIDDEN = Set.of("command_block", "chain_command_block", "repeating_command_block",
            "structure_block", "jigsaw", "barrier", "bedrock", "tnt", "spawner", "trial_spawner", "end_portal", "end_portal_frame",
            "nether_portal", "end_gateway", "light", "reinforced_deepslate", "lava", "fire", "soul_fire", "respawn_anchor");

    private static final Map<String, BlockState> PARSED = new ConcurrentHashMap<>();

    public static BlockState state(Blueprint.Placement p, Blueprint bp, int variant, Random rng) {
        Material m = p.material();
        Direction dir = dir(p.facing());
        if (m == Material.RAW) return parse(p.block());
        String override = bp.materials() == null ? null : bp.materials().get(m);
        if (override != null) {
            BlockState s = parse(override);
            if (!s.isAir()) return orient(s, m, dir);
        }
        int v = Math.floorMod(variant, 3);
        return switch (m) {
            case AIR, RAW -> Blocks.AIR.defaultBlockState();
            case FOUNDATION -> FOUNDATIONS[v].defaultBlockState();
            case FLOOR -> WALLS[(v + 1) % 3].defaultBlockState();
            case WALL -> WALLS[v].defaultBlockState();
            case PILLAR -> PILLARS[v].defaultBlockState();
            case ROOF, ROOF_EDGE -> ROOFS[v].defaultBlockState();
            case ROOF_STAIR -> orient(ROOF_STAIRS[v].defaultBlockState(), m, dir);
            case WINDOW -> Blocks.GLASS_PANE.defaultBlockState();
            case DOOR_LOWER, DOOR_UPPER -> orient(DOORS[v].defaultBlockState(), m, dir);
            case LIGHT -> Blocks.TORCH.defaultBlockState();
            case BED_FOOT, BED_HEAD -> orient(BEDS[v].defaultBlockState(), m, dir);
            case CHEST -> Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, dir);
            case BARREL -> Blocks.BARREL.defaultBlockState();
            case CRAFTING -> Blocks.CRAFTING_TABLE.defaultBlockState();
            case FURNACE -> Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, dir);
            case ANVIL -> Blocks.ANVIL.defaultBlockState();
            case FARMLAND -> Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
            case CROP -> Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, rng.nextInt(8));
            case WATER -> Blocks.WATER.defaultBlockState();
            case FENCE -> Blocks.OAK_FENCE.defaultBlockState();
            case PATH -> Blocks.DIRT_PATH.defaultBlockState();
            case BELL -> Blocks.BELL.defaultBlockState();
            case HAY -> Blocks.HAY_BLOCK.defaultBlockState();
            case BANNER -> Blocks.WHITE_BANNER.defaultBlockState();
            case LADDER -> Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, dir);
            case CHIMNEY -> Blocks.BRICKS.defaultBlockState();
            case BOOKSHELF -> Blocks.BOOKSHELF.defaultBlockState();
            case LECTERN -> Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, dir);
        };
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

    /** Bloco usado para preencher buracos sob a fundação. */
    public static BlockState support(Material m, int variant) {
        return m == Material.FOUNDATION ? FOUNDATIONS[Math.floorMod(variant, 3)].defaultBlockState() : Blocks.DIRT.defaultBlockState();
    }
}
