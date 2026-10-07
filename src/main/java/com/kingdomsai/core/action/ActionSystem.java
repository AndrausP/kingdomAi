package com.kingdomsai.core.action;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.*;

import java.util.*;

/**
 * "A IA decide o que quer fazer. O jogo decide se pode fazer. O Action System decide como fazer."
 */
public final class ActionSystem {
    private final KingdomsCore core;
    private final List<Validators.Validator> pipeline = List.of(
            Validators.SCHEMA, Validators.PERMISSION, Validators.WORLD, Validators.RESOURCES);

    public ActionSystem(KingdomsCore core) {
        this.core = core;
    }

    public ActionResult validate(ActionRequest r) {
        for (Validators.Validator v : pipeline) {
            ActionResult rej = v.validate(r, core);
            if (rej != null) return rej;
        }
        return null;
    }

    public ActionResult execute(ActionRequest r) {
        ActionResult rej = validate(r);
        if (rej != null) {
            if (r.source() != ActionRequest.Source.DIRECTOR)
                core.bus().publish(core.tick(), EventType.ACTION_REJECTED, GameEvent.Severity.INFO, r.kingdomId(), r.actorId(),
                        r.type() + " rejeitada: " + rej.code(), Map.of("code", rej.code(), "source", r.source().name()));
            return rej;
        }
        Kingdom k = core.kingdom(r.kingdomId());
        ActionResult res;
        try {
            res = switch (r.type()) {
                case BUILD -> build(k, r);
                case DEADLINE -> ActionResult.ok(core.construction().setDeadline(findProject(core, k, r.param("building")),
                        com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline"))));
                case CANCEL_BUILD -> ActionResult.ok(core.construction().cancel(findProject(core, k, r.param("building"))));
                case RECRUIT -> recruit(k, r.intParam("amount", 1));
                case RELEASE -> release(k, r.intParam("amount", 1), r.param("profession"));
                case WORK -> work(k, r);
                case PROMOTE -> promote(k, core.findNpc(k.id, r.param("npc")), Office.parse(r.param("office")));
                case DEMOTE -> demote(k, core.findNpc(k.id, r.param("npc")));
                case PATROL, GUARD -> setProfession(k, core.findNpc(k.id, r.param("npc")), Profession.GUARD);
                case TAX -> tax(k, r.param("level"));
                case LAW -> law(k, r.param("law"), r.param("value"));
                case CLAIM -> claim(k, r.intParam("amount", 1));
                case NEGOTIATE -> negotiate(k, r);
                case GIVE -> give(k, r);
                case DECLARE_WAR -> ActionResult.ok(core.diplomacy().declareWar(k, core.findKingdom(r.param("target"))));
                case MAKE_PEACE -> {
                    String why = core.diplomacy().proposePeace(k, core.findKingdom(r.param("target")));
                    yield why == null ? ActionResult.ok("Paz aceita.") : ActionResult.reject("refused", why);
                }
                case TALK -> ActionResult.ok("");
                case CHAIN -> chain(k, r);
                case STOP_CHAIN -> ActionResult.ok(core.work().stop(Validators.findChain(core, k, r), "ordem do rei"));
                default -> ActionResult.reject("not_available_in_this_phase", r.type() + " ainda não foi implementada.");
            };
        } catch (RuntimeException ex) {
            res = ActionResult.reject("execution_error", "Falha ao executar " + r.type() + ": " + ex.getMessage());
        }
        if (res.ok() && r.actorKind() == ActionRequest.ActorKind.PLAYER && r.type() != ActionType.TALK)
            core.bus().publish(core.tick(), EventType.PLAYER_ORDER, GameEvent.Severity.INFO, k.id, r.actorId(),
                    "Ordem real: " + r.type() + " — " + res.message(), Map.of("source", r.source().name()));
        return res;
    }

    // ----------------------------------------------------------------- handlers

    /** Obra por referência: null = a mais recente; número = posição em /k projects; texto = nome/planta. */
    public static Building findProject(KingdomsCore core, Kingdom k, String ref) {
        List<Building> ps = core.construction().projects(k.id);
        if (ps.isEmpty()) return null;
        if (ref == null || ref.isBlank()) return ps.stream().max(Comparator.comparingLong(b -> b.startedTick)).orElse(null);
        String n = Text.norm(ref).replace("#", "");
        if (n.matches("\\d+")) {
            int i = Integer.parseInt(n) - 1;
            return i >= 0 && i < ps.size() ? ps.get(i) : null;
        }
        for (Building b : ps) {
            if (b.blueprintId.equals(n) || Text.norm(b.blueprint().displayName()).contains(n)) return b;
            Blueprint found = BlueprintLibrary.find(n);
            if (found != null && found.id().equals(b.blueprintId)) return b;
        }
        return null;
    }

    private ActionResult build(Kingdom k, ActionRequest r) {
        Blueprint bp = Validators.isCustom(r)
                ? core.registerSpec(com.kingdomsai.core.construction.ParametricBlueprints.spec(r.params()))
                : BlueprintLibrary.find(r.param("blueprint"));
        long deadline = r.param("deadline") == null ? -1 : com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline"));
        List<Building> planned = new ArrayList<>();
        int amount = r.intParam("amount", 1);
        Npc forNpc = r.param("for") == null ? null : core.findNpc(k.id, r.param("for"));
        int started = 0;
        for (int i = 0; i < amount; i++) {
            Building b = core.construction().plan(k, bp, forNpc == null ? null : forNpc.id);
            if (b == null) break;
            k.pay(bp.cost());
            planned.add(b);
            started++;
        }
        if (started == 0) return ActionResult.reject("no_site", "Não encontrei terreno livre e plano dentro do território para " + bp.displayName() + ". Expanda o território (CLAIM).");
        boolean hasBuilder = core.count(k.id, Profession.BUILDER) > 0;
        String msg = started + "x " + bp.displayName() + " planejada(s)" + (forNpc != null ? " para " + forNpc.name : "")
                + (started < amount ? " (só havia terreno para " + started + ")" : "") + ".";
        if (!hasBuilder) msg += " Atenção: não há construtores — use /k assign construtor 1.";
        if (deadline > 0) for (Building b : planned) msg += " " + core.construction().setDeadline(b, deadline);
        else if (hasBuilder && !planned.isEmpty()) msg += " ETA " + core.construction().formatEta(planned.get(0)) + ".";
        return ActionResult.ok(msg);
    }

    /** Cadeia já aprovada pelos validadores: cria e explica o plano (com os avisos). */
    private ActionResult chain(Kingdom k, ActionRequest r) {
        var spec = com.kingdomsai.core.work.ChainTemplates.spec(r.params(), core, k);
        var v = com.kingdomsai.core.work.ChainValidator.validate(core, k, spec);
        var c = core.work().start(k, v.chain(), r.param("order") != null ? r.param("order") : spec.name);
        StringBuilder sb = new StringBuilder("Cadeia #" + c.number + " «" + c.name + "» " + (c.repeat ? "(rotina contínua)" : "(tarefa única)") + ":");
        for (String line : v.plan()) sb.append("\n   ").append(line);
        for (String w : v.warnings()) sb.append("\n⚠ ").append(w);
        return ActionResult.ok(sb.toString());
    }

    private ActionResult recruit(Kingdom k, int amount) {
        List<Npc> civ = new ArrayList<>();
        for (Npc n : core.citizens(k.id)) if (!n.profession.isMilitary() && n.office == Office.NONE) civ.add(n);
        // Prioridade: camponeses, depois quem tem mais coragem.
        civ.sort(Comparator.comparingInt((Npc n) -> expendability(k, n) + (isLastOf(k, n) ? 100 : 0))
                .thenComparing(n -> -n.trait(Trait.COURAGE)));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < amount && i < civ.size(); i++) {
            Npc n = civ.get(i);
            Profession before = n.profession;
            n.profession = Profession.SOLDIER;
            n.remember(core.tick(), "Fui recrutado para o exército (antes era " + before.display.toLowerCase() + ").", 55, null, "exército");
            if (k.get(ResourceType.WEAPONS) >= 1) k.add(ResourceType.WEAPONS, -1);
            names.add(n.name);
        }
        k.add(ResourceType.GOLD, -15 * names.size());
        core.bus().publish(core.tick(), EventType.ARMY_RECRUITED, GameEvent.Severity.INFO, k.id, null,
                names.size() + " recrutado(s) em " + k.name + ": " + String.join(", ", names) + ".");
        return ActionResult.ok("Recrutados: " + String.join(", ", names) + ".");
    }

    private ActionResult release(Kingdom k, int amount, String professionName) {
        Profession to = professionName == null ? Profession.FARMER : Profession.parse(professionName);
        List<Npc> soldiers = new ArrayList<>();
        for (Npc n : core.citizens(k.id)) if (n.profession == Profession.SOLDIER) soldiers.add(n);
        soldiers.sort(Comparator.comparingInt(n -> n.fame));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < amount && i < soldiers.size(); i++) {
            Npc n = soldiers.get(i);
            n.profession = to;
            n.remember(core.tick(), "Fui liberado do exército para trabalhar como " + to.display.toLowerCase() + ".", 40, null, "exército");
            names.add(n.name);
        }
        core.bus().publish(core.tick(), EventType.ARMY_RELEASED, GameEvent.Severity.INFO, k.id, null,
                names.size() + " soldado(s) liberado(s) para " + to.display + ".");
        return ActionResult.ok(String.join(", ", names) + " agora trabalham como " + to.display.toLowerCase() + ".");
    }

