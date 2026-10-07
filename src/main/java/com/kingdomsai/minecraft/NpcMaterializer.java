package com.kingdomsai.minecraft;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * LOD: NPC perto do jogador vira entidade; longe, volta a ser só números no Core.
 * Abstract NPC → restaurar identidade → spawn → carregar profissão/casa/relações → retomar simulação.
 */
public final class NpcMaterializer {
    private final Map<UUID, KingdomNpcEntity> live = new HashMap<>();

    public KingdomNpcEntity entity(UUID npcId) {
        KingdomNpcEntity e = live.get(npcId);
        return e == null || e.isRemoved() ? null : e;
    }

    public int activeCount() {
        return live.size();
    }

    public void tick(ServerLevel level, KingdomsCore core) {
        int radius = KingdomsConfig.NPC_DETAIL_RADIUS.get();
        int max = KingdomsConfig.MAX_ACTIVE_NPCS.get();
        List<ServerPlayer> players = level.players();

        // Desmaterializa quem ficou longe (ou foi removido).
        for (Iterator<Map.Entry<UUID, KingdomNpcEntity>> it = live.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            KingdomNpcEntity e = en.getValue();
            Npc n = core.npc(en.getKey());
            if (e.isRemoved() || n == null || !n.alive) {
                if (n != null) n.materialized = false;
                if (!e.isRemoved()) e.discard();
                it.remove();
                continue;
            }
            double d = nearestPlayerDist(players, e.getX(), e.getZ());
            if (d > radius + 24) {
                n.pos = new Pos(e.getBlockX(), e.getBlockY(), e.getBlockZ());
                n.materialized = false;
                e.discard();
                it.remove();
            }
        }
        if (players.isEmpty()) return;

        // Materializa quem está perto, do mais próximo para o mais distante.
        List<Npc> candidates = new ArrayList<>();
        for (Npc n : core.allAlive()) {
            if (n.pos == null || live.containsKey(n.id)) continue;
            if (nearestPlayerDist(players, n.pos.x(), n.pos.z()) <= radius) candidates.add(n);
            else n.materialized = false;
        }
        candidates.sort(Comparator.comparingDouble(n -> nearestPlayerDist(players, n.pos.x(), n.pos.z())));
        for (Npc n : candidates) {
            if (live.size() >= max) break;
            BlockPos at = spawnPos(level, n.pos);
            if (at == null) continue;
            KingdomNpcEntity e = KingdomsMod.NPC.get().create(level);
            if (e == null) continue;
            e.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360f, 0f);
            e.bind(n);
            if (level.addFreshEntity(e)) {
                live.put(n.id, e);
                n.materialized = true;
            }
        }
    }

    public void despawnAll(KingdomsCore core) {
        for (var en : live.entrySet()) {
            Npc n = core.npc(en.getKey());
            KingdomNpcEntity e = en.getValue();
            if (n != null && !e.isRemoved()) {
                n.pos = new Pos(e.getBlockX(), e.getBlockY(), e.getBlockZ());
                n.materialized = false;
            }
            if (!e.isRemoved()) e.discard();
        }
        live.clear();
    }

    private static double nearestPlayerDist(List<ServerPlayer> players, double x, double z) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : players) {
            // espectadores contam: no Manager Mode o rei voa como espectador e quer ver os NPCs
            double dx = p.getX() - x, dz = p.getZ() - z;
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }

    private static BlockPos spawnPos(ServerLevel level, Pos p) {
        BlockPos base = new BlockPos(p.x(), 0, p.z());
        if (!level.isLoaded(base)) return null;
        if (p.y() != Integer.MIN_VALUE) {
            BlockPos at = new BlockPos(p.x(), p.y(), p.z());
            if (standable(level, at)) return at;
            if (standable(level, at.above())) return at.above();
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x(), p.z());
        return new BlockPos(p.x(), y, p.z());
    }

    private static boolean standable(ServerLevel level, BlockPos at) {
        BlockState feet = level.getBlockState(at), head = level.getBlockState(at.above()), below = level.getBlockState(at.below());
        return feet.getCollisionShape(level, at).isEmpty() && head.getCollisionShape(level, at.above()).isEmpty()
                && !below.getCollisionShape(level, at.below()).isEmpty() && feet.getFluidState().isEmpty();
    }
}
