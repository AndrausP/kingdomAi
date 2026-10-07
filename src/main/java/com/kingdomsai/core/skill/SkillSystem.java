package com.kingdomsai.core.skill;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.ai.NpcScheduler;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.NpcActivity;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.port.PhysicalPort;
import com.kingdomsai.core.port.PhysicalPort.BlockInfo;
import com.kingdomsai.core.port.PhysicalPort.Recipe;
import com.kingdomsai.core.work.ChainValidator;
import com.kingdomsai.core.work.Place;

import java.util.*;
import java.util.function.Predicate;

/**
 * Executa as ordens físicas, um segundo por vez, como um jogador faria: vai até o bloco, bate com a
 * ferramenta (o bloco racha), junta o que caiu na mochila, abre o baú, fabrica na bancada, entrega ao rei.
 *
 * Regras de jogo:
 * - ordem direta do rei passa na frente da rotina e do sono; um chamado (tecla G) só PAUSA a ordem;
 * - tudo é revalidado na hora: se alguém colocou um baú ou começou uma obra ali, o bloco é poupado;
 * - bloco que não dá para alcançar em {@link #BLOCK_TIMEOUT} s é pulado (não trava a ordem);
 * - mochila cheia → esvazia no baú do armazém e volta; sem armazém, espera e depois desiste;
 * - longe do rei a área descarrega: a ordem espera (não falha) até ele voltar.
 */
public final class SkillSystem {
    public static final int BLOCK_TIMEOUT = 20;
    public static final int MOVE_TIMEOUT = 120;
    public static final int WAIT_FAIL = 90;
    /** Além disso o rei está "longe" para receber em mãos: o súdito guarda no armazém. */
    public static final int GIVE_RANGE = 96;

    private final KingdomsCore core;
    private final ChunkKeeper chunks;
    private boolean chunksDirty = true;

    public SkillSystem(KingdomsCore core) {
        this.core = core;
        this.chunks = new ChunkKeeper(core);
        core.bus().subscribe(EventType.NPC_DIED, e -> {
            for (PhysicalJob j : core.state().jobs.values())
                if (j.status.live() && j.npcId.equals(e.actorId())) fail(j, "quem fazia morreu");
            // a mochila não some: cai no chão onde ele morreu (como a de um jogador)
            Npc dead = core.npc(e.actorId());
            if (dead != null && !dead.bag.isEmpty() && dead.pos != null && port().isLoaded(dead.pos)) {
                port().drop(dead.pos, new TreeMap<>(dead.bag));
                core.bus().publish(core.tick(), EventType.ITEMS_DROPPED, GameEvent.Severity.WARN, dead.kingdomId, dead.id,
                        "A mochila de " + dead.name + " caiu em " + dead.pos + " (" + summary(dead.bag) + ").");
                dead.bag.clear();
            }
        });
    }

    private PhysicalPort port() {
        return core.physical();
    }

    // ------------------------------------------------------------------ ciclo de vida

    public PhysicalJob start(Kingdom k, PhysicalJob job, String orderText) {
        Npc n = core.npc(job.npcId);
        if (n.jobId != null) {
            PhysicalJob old = core.state().jobs.get(n.jobId);
            if (old != null && old.status.live()) cancel(old, "substituída por uma nova ordem");
        }
        job.id = UUID.randomUUID();
        job.number = ++core.state().jobCounter;
        job.createdTick = core.tick();
        job.stateSince = core.tick();
        job.status = PhysicalJob.Status.ACTIVE;
        core.state().jobs.put(job.id, job);
        chunksDirty = true;
        n.jobId = job.id;
        n.remember(core.tick(), "O rei me mandou " + job.name + ".", 45, null, "ordem", "trabalho");
        job.addLog(core.tick(), "Ordem: " + Text.truncate(orderText == null ? job.name : orderText, 80));
        core.bus().publish(core.tick(), EventType.JOB_STARTED, GameEvent.Severity.INFO, k.id, n.id,
                n.name + " começou: " + job.name + ".", Map.of("job", job.id.toString()));
        return job;
    }