    private ActionResult work(Kingdom k, ActionRequest r) {
        Profession to = Profession.parse(r.param("profession"));
        if (r.param("npc") != null) return setProfession(k, core.findNpc(k.id, r.param("npc")), to);
        int amount = r.intParam("amount", 1);
        Profession from = r.param("from") == null ? null : Profession.parse(r.param("from"));
        List<Npc> pool = new ArrayList<>();
        for (Npc n : core.citizens(k.id))
            if (n.profession != to && n.office == Office.NONE && (from == null ? !n.profession.isMilitary() : n.profession == from)) pool.add(n);
        if (pool.isEmpty())
            return ActionResult.reject("not_enough_people", "Ninguém disponível" + (from != null ? " entre " + from.display.toLowerCase() + "s" : "") + ".");
        // Quem sai primeiro: camponeses; quem fica por último: o último construtor/ferreiro do reino.
        pool.sort(Comparator.comparingInt((Npc n) -> expendability(k, n)).thenComparingInt(n -> n.fame));
        if (from == null) pool.removeIf(n -> isLastOf(k, n));
        if (pool.isEmpty()) return ActionResult.reject("not_enough_people", "Só restam pessoas em funções essenciais (último construtor/ferreiro).");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < amount && i < pool.size(); i++) {
            setProfession(k, pool.get(i), to);
            names.add(pool.get(i).name);
        }
        return ActionResult.ok(String.join(", ", names) + " agora trabalha(m) como " + to.display.toLowerCase() + ".");
    }

    private static final List<Profession> EXPENDABLE_ORDER = List.of(Profession.PEASANT, Profession.SCHOLAR, Profession.MERCHANT,
            Profession.LUMBERJACK, Profession.MINER, Profession.PRIEST, Profession.FARMER, Profession.GUARD, Profession.SOLDIER,
            Profession.BLACKSMITH, Profession.BUILDER);

    private int expendability(Kingdom k, Npc n) {
        return EXPENDABLE_ORDER.indexOf(n.profession);
    }

    /** Último construtor ou ferreiro: remover trava o reino. */
    private boolean isLastOf(Kingdom k, Npc n) {
        return (n.profession == Profession.BUILDER || n.profession == Profession.BLACKSMITH) && core.count(k.id, n.profession) <= 1;
    }

    private ActionResult setProfession(Kingdom k, Npc n, Profession to) {
        Profession before = n.profession;
        if (before == to) return ActionResult.ok(n.name + " já é " + to.display.toLowerCase() + ".");
        n.profession = to;
        n.remember(core.tick(), "Passei a trabalhar como " + to.display.toLowerCase() + " (antes: " + before.display.toLowerCase() + ").", 35, null, "trabalho");
        core.bus().publish(core.tick(), EventType.NPC_PROFESSION_CHANGED, GameEvent.Severity.INFO, k.id, n.id,
                n.name + ": " + before.display + " → " + to.display + ".");
        return ActionResult.ok(n.name + " agora é " + to.display.toLowerCase() + ".");
    }

    private ActionResult promote(Kingdom k, Npc n, Office office) {
        for (Npc other : core.citizens(k.id))
            if (other.office == office && office != Office.NONE && other != n) {
                other.office = Office.NONE;
                other.remember(core.tick(), "Perdi o cargo de " + office.display + " para " + n.name + ".", 80, n.id, "cargo", "humilhação");
                other.relationTo(n.id).adjust(-15, -5, 0, 30, -20);
                other.loyalty = Math.max(0, other.loyalty - 15);
            }
        n.office = office;
        n.level = n.level.atLeast(IntelligenceLevel.IMPORTANT);
        n.fame += 10;
        n.loyalty = Math.min(100, n.loyalty + 10);
        n.remember(core.tick(), "O rei me nomeou " + office.display + ".", 90, null, "cargo", "honra");
        core.bus().publish(core.tick(), EventType.NPC_PROMOTED, GameEvent.Severity.GOOD, k.id, n.id,
                n.name + " foi nomeado(a) " + office.display + ".");
        core.chronicle(n.name + " foi nomeado(a) " + office.display + " de " + k.name + ".");
        return ActionResult.ok(n.name + " agora é " + office.display + ".");
    }

    private ActionResult demote(Kingdom k, Npc n) {
        if (n.office == Office.NONE) return ActionResult.ok(n.name + " não tem cargo.");
        Office before = n.office;
        n.office = Office.NONE;
        n.loyalty = Math.max(0, n.loyalty - 20);
        n.remember(core.tick(), "O rei me tirou do cargo de " + before.display + ".", 85, null, "cargo", "humilhação");
        core.bus().publish(core.tick(), EventType.NPC_PROMOTED, GameEvent.Severity.WARN, k.id, n.id,
                n.name + " perdeu o cargo de " + before.display + ".");
        return ActionResult.ok(n.name + " deixou de ser " + before.display + ".");
    }

    private ActionResult tax(Kingdom k, String level) {
        String v = level.trim().toLowerCase();
        int before = k.laws.taxLevel;
        int lvl = switch (v) {
            case "up", "+1", "aumentar" -> before + 1;
            case "down", "-1", "baixar", "reduzir" -> before - 1;
            default -> Integer.parseInt(v);
        };
        k.laws.taxLevel = Text.clamp(lvl, 0, 4);
        if (k.laws.taxLevel > before) k.legitimacy = Text.clamp(k.legitimacy - 3, 0, 100);
        String[] names = {"isento", "baixo", "normal", "alto", "extorsivo"};
        core.bus().publish(core.tick(), EventType.LAW_CHANGED, GameEvent.Severity.INFO, k.id, null,
                "Impostos: " + names[before] + " → " + names[k.laws.taxLevel] + ".");
        return ActionResult.ok("Impostos agora: " + names[k.laws.taxLevel] + ".");
    }

    private ActionResult law(Kingdom k, String law, String value) {
        boolean on = value != null && (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("sim") || value.equals("1"));
        String l = law.toLowerCase();
        String msg;
        if (l.startsWith("consc") || l.startsWith("serv")) {
            k.laws.conscription = on;
            msg = "Serviço militar obrigatório " + (on ? "instituído" : "abolido") + ".";
        } else {
            k.laws.openMigration = on;
            msg = "Imigração " + (on ? "aberta" : "fechada") + ".";
        }
        core.bus().publish(core.tick(), EventType.LAW_CHANGED, GameEvent.Severity.INFO, k.id, null, msg);
        return ActionResult.ok(msg);
    }

    private ActionResult claim(Kingdom k, int amount) {
        int done = 0;
        for (int[] c : core.state().territory.claimableFrontier(k.id, k.center)) {
            if (done >= amount) break;
            core.state().territory.claim(c[0], c[1], k.id, 40, core.tick());
            done++;
        }
        k.add(ResourceType.GOLD, -40 * done);
        core.bus().publish(core.tick(), EventType.TERRITORY_CLAIMED, GameEvent.Severity.INFO, k.id, null,
                k.name + " reivindicou " + done + " nova(s) região(ões).");
        return ActionResult.ok(done + " célula(s) reivindicada(s). Território: " + core.state().territory.countOwned(k.id) + " células ("
                + String.format(Locale.ROOT, "%.2f", core.state().territory.areaKm2(k.id)) + " km²).");
    }

    private ActionResult negotiate(Kingdom k, ActionRequest r) {
        Kingdom t = core.findKingdom(r.param("target"));
        if (r.param("treaty") != null) {
            Diplomacy.TreatyType type = Diplomacy.TreatyType.parse(r.param("treaty"));
            String why = core.diplomacy().proposeTreaty(k, t, type);
            return why == null ? ActionResult.ok(t.name + " aceitou: " + type.display + ".") : ActionResult.reject("refused", why);
        }
        ResourceType give = ResourceType.parse(r.param("give"));
        ResourceType want = ResourceType.parse(r.param("want"));
        int ga = r.intParam("give_amount", 0), wa = r.intParam("want_amount", 0);
        if (ga <= 0 || wa <= 0) return ActionResult.reject("invalid_param", "Quantidades devem ser positivas.");
        String why = core.diplomacy().trade(k, t, give, ga, want, wa);
        return why == null ? ActionResult.ok("Troca aceita por " + t.name + ".") : ActionResult.reject("refused", why);
    }

    private ActionResult give(Kingdom k, ActionRequest r) {
        Kingdom t = core.findKingdom(r.param("target"));
        ResourceType res = ResourceType.parse(r.param("resource"));
        int amount = r.intParam("amount", 0);
        core.diplomacy().gift(k, t, res, amount);
        return ActionResult.ok("Presente entregue a " + t.name + ".");
    }
}
