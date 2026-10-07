package com.kingdomsai.core.skill;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.VillageWall;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Office;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.port.PhysicalPort;
import com.kingdomsai.core.port.PhysicalPort.BlockInfo;
import com.kingdomsai.core.port.PhysicalPort.Recipe;
import com.kingdomsai.core.work.ChainValidator;
import com.kingdomsai.core.work.Place;

import java.util.*;
import java.util.function.Predicate;

/**
 * Transforma uma ordem física em tarefas e valida tudo ANTES de o NPC mexer um dedo:
 *
 * - Quebrar: área carregada, perto do rei, nunca dentro de construções nem em terra de outro reino;
 *   pula baús/fornalhas (block entities), blocos indestrutíveis e vizinhos de água/lava (sem inundação);
 *   cava de cima para baixo e deixa uma escada para sair de buracos fundos; avisa quando falta a ferramenta.
 * - Árvore: só tronco com folhas (árvore natural, não a parede de uma casa); replanta a muda.
 * - Baús: só do próprio reino (ou do rei, ao lado dele); confere o que tem dentro.
 * - Fabricar: receita do jogo, bancada/fornalha por perto, ofício certo (ferramentas de metal = ferreiro).
 *   Ingredientes que faltam viram tarefas automáticas: buscar no baú ou fabricar antes
 *   (picareta → gravetos → tábuas → toras), até 3 níveis.
 * - Mochila com limite: o que passar disso vai para o baú do armazém no meio do trabalho.
 */
public final class JobPlanner {
    public static final int MAX_VOLUME = 343;
    public static final int MAX_SIDE = 32;
    public static final int BAG_CAPACITY = 320;
    public static final int KING_RANGE = 64;
    public static final int MAX_TREE_LOGS = 96;
    public static final int MAX_LEAVES = 160;

    public record Plan(List<String> errors, List<String> warnings, List<String> lines, PhysicalJob job) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final KingdomsCore core;
    private final PhysicalPort port;
    private final Kingdom k;
    private final UUID player;
    private final List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
    private final List<PhysicalJob.Task> tasks = new ArrayList<>();
    private Map<String, Integer> bag;
    private Npc npc;
    private String lastProduct;

    private JobPlanner(KingdomsCore core, Kingdom k, UUID player) {
        this.core = core;
        this.port = core.physical();
        this.k = k;
        this.player = player;
    }

    // ------------------------------------------------------------------ esquema

