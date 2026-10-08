package com.kingdomsai.minecraft;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.ConstructionSystem;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Construção real: o construtor materializado caminha até a obra e coloca bloco a bloco.
 * Obras longe do jogador avançam no Core; quando a área carrega, os blocos que faltam aparecem (LOD).
 */
public final class ConstructionExecutor {
    private final Map<UUID, Double> accumulator = new HashMap<>();
    private final Random rng = new Random();

    public void tick(ServerLevel level, KingdomsCore core, McWorldPort port, NpcMaterializer materializer) {
        int budget = KingdomsConfig.MAX_BLOCKS_PER_TICK.get();
        double rate = KingdomsConfig.BUILDER_BLOCKS_PER_SECOND.get();
        for (Building b : List.copyOf(core.state().buildings.values())) {
            if (budget <= 0) break;
            if (b.status == Building.Status.ABANDONED || b.status == Building.Status.PLANNED) continue;
            Blueprint bp = b.blueprint();
            if (bp == null) continue;
            boolean active = b.status == Building.Status.UNDER_CONSTRUCTION;
            if (!active && b.placed >= bp.blockCount() && b.install.isEmpty()) continue;
            Pos o = b.origin;
            if (!loaded(level, o.x() - 1, o.z() - 1) || !loaded(level, o.x() + bp.sizeX(), o.z() + bp.sizeZ())) continue;
            if (o.y() == ConstructionSystem.UNKNOWN_Y) {
                var check = port.checkSite(o.x(), o.z(), bp);
                int y = check.groundY() != Integer.MIN_VALUE ? check.groundY() : port.groundY(o.x() + bp.sizeX() / 2, o.z() + bp.sizeZ() / 2);
                core.construction().resolveGround(b, y);
            }
            // 1) materializa o que a simulação já construiu (pendências sem material não aparecem)
            while (b.placed < b.progress && budget > 0) {
                int idx = b.placed;
                if (!b.isPending(idx)) place(level, core, b, bp.placements().get(idx), false);
                if (b.isInstalling(idx)) {
                    b.install.remove(Integer.valueOf(idx));
                    b.touchSets();
                }
                b.placed++;
                budget--;
            }
            // 2) acabamentos que chegaram depois (vidro, camas, sino...): o construtor instala
            if (!b.install.isEmpty() && budget > 0) {
                for (Integer idx : List.copyOf(b.install)) {
                    if (budget <= 0) break;
                    if (idx >= b.placed || idx < 0 || idx >= bp.blockCount()) continue;
                    place(level, core, b, bp.placements().get(idx), true);
                    b.install.remove(idx);
                    budget--;
                }
                b.touchSets();
            }
            if (!active || b.placed < b.progress) continue;
            // 3) construção ao vivo: cada construtor materializado que está na obra soma velocidade
            List<KingdomNpcEntity> workers = new ArrayList<>();
            Pos c = b.centerPos();
            double reach = Math.max(bp.sizeX(), bp.sizeZ()) / 2.0 + 7;
            for (Npc n : core.construction().activeBuilders(b)) {
                if (!n.materialized) continue;
                KingdomNpcEntity e = materializer.entity(n.id);
                if (e == null) continue;
                double dx = e.getX() - c.x(), dz = e.getZ() - c.z();
                if (dx * dx + dz * dz <= reach * reach) workers.add(e);
            }
            if (workers.isEmpty()) continue;
            double acc = accumulator.getOrDefault(b.id, 0.0) + rate * workers.size() / 20.0;
            int guard = 0, turn = 0;
            while (acc >= 1 && budget > 0 && b.status == Building.Status.UNDER_CONSTRUCTION && guard++ < 96) {
                int idx = b.progress;
                Blueprint.Placement p = bp.placements().get(idx);
                int r = core.construction().takeStep(b); // gasta o material do canteiro (ou vira pendência)
                if (r < 0) break;                          // sem material de estrutura: a obra espera
                boolean changed = r > 0 && place(level, core, b, p, true);
                b.placed = b.progress;
                budget--;
                if (changed && p.material() != Material.AIR) {
                    acc -= 1;
                    KingdomNpcEntity e = workers.get(turn++ % workers.size());
                    e.swing(InteractionHand.MAIN_HAND);
                    BlockPos at = toWorld(b, p);
                    e.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
                    level.sendParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, 2, 0.25, 0.2, 0.25, 0.01);
                } else if (changed) acc -= 0.35; // limpar terreno é mais rápido
            }
            accumulator.put(b.id, Math.min(acc, 3));
            if (b.status == Building.Status.COMPLETE) {
                Pos top = b.centerPos();
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, top.x() + 0.5, b.origin.y() + bp.sizeY() * 0.6, top.z() + 0.5,
                        30, bp.sizeX() / 3.0, 1.5, bp.sizeZ() / 3.0, 0.1);
            }
        }
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        return level.isLoaded(new BlockPos(x, 0, z));
    }

    private static BlockPos toWorld(Building b, Blueprint.Placement p) {
        return new BlockPos(b.origin.x() + p.x(), b.origin.y() + p.y(), b.origin.z() + p.z());
    }

    /** @return true se algum bloco mudou. */
    private boolean place(ServerLevel level, KingdomsCore core, Building b, Blueprint.Placement p, boolean sound) {
        BlockPos pos = toWorld(b, p);
        BlockState current = level.getBlockState(pos);
        if (p.material() == Material.AIR) {
            if (current.isAir() || !clearable(current)) return false;
            String salvage = salvageOf(current);
            if (salvage != null) core.construction().salvage(b, salvage, 1); // tora/pedra/areia da limpeza vão para o reino
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return true;
        }
        if (current.hasBlockEntity() && !current.is(Blocks.CHEST) && !current.is(Blocks.BARREL) && !current.is(Blocks.FURNACE)
                && !current.is(Blocks.BELL) && !current.is(BlockTags.BEDS)) return false; // não destrói coisas do jogador
        BlockState target = MaterialPalette.state(p, b.blueprint(), b.variant, rng);
        if (target.isAir() && p.material() != Material.AIR) return false; // bloco proibido/inválido: pula
        if (current.is(target.getBlock()) && p.material() != Material.CROP) return false;
        int flags = Block.UPDATE_ALL;
        switch (p.material()) {
            case DOOR_LOWER, DOOR_UPPER, BED_FOOT, BED_HEAD, LADDER -> flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            case WINDOW, FENCE, ROOF_STAIR -> target = Block.updateFromNeighbourShapes(target, level, pos);
            case RAW -> {
                if (attached(target)) flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE; // metades, tochas, lanternas: não caem
                else if (target.getBlock() instanceof FenceBlock || target.getBlock() instanceof WallBlock || target.getBlock() instanceof IronBarsBlock
                        || target.getBlock() instanceof StairBlock || target.getBlock() instanceof FenceGateBlock)
                    target = Block.updateFromNeighbourShapes(target, level, pos);
            }
            default -> {
            }
        }
        level.setBlock(pos, target, flags);
        if (p.y() == -1) fillBelow(level, pos.below(), MaterialPalette.support(p.material(), b.variant));
        if (sound) level.playSound(null, pos, target.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.6f, 0.9f + rng.nextFloat() * 0.2f);
        return true;
    }

    /** Blocos que dependem de apoio ou de outra metade: colocados sem atualizar vizinhos (não caem nem duplicam). */
    private static boolean attached(BlockState s) {
        Block k = s.getBlock();
        return s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.hasProperty(BlockStateProperties.BED_PART) || k instanceof LanternBlock
                || k instanceof TorchBlock || k instanceof BannerBlock || k instanceof WallBannerBlock || k instanceof LadderBlock
                || k instanceof CarpetBlock || k instanceof BellBlock || k instanceof TrapDoorBlock || k instanceof PressurePlateBlock
                || k instanceof FlowerPotBlock || k instanceof CampfireBlock;
    }

    /** O que a limpeza do terreno rende para o reino (null = nada: terra, grama, folhas, flores). */
    private static String salvageOf(BlockState s) {
        if (s.is(BlockTags.LOGS)) return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE)) return "minecraft:cobblestone";
        if (s.is(Blocks.SAND)) return "minecraft:sand";
        if (s.is(Blocks.RED_SAND)) return "minecraft:red_sand";
        if (s.is(Blocks.GRAVEL)) return "minecraft:gravel";
        return null;
    }

    private static void fillBelow(ServerLevel level, BlockPos start, BlockState support) {
        BlockPos.MutableBlockPos m = start.mutable();
        for (int i = 0; i < 6; i++) {
            BlockState s = level.getBlockState(m);
            if (!(s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty())) break;
            level.setBlock(m, support, Block.UPDATE_CLIENTS);
            m.move(0, -1, 0);
        }
    }

    /** Só limpa blocos naturais — nunca algo que o jogador construiu. */
    private static boolean clearable(BlockState s) {
        if (s.hasBlockEntity()) return false;
        return s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FLOWERS)
                || McWorldPort.isNaturalGround(s) || s.is(Blocks.SNOW) || s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE)
                || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.MOSS_CARPET)
                || s.is(Blocks.BAMBOO) || s.is(BlockTags.SAPLINGS);
    }
}
