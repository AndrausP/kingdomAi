package com.kingdomsai.core.construction;

import com.kingdomsai.core.kingdom.ResourceType;

import java.util.*;

/**
 * Lista de materiais de uma planta numa variante: o item que cada passo consome (null = não consome — limpar terreno,
 * água, terra do próprio chão, metade de cima da porta) e os totais. É a conta que a obra cobra do reino: exatamente
 * os blocos que vão aparecer no mundo.
 */
public final class BillOfMaterials {
    private final String[] item;
    private final int[] count;
    private final boolean[] finish;
    /** Segunda metade (cabeceira da cama, metade de cima da porta): índice da primeira metade, ou -1. */
    private final int[] partner;
    private final Map<String, Integer> structural = new TreeMap<>(), finishes = new TreeMap<>(), all = new TreeMap<>();

    private BillOfMaterials(Blueprint bp, int variant) {
        List<Blueprint.Placement> ps = bp.placements();
        item = new String[ps.size()];
        count = new int[ps.size()];
        finish = new boolean[ps.size()];
        partner = new int[ps.size()];
        Arrays.fill(partner, -1);
        for (int i = 0; i < ps.size(); i++) {
            Blueprint.Placement p = ps.get(i);
            if (p.material() == Material.AIR) continue;
            if (secondHalf(p, bp, variant)) {
                for (int j = i - 1; j >= Math.max(0, i - 3); j--)
                    if (firstHalfOf(ps.get(j), p)) {
                        partner[i] = j;
                        break;
                    }
            }
            // a segunda metade (de cima da porta, cabeceira da cama) vem junto com o item da primeira
            if (p.material() == Material.DOOR_UPPER || p.material() == Material.BED_HEAD) continue;
            Palette.Need need = Palette.itemFor(Palette.block(p, bp, variant));
            if (need == null) continue;
            item[i] = need.item();
            count[i] = need.count();
            finish[i] = finishMaterial(p.material()) || p.material() == Material.RAW && Materials.isFinish(need.item())
                    || p.material() == Material.LIGHT;
            (finish[i] ? finishes : structural).merge(need.item(), need.count(), Integer::sum);
            all.merge(need.item(), need.count(), Integer::sum);
        }
    }

    private static boolean secondHalf(Blueprint.Placement p, Blueprint bp, int variant) {
        if (p.material() == Material.DOOR_UPPER || p.material() == Material.BED_HEAD) return true;
        if (p.material() != Material.RAW || p.block() == null) return false;
        return "upper".equals(Palette.prop(p.block(), "half")) || "head".equals(Palette.prop(p.block(), "part"));
    }

    private static boolean firstHalfOf(Blueprint.Placement a, Blueprint.Placement b) {
        boolean adjacent = Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()) == 1;
        if (!adjacent) return false;
        if (b.material() == Material.DOOR_UPPER) return a.material() == Material.DOOR_LOWER;
        if (b.material() == Material.BED_HEAD) return a.material() == Material.BED_FOOT;
        return a.material() == Material.RAW && a.block() != null && Palette.id(a.block()).equals(Palette.id(b.block()));
    }

    /** Materiais abstratos que são acabamento (a obra fica pronta sem eles; instala depois). */
    private static boolean finishMaterial(Material m) {
        return switch (m) {
            case WINDOW, BED_FOOT, BED_HEAD, BANNER, CROP, BELL, ANVIL, BOOKSHELF, LECTERN, HAY, CHIMNEY, LIGHT -> true;
            default -> false;
        };
    }

    private record Key(Blueprint bp, int variant) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.bp == bp && k.variant == variant;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(bp) * 31 + variant;
        }
    }

    private static final Map<Key, BillOfMaterials> CACHE = new HashMap<>();

    /** Lista da planta (cacheada por planta e variante). */
    public static synchronized BillOfMaterials of(Blueprint bp, int variant) {
        int v = Math.floorMod(variant, 3);
        Key key = new Key(bp, v);
        BillOfMaterials b = CACHE.get(key);
        if (b == null) {
            if (CACHE.size() > 600) CACHE.clear(); // plantas do mundo recarregadas: não acumula versões velhas
            b = new BillOfMaterials(bp, v);
            CACHE.put(key, b);
        }
        return b;
    }

    /** Sem cache (usado ao montar o custo de uma planta nova). */
    public static BillOfMaterials compute(Blueprint bp, int variant) {
        return new BillOfMaterials(bp, Math.floorMod(variant, 3));
    }

    public String item(int index) {
        return index >= 0 && index < item.length ? item[index] : null;
    }

    public int count(int index) {
        return index >= 0 && index < count.length ? count[index] : 0;
    }

    /** Primeira metade do par (a segunda só aparece se a primeira tiver material), ou -1. */
    public int partner(int index) {
        return index >= 0 && index < partner.length ? partner[index] : -1;
    }

    public boolean finish(int index) {
        return index >= 0 && index < finish.length && finish[index];
    }

    /** Estrutura: sem ela a obra não começa. */
    public Map<String, Integer> structural() {
        return Collections.unmodifiableMap(structural);
    }

    /** Acabamentos: viram pendência se faltarem. */
    public Map<String, Integer> finishes() {
        return Collections.unmodifiableMap(finishes);
    }

    public Map<String, Integer> all() {
        return Collections.unmodifiableMap(all);
    }

    /** O que ainda falta colocar a partir do passo {@code from} (para cancelamentos e para o painel). */
    public Map<String, Integer> remaining(int from) {
        Map<String, Integer> out = new TreeMap<>();
        for (int i = Math.max(0, from); i < item.length; i++) if (item[i] != null) out.merge(item[i], count[i], Integer::sum);
        return out;
    }

    /** Só a estrutura que falta a partir do passo {@code from} (o que a obra precisa ter separado para começar/continuar). */
    public Map<String, Integer> remainingStructural(int from) {
        Map<String, Integer> out = new TreeMap<>();
        for (int i = Math.max(0, from); i < item.length; i++)
            if (item[i] != null && !finish[i]) out.merge(item[i], count[i], Integer::sum);
        return out;
    }

    /** Número de camas (cabeceiras) da planta: cada cama abriga uma pessoa. */
    public static int beds(Blueprint bp) {
        int n = 0;
        for (Blueprint.Placement p : bp.placements())
            if (p.material() == Material.BED_HEAD || p.material() == Material.RAW && p.block() != null && p.block().contains("_bed")
                    && "head".equals(Palette.prop(p.block(), "part"))) n++;
        return n;
    }

    /** Custo realista em recursos (tudo fabricado do zero) — o que a planta mostra como "custo". */
    public static Map<ResourceType, Integer> cost(Blueprint bp) {
        return Materials.rawCost(compute(bp, 0).all(), null);
    }
}