    public String cancel(PhysicalJob j, String why) {
        if (!j.status.live()) return "A ordem #" + j.number + " já está " + j.status.display + ".";
        j.status = PhysicalJob.Status.CANCELLED;
        j.reason = why;
        release(j);
        j.addLog(core.tick(), "Cancelada: " + why);
        Npc n = core.npc(j.npcId);
        return "Ordem #" + j.number + " cancelada" + (n == null ? "." : "; " + n.name + " guarda o que juntou (" + summary(n.bag) + ").");
    }

    private void release(PhysicalJob j) {
        chunksDirty = true;
        Npc n = core.npc(j.npcId);
        if (n != null && j.id.equals(n.jobId)) {
            n.jobId = null;
            n.heldItem = "";
        }
    }

    private void finish(PhysicalJob j, Npc n) {
        j.status = PhysicalJob.Status.DONE;
        j.reason = "";
        release(j);
        String got = j.gained.isEmpty() ? "" : " Rendeu: " + summary(j.gained) + ".";
        j.addLog(core.tick(), "Concluída." + got);
        n.remember(core.tick(), "Cumpri a ordem do rei: " + j.name + "." + got, 45, null, "trabalho");
        core.bus().publish(core.tick(), EventType.JOB_DONE, GameEvent.Severity.GOOD, j.kingdomId, n.id,
                n.name + " terminou: " + j.name + "." + got + (j.note.isBlank() ? "" : " " + j.note), Map.of("job", j.id.toString()));
    }

    private void fail(PhysicalJob j, String why) {
        if (!j.status.live()) return;
        j.status = PhysicalJob.Status.FAILED;
        j.reason = why;
        release(j);
        j.addLog(core.tick(), "Falhou: " + why);
        Npc n = core.npc(j.npcId);
        core.bus().publish(core.tick(), EventType.JOB_FAILED, GameEvent.Severity.WARN, j.kingdomId, j.npcId,
                (n == null ? "A ordem" : n.name) + " não conseguiu " + j.name + ": " + why + ".", Map.of("job", j.id.toString()));
    }

    /** Espera com motivo; failAfter < 0 = espera para sempre (ex.: área descarregada). */
    private void waitFor(PhysicalJob j, String why, int failAfter) {
        if (j.status != PhysicalJob.Status.WAITING || !why.equals(j.reason)) {
            j.status = PhysicalJob.Status.WAITING;
            j.reason = why;
            j.stateSince = core.tick();
        } else if (failAfter >= 0 && core.tick() - j.stateSince > 20L * failAfter) fail(j, why);
    }

    private void active(PhysicalJob j, String what) {
        if (j.status != PhysicalJob.Status.ACTIVE) j.stateSince = core.tick();
        j.status = PhysicalJob.Status.ACTIVE;
        j.reason = what;
    }

    // ------------------------------------------------------------------ tick

    public void tickSecond() {
        // chunks primeiro: a etapa de agora precisa do mundo carregado mesmo com o rei longe
        if (chunksDirty || core.tick() % 100 == 0) {
            chunks.sync();
            chunksDirty = false;
        }
        for (PhysicalJob j : List.copyOf(core.state().jobs.values())) {
            if (!j.status.live()) continue;
            int cursor = j.cursor;
            try {
                tick(j);
            } catch (RuntimeException e) {
                fail(j, "erro inesperado (" + e.getClass().getSimpleName() + ")");
            }
            if (j.cursor != cursor) chunksDirty = true; // etapa nova, chunks novos
        }
    }

    public ChunkKeeper chunks() {
        return chunks;
    }

    /** Área da etapa descarregada: por quê, e se dá para esperar. */
    private void unloaded(PhysicalJob j) {
        if (!core.config().keepOrderChunksLoaded)
            waitFor(j, "a área descarregou (Vossa Majestade se afastou; manter carregado está desligado)", -1);
        else if (chunks.starved(j.id))
            waitFor(j, "na fila: limite de " + core.config().maxForcedChunks + " chunks carregados à distância", -1);
        else {
            chunksDirty = true;
            waitFor(j, "carregando a área", -1);
        }
    }

