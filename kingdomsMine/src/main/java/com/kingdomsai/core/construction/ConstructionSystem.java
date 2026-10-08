package com.kingdomsai.core.construction;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.core.port.WorldPort.SiteCheck;
import com.kingdomsai.core.territory.TerritoryMap;

import java.util.*;

/**
 * Construction Engine: encontra terreno, verifica território, reserva área, cria o projeto,
 * atribui construtores e avança o progresso (abstrato quando ninguém está olhando).
 */
public final class ConstructionSystem {
    public static final int UNKNOWN_Y = Integer.MIN_VALUE;
    private final KingdomsCore core;

    public ConstructionSystem(KingdomsCore core) {
        this.core = core;
    }

    /** Reserva de comida do povo por morador: obra nenhuma (feno do celeiro) come a despensa abaixo disso. */
    public static final double FOOD_RESERVE_PER_CITIZEN = 6;
    /** Por que a última obra não saiu: "no_site" (terreno) ou "no_material" (material). */
    private String lastFailure = "";
    /** O que faltou na última tentativa (para a mensagem ao rei). */
    private Materials.Plan lastShortage;
    /** Resumo da última reserva (material separado / acabamentos pendentes). */
    private String lastNote = "";

    public String lastFailure() {
        return lastFailure;
    }

    public Materials.Plan lastShortage() {
        return lastShortage;
    }

    public String lastNote() {
        return lastNote;
    }

    /** Planeja um prédio em local escolhido automaticamente. Retorna null se não houver terreno (ou material). */
    public Building plan(Kingdom k, Blueprint bp, UUID dedicatedTo) {
        return plan(k, bp, dedicatedTo, false);
    }

    /** @param wait sem material: reserva o terreno e começa sozinha quando o material chegar (status PLANNED). */
    public Building plan(Kingdom k, Blueprint bp, UUID dedicatedTo, boolean wait) {
        Pos site = findSite(k, bp);
        if (site == null) {
            lastFailure = "no_site";
            return null;
        }
        Building b = planAt(k, bp, site, true, wait);
        if (b != null) b.dedicatedTo = dedicatedTo;
        return b;
    }

    /** Obra num ponto dado; sem material, o terreno fica reservado e ela espera (PLANNED). */
    public Building planAt(Kingdom k, Blueprint bp, Pos origin, boolean force) {
        return planAt(k, bp, origin, force, true);
    }

    public Building planAt(Kingdom k, Blueprint bp, Pos origin, boolean force, boolean wait) {
        for (Building o : core.state().buildings.values())
            if (o.status != Building.Status.ABANDONED && o.overlaps(origin, bp, 2)) {
                lastFailure = "no_site";
                return null;
            }
        SiteCheck check = core.world().checkSite(origin.x(), origin.z(), bp);
        if (check.kind() == SiteCheck.Kind.BAD && !force) {
            lastFailure = "no_site";
            return null;
        }
        int y = check.kind() == SiteCheck.Kind.UNLOADED ? UNKNOWN_Y : check.groundY();
        if (y == UNKNOWN_Y && k.center != null && core.world().isLoaded(k.center.x(), k.center.z())) {
            int sy = core.world().surfaceY(origin.x() + bp.sizeX() / 2, origin.z() + bp.sizeZ() / 2);
            if (sy != Integer.MIN_VALUE) y = sy;
        }
        return create(k, bp, new Pos(origin.x(), y, origin.z()), wait);
    }

    /**
     * Muralha da vila: o lugar é a própria vila (não se procura terreno plano) e a altura de cada coluna
     * já vem na planta, que acompanha o relevo. Recusa se cruzar alguma obra.
     */
    public Building planWall(Kingdom k, Blueprint wall) {
        return planWall(k, wall, false);
    }

    public Building planWall(Kingdom k, Blueprint wall, boolean wait) {
        Pos origin = VillageWall.origin(core, k);
        for (Building o : core.state().buildings.values())
            if (o.status != Building.Status.ABANDONED && o.overlaps(origin, wall, 1)) {
                lastFailure = "no_site";
                return null;
            }
        return create(k, wall, origin, wait);
    }

    private Building create(Kingdom k, Blueprint bp, Pos origin, boolean wait) {
        int y = origin.y();
        Building b = new Building();
        b.id = UUID.randomUUID();
        b.kingdomId = k.id;
        b.blueprintId = bp.id();
        b.origin = new Pos(origin.x(), y, origin.z());
        b.variant = core.rng().nextInt(3);
        b.design = BlueprintLibrary.CURRENT_DESIGN;
        lastNote = "";
        Materials.Plan res = reserve(k, b);
        if (res != null && !res.complete()) {
            lastShortage = res;
            if (!wait) {
                lastFailure = "no_material";
                return null;
            }
            b.status = Building.Status.PLANNED;
            b.waitReason = shortText(res);
            b.waitingSince = core.tick();
        } else {
            b.status = Building.Status.UNDER_CONSTRUCTION;
            b.startedTick = core.tick();
        }
        core.state().buildings.put(b.id, b);
        assignBuilder(b);
        String what = bp.displayName() + " em " + b.origin.x() + ", " + b.origin.z();
        core.bus().publish(core.tick(), EventType.BUILDING_STARTED, GameEvent.Severity.INFO, k.id, b.builderId,
                b.status == Building.Status.PLANNED ? "Terreno reservado: " + what + " — aguardando material (" + b.waitReason + ")."
                        : "Obra iniciada: " + what + (lastNote.isEmpty() ? "" : " — " + lastNote),
                Map.of("building", b.id.toString(), "blueprint", bp.id()));
        return b;
    }

