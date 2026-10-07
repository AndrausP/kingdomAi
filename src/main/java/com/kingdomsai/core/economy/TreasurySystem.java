package com.kingdomsai.core.economy;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.construction.Material;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.port.PhysicalPort;

import java.util.*;

/**
 * Tesouro físico: o estoque do reino MORA nos baús do armazém (ou do Salão Real, antes de haver armazém).
 * <ul>
 *   <li>O que o jogador (ou um súdito) põe nos baús entra no estoque; o que tira, sai.</li>
 *   <li>Produção vira item no baú (tábuas, pedregulho, pão, barras); consumo e obras tiram do baú.</li>
 *   <li>Com os baús longe (chunk descarregado) o reino segue pelo registro; ao carregar, os baús são acertados.</li>
 * </ul>
 * O registro (stock) continua sendo a conta oficial; os baús são o espelho físico e a porta de entrada/saída do jogador.
 */
public final class TreasurySystem {
    /** Item que representa cada recurso nos baús. */
    public static final Map<ResourceType, String> CANONICAL = Map.of(
            ResourceType.WOOD, "minecraft:oak_planks",
            ResourceType.STONE, "minecraft:cobblestone",
            ResourceType.IRON, "minecraft:iron_ingot",
            ResourceType.GOLD, "minecraft:gold_ingot",
            ResourceType.FOOD, "minecraft:bread",
            ResourceType.WEAPONS, "minecraft:iron_sword");

    /** Quanto um item vale no estoque. */
    public record Unit(ResourceType resource, int value) {}

    private final KingdomsCore core;

    public TreasurySystem(KingdomsCore core) {
        this.core = core;
    }