    /** Lê as tarefas dos parâmetros (kind=... ou tasks=[...]). @throws IllegalArgumentException se mal formado. */
    public static List<Map<String, String>> specs(Map<String, String> p) {
        List<Map<String, String>> out = new ArrayList<>();
        String json = p.get("tasks");
        if (json != null && !json.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonArray()) throw new IllegalArgumentException("tasks deve ser uma lista JSON.");
                for (JsonElement e : root.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    Map<String, String> m = new LinkedHashMap<>();
                    for (var en : e.getAsJsonObject().entrySet())
                        if (en.getValue().isJsonPrimitive()) m.put(en.getKey(), en.getValue().getAsString());
                        else if (en.getValue().isJsonObject())
                            for (var inner : en.getValue().getAsJsonObject().entrySet()) m.put(inner.getKey(), inner.getValue().getAsString());
                    m.putIfAbsent("kind", m.getOrDefault("do", m.getOrDefault("type", "")));
                    out.add(m);
                }
            } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                throw new IllegalArgumentException("tasks não é um JSON válido: " + e.getMessage());
            }
        } else if (p.get("kind") != null) {
            out.add(new LinkedHashMap<>(p));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("JOB precisa de kind (break|dig|tunnel|clear|chop|take|put|craft|give) ou tasks.");
        if (out.size() > 8) throw new IllegalArgumentException("No máximo 8 tarefas por ordem.");
        for (Map<String, String> m : out)
            if (kindOf(m.get("kind")) == null) throw new IllegalArgumentException("Tarefa desconhecida: «" + m.get("kind") + "».");
        if (truthy(p.get("give")) && out.stream().noneMatch(m -> "give".equals(kindOf(m.get("kind")))))
            out.add(new LinkedHashMap<>(Map.of("kind", "give")));
        return out;
    }

    static String kindOf(String s) {
        String n = Text.norm(s);
        return switch (n) {
            case "break", "quebrar", "quebre", "mine_block" -> "break";
            case "dig", "cavar", "cave", "escavar", "buraco", "hole" -> "dig";
            case "tunnel", "tunel", "galeria" -> "tunnel";
            case "clear", "limpar", "limpe", "aplainar" -> "clear";
            case "chop", "cortar", "corte", "arvore", "tree" -> "chop";
            case "take", "pegar", "pegue", "retirar", "withdraw" -> "take";
            case "put", "guardar", "guarde", "depositar", "deposit", "store" -> "put";
            case "craft", "fabricar", "fabrique", "fazer", "faca", "criar", "crie", "forjar", "smelt", "fundir" -> "craft";
            case "give", "entregar", "entregue", "dar" -> "give";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ plano

    public static Plan plan(KingdomsCore core, Kingdom k, UUID player, Map<String, String> params) {
        JobPlanner jp = new JobPlanner(core, k, player);
        return jp.run(params);
    }

    private Plan run(Map<String, String> params) {
        List<Map<String, String>> specs = specs(params);
        npc = chooseNpc(params.get("npc"), kindOf(specs.get(0).get("kind")), specs);
        if (npc == null) return done();
        bag = new TreeMap<>(npc.bag);
        if (npc.pos != null && core.playerPos(player) != null && npc.pos.distXZ(core.playerPos(player)) > 400)
            errors.add(npc.name + " está longe demais (" + (int) npc.pos.distXZ(core.playerPos(player)) + " blocos). Chame-o antes (tecla G).");
        for (Map<String, String> s : specs) {
            int before = errors.size();
            switch (kindOf(s.get("kind"))) {
                case "break", "dig", "tunnel", "clear" -> area(s);
                case "chop" -> chop(s);
                case "take" -> take(s);
                case "put" -> put(s);
                case "craft" -> craft(s);
                case "give" -> give(s);
                default -> {
                }
            }
            if (errors.size() > before) break; // o resto depende desta tarefa
        }
        int peak = bag.values().stream().mapToInt(Integer::intValue).sum();
        if (errors.isEmpty() && peak > BAG_CAPACITY) {
            if (storageChest() == null)
                errors.add("Isso rende ~" + peak + " itens e a mochila leva " + BAG_CAPACITY + "; sem baú no armazém para esvaziar. Diminua a área.");
            else warnings.add("Rende ~" + peak + " itens: " + npc.name + " vai esvaziar a mochila no baú do armazém no meio do trabalho.");
        }
        return done();
    }

    private Plan done() {
        if (!errors.isEmpty() || tasks.isEmpty()) {
            if (errors.isEmpty()) errors.add("Nada a fazer.");
            return new Plan(errors, warnings, List.of(), null);
        }
        PhysicalJob job = new PhysicalJob();
        job.kingdomId = k.id;
        job.npcId = npc.id;
        job.orderedBy = player;
        job.tasks.addAll(tasks);
        PhysicalJob.Task main = tasks.stream().filter(t -> !t.auto).findFirst().orElse(tasks.get(0));
        job.name = Text.truncate(main.label, 48);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++)
            lines.add((i + 1) + ". " + tasks.get(i).label + (tasks.get(i).auto ? " (planejado)" : ""));
        return new Plan(errors, warnings, lines, job);
    }

    // ------------------------------------------------------------------ quem faz

    private Npc chooseNpc(String name, String firstKind, List<Map<String, String>> specs) {
        if (name != null && !name.isBlank()) {
            Npc n = core.findNpc(k.id, name);
            if (n == null) errors.add("Não encontrei " + name + " em " + k.name + ".");
            else if (n.office == Office.KING) errors.add("O rei não pega no pesado.");
            return errors.isEmpty() ? n : null;
        }
        Profession want = switch (firstKind) {
            case "break", "dig", "tunnel", "clear" -> Profession.MINER;
            case "chop" -> Profession.LUMBERJACK;
            default -> null;
        };
        for (Map<String, String> s : specs)
            if ("craft".equals(kindOf(s.get("kind")))) {
                String item = ItemNames.resolve(s.get("item"));
                if (item != null && ItemNames.smithing(item)) want = Profession.BLACKSMITH;
            }
        Pos king = core.playerPos(player);
        Npc best = null;
        double bestScore = -1e9;
        for (Npc n : core.citizens(k.id)) {
            if (n.office == Office.KING || n.profession.isMilitary()) continue;
            double score = (want != null && n.profession == want ? 1000 : 0) - (n.jobId != null ? 500 : 0)
                    - (king != null && n.pos != null ? n.pos.distXZ(king) : 0);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best == null) errors.add("Não há ninguém disponível para isso.");
        return best;
    }

    /** Ferramentas que o NPC tem: as do ofício + as que estiverem na mochila. */
    public static Set<String> tools(Npc n) {
        Set<String> t = new HashSet<>();
        switch (n.profession) {
            case MINER -> t.addAll(List.of("pickaxe", "shovel"));
            case LUMBERJACK -> t.add("axe");
            case BUILDER -> t.addAll(List.of("pickaxe", "axe", "shovel"));
            case FARMER -> t.addAll(List.of("hoe", "shovel"));
            case BLACKSMITH -> t.add("pickaxe");
            default -> {
            }
        }
        for (String id : n.bag.keySet())
            for (String tool : new String[]{"pickaxe", "axe", "shovel", "hoe"}) if (id.endsWith("_" + tool)) t.add(tool);
        return t;
    }

    // ------------------------------------------------------------------ quebrar

    private void area(Map<String, String> s) {
        String kind = kindOf(s.get("kind"));
        Pos a, b;
        int[] size = size(s.get("size"));
        if (s.get("x") != null && s.get("y") != null && s.get("z") != null) {
            a = new Pos(num(s.get("x")), num(s.get("y")), num(s.get("z")));
            b = s.get("x2") != null ? new Pos(num(s.get("x2")), num(s.get("y2") == null ? s.get("y") : s.get("y2")), num(s.get("z2"))) : a;
        } else {
            KingdomsCore.Look look = core.playerLook(player);
            if (look == null || look.block() == null) {
                errors.add("Mire no bloco (ou informe x y z) para eu saber onde.");
                return;
            }
            Pos l = look.block();
            switch (kind) {
                case "dig" -> {
                    int w = size == null ? 3 : size[0], d = size == null ? 3 : size.length > 1 ? size[1] : size[0];
                    int depth = size == null ? 3 : size.length > 2 ? size[2] : Math.max(1, Math.min(w, d));
                    a = new Pos(l.x() - (w - 1) / 2, l.y() - depth + 1, l.z() - (d - 1) / 2);
                    b = new Pos(a.x() + w - 1, l.y(), a.z() + d - 1);
                }
                case "tunnel" -> {
                    int len = s.get("length") != null ? num(s.get("length")) : size != null ? size[0] : 8;
                    int[] dir = dir(look.facing());
                    a = l;
                    b = l.offset(dir[0] * (len - 1), 1, dir[1] * (len - 1));
                }
                case "clear" -> {
                    int w = size == null ? 5 : size[0], d = size == null ? 5 : size.length > 1 ? size[1] : size[0];
                    a = new Pos(l.x() - (w - 1) / 2, l.y() + 1, l.z() - (d - 1) / 2);
                    b = new Pos(a.x() + w - 1, l.y() + 4, a.z() + d - 1);
                }
                default -> a = b = l;
            }
        }
        Pos lo = new Pos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
        Pos hi = new Pos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        int w = hi.x() - lo.x() + 1, h = hi.y() - lo.y() + 1, d = hi.z() - lo.z() + 1;
        if (w > MAX_SIDE || h > MAX_SIDE || d > MAX_SIDE || (long) w * h * d > MAX_VOLUME) {
            errors.add("Área grande demais (" + w + "×" + d + "×" + h + "): no máximo " + MAX_VOLUME + " blocos por ordem e " + MAX_SIDE + " de lado.");
            return;
        }
        if (!nearKing(center(lo, hi))) return;
        if (!port.isLoaded(lo) || !port.isLoaded(hi)) {
            errors.add("Essa área não está carregada. Chegue mais perto.");
            return;
        }
        Set<String> tools = tools(npc);
        int prot = 0, foreign = 0, chests = 0, fluid = 0, unbreakable = 0, noDrop = 0;
        String protName = null, foreignName = null, missingTool = null;
        boolean stairs = kind.equals("dig") && h >= 3 && Math.max(w, d) >= 2;
        List<Pos> queue = new ArrayList<>();
        for (int y = hi.y(); y >= lo.y(); y--)
            for (int x = lo.x(); x <= hi.x(); x++)
                for (int z = lo.z(); z <= hi.z(); z++) {
                    Pos p = new Pos(x, y, z);
                    BlockInfo info = port.block(p);
                    if (info.air() || info.fluid()) {
                        if (info.fluid()) fluid++;
                        continue;
                    }
                    Building owner = protectedBy(p);
                    if (owner != null) {
                        prot++;
                        protName = owner.blueprint().displayName();
                        continue;
                    }
                    Kingdom terr = foreignOwner(p);
                    if (terr != null) {
                        foreign++;
                        foreignName = terr.name;
                        continue;
                    }
                    if (info.blockEntity()) {
                        chests++;
                        continue;
                    }
                    if (!info.breakable() || info.hardness() < 0) {
                        unbreakable++;
                        continue;
                    }
                    if (info.nearFluid()) {
                        fluid++;
                        continue;
                    }
                    if (stairs && keepForStairs(p, lo, hi)) continue;
                    if (info.needsTool() && info.tool() != null && !tools.contains(info.tool())) {
                        noDrop++;
                        missingTool = info.tool();
                    } else if (info.drop() != null) bag.merge(info.drop(), 1, Integer::sum);
                    queue.add(p);
                }
        if (kind.equals("tunnel")) {
            Pos start = a;
            queue.sort(Comparator.comparingDouble((Pos p) -> Math.abs(p.x() - start.x()) + Math.abs(p.z() - start.z())).thenComparing(p -> -p.y()));
        }
        if (foreign > 0) {
            errors.add("Parte disso é terra de " + foreignName + " — quebrar lá seria invasão.");
            return;
        }
        if (queue.isEmpty()) {
            errors.add(prot > 0 ? "Isso é parte de «" + protName + "»: não destruo construções do reino."
                    : chests > 0 ? "Só há baús/fornalhas aí — não quebro contêineres (os itens se perderiam)."
                    : fluid > 0 ? "É água/lava ou está colado nelas: quebrar inundaria a área."
                    : "Não há nada que eu possa quebrar aí.");
            return;
        }
        if (prot > 0) warnings.add(prot + " bloco(s) de «" + protName + "» foram preservados.");
        if (chests > 0) warnings.add(chests + " baú(s)/fornalha(s) ficam onde estão (contêineres não são quebrados).");
        if (fluid > 0) warnings.add(fluid + " bloco(s) perto de água/lava ficam (evita inundação).");
        if (unbreakable > 0) warnings.add(unbreakable + " bloco(s) indestrutíveis ignorados.");
        if (noDrop > 0) warnings.add(npc.name + " não tem " + toolName(missingTool) + ": " + noDrop + " bloco(s) vão demorar e não render nada.");
        if (stairs) warnings.add("Deixarei uma escada num canto para sair do buraco.");
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.BREAK;
        t.blocks = queue;
        t.total = queue.size();
        String what = switch (kind) {
            case "dig" -> "cavar " + w + "×" + d + "×" + h;
            case "tunnel" -> "abrir túnel de " + Math.max(w, d) + " blocos";
            case "clear" -> "limpar área " + w + "×" + d;
            default -> queue.size() == 1 ? "quebrar " + ItemNames.display(port.block(queue.get(0)).id()) : "quebrar " + queue.size() + " blocos";
        };
        t.label = what + " em " + lo.x() + " " + lo.y() + " " + lo.z() + " (" + queue.size() + " blocos)";
        tasks.add(t);
    }

    /** Escada no canto: na coluna i (ao longo de x), os blocos abaixo da camada i ficam — degraus de 1 bloco. */
    private static boolean keepForStairs(Pos p, Pos lo, Pos hi) {
        if (p.z() != lo.z()) return false;
        int col = p.x() - lo.x();
        int layer = hi.y() - p.y(); // 0 = camada de cima
        return col < hi.y() - lo.y() && layer > col;
    }

    // ------------------------------------------------------------------ árvore

    private void chop(Map<String, String> s) {
        Pos start;
        if (s.get("x") != null) start = new Pos(num(s.get("x")), num(s.get("y")), num(s.get("z")));
        else {
            KingdomsCore.Look look = core.playerLook(player);
            if (look == null || look.block() == null) {
                errors.add("Mire na árvore para eu saber qual cortar.");
                return;
            }
            start = look.block();
        }
        if (!nearKing(start)) return;
        if (!port.block(start).log()) {
            errors.add("Isso não é um tronco (" + ItemNames.display(port.block(start).id()) + ").");
            return;
        }
        Set<Pos> logs = new LinkedHashSet<>();
        ArrayDeque<Pos> q = new ArrayDeque<>(List.of(start));
        while (!q.isEmpty() && logs.size() < MAX_TREE_LOGS) {
            Pos p = q.poll();
            if (logs.contains(p) || Math.abs(p.x() - start.x()) > 6 || Math.abs(p.z() - start.z()) > 6) continue;
            if (!port.block(p).log()) continue;
            logs.add(p);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dy != 0 || dz != 0) q.add(p.offset(dx, dy, dz));
        }
        Set<Pos> leaves = new LinkedHashSet<>();
        for (Pos l : logs)
            for (int dx = -3; dx <= 3 && leaves.size() < MAX_LEAVES; dx++)
                for (int dy = -1; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++) {
                        Pos p = l.offset(dx, dy, dz);
                        if (!leaves.contains(p) && port.block(p).leaves()) leaves.add(p);
                    }
        if (leaves.size() < 3) {
            errors.add("Isso não parece uma árvore (tronco sem folhas) — pode ser parte de uma construção.");
            return;
        }
        for (Pos p : logs)
            if (protectedBy(p) != null) {
                errors.add("Essa madeira é parte de «" + protectedBy(p).blueprint().displayName() + "».");
                return;
            }
        if (foreignOwner(start) != null) {
            errors.add("Essa árvore está em terra de " + foreignOwner(start).name + ".");
            return;
        }
        Pos base = logs.stream().min(Comparator.comparingInt(Pos::y)).orElse(start);
        List<Pos> queue = new ArrayList<>(logs);
        queue.sort(Comparator.comparingInt(Pos::y));
        int high = 0;
        for (Iterator<Pos> it = queue.iterator(); it.hasNext(); ) {
            Pos p = it.next();
            if (p.y() - base.y() > 12) {
                it.remove();
                high++;
            }
        }
        List<Pos> leafQueue = new ArrayList<>(leaves);
        leafQueue.removeIf(p -> p.y() - base.y() > 12 || protectedBy(p) != null);
        leafQueue.sort(Comparator.comparingInt(Pos::y));
        String logId = port.block(start).id();
        Set<String> tools = tools(npc);
        if (!tools.contains("axe")) warnings.add(npc.name + " não tem machado: vai demorar mais.");
        bag.merge(port.block(start).drop() == null ? logId : port.block(start).drop(), queue.size(), Integer::sum);
        if (high > 0) warnings.add(high + " tora(s) altas demais ficam (mais de 12 blocos acima do chão).");
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.CHOP;
        t.blocks = new ArrayList<>(queue);
        t.blocks.addAll(leafQueue);
        t.total = t.blocks.size();
        t.label = "cortar a árvore (" + queue.size() + " toras" + (leafQueue.isEmpty() ? "" : ", " + leafQueue.size() + " folhas") + ") em "
                + base.x() + " " + base.y() + " " + base.z();
        tasks.add(t);
        String sapling = saplingFor(logId);
        BlockInfo ground = port.block(base.offset(0, -1, 0));
        if (sapling != null && ground.id().matches(".*(dirt|grass|podzol|mud|moss).*")) {
            PhysicalJob.Task p = new PhysicalJob.Task();
            p.kind = PhysicalJob.Kind.PLANT;
            p.at = base;
            p.block = sapling;
            p.auto = true;
            p.label = "replantar " + ItemNames.display(sapling) + " (se cair uma das folhas)";
            tasks.add(p);
        }
    }

    public static String saplingFor(String logId) {
        if (logId == null) return null;
        String p = logId.substring(logId.indexOf(':') + 1);
        if (p.startsWith("mangrove")) return "minecraft:mangrove_propagule";
        if (p.startsWith("stripped_") || p.startsWith("crimson") || p.startsWith("warped")) return null;
        for (String wood : new String[]{"dark_oak", "oak", "spruce", "birch", "jungle", "acacia", "cherry"})
            if (p.startsWith(wood + "_")) return "minecraft:" + wood + "_sapling";
        return null;
    }

    // ------------------------------------------------------------------ baús

    private void take(Map<String, String> s) {
        String spec = ItemNames.resolve(s.get("item"));
        if (spec == null) {
            errors.add("Não sei que item é «" + s.get("item") + "».");
            return;
        }
        Pos chest = chest(s.get("from"));
        if (chest == null) return;
        Map<String, Integer> contents = port.container(chest);
        Predicate<String> m = ItemNames.matcher(spec);
        int have = contents.entrySet().stream().filter(e -> m.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
        int want = num(s.getOrDefault("count", "0"));
        if (have == 0) {
            errors.add("O baú em " + chest + " não tem " + ItemNames.display(spec) + ".");
            return;
        }
        if (want <= 0) want = have;
        if (want > have) {
            warnings.add("O baú só tem " + have + " de " + ItemNames.display(spec) + " (pedido: " + want + ").");
            want = have;
        }
        addTake(chest, spec, want, contents, false);
    }

    private void addTake(Pos chest, String spec, int want, Map<String, Integer> contents, boolean auto) {
        Predicate<String> m = ItemNames.matcher(spec);
        int left = want;
        for (var e : contents.entrySet()) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int n = Math.min(left, e.getValue());
            bag.merge(e.getKey(), n, Integer::sum);
            left -= n;
        }
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.TAKE;
        t.at = chest;
        t.item = spec;
        t.count = want;
        t.auto = auto;
        t.label = "pegar " + want + " " + ItemNames.display(spec) + " no baú em " + chest;
        tasks.add(t);
    }

    private void put(Map<String, String> s) {
        String spec = s.get("item") == null || s.get("item").isBlank() ? "#all" : ItemNames.resolve(s.get("item"));
        if (spec == null) {
            errors.add("Não sei que item é «" + s.get("item") + "».");
            return;
        }
        Pos chest = chest(s.get("to") != null ? s.get("to") : s.get("from"));
        if (chest == null) return;
        Predicate<String> m = ItemNames.matcher(spec);
        int have = bag.entrySet().stream().filter(e -> m.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
        if (have == 0) {
            errors.add(npc.name + " não terá " + ItemNames.display(spec) + " na mochila para guardar.");
            return;
        }
        int want = num(s.getOrDefault("count", "0"));
        if (want <= 0 || want > have) want = have;
        int left = want;
        for (var e : new ArrayList<>(bag.entrySet())) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int n = Math.min(left, e.getValue());
            bag.merge(e.getKey(), -n, Integer::sum);
            left -= n;
        }
        bag.values().removeIf(v -> v <= 0);
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.PUT;
        t.at = chest;
        t.item = spec;
        t.count = want;
        t.label = "guardar " + want + " " + ItemNames.display(spec) + " no baú em " + chest;
        tasks.add(t);
    }

    /** "look" = baú na mira do rei · "storage" = baú do armazém · "x y z" · null = mira se for baú, senão armazém. */
    private Pos chest(String from) {
        Pos p = null;
        String f = from == null ? "" : Text.norm(from);
        KingdomsCore.Look look = core.playerLook(player);
        if (f.matches("-?\\d+[ ,]+-?\\d+[ ,]+-?\\d+")) {
            String[] v = f.split("[ ,]+");
            p = new Pos(num(v[0]), num(v[1]), num(v[2]));
        } else if (f.matches("storage|armazem|celeiro|deposito")) p = storageChest();
        else if (f.matches("look|mira|aqui|esse|este|esse bau|este bau")) p = look == null ? null : look.block();
        else {
            if (look != null && look.block() != null && port.container(look.block()) != null) p = look.block();
            else p = storageChest();
        }
        if (p == null) {
            errors.add(f.matches("storage|armazem|celeiro|deposito") || f.isEmpty()
                    ? "Não achei baú: mire num baú ou construa um armazém." : "Mire num baú para eu saber qual.");
            return null;
        }
        if (port.container(p) == null) {
            errors.add("Em " + p + " não há baú (é " + ItemNames.display(port.block(p).id()) + ").");
            return null;
        }
        UUID owner = core.state().territory.ownerAt(p);
        Pos king = core.playerPos(player);
        boolean mine = k.id.equals(owner) || (owner == null && king != null && king.distXZ(p) <= 12);
        if (!mine) {
            Kingdom o = owner == null ? null : core.kingdom(owner);
            errors.add(o != null ? "Esse baú é de " + o.name + ": mexer nele seria roubo." : "Esse baú não é do reino (fora do território e longe de Vossa Majestade).");
            return null;
        }
        return p;
    }

    private Pos storageChest() {
        Building b = ChainValidator.findBuilding(core, k, Place.STORAGE, true);
        if (b == null || b.origin.y() == Integer.MIN_VALUE) return null;
        Pos c = port.findNear(b.centerPos(), "minecraft:chest", 6);
        return c != null ? c : port.findNear(b.centerPos(), "minecraft:barrel", 6);
    }

    // ------------------------------------------------------------------ fabricar

    private void craft(Map<String, String> s) {
        String item = ItemNames.resolve(s.get("item"));
        if (item == null || item.startsWith("#") || !port.itemExists(item)) {
            errors.add("Não sei que item é «" + s.get("item") + "».");
            return;
        }
        int count = Math.max(1, Math.min(64, num(s.getOrDefault("count", "1"))));
        if (ItemNames.smithing(item) && npc.profession != Profession.BLACKSMITH) {
            errors.add(ItemNames.display(item) + " é trabalho de ferreiro — " + npc.name + " (" + npc.title().toLowerCase() + ") não sabe forjar.");
            return;
        }
        String why = planCraft(item, count, 0);
        if (why != null) errors.add(why);
        else lastProduct = item;
    }

    /** Planeja fabricar (com sub-tarefas). @return null se deu certo; senão o motivo. Desfaz o que tentou ao falhar. */
    private String planCraft(String item, int count, int depth) {
        List<Recipe> recipes = port.recipes(item);
        if (recipes.isEmpty()) return "Não existe receita para " + ItemNames.display(item) + ".";
        String firstWhy = null;
        for (Recipe r : recipes.subList(0, Math.min(6, recipes.size()))) {
            Map<String, Integer> bagBefore = new TreeMap<>(bag);
            int tasksBefore = tasks.size();
            int warnBefore = warnings.size();
            String why = tryRecipe(r, item, count, depth);
            if (why == null) return null;
            bag = bagBefore;
            while (tasks.size() > tasksBefore) tasks.remove(tasks.size() - 1);
            while (warnings.size() > warnBefore) warnings.remove(warnings.size() - 1);
            if (firstWhy == null) firstWhy = why;
        }
        return firstWhy;
    }

    private String tryRecipe(Recipe r, String item, int count, int depth) {
        int units = (count + r.count() - 1) / r.count();
        Pos station = null;
        if (r.station() != PhysicalPort.Station.NONE) {
            String block = r.station() == PhysicalPort.Station.FURNACE ? "minecraft:furnace" : "minecraft:crafting_table";
            station = findStation(block);
            if (station == null)
                return "Não há " + (r.station() == PhysicalPort.Station.FURNACE ? "fornalha" : "bancada de trabalho") + " por perto para fazer "
                        + ItemNames.display(item) + ". Coloque uma perto de Vossa Majestade ou do centro da vila.";
        }
        // ingredientes agrupados por casa (as opções de cada casa)
        Map<List<String>, Integer> need = new LinkedHashMap<>();
        for (List<String> slot : r.ingredients()) if (!slot.isEmpty()) need.merge(slot, units, Integer::sum);
        if (r.station() == PhysicalPort.Station.FURNACE)
            need.merge(List.of("minecraft:coal", "minecraft:charcoal"), (units + 7) / 8, Integer::sum);
        for (var e : need.entrySet()) {
            String why = obtain(e.getKey(), e.getValue(), depth);
            if (why != null) return why;
        }
        // consome e produz (na simulação)
        for (var e : need.entrySet()) consume(e.getKey(), e.getValue());
        bag.merge(item, units * r.count(), Integer::sum);
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.CRAFT;
        t.item = item;
        t.count = units;
        t.at = station;
        t.block = r.station().name();
        t.auto = depth > 0;
        t.label = (r.station() == PhysicalPort.Station.FURNACE ? "fundir " : "fabricar ") + (units * r.count()) + " " + ItemNames.display(item)
                + (r.station() == PhysicalPort.Station.CRAFTING_TABLE ? " na bancada" : r.station() == PhysicalPort.Station.FURNACE ? " na fornalha" : "");
        tasks.add(t);
        return null;
    }

    /** Garante n itens de qualquer uma das opções na mochila simulada: já tem → baú → fabricar antes. */
    private String obtain(List<String> options, int n, int depth) {
        int have = count(options);
        if (have >= n) return null;
        int missing = n - have;
        // 1) baús de onde dá para pegar (o da mira e o do armazém)
        for (Pos chest : sources()) {
            Map<String, Integer> c = port.container(chest);
            if (c == null) continue;
            for (String opt : options) {
                if (missing <= 0) break;
                int inChest = c.getOrDefault(opt, 0) - alreadyTaken(chest, opt);
                if (inChest <= 0) continue;
                int take = Math.min(inChest, missing);
                addTake(chest, opt, take, Map.of(opt, take), true);
                missing -= take;
            }
        }
        if (missing <= 0) return null;
        // 2) fabricar antes (até 3 níveis: picareta → gravetos → tábuas)
        if (depth < 3) {
            for (String opt : options.subList(0, Math.min(8, options.size()))) {
                if (port.recipes(opt).isEmpty()) continue;
                if (ItemNames.smithing(opt) && npc.profession != Profession.BLACKSMITH) continue;
                if (planCraft(opt, missing, depth + 1) == null) return null;
            }
        }
        return "Faltam " + missing + " " + ItemNames.display(options.get(0)) + (options.size() > 1 ? " (ou similar)" : "")
                + " — não há na mochila, nos baús nem dá para fabricar com o que temos.";
    }

    private int alreadyTaken(Pos chest, String item) {
        int n = 0;
        for (PhysicalJob.Task t : tasks) if (t.kind == PhysicalJob.Kind.TAKE && chest.equals(t.at) && item.equals(t.item)) n += t.count;
        return n;
    }

    private List<Pos> sources() {
        List<Pos> out = new ArrayList<>();
        KingdomsCore.Look look = core.playerLook(player);
        if (look != null && look.block() != null && port.container(look.block()) != null) {
            UUID owner = core.state().territory.ownerAt(look.block());
            if (owner == null || owner.equals(k.id)) out.add(look.block());
        }
        Pos st = storageChest();
        if (st != null && !out.contains(st)) out.add(st);
        return out;
    }

    private int count(List<String> options) {
        int n = 0;
        for (String o : options) n += bag.getOrDefault(o, 0);
        return n;
    }

    private void consume(List<String> options, int n) {
        for (String o : options) {
            if (n <= 0) break;
            int have = bag.getOrDefault(o, 0);
            int use = Math.min(have, n);
            if (use > 0) bag.merge(o, -use, Integer::sum);
            n -= use;
        }
        bag.values().removeIf(v -> v <= 0);
    }

    private Pos findStation(String block) {
        Pos king = core.playerPos(player);
        Pos p = npc.pos == null ? null : port.findNear(npc.pos, block, 16);
        if (p == null && king != null) p = port.findNear(king, block, 24);
        if (p == null) p = port.findNear(k.center, block, 32);
        return p;
    }

    // ------------------------------------------------------------------ entregar

    private void give(Map<String, String> s) {
        String spec = s.get("item") != null && !s.get("item").isBlank() ? ItemNames.resolve(s.get("item"))
                : lastProduct != null ? lastProduct : "#all";
        if (spec == null) {
            errors.add("Não sei que item é «" + s.get("item") + "».");
            return;
        }
        if (core.playerPos(player) == null) {
            errors.add("Não sei onde Vossa Majestade está.");
            return;
        }
        Predicate<String> m = ItemNames.matcher(spec);
        int have = bag.entrySet().stream().filter(e -> m.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
        if (have == 0) {
            errors.add(npc.name + " não terá " + ItemNames.display(spec) + " para entregar.");
            return;
        }
        int want = num(s.getOrDefault("count", "0"));
        if (want <= 0 || want > have) want = have;
        int left = want;
        for (var e : new ArrayList<>(bag.entrySet())) {
            if (left <= 0) break;
            if (!m.test(e.getKey())) continue;
            int n = Math.min(left, e.getValue());
            bag.merge(e.getKey(), -n, Integer::sum);
            left -= n;
        }
        bag.values().removeIf(v -> v <= 0);
        PhysicalJob.Task t = new PhysicalJob.Task();
        t.kind = PhysicalJob.Kind.GIVE;
        t.item = spec;
        t.count = want;
        t.label = "entregar " + want + " " + ItemNames.display(spec) + " a Vossa Majestade";
        tasks.add(t);
    }

    // ------------------------------------------------------------------ regras de proteção

    private boolean nearKing(Pos p) {
        Pos king = core.playerPos(player);
        if (king != null && king.distXZ(p) > KING_RANGE) {
            errors.add("Fica a " + (int) king.distXZ(p) + " blocos de Vossa Majestade: trabalho com as mãos só onde o rei pode ver (até "
                    + KING_RANGE + ").");
            return false;
        }
        return true;
    }

    /** Construção (de qualquer reino) que ocupa este bloco. Muralha protege só o anel. */
    public Building protectedBy(Pos p) {
        return protectedBy(core, p);
    }

    public static Building protectedBy(KingdomsCore core, Pos p) {
        for (Building b : core.state().buildings.values()) {
            if (b.status == Building.Status.ABANDONED || b.origin == null || b.origin.y() == Integer.MIN_VALUE) continue;
            Blueprint bp = b.blueprint();
            if (bp == null) continue;
            int x0 = b.origin.x(), z0 = b.origin.z(), x1 = x0 + bp.sizeX() - 1, z1 = z0 + bp.sizeZ() - 1;
            if (p.x() < x0 || p.x() > x1 || p.z() < z0 || p.z() > z1) continue;
            boolean ring = VillageWall.isWall(bp);
            if (ring && p.x() != x0 && p.x() != x1 && p.z() != z0 && p.z() != z1) continue;
            int slack = ring ? 9 : 0;
            if (p.y() < b.origin.y() - 1 - slack || p.y() > b.origin.y() + bp.sizeY() + slack) continue;
            if (b.placed == 0 && !b.isComplete()) continue; // obra que nem começou não protege terreno
            return b;
        }
        return null;
    }

    private Kingdom foreignOwner(Pos p) {
        UUID o = core.state().territory.ownerAt(p);
        return o == null || o.equals(k.id) ? null : core.kingdom(o);
    }

    // ------------------------------------------------------------------ util

    private static Pos center(Pos a, Pos b) {
        return new Pos((a.x() + b.x()) / 2, (a.y() + b.y()) / 2, (a.z() + b.z()) / 2);
    }

    static int[] dir(String facing) {
        return switch (facing == null ? "" : facing) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            case "west" -> new int[]{-1, 0};
            default -> new int[]{0, 1};
        };
    }

    static int[] size(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.toLowerCase(Locale.ROOT).split("[x×*]");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Math.max(1, Math.min(MAX_SIDE, Integer.parseInt(parts[i].trim())));
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static boolean truthy(String v) {
        return v != null && Text.norm(v).matches("true|sim|1|yes|s");
    }

    static String toolName(String tool) {
        return switch (tool == null ? "" : tool) {
            case "pickaxe" -> "picareta";
            case "axe" -> "machado";
            case "shovel" -> "pá";
            case "hoe" -> "enxada";
            default -> "a ferramenta certa";
        };
    }
}
