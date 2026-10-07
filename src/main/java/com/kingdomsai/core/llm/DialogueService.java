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
            List<String> lines = kingdomForActions == null ? List.of() : execute(kingdomForActions, playerId, res.plan());
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
            List<String> lines = execute(k.id, playerId, res.plan());
            if (adv != null) adv.lastDecision = res.plan().toJson();
            callback.accept(new Reply(adv != null ? adv.name + " (Conselheiro)" : "Conselho", res.plan().reply(), lines,
                    res.provider(), res.fallback(), res.note()));
        }, core.mainThread()).exceptionally(err -> {
            core.mainThread().execute(() -> callback.accept(new Reply("Conselho", "...", List.of("Erro: " + err.getMessage()), "erro", true, "")));
            return null;
        });
    }

    private List<String> execute(UUID kingdomId, UUID playerId, Plan plan) {
        List<String> lines = new ArrayList<>();
        for (Plan.PlannedAction a : plan.actions()) {
            if (a.type() == null) {
                lines.add("✗ " + a.rawType() + " — ação desconhecida (rejeitada pelo Schema Validator)");
                continue;
            }
            ActionResult r = core.actions().execute(new ActionRequest(kingdomId, playerId, ActionRequest.ActorKind.PLAYER,
                    a.type(), a.params(), ActionRequest.Source.LLM));
            lines.add((r.ok() ? "✓ " : "✗ ") + a.type() + (r.ok() ? ": " + r.message() : " [" + r.code() + "]: " + r.message()));
        }
        return lines;
    }
}