    // ------------------------------------------------------------------ material

    /** Comida que as obras não podem usar (o povo precisa comer). */
    public double foodReserve(Kingdom k) {
        return Math.max(20, core.population(k.id) * FOOD_RESERVE_PER_CITIZEN);
    }

    /**
     * Separa do armazém para o canteiro: a estrutura inteira (ou nada) e os acabamentos que houver.
     * @return o plano da estrutura (incompleto = faltou material e nada saiu do armazém); null = obra antiga (não cobra por bloco)
     */
    public Materials.Plan reserve(Kingdom k, Building b) {
        if (!b.enforcesMaterials() || k == null) return null;
        Blueprint bp = b.blueprint();
        if (bp == null) return null;
        BillOfMaterials bom = BillOfMaterials.of(bp, b.variant);
        double food = foodReserve(k);
        Materials.Plan p = Materials.plan(k, deficit(bom.remainingStructural(b.progress), b.site), food);
        if (!p.complete()) return p;
        Materials.apply(k, p);
        addAll(b.site, p.supplied);
        Materials.Plan f = Materials.plan(k, deficit(bom.remaining(b.progress), b.site), food);
        Materials.apply(k, f);
        addAll(b.site, f.supplied);
        List<String> note = new ArrayList<>();
        Map<String, Integer> sep = new TreeMap<>(p.supplied);
        addAll(sep, f.supplied);
        if (!sep.isEmpty()) note.add("material separado: " + Materials.list(sep, 5));
        if (!p.crafted.isEmpty() || !f.crafted.isEmpty()) {
            List<String> c = new ArrayList<>(p.crafted);
            c.addAll(f.crafted);
            note.add("fabricado: " + String.join("; ", c.subList(0, Math.min(3, c.size()))) + (c.size() > 3 ? "…" : ""));
        }
        if (!f.missing.isEmpty())
            note.add("acabamentos sem material (instalados quando chegar): " + Materials.list(f.missing, 4) + " — " + howTo(f.roots));
        lastNote = String.join(". ", note) + (note.isEmpty() ? "" : ".");
        return p;
    }

    /** Quanto de material a estrutura de n obras desta planta pede, abastecido pelo que o reino tem (nada é aplicado). */
    public Materials.Plan preview(Kingdom k, Blueprint bp, int n) {
        Map<String, Integer> need = new TreeMap<>();
        for (var e : BillOfMaterials.of(bp, 0).structural().entrySet()) need.put(e.getKey(), e.getValue() * Math.max(1, n));
        return Materials.plan(k, need, foodReserve(k));
    }

    /** "46× tábua de carvalho, 12× escada (Madeira: faltam ~58)" — curto, para o painel. */
    public static String shortText(Materials.Plan p) {
        if (p == null || p.complete()) return "";
        StringBuilder sb = new StringBuilder("falta " + Materials.list(p.missing, 3));
        String res = resourceGap(p);
        if (!res.isEmpty()) sb.append(" (").append(res).append(')');
        return sb.toString();
    }

    /** Mensagem completa para o rei: o que falta, quanto de matéria-prima e como conseguir. */
    public String shortageText(Blueprint bp, int amount, Materials.Plan p) {
        StringBuilder sb = new StringBuilder("Falta material para " + (amount > 1 ? amount + "× " : "") + bp.displayName() + ": "
                + Materials.list(p.missing, 5) + ".");
        String res = resourceGap(p);
        if (!res.isEmpty()) sb.append(" Matéria-prima: ").append(res).append('.');
        sb.append(" Como conseguir: ").append(howTo(p.roots)).append('.');
        sb.append(" Ou mande construir «quando tiver material»: o terreno fica reservado e a obra começa sozinha quando o material chegar.");
        return sb.toString();
    }

    /** "Madeira: faltam ~58; Pedra: faltam ~20" (recursos do registro que faltaram, já descontado o que há). */
    private static String resourceGap(Materials.Plan p) {
        Map<String, Integer> others = new TreeMap<>();
        Map<com.kingdomsai.core.kingdom.ResourceType, Integer> raw = Materials.rawCost(p.missing, others);
        List<String> parts = new ArrayList<>();
        for (var e : raw.entrySet())
            if (p.roots.contains("RES:" + e.getKey().name())) parts.add(e.getKey().display + ": faltam ~" + e.getValue());
        for (var e : others.entrySet()) if (p.roots.stream().anyMatch(r -> r.contains(e.getKey()) || r.equals("#wool") && e.getKey().endsWith("_wool")))
            parts.add(Materials.name(e.getKey()) + ": ~" + e.getValue());
        return String.join("; ", parts);
    }