    private void tick(PhysicalJob j) {
        Npc n = core.npc(j.npcId);
        if (n == null || !n.alive) {
            fail(j, "quem fazia não existe mais");
            return;
        }
        if (!j.id.equals(n.jobId)) {
            fail(j, n.name + " recebeu outra ordem");
            return;
        }
        if (core.scheduler().isSummoned(n)) {
            j.status = PhysicalJob.Status.PAUSED;
            j.reason = "atendendo ao chamado do rei";
            return;
        }
        PhysicalJob.Task t = j.current();
        if (t == null) {
            finish(j, n);
            return;
        }
        n.onDuty = true;
        n.heldItem = held(n, t);
        n.currentTask = "⚒ " + t.label + (t.total > 1 ? " (" + t.done + "/" + t.total + ")" : t.kind == PhysicalJob.Kind.CRAFT ? " (" + t.done + "/" + t.count + ")" : "");
        Kingdom k = core.kingdom(j.kingdomId);
        boolean taskDone = switch (t.kind) {
            case BREAK, CHOP -> breaking(j, t, n, k);
            case TAKE -> take(j, t, n);
            case PUT -> put(j, t, n);
            case CRAFT -> craft(j, t, n);
            case GIVE -> give(j, t, n);
            case PLANT -> plant(j, t, n);
        };
        if (taskDone) {
            j.addLog(core.tick(), "✓ " + t.label + (t.kind == PhysicalJob.Kind.BREAK || t.kind == PhysicalJob.Kind.CHOP ? " (" + t.done + " quebrados)" : ""));
            j.cursor++;
            t.progress = 0;
            if (j.current() == null) finish(j, n);
        }
    }

    // ------------------------------------------------------------------ tarefas

    private boolean breaking(PhysicalJob j, PhysicalJob.Task t, Npc n, Kingdom k) {
        Set<String> tools = JobPlanner.tools(n);
        double budget = speed(n, t.kind);
        for (int guard = 0; guard < 6; guard++) {
            if (t.blocks.isEmpty()) return true;
            Pos p = t.blocks.get(0);
            if (!port().isLoaded(p)) {
                unloaded(j);
                return false;
            }
            BlockInfo info = port().block(p);
            // revalida na hora: algo pode ter mudado desde a ordem
            if (info.air() || info.fluid() || info.blockEntity() || info.nearFluid() || !info.breakable()
                    || JobPlanner.protectedBy(core, p) != null || !port().mayBreak(j.orderedBy, p)) {
                t.blocks.remove(0);
                t.progress = 0;
                continue;
            }
            if (!inReach(n, p, 5)) {
                if (!p.equals(t.targetBlock)) {
                    t.targetBlock = p;
                    t.targetSince = core.tick();
                } else if (core.tick() - t.targetSince > 20L * BLOCK_TIMEOUT) {
                    j.addLog(core.tick(), "Não alcancei " + ItemNames.display(info.id()) + " em " + p + " — pulei.");
                    t.blocks.remove(0);
                    t.progress = 0;
                    continue;
                }
                active(j, "a caminho do bloco");
                return false;
            }
            if (bagTotal(n) >= JobPlanner.BAG_CAPACITY) {
                Pos chest = storageChest(k);
                if (chest == null) {
                    waitFor(j, "mochila cheia e não há baú no armazém", WAIT_FAIL);
                    return false;
                }
                PhysicalJob.Task dep = new PhysicalJob.Task();
                dep.kind = PhysicalJob.Kind.PUT;
                dep.at = chest;
                dep.item = "#all";
                dep.auto = true;
                dep.label = "esvaziar a mochila no armazém";
                j.tasks.add(j.cursor, dep);
                j.addLog(core.tick(), "Mochila cheia: indo ao armazém.");
                return false;
            }
            active(j, "quebrando");
            t.targetBlock = p;
            t.targetSince = core.tick();
            boolean hasTool = info.tool() == null || tools.contains(info.tool());
            double cost = Math.max(0.15, info.hardness()) * (hasTool ? 1.0 : info.needsTool() ? 5.0 : 2.0);
            port().animate(n.id, p, PhysicalPort.Anim.SWING, 0);
            if (t.progress + budget < cost) {
                t.progress += budget;
                port().animate(n.id, p, PhysicalPort.Anim.CRACK, (int) Math.min(9, t.progress / cost * 10));
                return false;
            }
            budget -= cost - t.progress;
            t.progress = 0;
            Map<String, Integer> drops = port().breakBlock(n.id, p, hasTool ? info.tool() : null);
            for (var e : drops.entrySet()) {
                n.bag.merge(e.getKey(), e.getValue(), Integer::sum);
                j.gained.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            t.blocks.remove(0);
            t.done++;
            if (budget <= 0.05) return t.blocks.isEmpty();
        }
        return t.blocks.isEmpty();
    }

    private boolean take(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        if (!reachOrWalk(j, t, n, t.at, 3)) return false;
        if (!port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (t.progress++ < 1) {
            port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_OPEN, 0);
            return false;
        }
        Map<String, Integer> c = port().container(t.at);
        if (c == null) {
            fail(j, "o baú em " + t.at + " sumiu");
            return false;
        }
        Predicate<String> m = ItemNames.matcher(t.item);
        int want = t.count <= 0 ? Integer.MAX_VALUE : t.count - t.done;
        int got = 0;
        for (var e : new ArrayList<>(c.entrySet())) {
            if (got >= want) break;
            if (!m.test(e.getKey())) continue;
            int room = JobPlanner.BAG_CAPACITY - bagTotal(n);
            int n2 = port().take(n.id, t.at, e.getKey(), Math.min(Math.min(want - got, e.getValue()), room));
            if (n2 > 0) n.bag.merge(e.getKey(), n2, Integer::sum);
            got += n2;
        }
        t.done += got;
        port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_CLOSE, 0);
        if (t.done == 0) {
            t.progress = 0;
            waitFor(j, "o baú não tem " + ItemNames.display(t.item), WAIT_FAIL);
            return false;
        }
        if (t.count > 0 && t.done < t.count) j.addLog(core.tick(), "Só havia " + t.done + " de " + ItemNames.display(t.item) + ".");
        return true;
    }

