package com.kingdomsai.core.construction;

/** Estradas: obras lineares (caminho de terra ou calçada) que não ocupam terreno como uma construção. */
public final class Roads {
    public static final String SOURCE = "road";

    private Roads() {}

    public static boolean isRoad(Blueprint bp) {
        return bp != null && SOURCE.equals(bp.source());
    }
}
