package com.kingdomsai.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.territory.TerritoryMap;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** Monta o JSON que o HUD do Manager desenha (o cliente não tem acesso ao Core). */
public final class ManagerSnapshot {
    private ManagerSnapshot() {}

    public static String build(ServerRuntime rt, ServerPlayer player, List<String> feedback) {
        KingdomsCore core = rt.core();
        JsonObject root = new JsonObject();
        root.addProperty("managerOn", ManagerMode.isOn(player));
        // configuração (aba ⚙)
        JsonObject cfg = new JsonObject();
        for (String key : KingdomsConfig.KEYS.keySet()) cfg.addProperty(key, KingdomsConfig.display(key));
        root.add("config", cfg);
        root.addProperty("canConfigure", player.server.isSingleplayerOwner(player.getGameProfile()) || player.hasPermissions(2));
        JsonArray models = new JsonArray();
        if (rt.extension() != null) rt.extension().lastModels().forEach(models::add);
        root.add("models", models);
        root.addProperty("lastTest", rt.extension() == null ? "" : rt.extension().lastTest());
        com.kingdomsai.core.npc.Npc sel = core.npc(rt.cli().selected(player.getUUID()));
        root.addProperty("selected", sel == null ? "" : sel.name);
        Kingdom k = core.kingdomOfPlayer(player.getUUID());
        root.addProperty("hasKingdom", k != null);
        root.addProperty("px", player.getBlockX());
        root.addProperty("pz", player.getBlockZ());
        root.addProperty("llm", core.llm().status());
        JsonArray fb = new JsonArray();
        if (feedback != null) feedback.forEach(fb::add);
        root.add("feedback", fb);
        if (k == null) return root.toString();

        Map<ResourceType, Double> delta = core.economy().projected(k);
        JsonObject kj = new JsonObject();
        kj.addProperty("name", k.name);
        kj.addProperty("day", core.day());
        kj.addProperty("pop", core.population(k.id));
        kj.addProperty("cap", core.housingCapacity(k.id));
        kj.addProperty("military", core.military(k.id));
        kj.addProperty("area", core.state().territory.areaKm2(k.id));
        kj.addProperty("cells", core.state().territory.countOwned(k.id));
        kj.addProperty("stability", k.stability);
        kj.addProperty("morale", k.morale);
        kj.addProperty("legitimacy", k.legitimacy);
        kj.addProperty("tax", k.laws.taxLevel);
        kj.addProperty("conscription", k.laws.conscription);
        kj.addProperty("migration", k.laws.openMigration);
        kj.addProperty("honor", k.honor);
        kj.addProperty("color", k.color);
        kj.addProperty("cx", k.center.x());
        kj.addProperty("cz", k.center.z());
        kj.addProperty("advice", core.advisor().explain(k));
        root.add("kingdom", kj);

        JsonArray res = new JsonArray();
        for (ResourceType r : ResourceType.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", r.display);
            o.addProperty("value", k.get(r));
            o.addProperty("delta", delta.getOrDefault(r, 0.0));
            res.add(o);
        }
        root.add("res", res);

        JsonArray ev = new JsonArray();
        for (GameEvent e : core.bus().log().recent(14, e -> k.id.equals(e.kingdomId()) && e.type() != com.kingdomsai.core.event.EventType.AI_DECISION)) {
            JsonObject o = new JsonObject();
            o.addProperty("icon", e.icon());
            o.addProperty("sev", e.severity().name());
            o.addProperty("msg", e.message());
            ev.add(o);
        }
        root.add("events", ev);

        List<Kingdom> ks = new ArrayList<>(core.state().kingdoms.values());
        Map<UUID, Integer> kIndex = new HashMap<>();
        JsonArray kingdoms = new JsonArray();
        for (int i = 0; i < ks.size(); i++) {
            Kingdom o = ks.get(i);
            kIndex.put(o.id, i);
            JsonObject j = new JsonObject();
            j.addProperty("name", o.name);
            j.addProperty("color", o.color);
            j.addProperty("me", o == k);
            j.addProperty("cx", o.center.x());
            j.addProperty("cz", o.center.z());
            j.addProperty("pop", core.population(o.id));
            j.addProperty("mil", core.military(o.id));
            j.addProperty("summary", o.personality.summary());
            if (o != k) {
                Diplomacy.Attitude att = core.diplomacy().attitude(o.id, k.id);
                j.addProperty("state", core.diplomacy().link(k.id, o.id).state.display);
                j.addProperty("label", att.label());
                j.addProperty("trust", att.trust);
                j.addProperty("fear", att.fear);
                j.addProperty("hostility", att.hostility);
                StringBuilder tr = new StringBuilder();
                for (Diplomacy.Treaty t : core.diplomacy().treaties(k.id, o.id)) tr.append(t.type.display).append("; ");
                j.addProperty("treaties", tr.toString());
            }
            kingdoms.add(j);
        }
        root.add("kingdoms", kingdoms);

        JsonArray npcs = new JsonArray();
        int px = player.getBlockX(), pz = player.getBlockZ();
        List<Npc> all = core.allAlive();
        all.sort(Comparator.comparing((Npc n) -> !k.id.equals(n.kingdomId)).thenComparing(n -> n.name));
        int count = 0;
        for (Npc n : all) {
            if (n.pos == null) continue;
            boolean mine = k.id.equals(n.kingdomId);
            if (!mine && Math.abs(n.pos.x() - px) > 400 && Math.abs(n.pos.z() - pz) > 400) continue;
            if (count++ > 220) break;
            JsonObject o = new JsonObject();
            o.addProperty("name", n.name);
            o.addProperty("title", n.title());
            o.addProperty("prof", n.profession.name());
            o.addProperty("profName", n.profession.display);
            o.addProperty("office", n.office.display);
            o.addProperty("level", n.level.name());
            o.addProperty("x", n.pos.x());
            o.addProperty("z", n.pos.z());
            o.addProperty("act", n.activity.display);
            o.addProperty("task", n.currentTask);
            o.addProperty("loyalty", n.loyalty);
            o.addProperty("fame", n.fame);
            o.addProperty("mine", mine);
            o.addProperty("k", kIndex.getOrDefault(n.kingdomId, -1));
            o.addProperty("summary", n.personalitySummary());
            npcs.add(o);
        }
        root.add("npcs", npcs);

        JsonArray projects = new JsonArray();
        int idx = 1;
        for (Building b : core.construction().projects(k.id)) {
            JsonObject o = new JsonObject();
            o.addProperty("n", idx++);
            o.addProperty("name", b.blueprint().displayName());
            o.addProperty("pct", b.percent());
            o.addProperty("eta", core.construction().formatEta(b));
            o.addProperty("builders", core.construction().activeBuilders(b).size());
            o.addProperty("need", core.construction().neededBuilders(b));
            o.addProperty("deadline", b.deadlineTick <= 0 ? "" : b.deadlineTick < core.tick() ? "ATRASADA"
                    : com.kingdomsai.core.construction.ConstructionSystem.formatDuration((b.deadlineTick - core.tick()) / 20.0));
            o.addProperty("late", b.deadlineTick > 0 && (b.deadlineTick < core.tick() || b.atRiskWarned));
            o.addProperty("x", b.origin.x());
            o.addProperty("z", b.origin.z());
            projects.add(o);
        }
        root.add("projects", projects);

        // rotinas / cadeias de trabalho vivas (ativas ou quebradas esperando retomada)
        JsonArray chains = new JsonArray();
        for (var c : core.work().chains(k.id)) {
            if (!c.live()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("n", c.number);
            o.addProperty("name", c.name);
            o.addProperty("broken", c.status == com.kingdomsai.core.work.WorkChain.Status.BROKEN);
            o.addProperty("reason", c.brokenReason);
            o.addProperty("cycles", c.cycles);
            JsonArray roles = new JsonArray();
            for (var r : c.roles.values()) {
                var n = core.npc(r.npcId);
                var s = c.current(r);
                if (n == null || s == null) continue;
                roles.add(n.name + " · " + r.state.display + (r.status.isBlank() || r.state == com.kingdomsai.core.work.WorkChain.DutyState.WORKING
                        ? ": " + s.describe() : ": " + r.status));
            }
            o.add("roles", roles);
            chains.add(o);
        }
        // ordens com as mãos em andamento aparecem no mesmo painel
        for (var j : core.skills().jobs(k.id)) {
            if (!j.status.live()) continue;
            var n = core.npc(j.npcId);
            var t = j.current();
            JsonObject o = new JsonObject();
            o.addProperty("n", j.number);
            o.addProperty("name", "⚒ " + j.name);
            o.addProperty("broken", j.status == com.kingdomsai.core.skill.PhysicalJob.Status.WAITING);
            o.addProperty("reason", j.reason);
            o.addProperty("cycles", 0);
            JsonArray roles = new JsonArray();
            if (n != null && t != null)
                roles.add(n.name + " · " + j.status.display + ": " + t.label + (t.total > 1 ? " (" + t.done + "/" + t.total + ")" : ""));
            o.add("roles", roles);
            chains.add(o);
        }
        root.add("chains", chains);

        JsonArray bs = new JsonArray();
        for (Building b : core.state().buildings.values()) {
            if (b.status == Building.Status.ABANDONED) continue;
            Blueprint bp = b.blueprint();
            if (bp == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("y", b.origin.y());
            o.addProperty("h", bp.sizeY());
            o.addProperty("eta", b.isComplete() ? "" : core.construction().formatEta(b));
            o.addProperty("name", bp.displayName());
            o.addProperty("x", b.origin.x());
            o.addProperty("z", b.origin.z());
            o.addProperty("w", bp.sizeX());
            o.addProperty("d", bp.sizeZ());
            o.addProperty("done", b.isComplete());
            o.addProperty("pct", b.percent());
            o.addProperty("k", kIndex.getOrDefault(b.kingdomId, -1));
            bs.add(o);
        }
        root.add("buildings", bs);

        JsonArray cells = new JsonArray();
        TerritoryMap t = core.state().territory;
        root.addProperty("cellSize", t.cellSize);
        for (TerritoryMap.Cell c : t.cells.values()) {
            if (c.owner == null) continue;
            JsonArray a = new JsonArray();
            a.add(c.cx);
            a.add(c.cz);
            a.add(kIndex.getOrDefault(c.owner, -1));
            cells.add(a);
        }
        root.add("cells", cells);

        JsonArray bps = new JsonArray();
        for (Blueprint b : BlueprintLibrary.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", b.id());
            o.addProperty("name", b.displayName());
            StringBuilder c = new StringBuilder();
            b.cost().forEach((r, v) -> c.append(v).append(' ').append(r.display.toLowerCase()).append(' '));
            o.addProperty("cost", c.toString().trim());
            o.addProperty("source", b.source());
            bps.add(o);
        }
        root.add("blueprints", bps);
        return root.toString();
    }
}
