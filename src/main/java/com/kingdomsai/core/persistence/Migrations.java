package com.kingdomsai.core.persistence;

import com.google.gson.JsonObject;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.Npc;

import java.util.*;

/** Migração de saves entre versões do schema. Cada versão nova adiciona um passo aqui. */
public final class Migrations {
    private Migrations() {}

    public static void migrate(JsonObject root) {
        int v = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 0;
        if (v < 1) {
            // v0 → v1: versão inicial; nada a converter.
            root.addProperty("schemaVersion", 1);
        }
        // if (v < 2) { ... }
    }

    /** Garante campos não nulos após carregar (Gson ignora inicializadores para campos ausentes em alguns casos). */
    public static void repair(WorldState s) {
        if (s.kingdoms == null) s.kingdoms = new LinkedHashMap<>();
        if (s.npcs == null) s.npcs = new LinkedHashMap<>();
        if (s.buildings == null) s.buildings = new LinkedHashMap<>();
        if (s.links == null) s.links = new HashMap<>();
        if (s.attitudes == null) s.attitudes = new HashMap<>();
        if (s.treaties == null) s.treaties = new ArrayList<>();
        if (s.chronicle == null) s.chronicle = new ArrayList<>();
        if (s.blueprintSpecs == null) s.blueprintSpecs = new LinkedHashMap<>();
        if (s.savedBlueprints == null) s.savedBlueprints = new ArrayList<>();
        for (var b : s.buildings.values()) {
            if (b.builderIds == null) b.builderIds = new ArrayList<>();
            if (b.builderId != null && !b.builderIds.contains(b.builderId)) b.builderIds.add(b.builderId);
            if (b.residents == null) b.residents = new ArrayList<>();
        }
        if (s.events == null) s.events = new ArrayList<>();
        for (Kingdom k : s.kingdoms.values()) {
            if (k.stock == null) k.stock = new EnumMap<>(ResourceType.class);
            if (k.lastDelta == null) k.lastDelta = new EnumMap<>(ResourceType.class);
            if (k.laws == null) k.laws = new Kingdom.Laws();
        }
        for (Npc n : s.npcs.values()) {
            if (n.relations == null) n.relations = new HashMap<>();
            if (n.memories == null) n.memories = new ArrayList<>();
            else n.memories = new ArrayList<>(n.memories);
            if (n.traits == null) n.traits = new HashMap<>();
            if (n.currentTask == null) n.currentTask = "";
            if (n.lastDecision == null) n.lastDecision = "";
            if (n.lastLlmCall == null) n.lastLlmCall = "";
        }
        s.schemaVersion = WorldState.SCHEMA_VERSION;
    }
}