    private boolean put(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        if (!reachOrWalk(j, t, n, t.at, 3)) return false;
        if (!port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (t.progress++ < 1) {
            port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_OPEN, 0);
            return false;
        }
        if (port().container(t.at) == null) {
            fail(j, "o baú em " + t.at + " sumiu");
            return false;
        }
        Predicate<String> m = ItemNames.matcher(t.item);
        Map<String, Integer> items = new TreeMap<>();
        int left = t.count <= 0 ? Integer.MAX_VALUE : t.count;
        for (var e : n.bag.entrySet()) {
            if (left <= 0) break;
            if (!m.test(e.getKey()) || isTool(e.getKey()) && "#all".equals(t.item)) continue;
            int v = Math.min(left, e.getValue());
            items.put(e.getKey(), v);
            left -= v;
        }
        Map<String, Integer> rest = port().put(n.id, t.at, items);
        for (var e : items.entrySet()) n.bag.merge(e.getKey(), -(e.getValue() - rest.getOrDefault(e.getKey(), 0)), Integer::sum);
        n.bag.values().removeIf(v -> v <= 0);
        port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_CLOSE, 0);
        int left2 = rest.values().stream().mapToInt(Integer::intValue).sum();
        if (left2 > 0) j.addLog(core.tick(), "Baú cheio: " + left2 + " item(ns) ficaram na mochila.");
        return true;
    }

    private boolean craft(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        if (t.at != null && !reachOrWalk(j, t, n, t.at, 3)) return false;
        if (t.at != null && !port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (t.done >= t.count) return true;
        boolean furnace = "FURNACE".equals(t.block);
        int seconds = furnace ? 5 : ItemNames.smithing(t.item) ? 4 : 2;
        if (t.at != null) {
            BlockInfo st = port().block(t.at);
            String need = furnace ? "furnace" : "crafting_table";
            if (!st.id().endsWith(need)) {
                fail(j, (furnace ? "a fornalha" : "a bancada") + " em " + t.at + " sumiu");
                return false;
            }
        }
        Recipe r = runnable(t.item, n, furnace, t.done);
        if (r == null) {
            waitFor(j, "faltam ingredientes para " + ItemNames.display(t.item), WAIT_FAIL);
            return false;
        }
        active(j, "fabricando");
        port().animate(n.id, t.at == null ? n.pos : t.at, PhysicalPort.Anim.SWING, 0);
        if (++t.progress < seconds) return false;
        t.progress = 0;
        for (List<String> slot : r.ingredients()) if (!slot.isEmpty()) take1(n, slot);
        if (furnace && t.done % 8 == 0) take1(n, List.of("minecraft:coal", "minecraft:charcoal"));
        n.bag.merge(t.item, r.count(), Integer::sum);
        j.gained.merge(t.item, r.count(), Integer::sum);
        t.done++;
        port().animate(n.id, t.at == null ? n.pos : t.at, PhysicalPort.Anim.CRAFT, 0);
        return t.done >= t.count;
    }

    /** Receita que dá para fazer agora com o que está na mochila. */
    private Recipe runnable(String item, Npc n, boolean furnace, int done) {
        for (Recipe r : port().recipes(item)) {
            if ((r.station() == PhysicalPort.Station.FURNACE) != furnace) continue;
            Map<String, Integer> sim = new TreeMap<>(n.bag);
            boolean ok = true;
            List<List<String>> slots = new ArrayList<>(r.ingredients());
            if (furnace && done % 8 == 0) slots.add(List.of("minecraft:coal", "minecraft:charcoal"));
            for (List<String> slot : slots) {
                if (slot.isEmpty()) continue;
                String found = null;
                for (String o : slot) if (sim.getOrDefault(o, 0) > 0) {
                    found = o;
                    break;
                }
                if (found == null) {
                    ok = false;
                    break;
                }
                sim.merge(found, -1, Integer::sum);
            }
            if (ok) return r;
        }
        return null;
    }

    private static void take1(Npc n, List<String> options) {
        for (String o : options)
            if (n.bag.getOrDefault(o, 0) > 0) {
                n.bag.merge(o, -1, Integer::sum);
                if (n.bag.get(o) <= 0) n.bag.remove(o);
                return;
            }
    }

    private boolean give(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        Pos king = core.playerPos(j.orderedBy);
        boolean away = king == null || !core.isOnline(j.orderedBy) || n.pos == null || n.pos.distXZ(king) > GIVE_RANGE;
        // Rei longe ou fora do jogo: não sai atrás dele pelo mundo — guarda no armazém e avisa onde.
        if (away || t.at != null) return giveToStorage(j, t, n);
        if (!reachOrWalk(j, t, n, king, 3)) return false;
        Predicate<String> m = ItemNames.matcher(t.item);
        Map<String, Integer> items = new TreeMap<>();
        int left = t.count <= 0 ? Integer.MAX_VALUE : t.count;
        for (var e : n.bag.entrySet()) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int v = Math.min(left, e.getValue());
            items.put(e.getKey(), v);
            left -= v;
        }
        if (items.isEmpty()) {
            fail(j, "não há " + ItemNames.display(t.item) + " na mochila para entregar");
            return false;
        }
        if (!port().give(n.id, j.orderedBy, items)) {
            waitFor(j, "não consegui entregar (Vossa Majestade está longe?)", WAIT_FAIL);
            return false;
        }
        for (var e : items.entrySet()) n.bag.merge(e.getKey(), -e.getValue(), Integer::sum);
        n.bag.values().removeIf(v -> v <= 0);
        port().animate(n.id, king, PhysicalPort.Anim.GIVE, 0);
        j.addLog(core.tick(), "Entregou " + summary(items) + " ao rei.");
        return true;
    }

    private boolean giveToStorage(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        Kingdom k = core.kingdom(j.kingdomId);
        if (t.at == null) {
            t.at = storageChest(k);
            if (t.at == null) {
                j.note = "Vossa Majestade estava longe e não há armazém: " + n.name + " guardou consigo (" + summary(itemsFor(n, t)) + ").";
                j.addLog(core.tick(), j.note);
                return true;
            }
            j.addLog(core.tick(), "Vossa Majestade está longe: vou guardar no armazém.");
        }
        if (!reachOrWalk(j, t, n, t.at, 3)) return false;
        if (!port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (port().container(t.at) == null) {
            fail(j, "o baú do armazém sumiu");
            return false;
        }
        Map<String, Integer> items = itemsFor(n, t);
        port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_OPEN, 0);
        Map<String, Integer> rest = port().put(n.id, t.at, items);
        for (var e : items.entrySet()) n.bag.merge(e.getKey(), -(e.getValue() - rest.getOrDefault(e.getKey(), 0)), Integer::sum);
        n.bag.values().removeIf(v -> v <= 0);
        port().animate(n.id, t.at, PhysicalPort.Anim.CHEST_CLOSE, 0);
        j.note = "Vossa Majestade estava longe: " + summary(items) + " ficou no baú do armazém em " + t.at + ".";
        j.addLog(core.tick(), j.note);
        return true;
    }

    private static Map<String, Integer> itemsFor(Npc n, PhysicalJob.Task t) {
        Predicate<String> m = ItemNames.matcher(t.item);
        Map<String, Integer> items = new TreeMap<>();
        int left = t.count <= 0 ? Integer.MAX_VALUE : t.count;
        for (var e : n.bag.entrySet()) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int v = Math.min(left, e.getValue());
            items.put(e.getKey(), v);
            left -= v;
        }
        return items;
    }

    private boolean plant(PhysicalJob j, PhysicalJob.Task t, Npc n) {
        if (n.bag.getOrDefault(t.block, 0) <= 0) {
            j.addLog(core.tick(), "Nenhuma muda caiu das folhas; não deu para replantar.");
            return true;
        }
        if (!reachOrWalk(j, t, n, t.at, 4)) return false;
        if (!port().isLoaded(t.at)) {
            unloaded(j);
            return false;
        }
        if (port().block(t.at).air() && port().place(n.id, t.at, t.block)) {
            n.bag.merge(t.block, -1, Integer::sum);
            n.bag.values().removeIf(v -> v <= 0);
            port().animate(n.id, t.at, PhysicalPort.Anim.PLANT, 0);
            j.addLog(core.tick(), "Replantou " + ItemNames.display(t.block) + ".");
        }
        return true;
    }

    // ------------------------------------------------------------------ movimento

    private boolean reachOrWalk(PhysicalJob j, PhysicalJob.Task t, Npc n, Pos target, double reach) {
        if (inReach(n, target, reach)) return true;
        if (!target.equals(t.targetBlock)) {
            t.targetBlock = target;
            t.targetSince = core.tick();
        } else if (core.tick() - t.targetSince > 20L * MOVE_TIMEOUT) {
            fail(j, "não consegui chegar a " + target);
            return false;
        }
        active(j, "a caminho");
        return false;
    }

    private static boolean inReach(Npc n, Pos p, double reach) {
        if (n.pos == null) return false;
        double dx = n.pos.x() - p.x(), dy = (n.pos.y() + 1) - p.y(), dz = n.pos.z() - p.z();
        return dx * dx + dy * dy + dz * dz <= reach * reach + 1;
    }

    /** Para o NpcScheduler: destino da tarefa atual (ordem direta passa na frente da rotina e do sono). */
    public NpcScheduler.Intent intentFor(Npc n) {
        if (n.jobId == null) return null;
        PhysicalJob j = core.state().jobs.get(n.jobId);
        if (j == null || !j.status.live() || j.status == PhysicalJob.Status.PAUSED) return null;
        PhysicalJob.Task t = j.current();
        if (t == null) return null;
        Pos target = switch (t.kind) {
            case BREAK, CHOP -> t.blocks.isEmpty() ? null : t.blocks.get(0);
            case GIVE -> t.at != null ? t.at : core.playerPos(j.orderedBy);
            default -> t.at;
        };
        if (target == null) return null;
        return new NpcScheduler.Intent(NpcActivity.WORK, target, t.kind == PhysicalJob.Kind.GIVE ? 2 : 1);
    }

    // ------------------------------------------------------------------ consultas

    public List<PhysicalJob> jobs(UUID kingdomId) {
        List<PhysicalJob> out = new ArrayList<>();
        for (PhysicalJob j : core.state().jobs.values()) if (kingdomId.equals(j.kingdomId)) out.add(j);
        out.sort(Comparator.comparingInt(j -> j.number));
        return out;
    }

    public PhysicalJob find(UUID kingdomId, String ref) {
        List<PhysicalJob> list = jobs(kingdomId);
        if (ref == null || ref.isBlank()) {
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).status.live()) return list.get(i);
            return null;
        }
        String r = Text.norm(ref).replace("#", "");
        for (PhysicalJob j : list) if (String.valueOf(j.number).equals(r)) return j;
        Npc n = core.findNpc(kingdomId, ref);
        if (n != null && n.jobId != null) return core.state().jobs.get(n.jobId);
        return null;
    }

    public List<String> describe(PhysicalJob j) {
        List<String> out = new ArrayList<>();
        Npc n = core.npc(j.npcId);
        out.add("# Ordem #" + j.number + " — " + (n == null ? "?" : n.name) + " · " + j.status.display + (j.reason.isBlank() ? "" : ": " + j.reason));
        for (int i = 0; i < j.tasks.size(); i++) {
            PhysicalJob.Task t = j.tasks.get(i);
            String mark = i < j.cursor ? "✓" : i == j.cursor && j.status.live() ? "▶" : " ";
            String prog = t.total > 1 ? " " + t.done + "/" + t.total : t.kind == PhysicalJob.Kind.CRAFT ? " " + t.done + "/" + t.count : "";
            out.add(mark + " " + (i + 1) + ". " + t.label + prog + (t.auto ? " (planejado)" : ""));
        }
        if (n != null && !n.bag.isEmpty()) out.add("Mochila de " + n.name + ": " + summary(n.bag));
        if (!j.gained.isEmpty()) out.add("Rendeu até agora: " + summary(j.gained));
        for (int i = Math.max(0, j.log.size() - 4); i < j.log.size(); i++) out.add("  " + j.log.get(i));
        return out;
    }

    // ------------------------------------------------------------------ util

    private double speed(Npc n, PhysicalJob.Kind kind) {
        double base = switch (n.profession) {
            case MINER -> kind == PhysicalJob.Kind.BREAK ? 2.0 : 1.0;
            case LUMBERJACK -> kind == PhysicalJob.Kind.CHOP ? 2.0 : 1.0;
            case BUILDER -> 1.5;
            default -> 1.0;
        };
        return base * (n.hunger < 20 ? 0.6 : 1.0);
    }

    private Pos storageChest(Kingdom k) {
        if (k == null) return null;
        Building b = ChainValidator.findBuilding(core, k, Place.STORAGE, true);
        if (b == null) return null;
        Pos c = port().findNear(b.centerPos(), "minecraft:chest", 6);
        return c != null ? c : port().findNear(b.centerPos(), "minecraft:barrel", 6);
    }

    private static int bagTotal(Npc n) {
        return n.bag.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static boolean isTool(String id) {
        return id.matches(".*_(pickaxe|axe|shovel|hoe|sword)");
    }

    private static String held(Npc n, PhysicalJob.Task t) {
        return switch (t.kind) {
            case BREAK -> n.profession == Profession.LUMBERJACK ? "axe" : "pickaxe";
            case CHOP -> "axe";
            case CRAFT -> t.item;
            case GIVE -> t.item != null && !t.item.startsWith("#") ? t.item
                    : n.bag.keySet().stream().findFirst().orElse("");
            case PLANT -> t.block;
            default -> "";
        };
    }

    public static String summary(Map<String, Integer> items) {
        List<String> parts = new ArrayList<>();
        items.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
                .forEach(e -> parts.add(e.getValue() + " " + ItemNames.display(e.getKey())));
        if (items.size() > 6) parts.add("…");
        return parts.isEmpty() ? "nada" : String.join(", ", parts);
    }
}
