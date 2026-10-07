package com.kingdomsai.core.kingdom;

import com.kingdomsai.core.common.Pos;

import java.util.*;

/**
 * Uma entidade política (Polity). O jogador é só um dos agentes do mundo:
 * reinos de IA usam exatamente a mesma classe, os mesmos sistemas e os mesmos validadores.
 */
public final class Kingdom {
    public enum PolityType { VILLAGE, CITY_STATE, KINGDOM, EMPIRE, TRIBE, THEOCRACY, REPUBLIC }

    public UUID id;
    public String name;
    public PolityType polity = PolityType.VILLAGE;
    /** UUID do jogador que é rei; null para reinos de IA. */
    public UUID rulerPlayer;
    /** Nome exibido do governante (jogador ou NPC). */
    public String rulerName;
    public UUID rulerNpc;
    public Pos center;
    public long foundedTick;
    public int color;

    public Map<ResourceType, Double> stock = new EnumMap<>(ResourceType.class);
    /** Variação do último tick econômico, para o HUD. */
    public Map<ResourceType, Double> lastDelta = new EnumMap<>(ResourceType.class);

    public double stability = 80;
    public double morale = 75;
    public double legitimacy = 70;

    public KingdomPersonality personality = KingdomPersonality.balanced();
    public Laws laws = new Laws();

    /** Reputação perante o mundo. */
    public double honor = 60;
    /** Fama de crueldade (massacres, escravidão): 0..100, cai devagar. Pesa na estabilidade e na diplomacia (v3). */
    public double infamy = 0;
    /** Tesouro físico: o que havia nos baús na última conferência (null = ainda não conferido) (v4). */
    public Map<ResourceType, Integer> treasurySeen;
    /** Itens do registro que não couberam nos baús (v4). */
    public int treasuryOverflow;
    public double reliability = 60;

    /** Pontos marcados pelo rei com a Bandeira do Reino (spawn, praça, mina, bosque). */
    public Map<Marker, Pos> markers = new EnumMap<>(Marker.class);

    public boolean famine;
    public boolean shortageWarned;
    public boolean housingWarned;

    public Kingdom() {}

    /** Onde chegam os novos moradores e o rei renasce: o marco, senão o centro. */
    public Pos spawnPoint() {
        Pos p = markers.get(Marker.SPAWN);
        return p != null ? p : center;
    }

    /** Ponto marcado ou o padrão. */
    public Pos marker(Marker m, Pos fallback) {
        Pos p = markers.get(m);
        return p != null ? p : fallback;
    }

    public boolean isPlayerKingdom() {
        return rulerPlayer != null;
    }

    public double get(ResourceType r) {
        return stock.getOrDefault(r, 0.0);
    }

    public void add(ResourceType r, double amount) {
        stock.put(r, Math.max(0, get(r) + amount));
    }

    public boolean has(Map<ResourceType, Integer> cost) {
        for (var e : cost.entrySet()) if (get(e.getKey()) + 1e-6 < e.getValue()) return false;
        return true;
    }

    public void pay(Map<ResourceType, Integer> cost) {
        for (var e : cost.entrySet()) add(e.getKey(), -e.getValue());
    }

    public static final class Laws {
        /** 0 = isento, 1 = baixo, 2 = normal, 3 = alto, 4 = extorsivo */
        public int taxLevel = 2;
        public boolean conscription = false;
        public boolean openMigration = true;
    }
}
