package com.kingdomsai.core.construction;

import com.kingdomsai.core.common.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Uma construção registrada no reino.
 *
 * progress = quantos passos do blueprint já foram concluídos (simulação, mesmo longe do jogador).
 * placed   = quantos passos já existem fisicamente no mundo. Quando a área carrega e placed < progress,
 *            o adaptador "materializa" o que falta — LOD aplicado a construções.
 */
public final class Building {
    public enum Status { PLANNED, UNDER_CONSTRUCTION, COMPLETE, ABANDONED }

    public UUID id;
    public UUID kingdomId;
    public String blueprintId;
    public Pos origin;
    public int variant;
    public Status status = Status.PLANNED;
    public int progress;
    public int placed;
    public long startedTick;
    public long completedTick;
    /** Construtor principal (compatibilidade com saves antigos). */
    public UUID builderId;
    /** Todos os construtores designados (principal + ajudantes puxados por prazo). */
    public List<UUID> builderIds = new ArrayList<>();
    /** Prazo dado pelo rei (tick absoluto); 0 = sem prazo. */
    public long deadlineTick;
    public boolean overdueWarned;
    public boolean atRiskWarned;
    public List<UUID> residents = new ArrayList<>();
    /** Para quem a construção foi feita (ex.: "casa para o ferreiro"). */
    public UUID dedicatedTo;

    public Building() {}

    public Blueprint blueprint() {
        return BlueprintLibrary.get(blueprintId);
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    public Pos entrance() {
        Blueprint b = blueprint();
        return origin.offset(b.sizeX() / 2, 0, -1);
    }

    public Pos centerPos() {
        Blueprint b = blueprint();
        return origin.offset(b.sizeX() / 2, 0, b.sizeZ() / 2);
    }

    public boolean overlaps(Pos otherOrigin, Blueprint other, int margin) {
        Blueprint b = blueprint();
        int ax0 = origin.x() - margin, ax1 = origin.x() + b.sizeX() + margin;
        int az0 = origin.z() - margin, az1 = origin.z() + b.sizeZ() + margin;
        int bx0 = otherOrigin.x(), bx1 = otherOrigin.x() + other.sizeX();
        int bz0 = otherOrigin.z(), bz1 = otherOrigin.z() + other.sizeZ();
        return ax0 < bx1 && bx0 < ax1 && az0 < bz1 && bz0 < az1;
    }

    public boolean hasBuilder(UUID npc) {
        return npc != null && (npc.equals(builderId) || builderIds.contains(npc));
    }

    public double percent() {
        Blueprint b = blueprint();
        if (b == null) return 0;
        int total = b.solidTotal();
        return total == 0 ? 100.0 * progress / Math.max(1, b.blockCount()) : 100.0 * b.solidBefore(progress) / total;
    }
}