    private static String howTo(Set<String> roots) {
        List<String> out = new ArrayList<>();
        for (String r : roots) {
            String h = Materials.howToGet(r);
            if (!out.contains(h)) out.add(h);
            if (out.size() >= 4) break;
        }
        return out.isEmpty() ? "ponha o material nos baús do armazém" : String.join("; ", out);
    }

    /** need − have (só o que falta). */
    static Map<String, Integer> deficit(Map<String, Integer> need, Map<String, Integer> have) {
        Map<String, Integer> out = new TreeMap<>();
        for (var e : need.entrySet()) {
            int d = e.getValue() - have.getOrDefault(e.getKey(), 0);
            if (d > 0) out.put(e.getKey(), d);
        }
        return out;
    }

    private static void addAll(Map<String, Integer> into, Map<String, Integer> add) {
        for (var e : add.entrySet()) if (e.getValue() > 0) into.merge(e.getKey(), e.getValue(), Integer::sum);
    }

    /** Devolve itens ao reino (recurso do registro ou bem guardado). */
    public static void refund(Kingdom k, Map<String, Integer> items) {
        if (k == null) return;
        for (var e : items.entrySet()) {
            if (e.getValue() <= 0) continue;
            com.kingdomsai.core.economy.TreasurySystem.Unit u = com.kingdomsai.core.economy.TreasurySystem.unit(e.getKey());
            if (u != null) k.add(u.resource(), (double) e.getValue() * u.value());
            else k.goods.merge(e.getKey(), e.getValue(), Integer::sum);
        }
    }

    /** O que a limpeza do terreno rendeu (toras, pedra, areia): vai para o reino. Chamado pelo adaptador. */
    public void salvage(Building b, String item, int n) {
        if (b == null || item == null || n <= 0) return;
        Kingdom k = core.kingdom(b.kingdomId);
        if (k == null) return;
        refund(k, Map.of(item, n));
        b.salvaged.merge(item, n, Integer::sum);
    }

    private boolean takeFromSite(Building b, String item, int n) {
        int have = b.site.getOrDefault(item, 0);
        if (have < n) return false;
        if (have == n) b.site.remove(item);
        else b.site.put(item, have - n);
        return true;
    }

    /**
     * Gasta o material do passo {@code i}. 1 = pode colocar; 0 = passo pulado (acabamento sem material vira pendência,
     * ou a outra metade da porta/cama ficou pendente); -1 = parado (falta estrutura).
     */
    private int consume(Building b, Blueprint bp, int i) {
        Blueprint.Placement p = bp.placements().get(i);
        if (p.material() == Material.AIR || !b.enforcesMaterials()) return 1;
        BillOfMaterials bom = BillOfMaterials.of(bp, b.variant);
        int partner = bom.partner(i);
        if (partner >= 0 && b.isPending(partner)) {
            b.markPending(i);
            return 0;
        }
        String item = bom.item(i);
        if (item == null) return 1;
        int n = bom.count(i);
        if (!takeFromSite(b, item, n)) {
            Kingdom k = core.kingdom(b.kingdomId);
            boolean got = false;
            if (k != null) {
                Materials.Plan pl = Materials.plan(k, Map.of(item, n - b.site.getOrDefault(item, 0)), foodReserve(k));
                if (pl.complete()) {
                    Materials.apply(k, pl);
                    addAll(b.site, pl.supplied);
                    got = takeFromSite(b, item, n);
                }
            }
            if (!got) {
                if (bom.finish(i)) {
                    b.markPending(i);
                    return 0;
                }
                if (b.waitReason.isEmpty()) {
                    b.waitReason = "falta " + n + "× " + Materials.name(item);
                    b.waitingSince = core.tick();
                    say(b, GameEvent.Severity.WARN, "Majestade, a obra de " + bp.displayName().toLowerCase() + " parou: " + b.waitReason
                            + ". " + Materials.howToGet(rootOf(k, item)) + ".");
                }
                return -1;
            }
        }
        if (!b.waitReason.isEmpty() && b.status == Building.Status.UNDER_CONSTRUCTION) b.waitReason = "";
        return 1;
    }

    private static String rootOf(Kingdom k, String item) {
        if (k == null) return item;
        Materials.Plan p = Materials.plan(k, Map.of(item, 1));
        return p.roots.isEmpty() ? item : p.roots.iterator().next();
    }

    /** Fala do mestre de obras (aparece para o rei como recado). */
    private void say(Building b, GameEvent.Severity sev, String text) {
        Npc n = core.npc(b.builderId);
        core.bus().publish(core.tick(), EventType.BUILDING_LATE, sev, b.kingdomId, n == null ? null : n.id,
                (n == null ? "Mestre de obras" : "«" + n.name + "»") + ": " + text, Map.of("building", b.id.toString()));
    }