    public static Unit unit(String id) {
        if (id == null) return null;
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (n.endsWith("_planks")) return new Unit(ResourceType.WOOD, 1);
        if (n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_stem") || n.endsWith("_hyphae")) return new Unit(ResourceType.WOOD, 4);
        switch (n) {
            case "cobblestone", "stone", "cobbled_deepslate", "deepslate", "stone_bricks", "andesite", "diorite", "granite", "tuff",
                 "mossy_cobblestone", "sandstone", "blackstone" -> {
                return new Unit(ResourceType.STONE, 1);
            }
            case "iron_ingot" -> {
                return new Unit(ResourceType.IRON, 1);
            }
            case "iron_block" -> {
                return new Unit(ResourceType.IRON, 9);
            }
            case "gold_ingot" -> {
                return new Unit(ResourceType.GOLD, 1);
            }
            case "gold_block" -> {
                return new Unit(ResourceType.GOLD, 9);
            }
            case "bread", "wheat", "carrot", "potato", "baked_potato", "apple", "beetroot", "cooked_cod", "cooked_salmon", "melon_slice", "sweet_berries" -> {
                return new Unit(ResourceType.FOOD, 1);
            }
            case "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_rabbit", "pumpkin_pie", "hay_block" -> {
                return new Unit(ResourceType.FOOD, n.equals("hay_block") ? 9 : 2);
            }
            case "iron_sword" -> {
                return new Unit(ResourceType.WEAPONS, 1);
            }
            default -> {
                return null;
            }
        }
    }

    /** Baús do tesouro: os do armazém (e outras construções de estoque); sem armazém, os do Salão Real. */
    public List<Pos> chests(Kingdom k) {
        List<Pos> storage = new ArrayList<>(), hall = new ArrayList<>();
        PhysicalPort port = core.physical();
        for (Building b : core.buildings(k.id)) {
            if (!b.isComplete() || b.origin == null || b.origin.y() == Integer.MIN_VALUE) continue;
            Blueprint bp = b.blueprint();
            if (bp == null) continue;
            boolean isStorage = bp.category() == Blueprint.Category.STORAGE || b.blueprintId.equals("storage");
            boolean isHall = b.blueprintId.equals("town_hall");
            if (!isStorage && !isHall) continue;
            for (var pl : bp.placements()) {
                if (pl.material() != Material.CHEST && pl.material() != Material.BARREL) continue;
                Pos p = b.origin.offset(pl.x(), pl.y(), pl.z());
                if (port.container(p) != null) (isStorage ? storage : hall).add(p);
            }
        }
        return storage.isEmpty() ? hall : storage;
    }

    /** Contagem física por recurso. */
    public Map<ResourceType, Integer> read(List<Pos> chests) {
        Map<ResourceType, Integer> out = new EnumMap<>(ResourceType.class);
        for (Pos c : chests) {
            Map<String, Integer> inv = core.physical().container(c);
            if (inv == null) continue;
            for (var e : inv.entrySet()) {
                Unit u = unit(e.getKey());
                if (u != null) out.merge(u.resource(), e.getValue() * u.value(), Integer::sum);
            }
        }
        return out;
    }

    public void syncAll() {
        for (Kingdom k : core.state().kingdoms.values()) sync(k);
    }

    /**
     * Acerta registro e baús. 1) o que mudou nos baús desde a última vez (jogador pôs/tirou) entra no registro;
     * 2) os baús passam a ter o que o registro diz (produção entra, consumo/obra sai). Retorna o resumo ou null se não deu.
     */
    public String sync(Kingdom k) {
        List<Pos> chests = chests(k);
        if (chests.isEmpty()) {
            k.treasurySeen = null;
            return null;
        }
        for (Pos c : chests) if (!core.physical().isLoaded(c)) return null; // longe: o registro manda; acerta quando carregar
        Map<ResourceType, Integer> phys = read(chests);
        StringBuilder moved = new StringBuilder();
        if (k.treasurySeen != null) {
            for (ResourceType r : CANONICAL.keySet()) {
                int delta = phys.getOrDefault(r, 0) - k.treasurySeen.getOrDefault(r, 0);
                if (delta == 0) continue;
                k.add(r, delta);
                moved.append(delta > 0 ? "+" : "").append(delta).append(' ').append(r.display.toLowerCase()).append(", ");
            }
            if (moved.length() > 0)
                core.bus().publish(core.tick(), EventType.TREASURY_CHANGED, GameEvent.Severity.INFO, k.id, null,
                        "Baús do armazém de " + k.name + ": " + moved.substring(0, moved.length() - 2) + " (entrou/saiu no estoque).");
        }
        int overflow = 0;
        for (ResourceType r : CANONICAL.keySet()) {
            int target = (int) Math.floor(k.get(r) + 1e-6);
            int have = phys.getOrDefault(r, 0);
            if (target > have) overflow += put(chests, CANONICAL.get(r), target - have);
            else if (target < have) remove(chests, r, have - target);
        }
        k.treasurySeen = read(chests);
        k.treasuryOverflow = overflow;
        return moved.toString();
    }

    /** Guarda n itens espalhando pelos baús; devolve o que não coube. */
    private int put(List<Pos> chests, String item, int n) {
        int left = n;
        for (Pos c : chests) {
            if (left <= 0) break;
            int batch = left;
            Map<String, Integer> rest = core.physical().put(null, c, new HashMap<>(Map.of(item, batch)));
            int notFit = rest == null ? 0 : rest.getOrDefault(item, 0);
            left = notFit;
        }
        return left;
    }

    /** Tira {@code units} do recurso: primeiro o item padrão, depois os outros (troco volta como item padrão). */
    private void remove(List<Pos> chests, ResourceType r, int units) {
        String canon = CANONICAL.get(r);
        int left = units;
        for (Pos c : chests) {
            if (left <= 0) return;
            left -= core.physical().take(null, c, canon, left);
        }
        for (Pos c : chests) {
            if (left <= 0) return;
            Map<String, Integer> inv = core.physical().container(c);
            if (inv == null) continue;
            for (var e : new ArrayList<>(inv.entrySet())) {
                if (left <= 0) break;
                Unit u = unit(e.getKey());
                if (u == null || u.resource() != r || e.getKey().equals(canon)) continue;
                int items = Math.min(e.getValue(), (left + u.value() - 1) / u.value());
                int took = core.physical().take(null, c, e.getKey(), items);
                int change = took * u.value() - left;
                left -= took * u.value();
                if (change > 0) {
                    put(chests, canon, change); // troco: 1 tronco = 4 tábuas
                    left = 0;
                }
            }
        }
    }

    /** Linha para o conselho/Manager: onde está o estoque. */
    public String describe(Kingdom k) {
        List<Pos> chests = chests(k);
        if (chests.isEmpty()) return "Estoque só no registro (o reino ainda não tem armazém nem Salão Real pronto com baús).";
        return "Estoque nos " + chests.size() + " baú(s) em " + chests.get(0)
                + (k.treasuryOverflow > 0 ? " · ⚠ " + k.treasuryOverflow + " itens não couberam (só no registro): construa outro armazém" : "") + ".";
    }
}
