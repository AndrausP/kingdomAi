package com.kingdomsai.core.skill;

import com.kingdomsai.core.common.Pos;

import java.util.*;

/**
 * Ordem física dada a um NPC ("cave aqui", "pegue 3 barras no baú, faça uma picareta e me entregue"):
 * uma sequência de tarefas já validada pelo {@link JobPlanner}, executada pelo {@link SkillSystem}.
 * Só anda onde o mundo está carregado (perto do rei); longe dele, a ordem espera.
 */
public final class PhysicalJob {
    public enum Status {
        ACTIVE("em andamento"), WAITING("esperando"), PAUSED("em pausa"), DONE("concluída"), FAILED("falhou"), CANCELLED("cancelada");

        public final String display;

        Status(String display) {
            this.display = display;
        }

        public boolean live() {
            return this == ACTIVE || this == WAITING || this == PAUSED;
        }
    }

    public enum Kind {
        BREAK("quebrar"), CHOP("cortar árvore"), TAKE("pegar no baú"), PUT("guardar no baú"), CRAFT("fabricar"), GIVE("entregar ao rei"),
        PLANT("plantar muda");

        public final String display;

        Kind(String display) {
            this.display = display;
        }
    }

    public static final class Task {
        public Kind kind;
        /** Fila de blocos a quebrar (BREAK/CHOP), em ordem; consumida durante a execução. */
        public List<Pos> blocks = new ArrayList<>();
        public int total;
        /** Baú (TAKE/PUT), bancada/fornalha (CRAFT) ou chão da muda (PLANT). */
        public Pos at;
        /** Item (id ou grupo #logs/#all) e quantidade (0 = tudo). */
        public String item;
        public int count;
        public String block;
        /** Inserida pelo planejador (buscar ingrediente, esvaziar a mochila). */
        public boolean auto;
        public int done;
        public double progress;
        public String label = "";
        /** Alvo atual e desde quando o NPC tenta alcançá-lo (para pular o inalcançável). */
        public Pos targetBlock;
        public long targetSince;
    }

    public UUID id;
    public UUID kingdomId;
    public UUID npcId;
    public UUID orderedBy;
    public int number;
    public String name = "";
    public Status status = Status.ACTIVE;
    public String reason = "";
    public List<Task> tasks = new ArrayList<>();
    public int cursor;
    public long createdTick;
    public long stateSince;
    public List<String> log = new ArrayList<>();
    /** Tudo o que esta ordem rendeu (blocos quebrados, itens feitos). */
    public Map<String, Integer> gained = new TreeMap<>();

    public Task current() {
        return cursor < tasks.size() ? tasks.get(cursor) : null;
    }

    public void addLog(long tick, String line) {
        log.add("[dia " + (tick / 24000 + 1) + "] " + line);
        if (log.size() > 20) log.remove(0);
    }
}