    /** Procura em espiral um terreno dentro do território, sem sobrepor outras obras. */
    public Pos findSite(Kingdom k, Blueprint bp) {
        TerritoryMap t = core.state().territory;
        Pos c = k.center;
        int minR = bp.category() == Blueprint.Category.FARM ? 18 : 10;
        Pos firstUnloaded = null;
        Pos best = null;
        double bestScore = -1;
        int okFound = 0;
        for (int r = minR; r <= 90 && okFound < 6; r += 4) {
            int steps = Math.max(8, r / 2);
            double phase = core.rng().nextDouble() * Math.PI * 2;
            for (int i = 0; i < steps; i++) {
                double a = phase + i * Math.PI * 2 / steps;
                int x = c.x() + (int) Math.round(Math.cos(a) * r) - bp.sizeX() / 2;
                int z = c.z() + (int) Math.round(Math.sin(a) * r) - bp.sizeZ() / 2;
                if (!insideTerritory(t, k.id, x, z, bp)) continue;
                if (overlapsAny(new Pos(x, 0, z), bp) || coversPlaza(k, x, z, bp)) continue;
                SiteCheck check = core.world().checkSite(x, z, bp);
                if (check.kind() == SiteCheck.Kind.UNLOADED) {
                    if (firstUnloaded == null) firstUnloaded = new Pos(x, UNKNOWN_Y, z);
                    continue;
                }
                if (check.kind() == SiteCheck.Kind.BAD) continue;
                double score = check.score() - r * 0.01;
                okFound++;
                if (score > bestScore) {
                    bestScore = score;
                    best = new Pos(x, check.groundY(), z);
                }
            }
        }
        return best != null ? best : firstUnloaded;
    }

    private boolean insideTerritory(TerritoryMap t, UUID owner, int x, int z, Blueprint bp) {
        int[][] corners = {{x - 1, z - 1}, {x + bp.sizeX(), z - 1}, {x - 1, z + bp.sizeZ()}, {x + bp.sizeX(), z + bp.sizeZ()}};
        for (int[] cc : corners) if (!owner.equals(t.ownerAt(new Pos(cc[0], 0, cc[1])))) return false;
        return true;
    }

    private boolean overlapsAny(Pos origin, Blueprint bp) {
        for (Building o : core.state().buildings.values())
            if (o.status != Building.Status.ABANDONED && o.overlaps(origin, bp, 3)) return true;
        return false;
    }

    /** Mantém a praça central (raio 7) livre para os NPCs se reunirem. */
    private boolean coversPlaza(Kingdom k, int x, int z, Blueprint bp) {
        int cx = Math.max(x, Math.min(k.center.x(), x + bp.sizeX()));
        int cz = Math.max(z, Math.min(k.center.z(), z + bp.sizeZ()));
        int dx = cx - k.center.x(), dz = cz - k.center.z();
        return dx * dx + dz * dz < 49;
    }

    // ------------------------------------------------------------------ construtores

    /** Garante pelo menos um construtor na obra (o menos ocupado do reino). */
    public void assignBuilder(Building b) {
        b.builderIds.removeIf(id -> !isBuilder(core.npc(id), b.kingdomId));
        if (b.builderId != null && !b.builderIds.contains(b.builderId) && isBuilder(core.npc(b.builderId), b.kingdomId))
            b.builderIds.add(0, b.builderId);
        if (b.builderIds.isEmpty()) {
            Npc best = null;
            int bestLoad = Integer.MAX_VALUE;
            for (Npc n : core.citizens(b.kingdomId)) {
                if (n.profession != Profession.BUILDER) continue;
                int load = 0;
                for (Building o : core.state().buildings.values())
                    if (o.status == Building.Status.UNDER_CONSTRUCTION && o.hasBuilder(n.id)) load++;
                if (load < bestLoad) {
                    bestLoad = load;
                    best = n;
                }
            }
            if (best != null) b.builderIds.add(best.id);
        }
        b.builderId = b.builderIds.isEmpty() ? null : b.builderIds.get(0);
    }

    /** O construtor com menos obras (para o conselho delegar). */
    public Npc leastBusyBuilder(UUID kingdomId) {
        Npc best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (Npc n : core.citizens(kingdomId)) {
            if (n.profession != Profession.BUILDER || !n.isFree()) continue;
            int load = 0;
            for (Building o : core.state().buildings.values())
                if (o.status == Building.Status.UNDER_CONSTRUCTION && o.hasBuilder(n.id)) load++;
            if (load < bestLoad) {
                bestLoad = load;
                best = n;
            }
        }
        return best;
    }

    private static boolean isBuilder(Npc n, UUID kingdom) {
        return n != null && n.alive && n.profession == Profession.BUILDER && kingdom.equals(n.kingdomId);
    }

