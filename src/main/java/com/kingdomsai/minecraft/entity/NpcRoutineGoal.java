package com.kingdomsai.minecraft.entity;

import com.kingdomsai.core.ai.NpcScheduler;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.NpcActivity;
import com.kingdomsai.minecraft.ServerRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * Executa a rotina decidida pelo Core (NpcScheduler): o Core diz O QUE e ONDE,
 * este goal faz o pathfinding e a "atuação" (bater a ferramenta, olhar, parar para conversar).
 */
public class NpcRoutineGoal extends Goal {
    private final KingdomNpcEntity mob;
    private int recalc;
    private BlockPos target;
    private double radius = 4;
    private int stuckTicks;
    private double lastDist = Double.MAX_VALUE;

    public NpcRoutineGoal(KingdomNpcEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.npc() != null && mob.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Npc n = mob.npc();
        ServerRuntime rt = ServerRuntime.get();
        if (n == null || rt == null) return;

        if (mob.isTalking()) {
            mob.getNavigation().stop();
            ServerPlayer p = rt.server().getPlayerList().getPlayer(mob.talkingTo());
            if (p != null) mob.getLookControl().setLookAt(p, 30f, 30f);
            n.activity = NpcActivity.TALKING;
            return;
        }
        if (n.activity == NpcActivity.TALKING) n.activity = NpcActivity.IDLE;

        if (--recalc <= 0) {
            // chamado/seguindo: recalcula mais vezes para acompanhar o rei
            recalc = n.activity == NpcActivity.SUMMONED || n.activity == NpcActivity.MARCH || n.activity == NpcActivity.TRAIN
                    ? 20 : 40 + mob.getRandom().nextInt(40);
            NpcScheduler.Intent intent = rt.core().scheduler().decide(n, mob.level().getDayTime() % 24000);
            n.activity = intent.activity();
            Pos t = intent.target();
            if (t != null) {
                double jitter = Math.max(1, intent.radius());
                int x = t.x() + (int) Math.round((mob.getRandom().nextDouble() * 2 - 1) * jitter);
                int z = t.z() + (int) Math.round((mob.getRandom().nextDouble() * 2 - 1) * jitter);
                if (!mob.level().isLoaded(new BlockPos(x, 0, z))) {
                    // Destino num chunk não carregado (marcha longa, mina distante): anda na direção dele pelo trecho
                    // carregado, 24 blocos por vez. Ao sair do raio de detalhe vira NPC abstrato e segue no Core.
                    double dx = x - mob.getX(), dz = z - mob.getZ(), len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
                    x = (int) Math.round(mob.getX() + dx / len * Math.min(24, len));
                    z = (int) Math.round(mob.getZ() + dz / len * Math.min(24, len));
                }
                if (mob.level().isLoaded(new BlockPos(x, 0, z))) {
                    int y = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    target = new BlockPos(x, y, z);
                    radius = intent.radius();
                }
            }
        }
        if (target == null) return;
        // conversa: para perto do outro e olha para ele (quem fala gesticula de vez em quando)
        if (n.activity == NpcActivity.CHAT && n.talkingWith != null) {
            Npc other = rt.core().npc(n.talkingWith);
            if (other != null && other.pos != null && mob.distanceToSqr(other.pos.x() + 0.5, mob.getY(), other.pos.z() + 0.5) <= 9) {
                mob.getNavigation().stop();
                mob.getLookControl().setLookAt(other.pos.x() + 0.5, other.pos.y() + 1.6, other.pos.z() + 0.5);
                if (mob.tickCount % 60 == 0 && mob.getRandom().nextInt(3) == 0) mob.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }
        double dist = Math.sqrt(mob.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5));
        if (dist > radius + 1) {
            if (mob.getNavigation().isDone() || mob.tickCount % 60 == 0) {
                double speed = n.activity == NpcActivity.FLEE ? 0.95 : n.activity == NpcActivity.SUMMONED ? 0.8 : n.activity == NpcActivity.MARCH ? 0.75
                        : n.activity == NpcActivity.TRAIN ? 0.7 : n.activity == NpcActivity.PATROL ? 0.6
                        : n.energy < 15 || n.health < 40 ? 0.42 : n.activity == NpcActivity.WANDER ? 0.45 : 0.55; // cansado/ferido anda devagar
                mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
            }
            // anti-travamento: se não progride por 30s, teleporta para perto do destino
            if (dist > lastDist - 0.05) stuckTicks++;
            else stuckTicks = 0;
            lastDist = dist;
            if (stuckTicks > 600 && dist > 6) {
                // nunca "teleporta" na frente de alguém: se um jogador está vendo, tenta outro caminho (pula, contorna)
                boolean seen = false;
                for (net.minecraft.world.entity.player.Player p : mob.level().players())
                    if (p.distanceToSqr(mob) < 48 * 48 && p.hasLineOfSight(mob)) seen = true;
                if (seen) {
                    mob.getJumpControl().jump();
                    recalc = 0;
                    stuckTicks = 400;
                } else {
                    mob.teleportTo(target.getX() + 0.5, target.getY() + 0.1, target.getZ() + 0.5);
                    stuckTicks = 0;
                }
            }
        } else {
            stuckTicks = 0;
            lastDist = dist;
            // chegou ao chamado: para e olha para o rei
            if (n.activity == NpcActivity.SUMMONED && n.summonedBy != null) {
                mob.getNavigation().stop();
                ServerPlayer king = rt.server().getPlayerList().getPlayer(n.summonedBy);
                if (king != null) mob.getLookControl().setLookAt(king, 30f, 30f);
            }
            if (n.activity == NpcActivity.WORK && mob.tickCount % 30 == 0 && mob.getRandom().nextInt(3) == 0) {
                mob.swing(InteractionHand.MAIN_HAND);
                mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY() - 0.5, target.getZ() + 0.5);
            }
        }
    }
}
