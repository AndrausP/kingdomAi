package com.kingdomsai.core.llm;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.IntelligenceLevel;
import com.kingdomsai.core.npc.Npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Conversas e ordens em linguagem natural.
 * Fluxo: ContextBuilder → LlmGateway (ou regras) → Plan → ActionSystem (validação) → resposta.
 */
public final class DialogueService {
    public record Reply(String speaker, String text, List<String> actionLines, String provider, boolean fallback, String note) {}

    private final KingdomsCore core;

    public DialogueService(KingdomsCore core) {
        this.core = core;
    }

    public void talk(UUID playerId, String playerName, Npc npc, String text, Consumer<Reply> callback) {
        Kingdom pk = core.kingdomOfPlayer(playerId);
        boolean own = pk != null && pk.id.equals(npc.kingdomId);
        npc.level = npc.level.atLeast(IntelligenceLevel.CONTEXTUAL);
        npc.remember(core.tick(), (own ? "O rei" : "O rei estrangeiro " + playerName) + " falou comigo: \"" + Text.truncate(text, 80) + "\"",
                own ? 40 : 55, playerId, "rei", "conversa");
        npc.relationTo(playerId).adjust(1, 1, 0, 0, 1);
        core.bus().publish(core.tick(), EventType.NPC_TALKED, GameEvent.Severity.INFO, npc.kingdomId, npc.id,
                playerName + " conversou com " + npc.name + ".", Map.of("player", playerId.toString()));
        LlmRequest req = core.contextBuilder().npcDialogue(pk, npc, text);
        npc.lastLlmCall = "npc_dialogue @" + core.tick();
        UUID kingdomForActions = own ? pk.id : null;
        core.llm().submit(req).thenAcceptAsync(res -> {
            List<String> lines = kingdomForActions == null ? List.of() : execute(kingdomForActions, playerId, res.plan(), npc, text);
            npc.level = npc.level.atLeast(IntelligenceLevel.CONTEXTUAL);
            npc.lastDecision = res.plan().toJson();
            callback.accept(new Reply(npc.name, res.plan().reply(), lines, res.provider(), res.fallback(), res.note()));
        }, core.mainThread()).exceptionally(err -> {
            core.mainThread().execute(() -> callback.accept(new Reply(npc.name, "...", List.of("Erro: " + err.getMessage()), "erro", true, "")));
            return null;
        });
    }

    public void order(UUID playerId, String text, Consumer<Reply> callback) {
        Kingdom k = core.kingdomOfPlayer(playerId);
        if (k == null) {
            callback.accept(new Reply("Sistema", "Você ainda não governa um reino. Use /kingdom found <nome>.", List.of(), "", false, ""));
            return;
        }
        Npc adv = core.advisor().advisorNpc(k);
        LlmRequest req = core.contextBuilder().councilOrder(k, text);
        core.llm().submit(req).thenAcceptAsync(res -> {
            List<String> lines = execute(k.id, playerId, res.plan(), null, text);
            if (adv != null) adv.lastDecision = res.plan().toJson();
            callback.accept(new Reply(adv != null ? adv.name + " (Conselheiro)" : "Conselho", res.plan().reply(), lines,
                    res.provider(), res.fallback(), res.note()));
        }, core.mainThread()).exceptionally(err -> {
            core.mainThread().execute(() -> callback.accept(new Reply("Conselho", "...", List.of("Erro: " + err.getMessage()), "erro", true, "")));
            return null;
        });
    }

    /**
     * @param listener NPC com quem o rei está falando: ordens de rotina sem "npc" são para ele
     *                 ("se eu falar para ele, ele adota a postura").
     */
    private List<String> execute(UUID kingdomId, UUID playerId, Plan plan, Npc listener, String orderText) {
        List<String> lines = new ArrayList<>();
        for (Plan.PlannedAction a : plan.actions()) {
            if (a.type() == null) {
                lines.add("✗ " + a.rawType() + " — ação desconhecida (rejeitada pelo Schema Validator)");
                continue;
            }
            Map<String, String> params = new java.util.LinkedHashMap<>(a.params());
            if (a.type() == com.kingdomsai.core.action.ActionType.CHAIN || a.type() == com.kingdomsai.core.action.ActionType.STOP_CHAIN
                    || a.type() == com.kingdomsai.core.action.ActionType.JOB || a.type() == com.kingdomsai.core.action.ActionType.CANCEL_JOB) {
                if (listener != null && listener.office != com.kingdomsai.core.npc.Office.ADVISOR && params.get("npc") == null && params.get("chain") == null)
                    params.put("npc", listener.name);
                params.putIfAbsent("order", Text.truncate(orderText, 120));
            }
            var t = a.type();
            if ((t == com.kingdomsai.core.action.ActionType.SUMMON || t == com.kingdomsai.core.action.ActionType.FOLLOW
                    || t == com.kingdomsai.core.action.ActionType.DISMISS) && listener != null && params.get("npc") == null)
                params.put("npc", listener.name);
            ActionResult r = core.actions().execute(new ActionRequest(kingdomId, playerId, ActionRequest.ActorKind.PLAYER,
                    a.type(), params, ActionRequest.Source.LLM));
            lines.addAll(resultLines(a.type().name(), r));
        }
        return lines;
    }

    /** "✓ TIPO: mensagem" — mensagens de várias linhas (planos de cadeia) viram várias linhas do chat. */
    public static List<String> resultLines(String type, ActionResult r) {
        List<String> out = new ArrayList<>();
        String[] parts = (r.message() == null ? "" : r.message()).split("\n");
        out.add((r.ok() ? "✓ " : "✗ ") + type + (r.ok() ? ": " + parts[0] : " [" + r.code() + "]: " + parts[0]));
        for (int i = 1; i < parts.length; i++) out.add(parts[i]);
        return out;
    }
}