    /** Obra em que o construtor está trabalhando agora: a de prazo mais urgente, senão a mais antiga. */
    public Building currentProjectOf(Npc builder) {
        Building cur = null;
        for (Building b : core.state().buildings.values()) {
            if (b.status != Building.Status.UNDER_CONSTRUCTION || !b.hasBuilder(builder.id)) continue;
            if (cur == null || score(b) < score(cur)) cur = b;
        }
        return cur;
    }

    /** Obra parada por falta de estrutura vai para o fim da fila (o construtor toca outra enquanto o material não chega). */
    private static double score(Building b) {
        return urgency(b) + (b.waitReason.isEmpty() ? 0 : 1e16);
    }

    private static double urgency(Building b) {
        return b.deadlineTick > 0 ? b.deadlineTick : 1e15 + b.startedTick;
    }

    /** Construtores trabalhando AGORA nesta obra. */
    public List<Npc> activeBuilders(Building b) {
        List<Npc> out = new ArrayList<>();
        for (UUID id : b.builderIds) {
            Npc n = core.npc(id);
            if (isBuilder(n, b.kingdomId) && currentProjectOf(n) == b) out.add(n);
        }
        return out;
    }

    public List<Building> projects(UUID kingdomId) {
        List<Building> out = new ArrayList<>();
        for (Building b : core.buildings(kingdomId))
            if (b.status == Building.Status.UNDER_CONSTRUCTION || b.status == Building.Status.PLANNED) out.add(b);
        out.sort(Comparator.comparingDouble((Building b) -> b.status == Building.Status.PLANNED ? 1 : 0).thenComparingDouble(ConstructionSystem::urgency));
        return out;
    }

    // ------------------------------------------------------------------ tempo: ETA e prazos

    public int remainingSolid(Building b) {
        Blueprint bp = b.blueprint();
        return bp == null ? 0 : bp.solidTotal() - bp.solidBefore(b.progress);
    }

    /** Segundos estimados para terminar; -1 se não há construtor. */
    public double etaSeconds(Building b) {
        int active = activeBuilders(b).size();
        if (active == 0) return -1;
        return remainingSolid(b) / (core.config().builderBlocksPerSecond * active);
    }

    public int neededBuilders(Building b) {
        if (b.deadlineTick <= 0) return 1;
        double secondsLeft = Math.max(1, (b.deadlineTick - core.tick()) / 20.0);
        return Math.max(1, (int) Math.ceil(remainingSolid(b) / (core.config().builderBlocksPerSecond * secondsLeft)));
    }

    /** Define o prazo e já remaneja construtores. Retorna um resumo para o jogador. */
    public String setDeadline(Building b, long ticksFromNow) {
        b.deadlineTick = core.tick() + Math.max(20, ticksFromNow);
        b.overdueWarned = false;
        b.atRiskWarned = false;
        rebalance(b.kingdomId);
        int need = neededBuilders(b), have = activeBuilders(b).size();
        String msg = "Prazo de " + b.blueprint().displayName() + ": " + formatDuration(ticksFromNow / 20.0)
                + ". " + have + " construtor(es) na obra" + (need > have ? ", precisaria de " + need + " — vai atrasar." : ", deve dar tempo.")
                + " ETA " + formatEta(b) + ".";
        core.bus().publish(core.tick(), EventType.BUILDING_DEADLINE_SET, need > have ? GameEvent.Severity.WARN : GameEvent.Severity.INFO,
                b.kingdomId, b.builderId, msg, Map.of("building", b.id.toString()));
        return msg;
    }

    public String formatEta(Building b) {
        if (b.status == Building.Status.PLANNED) return "aguardando material: " + b.waitReason;
        if (!b.waitReason.isEmpty()) return "parada: " + b.waitReason;
        double eta = etaSeconds(b);
        if (eta >= 0) return formatDuration(eta);
        if (!b.builderIds.isEmpty()) {
            Npc n = core.npc(b.builderIds.get(0));
            Building cur = n == null ? null : currentProjectOf(n);
            if (n != null && cur != null && cur != b) return "na fila de " + n.name + " (termina «" + cur.blueprint().displayName() + "» antes)";
        }
        return "parada (sem construtor)";
    }

    public static String formatDuration(double seconds) {
        if (seconds < 60) return (int) Math.ceil(seconds) + "s";
        if (seconds < 1200) return (int) (seconds / 60) + "min " + ((int) seconds % 60) + "s";
        double days = seconds / 1200.0;
        if (days < 1) return (int) (seconds / 60) + "min";
        return String.format(Locale.ROOT, "%.1f dia(s)", days);
    }

