package com.kingdomsai.minecraft;

import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.port.WorldPort;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

/** Implementação da porta do Core sobre o Overworld. Só lê o mundo — nunca carrega chunks. */
public final class McWorldPort implements WorldPort {
    private final ServerLevel level;

    public McWorldPort(ServerLevel level) {
        this.level = level;
    }

    @Override
    public long dayTime() {
        return level.getDayTime() % 24000L;
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return level.isLoaded(new BlockPos(x, 0, z));
    }

    @Override
    public int surfaceY(int x, int z) {
        if (!isLoaded(x, z)) return Integer.MIN_VALUE;
        return groundY(x, z);
    }

    /** Primeiro bloco de ar acima do chão "natural" (ignora troncos, folhas, plantas e neve fina). */
    public int groundY(int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int min = level.getMinBuildHeight();
        for (int i = 0; i < 24 && y > min; i++) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.canBeReplaced() || s.is(Blocks.SNOW)
                    || s.is(BlockTags.FLOWERS) || s.is(Blocks.BAMBOO) || s.is(Blocks.CACTUS)) {
                if (!s.getFluidState().isEmpty()) break; // água: o chão é aqui (será BAD)
                y--;
            } else break;
        }
        return y + 1;
    }

    public static boolean isNaturalGround(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.CLAY) || s.is(Blocks.SANDSTONE)
                || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND) || s.is(Blocks.PACKED_ICE)
                || s.is(Blocks.MUD) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE);
    }

    @Override
    public SiteCheck checkSite(int x, int z, Blueprint bp) {
        int w = bp.sizeX(), d = bp.sizeZ();
        if (!isLoaded(x - 1, z - 1) || !isLoaded(x + w, z - 1) || !isLoaded(x - 1, z + d) || !isLoaded(x + w, z + d))
            return SiteCheck.unloaded();
        int[] heights = new int[w * d];
        int i = 0;
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++) {
                int gy = groundY(x + dx, z + dz);
                BlockState ground = level.getBlockState(new BlockPos(x + dx, gy - 1, z + dz));
                if (!ground.getFluidState().isEmpty() || !isNaturalGround(ground)) return SiteCheck.bad();
                heights[i++] = gy;
            }
        int[] sorted = heights.clone();
        Arrays.sort(sorted);
        int range = sorted[sorted.length - 1] - sorted[0];
        if (range > 4) return SiteCheck.bad();
        int median = sorted[sorted.length / 2];
        return new SiteCheck(SiteCheck.Kind.OK, median, 1.0 / (1 + range));
    }
}
