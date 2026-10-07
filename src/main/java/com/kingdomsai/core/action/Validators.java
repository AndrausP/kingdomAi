package com.kingdomsai.core.action;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Office;
import com.kingdomsai.core.npc.Profession;

import java.util.EnumMap;
import java.util.Map;

/**
 * Pipeline: Schema → Permission → World → Resource. Toda decisão (jogador, LLM, IA de reino)
 * passa por aqui antes de virar execução. Retornam null quando aprovam.
 */
public final class Validators {
    private Validators() {}

    public interface Validator {
        ActionResult validate(ActionRequest r, KingdomsCore core);
    }

    /** Ação existe nesta fase? Parâmetros obrigatórios presentes e bem formados? */
    public static final Validator SCHEMA = (r, core) -> {
        if (r.type() == null) return ActionResult.reject("unknown_action", "Ação desconhecida.");
        if (!r.type().implemented)
            return ActionResult.reject("not_available_in_this_phase", r.type() + " ainda não existe nesta versão do mod.");
        for (String p : r.type().requiredParams)
            if (r.param(p) == null || r.param(p).isBlank())
                return ActionResult.reject("missing_param", r.type() + " exige o parâmetro '" + p + "' (" + r.type().paramHelp + ").");
        int amount = r.intParam("amount", 1);
        if (r.type() != ActionType.GIVE && (amount < 1 || amount > 20)) return ActionResult.reject("invalid_param", "amount deve estar entre 1 e 20.");
        switch (r.type()) {
            case BUILD -> {
                if (r.param("deadline") != null && com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline")) < 0)
                    return ActionResult.reject("invalid_param", "Prazo inválido: " + r.param("deadline") + " (use 30s, 5m, 2h, 1d ou amanha).");
                if (com.kingdomsai.core.construction.VillageWall.isWallRequest(r.param("blueprint"))) {
                    int h = r.intParam("height", 4);
                    if (h < 3 || h > 6) return ActionResult.reject("invalid_param", "A muralha tem de 3 a 6 blocos de altura.");
                    if (amount != 1) return ActionResult.reject("invalid_param", "A vila só tem uma muralha.");
                    return null;
                }
                if (isCustom(r)) {
                    try {
                        com.kingdomsai.core.construction.ParametricBlueprints.spec(r.params());
                    } catch (IllegalArgumentException e) {
                        return ActionResult.reject("invalid_param", e.getMessage());
                    }
                    if (amount > 5) return ActionResult.reject("invalid_param", "No máximo 5 construções por ordem.");
                    return null;
                }
                if (BlueprintLibrary.find(r.param("blueprint")) == null)
                    return ActionResult.reject("invalid_param", "Planta desconhecida: " + r.param("blueprint")
                            + ". Disponíveis: " + String.join(", ", BlueprintLibrary.all().stream().map(Blueprint::id).toList()) + ".");
                if (amount > 5) return ActionResult.reject("invalid_param", "No máximo 5 construções por ordem.");
            }
            case DEADLINE -> {
                if (com.kingdomsai.core.construction.ConstructionSystem.parseDuration(r.param("deadline")) < 0)
                    return ActionResult.reject("invalid_param", "Prazo inválido: " + r.param("deadline") + " (use 30s, 5m, 2h, 1d ou amanha).");
            }
            case WORK -> {
                if (Profession.parse(r.param("profession")) == null)
                    return ActionResult.reject("invalid_param", "Profissão desconhecida: " + r.param("profession") + ".");
                if (r.param("from") != null && Profession.parse(r.param("from")) == null)
                    return ActionResult.reject("invalid_param", "Profissão de origem desconhecida: " + r.param("from") + ".");
            }
            case RELEASE -> {
                if (r.param("profession") != null && Profession.parse(r.param("profession")) == null)
                    return ActionResult.reject("invalid_param", "Profissão desconhecida: " + r.param("profession") + ".");
            }
            case PROMOTE -> {
                Office o = Office.parse(r.param("office"));
                if (o == null) return ActionResult.reject("invalid_param", "Cargo desconhecido: " + r.param("office") + ".");
                if (o == Office.KING) return ActionResult.reject("invalid_param", "Só pode haver um rei.");
            }
            case TAX -> {
                String v = r.param("level").trim().toLowerCase();
                if (!v.matches("[0-4]|up|down|\\+1|-1|aumentar|baixar|reduzir"))
                    return ActionResult.reject("invalid_param", "level deve ser 0-4, up ou down.");
            }
            case LAW -> {
                String law = r.param("law").toLowerCase();
                if (!law.startsWith("consc") && !law.startsWith("serv") && !law.startsWith("migr"))
                    return ActionResult.reject("invalid_param", "Leis disponíveis: conscription, migration.");
            }
            case GIVE -> {
                if (ResourceType.parse(r.param("resource")) == null)
                    return ActionResult.reject("invalid_param", "Recurso desconhecido: " + r.param("resource") + ".");
                if (r.intParam("amount", 0) < 1 || r.intParam("amount", 0) > 5000)
                    return ActionResult.reject("invalid_param", "Quantidade inválida.");
            }
            case JOB -> {
                try {
                    com.kingdomsai.core.skill.JobPlanner.specs(r.params());
                } catch (IllegalArgumentException e) {
                    return ActionResult.reject("invalid_param", e.getMessage());
                }
            }
            case CHAIN -> {
                try {
                    com.kingdomsai.core.work.ChainTemplates.spec(r.params(), core, core.kingdom(r.kingdomId()));
                } catch (IllegalArgumentException e) {
                    return ActionResult.reject("invalid_param", e.getMessage());
                }
            }
            case NEGOTIATE -> {
                if (r.param("treaty") == null && (ResourceType.parse(r.param("give")) == null || ResourceType.parse(r.param("want")) == null))
                    return ActionResult.reject("missing_param", "NEGOTIATE exige treaty=<tipo> ou give/give_amount/want/want_amount.");
            }
            default -> {
            }
        }
        return null;
    };

    public static boolean isCustom(ActionRequest r) {
        String b = r.param("blueprint");
        if (b == null) return false;
        String n = com.kingdomsai.core.common.Text.norm(b);
        return n.equals("custom") || n.equals("projeto") || n.equals("personalizado") || n.equals("parametric");
    }

    /** O ator tem autoridade? Um camponês não declara guerra. */
    public static final Validator PERMISSION = (r, core) -> {
        Kingdom k = core.kingdom(r.kingdomId());
        if (k == null) return ActionResult.reject("no_kingdom", "Você não governa um reino.");
        switch (r.actorKind()) {
            case PLAYER -> {
                if (r.actorId() == null || !r.actorId().equals(k.rulerPlayer))
                    return ActionResult.reject("permission_denied", "Só o governante de " + k.name + " pode ordenar isso.");
            }
            case DIRECTOR -> {
                if (k.isPlayerKingdom())
                    return ActionResult.reject("permission_denied", "A IA não governa o reino do jogador.");
            }
            case NPC -> {
                Npc n = core.npc(r.actorId());
                if (n == null || !n.alive || !k.id.equals(n.kingdomId))
                    return ActionResult.reject("permission_denied", "NPC inválido para este reino.");
                if (!n.office.allows(r.type().permission))
                    return ActionResult.reject("permission_denied", n.displayName() + " não tem autoridade para " + r.type() + ".");
            }
        }
        return null;
    };

    /** O mundo permite? Alvos existem, limites respeitados. */
    public static final Validator WORLD = (r, core) -> {
        Kingdom k = core.kingdom(r.kingdomId());
        switch (r.type()) {
            case BUILD -> {
                if (!core.config().constructionEnabled) return ActionResult.reject("disabled", "Construção desativada na config.");
                if (core.construction().projects(k.id).size() + r.intParam("amount", 1) > 8)
                    return ActionResult.reject("too_many_projects", "Já existem obras demais em andamento (máx. 8).");
                if (r.param("for") != null && core.findNpc(k.id, r.param("for")) == null)
                    return ActionResult.reject("not_found", "Não encontrei " + r.param("for") + " no reino.");
                if (com.kingdomsai.core.construction.VillageWall.isWallRequest(r.param("blueprint"))) {
                    var existing = com.kingdomsai.core.construction.VillageWall.existing(core, k);
                    if (existing != null)
                        return ActionResult.reject("already_exists", "A vila já tem " + existing.blueprint().displayName().toLowerCase()
                                + (existing.isComplete() ? "." : " em obra (" + (int) existing.percent() + "%)."));
                }
            }
            case DEADLINE, CANCEL_BUILD -> {
                if (ActionSystem.findProject(core, k, r.param("building")) == null)
                    return ActionResult.reject("not_found", "Não encontrei a obra " + (r.param("building") == null ? "" : r.param("building"))
                            + ". Veja /k projects (use o número da obra).");
            }
            case PROMOTE, DEMOTE, PATROL, GUARD -> {
                Npc n = core.findNpc(k.id, r.param("npc"));
                if (n == null) return ActionResult.reject("not_found", "Não encontrei " + r.param("npc") + " em " + k.name + ".");
                if (n.office == Office.KING) return ActionResult.reject("invalid_target", "Não é possível alterar o rei.");
            }
            case WORK -> {
                if (r.param("npc") != null) {
                    Npc n = core.findNpc(k.id, r.param("npc"));
                    if (n == null) return ActionResult.reject("not_found", "Não encontrei " + r.param("npc") + " em " + k.name + ".");
                    if (n.office == Office.KING) return ActionResult.reject("invalid_target", "O rei não muda de profissão.");
                }
            }
            case RECRUIT -> {
                int candidates = 0;
                for (Npc n : core.citizens(k.id)) if (!n.profession.isMilitary() && n.office == Office.NONE) candidates++;
                if (candidates < r.intParam("amount", 1))
                    return ActionResult.reject("not_enough_people", "Só há " + candidates + " civis disponíveis para recrutar.");
            }
            case RELEASE -> {
                if (core.count(k.id, Profession.SOLDIER) < r.intParam("amount", 1))
                    return ActionResult.reject("not_enough_people", "Só há " + core.count(k.id, Profession.SOLDIER) + " soldados.");
            }
            case CHAIN -> {
                var v = com.kingdomsai.core.work.ChainValidator.validate(core, k,
                        com.kingdomsai.core.work.ChainTemplates.spec(r.params(), core, k));
                if (!v.ok()) return ActionResult.reject("chain_invalid", "A cadeia não fecha: " + String.join(" ", v.errors()));
            }
            case SUMMON, FOLLOW, DISMISS -> {
                Npc n = core.findNpc(k.id, r.param("npc"));
                if (n == null) return ActionResult.reject("not_found", "Não encontrei " + r.param("npc") + " em " + k.name + ".");
                if (n.office == Office.KING) return ActionResult.reject("invalid_target", "O rei não é chamado: ele chama.");
                if (r.type() == ActionType.DISMISS) {
                    if (!core.scheduler().isSummoned(n)) return ActionResult.reject("invalid_target", n.name + " não foi chamado.");
                    break;
                }
                com.kingdomsai.core.common.Pos to = summonTarget(core, r);
                if (to == null) return ActionResult.reject("unknown_position", "Não sei onde Vossa Majestade está agora.");
                if (n.pos != null && n.pos.distXZ(to) > com.kingdomsai.core.ai.NpcScheduler.MAX_SUMMON_DISTANCE)
                    return ActionResult.reject("too_far", n.name + " está a " + (int) n.pos.distXZ(to) + " blocos — longe demais para atender ao chamado (máx. "
                            + com.kingdomsai.core.ai.NpcScheduler.MAX_SUMMON_DISTANCE + ").");
            }
            case JOB -> {
                var plan = com.kingdomsai.core.skill.JobPlanner.plan(core, k, r.actorId(), r.params());
                if (!plan.ok()) return ActionResult.reject("job_invalid", String.join(" ", plan.errors()));
            }
            case CANCEL_JOB -> {
                var j = core.skills().find(k.id, r.param("job") != null ? r.param("job") : r.param("npc"));
                if (j == null || !j.status.live()) return ActionResult.reject("not_found", "Não há ordem em andamento" + (r.param("npc") != null ? " para " + r.param("npc") : "") + ".");
            }
            case STOP_CHAIN -> {
                if (findChain(core, k, r) == null)
                    return ActionResult.reject("not_found", "Não encontrei essa cadeia" + (r.param("npc") != null ? " para " + r.param("npc") : "") + ". Veja /k chains.");
            }
            case CLAIM -> {
                if (core.state().territory.claimableFrontier(k.id, k.center).isEmpty())
                    return ActionResult.reject("no_frontier", "Não há células livres na fronteira.");
            }
            case NEGOTIATE, GIVE, DECLARE_WAR, MAKE_PEACE -> {
                Kingdom t = core.findKingdom(r.param("target"));
                if (t == null) return ActionResult.reject("not_found", "Reino desconhecido: " + r.param("target") + ".");
                if (t.id.equals(k.id)) return ActionResult.reject("invalid_target", "Não dá para negociar consigo mesmo.");
                if (r.type() == ActionType.DECLARE_WAR && core.diplomacy().link(k.id, t.id).state == com.kingdomsai.core.diplomacy.Diplomacy.State.WAR)
                    return ActionResult.reject("invalid_target", "Já estamos em guerra com " + t.name + ".");
                if (r.type() == ActionType.NEGOTIATE && r.param("treaty") != null
                        && com.kingdomsai.core.diplomacy.Diplomacy.TreatyType.parse(r.param("treaty")) == null)
                    return ActionResult.reject("invalid_param", "Tratado desconhecido: " + r.param("treaty") + ".");
            }
            default -> {
            }
        }
        return null;
    };

    /** Para onde o NPC vai: x/z explícitos ou a última posição conhecida do rei. */
    public static com.kingdomsai.core.common.Pos summonTarget(KingdomsCore core, ActionRequest r) {
        if (r.param("x") != null && r.param("z") != null) {
            try {
                int x = Integer.parseInt(r.param("x").trim()), z = Integer.parseInt(r.param("z").trim());
                int y = r.param("y") == null ? core.world().surfaceY(x, z) : Integer.parseInt(r.param("y").trim());
                return new com.kingdomsai.core.common.Pos(x, y == Integer.MIN_VALUE ? 64 : y, z);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return r.actorKind() == ActionRequest.ActorKind.PLAYER ? core.playerPos(r.actorId()) : null;
    }

    public static com.kingdomsai.core.work.WorkChain findChain(KingdomsCore core, Kingdom k, ActionRequest r) {
        String ref = r.param("chain") != null ? r.param("chain") : r.param("npc");
        var c = core.work().find(k.id, ref);
        return c != null && c.live() ? c : null;
    }

    /** Há recursos? A IA nunca inventa recursos. */
    public static final Validator RESOURCES = (r, core) -> {
        Kingdom k = core.kingdom(r.kingdomId());
        Map<ResourceType, Integer> cost = cost(r, core);
        if (!k.has(cost)) {
            StringBuilder sb = new StringBuilder();
            for (var e : cost.entrySet())
                if (k.get(e.getKey()) < e.getValue())
                    sb.append(e.getKey().display).append(' ').append((int) k.get(e.getKey())).append('/').append(e.getValue()).append(", ");
            return ActionResult.reject("insufficient_resources", "Recursos insuficientes: " + sb.substring(0, sb.length() - 2) + ".");
        }
        return null;
    };

    public static Map<ResourceType, Integer> cost(ActionRequest r, KingdomsCore core) {
        Map<ResourceType, Integer> c = new EnumMap<>(ResourceType.class);
        int amount = r.intParam("amount", 1);
        switch (r.type()) {
            case BUILD -> {
                if (com.kingdomsai.core.construction.VillageWall.isWallRequest(r.param("blueprint"))) {
                    Blueprint wall = com.kingdomsai.core.construction.VillageWall.generate(core, core.kingdom(r.kingdomId()), r.intParam("height", 4));
                    c.putAll(wall.cost());
                    break;
                }
                Blueprint bp = isCustom(r)
                        ? com.kingdomsai.core.construction.ParametricBlueprints.generate(com.kingdomsai.core.construction.ParametricBlueprints.spec(r.params()))
                        : BlueprintLibrary.find(r.param("blueprint"));
                for (var e : bp.cost().entrySet()) c.put(e.getKey(), e.getValue() * amount);
            }
            case RECRUIT -> c.put(ResourceType.GOLD, 15 * amount);
            case CLAIM -> c.put(ResourceType.GOLD, 40 * amount);
            case GIVE -> c.put(ResourceType.parse(r.param("resource")), r.intParam("amount", 0));
            case NEGOTIATE -> {
                ResourceType give = ResourceType.parse(r.param("give"));
                if (give != null && r.param("treaty") == null) c.put(give, r.intParam("give_amount", 0));
            }
            default -> {
            }
        }
        return c;
    }
}