    /**
     * Converte "30s", "5m", "2min", "1d", "amanha", "hoje" em ticks. 1 dia = 1 dia do Minecraft (20 min).
     * @return -1 se não reconhecido.
     */
    public static long parseDuration(String s) {
        if (s == null) return -1;
        String n = com.kingdomsai.core.common.Text.norm(s).replace(" ", "");
        if (n.startsWith("amanha") || n.equals("tomorrow")) return 24000;
        if (n.equals("hoje") || n.equals("today")) return 12000;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:[.,]\\d+)?)(s|seg|segundos?|m|min|minutos?|h|horas?|d|dias?)?$").matcher(n);
        if (!m.find()) return -1;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        String u = m.group(2) == null ? "m" : m.group(2);
        if (u.startsWith("s")) return (long) (v * 20);
        if (u.startsWith("h")) return (long) (v * 72000);
        if (u.startsWith("d")) return (long) (v * 24000);
        return (long) (v * 1200);
    }

    /** Remaneja construtores: toda obra tem alguém; obras com prazo puxam ajudantes das sem prazo. */
    public void rebalance(UUID kingdomId) {
        List<Building> ps = projects(kingdomId);
        for (Building b : ps) assignBuilder(b);
        for (Building b : ps) {
            if (b.deadlineTick <= 0) continue;
            int need = neededBuilders(b);
            for (Npc n : core.citizens(kingdomId)) {
                if (activeBuilders(b).size() >= need) break;
                if (n.profession != Profession.BUILDER || b.hasBuilder(n.id)) continue;
                Building cur = currentProjectOf(n);
                if (cur != null && cur.deadlineTick > 0 && cur.deadlineTick <= b.deadlineTick) continue;
                b.builderIds.add(n.id);
                n.remember(core.tick(), "Fui chamado para ajudar na obra de " + b.blueprint().displayName().toLowerCase() + " (tem prazo).", 30, b.id, "obra");
            }
        }
    }

    /** Cancela a obra: o material que estava no canteiro volta ao armazém (obras antigas: metade do custo). */
    public String cancel(Building b) {
        b.status = Building.Status.ABANDONED;
        Kingdom k = core.kingdom(b.kingdomId);
        Blueprint bp = b.blueprint();
        String back;
        if (b.enforcesMaterials()) {
            Map<String, Integer> site = new TreeMap<>(b.site);
            refund(k, site);
            b.site.clear();
            back = site.isEmpty() ? "o canteiro estava vazio" : "voltou ao armazém: " + Materials.list(site, 6);
        } else {
            if (k != null && bp != null) for (var e : bp.cost().entrySet()) k.add(e.getKey(), e.getValue() / 2.0);
            back = "metade dos materiais devolvida";
        }
        String msg = "Obra cancelada: " + (bp == null ? "?" : bp.displayName()) + " (" + back + ").";
        core.bus().publish(core.tick(), EventType.BUILDING_CANCELLED, GameEvent.Severity.INFO, b.kingdomId, null, msg);
        return msg;
    }

    private final Map<UUID, Double> carry = new HashMap<>();

    /** Progresso abstrato: cada construtor NÃO materializado avança a sua obra atual por cálculo. */
    public void tickSecond() {
        if (!core.config().constructionEnabled) return;
        if (core.tick() % 200 < 20) for (UUID k : core.state().kingdoms.keySet()) rebalance(k);
        double rate = core.config().builderBlocksPerSecond;
        for (Npc n : core.allAlive()) {
            if (n.profession != Profession.BUILDER || n.materialized) continue;
            Building b = currentProjectOf(n);
            if (b == null) continue;
            double acc = carry.getOrDefault(n.id, 0.0) + rate;
            int add = (int) Math.floor(acc);
            carry.put(n.id, acc - add);
            if (add > 0) advance(b, add);
            checkComplete(b);
        }
        boolean supplyTick = core.tick() % 200 < 20;
        for (Building b : List.copyOf(core.state().buildings.values())) {
            if (supplyTick && b.status == Building.Status.PLANNED) tryStart(b);
            if (supplyTick && !b.pending.isEmpty() && (b.status == Building.Status.UNDER_CONSTRUCTION || b.status == Building.Status.COMPLETE))
                supplyPending(b);
            if (b.status != Building.Status.UNDER_CONSTRUCTION) continue;
            if (b.builderIds.isEmpty()) {
                assignBuilder(b);
                if (b.builderIds.isEmpty()) warnNoBuilder(b.kingdomId);
            }
            checkDeadline(b);
        }
    }

    private void checkDeadline(Building b) {
        if (b.deadlineTick <= 0) return;
        String name = b.blueprint().displayName();
        if (core.tick() > b.deadlineTick && !b.overdueWarned) {
            b.overdueWarned = true;
            Kingdom k = core.kingdom(b.kingdomId);
            if (k != null) k.legitimacy = Math.max(0, k.legitimacy - 2);
            for (Npc n : activeBuilders(b))
                n.remember(core.tick(), "Não conseguimos terminar " + name.toLowerCase() + " no prazo do rei.", 55, b.id, "obra", "atraso");
            core.bus().publish(core.tick(), EventType.BUILDING_LATE, GameEvent.Severity.DANGER, b.kingdomId, b.builderId,
                    "Obra atrasada: " + name + " passou do prazo (" + (int) b.percent() + "% pronta).", Map.of("building", b.id.toString()));
        } else if (!b.atRiskWarned && core.tick() < b.deadlineTick) {
            double eta = etaSeconds(b);
            double left = (b.deadlineTick - core.tick()) / 20.0;
            if (eta < 0 || eta > left * 1.05) {
                b.atRiskWarned = true;
                core.bus().publish(core.tick(), EventType.BUILDING_LATE, GameEvent.Severity.WARN, b.kingdomId, b.builderId,
                        name + " vai atrasar: ETA " + formatEta(b) + ", faltam " + formatDuration(left) + ". Precisa de "
                                + neededBuilders(b) + " construtor(es), tem " + activeBuilders(b).size() + ".",
                        Map.of("building", b.id.toString()));
            }
        }
    }

    private final Map<UUID, Long> lastNoBuilderWarning = new HashMap<>();

    private void warnNoBuilder(UUID kingdomId) {
        long last = lastNoBuilderWarning.getOrDefault(kingdomId, -100000L);
        if (core.tick() - last < 20 * 120) return;
        lastNoBuilderWarning.put(kingdomId, core.tick());
        core.bus().publish(core.tick(), EventType.ACTION_REJECTED, GameEvent.Severity.WARN, kingdomId, null,
                "Obras paradas: não há construtores. Use /k assign construtor 1.");
    }

    /** Avança n blocos "reais" gastando o material do canteiro; limpeza (AIR) e pendências não custam tempo. */
    private void advance(Building b, int n) {
        List<Blueprint.Placement> ps = b.blueprint().placements();
        while (n > 0 && b.progress < ps.size() && b.status == Building.Status.UNDER_CONSTRUCTION) {
            boolean air = ps.get(b.progress).material() == Material.AIR;
            int r = takeStep(b);
            if (r < 0) break;
            if (!air && r > 0) n--;
        }
    }

    /**
     * Executa o passo atual da obra: gasta o material e avança. 1 = coloque o bloco do passo que acabou de ser feito;
     * 0 = passo pulado (pendência); -1 = nada feito (sem material de estrutura, ou a obra não está em andamento).
     */
    public int takeStep(Building b) {
        if (b.status != Building.Status.UNDER_CONSTRUCTION) return -1;
        Blueprint bp = b.blueprint();
        if (bp == null) return -1;
        if (b.progress >= bp.blockCount()) {
            checkComplete(b);
            return -1;
        }
        int r = consume(b, bp, b.progress);
        if (r < 0) return -1;
        b.progress++;
        checkComplete(b);
        return r;
    }

    /** Compatibilidade: um passo físico (o adaptador novo usa {@link #takeStep}). */
    public void stepPhysical(Building b) {
        takeStep(b);
    }

    /** Obra aguardando material: quando a estrutura inteira couber no armazém, separa e começa. */
    private void tryStart(Building b) {
        Kingdom k = core.kingdom(b.kingdomId);
        if (k == null) return;
        Materials.Plan res = reserve(k, b);
        if (res != null && !res.complete()) {
            String now = shortText(res);
            if (!now.equals(b.waitReason)) b.waitReason = now;
            return;
        }
        b.status = Building.Status.UNDER_CONSTRUCTION;
        b.startedTick = core.tick();
        b.waitReason = "";
        assignBuilder(b);
        core.bus().publish(core.tick(), EventType.BUILDING_STARTED, GameEvent.Severity.GOOD, k.id, b.builderId,
                "O material chegou: começou a obra de " + b.blueprint().displayName() + " em " + b.origin.x() + ", " + b.origin.z()
                        + (lastNote.isEmpty() ? "." : " — " + lastNote),
                Map.of("building", b.id.toString(), "blueprint", b.blueprintId));
    }

    /** Acabamentos pendentes: o que chegou ao armazém é separado e instalado (aparece no mundo quando a área carregar). */
    private void supplyPending(Building b) {
        Kingdom k = core.kingdom(b.kingdomId);
        Blueprint bp = b.blueprint();
        if (k == null || bp == null) return;
        BillOfMaterials bom = BillOfMaterials.of(bp, b.variant);
        Map<String, Integer> need = new TreeMap<>();
        for (int idx : b.pending) {
            String it = bom.item(idx);
            if (it != null) need.merge(it, bom.count(idx), Integer::sum);
        }
        Map<String, Integer> got = new HashMap<>();
        for (var e : need.entrySet()) { // primeiro o que já está no canteiro
            int have = Math.min(b.site.getOrDefault(e.getKey(), 0), e.getValue());
            if (have > 0) {
                takeFromSite(b, e.getKey(), have);
                got.put(e.getKey(), have);
            }
        }
        Materials.Plan p = Materials.plan(k, deficit(need, got), foodReserve(k));
        if (!p.supplied.isEmpty()) {
            Materials.apply(k, p);
            addAll(got, p.supplied);
        }
        if (got.isEmpty()) return;
        List<Integer> done = new ArrayList<>();
        Map<String, Integer> used = new TreeMap<>();
        for (int idx : b.pending) {
            String it = bom.item(idx);
            if (it == null) continue;
            int c = bom.count(idx);
            if (got.getOrDefault(it, 0) >= c) {
                got.merge(it, -c, Integer::sum);
                used.merge(it, c, Integer::sum);
                done.add(idx);
            }
        }
        for (int idx : b.pending) if (bom.item(idx) == null && done.contains(bom.partner(idx))) done.add(idx);
        addAll(b.site, got); // sobra volta ao canteiro
        if (done.isEmpty()) return;
        int housingBefore = b.housing();
        b.pending.removeAll(done);
        for (int idx : done) if (!b.install.contains(idx)) b.install.add(idx);
        b.touchSets();
        say(b, GameEvent.Severity.GOOD, "chegou material: instalando " + Materials.list(used, 4) + " em " + bp.displayName().toLowerCase()
                + (b.pending.isEmpty() ? " — agora está completa." : " (ainda falta " + b.pending.size() + " peça(s))."));
        if (b.isComplete() && b.housing() > housingBefore) assignResidents(b);
    }

    private void checkComplete(Building b) {
        Blueprint bp = b.blueprint();
        if (b.progress < bp.blockCount() || b.status != Building.Status.UNDER_CONSTRUCTION) return;
        b.status = Building.Status.COMPLETE;
        b.completedTick = core.tick();
        b.waitReason = "";
        Kingdom k = core.kingdom(b.kingdomId);
        String extra = "";
        if (b.enforcesMaterials()) {
            // sobra do canteiro que nenhuma pendência vai usar volta ao armazém
            BillOfMaterials bom = BillOfMaterials.of(bp, b.variant);
            Map<String, Integer> keep = new TreeMap<>();
            for (int idx : b.pending) if (bom.item(idx) != null) keep.merge(bom.item(idx), bom.count(idx), Integer::sum);
            Map<String, Integer> back = new TreeMap<>();
            for (var e : b.site.entrySet()) {
                int extraN = e.getValue() - keep.getOrDefault(e.getKey(), 0);
                if (extraN > 0) back.put(e.getKey(), extraN);
            }
            refund(k, back);
            for (var e : back.entrySet()) takeFromSite(b, e.getKey(), e.getValue());
            if (!b.pending.isEmpty()) {
                Map<String, Integer> miss = new TreeMap<>();
                for (int idx : b.pending) if (bom.item(idx) != null) miss.merge(bom.item(idx), bom.count(idx), Integer::sum);
                if (!miss.isEmpty()) extra = " Faltam acabamentos (instalados quando chegarem): " + Materials.list(miss, 4) + ".";
            }
        }
        Npc builder = core.npc(b.builderId);
        if (builder != null) {
            builder.fame += 3;
            builder.remember(core.tick(), "Terminei de construir " + bp.displayName().toLowerCase() + ".", 45, b.id, "obra", "orgulho");
        }
        if (b.housing() > 0) assignResidents(b);
        core.bus().publish(core.tick(), EventType.BUILDING_COMPLETED, GameEvent.Severity.GOOD, b.kingdomId, b.builderId,
                bp.displayName() + " concluída" + (k != null ? " em " + k.name : "") + "." + extra,
                Map.of("building", b.id.toString(), "blueprint", bp.id()));
        if (bp.id().equals("town_hall") && k != null) core.chronicle("O Salão Real de " + k.name + " foi erguido.");
    }

    private void assignResidents(Building b) {
        int cap = b.housing();
        List<Npc> homeless = new ArrayList<>();
        for (Npc n : core.citizens(b.kingdomId)) if (n.homeId == null || core.state().buildings.get(n.homeId) == null) homeless.add(n);
        if (b.dedicatedTo != null) homeless.sort(Comparator.comparing(n -> !n.id.equals(b.dedicatedTo)));
        if (b.blueprintId.equals("barracks")) homeless.sort(Comparator.comparing(n -> !n.profession.isMilitary()));
        for (Npc n : homeless) {
            if (b.residents.size() >= cap) break;
            if (b.residents.contains(n.id)) continue;
            b.residents.add(n.id);
            n.homeId = b.id;
            boolean dedicated = n.id.equals(b.dedicatedTo);
            n.remember(core.tick(), dedicated ? "O rei mandou construir uma casa só para mim." : "Ganhei um lugar para morar.",
                    dedicated ? 75 : 40, b.id, "casa");
            n.loyalty = Math.min(100, n.loyalty + (dedicated ? 12 : 4));
            if (dedicated) n.fame += 5;
        }
    }

    /** O adaptador informa que a obra precisou de uma altura (área carregou pela primeira vez). */
    public void resolveGround(Building b, int y) {
        if (b.origin.y() == UNKNOWN_Y) b.origin = new Pos(b.origin.x(), y, b.origin.z());
    }
}
