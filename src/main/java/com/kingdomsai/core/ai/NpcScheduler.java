package com.kingdomsai.core.ai;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.NpcActivity;

/**
 * Rotina dos NPCs (Utility AI simples, sem LLM): decide O QUE fazer e ONDE.
 * O adaptador decide COMO (pathfinding, animação).
 */
public final class NpcScheduler {
    private final KingdomsCore core;

    public record Intent(NpcActivity activity, Pos target, double radius) {}

    public NpcScheduler(KingdomsCore core) {
        this.core = core;
    }

    public void tickSecond() {
        long time = core.world().dayTime();
        for (Npc n : core.allAlive()) {
            Intent i = decide(n, time);
            if (n.activity != NpcActivity.TALKING) n.activity = i.activity();
            n.energy = Text.clamp(n.energy + (n.activity == NpcActivity.SLEEP ? 0.25 : -0.02), 0, 100);
            if (!n.materialized && i.target() != null && n.pos != null) {
                // NPC abstrato: "teleporta" gradualmente para o destino (simulação agregada).
                n.pos = moveTowards(n.pos, i.target(), 4);
            }
        }
    }

    private static Pos moveTowards(Pos from, Pos to, int step) {
        int dx = to.x() - from.x(), dz = to.z() - from.z();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= step) return new Pos(to.x(), to.y() == Integer.MIN_VALUE ? from.y() : to.y(), to.z());
        return new Pos(from.x() + (int) Math.round(dx / d * step), from.y(), from.z() + (int) Math.round(dz / d * step));
    }

    public Intent decide(Npc n, long dayTime) {
        Kingdom k = core.kingdom(n.kingdomId);
        if (k == null) return new Intent(NpcActivity.IDLE, n.pos, 4);
        boolean night = dayTime >= 12600 && dayTime < 23400;
        boolean evening = dayTime >= 11000 && dayTime < 12600;
        int h = Math.abs(n.id.hashCode());

        if (night && n.profession != com.kingdomsai.core.npc.Profession.GUARD) {
            Building home = n.homeId == null ? null : core.state().buildings.get(n.homeId);
            if (home != null && home.isComplete() && home.origin.y() != Integer.MIN_VALUE)
                return new Intent(NpcActivity.SLEEP, home.centerPos(), 2);
            return new Intent(NpcActivity.SLEEP, k.center.offset((h % 9) - 4, 0, (h / 9 % 9) - 4), 3);
        }
        if (evening) return new Intent(NpcActivity.SOCIALIZE, k.center.offset((h % 11) - 5, 0, (h / 11 % 7) - 3), 4);

        return switch (n.profession) {
            case BUILDER -> {
                Building b = core.construction().currentProjectOf(n);
                if (b != null) {
                    n.currentTask = "Construir " + b.blueprint().displayName();
                    Pos target = b.origin.y() == Integer.MIN_VALUE ? b.entrance() : b.entrance();
                    yield new Intent(NpcActivity.BUILD, target, 3);
                }
                n.currentTask = "Aguardando obras";
                yield new Intent(NpcActivity.IDLE, k.center.offset(3, 0, -3), 6);
            }
            case FARMER -> {
                Building farm = nearest(k, "farm", n.pos);
                n.currentTask = farm != null ? "Cuidar da fazenda" : "Plantar nos campos";
                yield new Intent(NpcActivity.WORK, farm != null ? farm.centerPos() : workSpot(k, h, 16), farm != null ? 4 : 7);
            }
            case LUMBERJACK -> {
                n.currentTask = "Cortar lenha";
                yield new Intent(NpcActivity.WORK, workSpot(k, h + 3, 28), 8);
            }
            case MINER -> {
                n.currentTask = "Extrair pedra e ferro";
                yield new Intent(NpcActivity.WORK, workSpot(k, h + 7, 24), 6);
            }
            case BLACKSMITH -> {
                Building smithy = nearest(k, "smithy", n.pos);
                n.currentTask = "Forjar armas";
                yield new Intent(NpcActivity.WORK, smithy != null ? smithy.centerPos() : k.center.offset(-5, 0, -5), 2);
            }
            case GUARD -> {
                n.currentTask = "Patrulhar o reino";
                long phase = (core.tick() / (20 * 40) + h) % 6;
                double a = phase * Math.PI / 3;
                int r = 22;
                yield new Intent(NpcActivity.PATROL, k.center.offset((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r)), 3);
            }
            case SOLDIER -> {
                Building barracks = nearest(k, "barracks", n.pos);
                n.currentTask = "Treinar";
                yield new Intent(NpcActivity.GUARD, barracks != null ? barracks.entrance() : k.center.offset(-6, 0, 6), 4);
            }
            case MERCHANT, PRIEST, SCHOLAR -> {
                n.currentTask = n.profession.display;
                yield new Intent(NpcActivity.WORK, k.center.offset((h % 7) - 3, 0, (h / 7 % 5) - 2), 3);
            }
            default -> {
                n.currentTask = "Ajudar na vila";
                yield new Intent(NpcActivity.IDLE, k.center.offset((h % 15) - 7, 0, (h / 15 % 15) - 7), 8);
            }
        };
    }

    /** Lugar de trabalho "na natureza" (bosque, pedreira) numa direção estável por NPC. */
    private Pos workSpot(Kingdom k, int hash, int dist) {
        double a = (hash % 360) * Math.PI / 180.0;
        return k.center.offset((int) (Math.cos(a) * dist), 0, (int) (Math.sin(a) * dist));
    }

    private Building nearest(Kingdom k, String blueprintId, Pos from) {
        Building best = null;
        double bd = Double.MAX_VALUE;
        for (Building b : core.buildings(k.id)) {
            if (!b.isComplete() || !b.blueprintId.equals(blueprintId) || b.origin.y() == Integer.MIN_VALUE) continue;
            double d = from == null ? 0 : b.centerPos().distSq(from);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }
}
