package com.kingdomsai.core.construction;

import com.kingdomsai.core.economy.TreasurySystem;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;

import java.util.*;

/**
 * De onde sai cada bloco de uma obra. Nada vem do nada: ou o item já está guardado no reino, ou é feito a partir do que
 * está guardado, pelas receitas do Minecraft (tora → 4 tábuas, 6 tábuas → 4 escadas, areia → vidro, 6 vidros → 16 vidraças,
 * 3 lãs + 3 tábuas → cama...). Fabricar rende em lotes, como no jogo: o que sobra do lote volta ao armazém.
 *
 * <p>Madeira (tábuas/toras de qualquer espécie), pedra (pedregulho, pedra, tijolo de pedra...), ferro, ouro e comida são os
 * recursos do registro do reino ({@link Kingdom#stock}); todo o resto é bem guardado ({@link Kingdom#goods}).
 */
public final class Materials {
    private Materials() {}

    /** Ingrediente de uma receita: quantidade de um recurso do registro OU de um item (com alternativas: "a|b" ou "#wool"). */
    public record In(ResourceType res, double amount, String item, int count) {
        static In res(ResourceType r, double amount) {
            return new In(r, amount, null, 0);
        }

        static In item(String item, int count) {
            return new In(null, 0, item, count);
        }
    }

    /** Receita: um lote rende {@code out} itens. {@code smelt} = forno (combustível de madeira incluso nos ingredientes). */
    public record Recipe(String output, int out, List<In> in, boolean smelt) {}

    private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo",
            "crimson", "warped", "pale_oak");
    private static final Set<String> STONES = Set.of("cobblestone", "stone", "stone_brick", "mossy_cobblestone", "mossy_stone_brick", "andesite",
            "polished_andesite", "diorite", "polished_diorite", "granite", "polished_granite", "cobbled_deepslate", "polished_deepslate",
            "deepslate_brick", "deepslate_tile", "blackstone", "polished_blackstone", "polished_blackstone_brick", "tuff", "polished_tuff",
            "tuff_brick", "smooth_stone", "sandstone", "smooth_sandstone", "red_sandstone", "smooth_red_sandstone", "cut_sandstone");
    /** Pedras que o registro conta como "Pedra" mas não estão na tabela do tesouro: valem 1 pedra cada (lapidar/fundir). */
    private static final Set<String> STONE_BLOCKS = Set.of("polished_andesite", "polished_diorite", "polished_granite", "smooth_stone",
            "chiseled_stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks", "polished_deepslate", "deepslate_bricks", "deepslate_tiles",
            "chiseled_deepslate", "cracked_deepslate_bricks", "cut_sandstone", "smooth_sandstone", "chiseled_sandstone", "red_sandstone",
            "cut_red_sandstone", "smooth_red_sandstone", "polished_blackstone", "polished_blackstone_bricks", "chiseled_polished_blackstone",
            "tuff_bricks", "polished_tuff", "chiseled_tuff", "chiseled_tuff_bricks", "polished_basalt", "smooth_basalt", "calcite");
    public static final List<String> WOOL = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final double FUEL = 0.7; // tábua queimada por item fundido (1 tábua funde 1,5)

    // ------------------------------------------------------------------ receitas

    /** Receita conhecida para o item (vanilla 1.21), ou null se ele só se obtém pronto (areia, lã, carvão, couro...). */
    public static Recipe recipe(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        String w = woodOf(p);
        if (w != null) {
            String form = p.substring(w.length() + 1);
            return switch (form) {
                case "stairs" -> r(id, 4, In.res(ResourceType.WOOD, 6));
                case "slab" -> r(id, 6, In.res(ResourceType.WOOD, 3));
                case "fence" -> r(id, 3, In.res(ResourceType.WOOD, 5));
                case "fence_gate" -> r(id, 1, In.res(ResourceType.WOOD, 4));
                case "door" -> r(id, 3, In.res(ResourceType.WOOD, 6));
                case "trapdoor" -> r(id, 2, In.res(ResourceType.WOOD, 6));
                case "pressure_plate" -> r(id, 1, In.res(ResourceType.WOOD, 2));
                case "button" -> r(id, 1, In.res(ResourceType.WOOD, 1));
                case "sign" -> r(id, 3, In.res(ResourceType.WOOD, 7));
                default -> null;
            };
        }
        if (p.endsWith("_stairs") || p.endsWith("_slab") || p.endsWith("_wall")) {
            String base = p.substring(0, p.lastIndexOf('_'));
            boolean stairs = p.endsWith("_stairs"), slab = p.endsWith("_slab");
            int out = stairs ? 4 : 6;
            double per = stairs ? 6 : slab ? 3 : 6;
            if (STONES.contains(base)) return r(id, out, In.res(ResourceType.STONE, per));
            String block = switch (base) {
                case "brick" -> "minecraft:bricks";
                case "mud_brick" -> "minecraft:mud_bricks";
                case "quartz", "smooth_quartz" -> "minecraft:quartz_block";
                case "nether_brick" -> "minecraft:nether_bricks";
                case "prismarine" -> "minecraft:prismarine";
                default -> null;
            };
            return block == null ? null : r(id, out, In.item(block, (int) per));
        }
        if (STONE_BLOCKS.contains(p)) return r(id, 1, In.res(ResourceType.STONE, 1));
        if (p.endsWith("_bed")) return r(id, 1, In.item("#wool", 3), In.res(ResourceType.WOOD, 3));
        if (p.endsWith("_carpet") && !p.equals("moss_carpet")) return r(id, 3, In.item("#wool", 2));
        if (p.endsWith("_banner")) return r(id, 1, In.item("#wool", 6), In.res(ResourceType.WOOD, 0.5));
        if (p.endsWith("_stained_glass_pane")) return r(id, 16, In.item("minecraft:" + p.replace("_pane", ""), 6));
        if (p.endsWith("_stained_glass")) return r(id, 8, In.item("minecraft:glass", 8), In.item("minecraft:" + p.replace("_stained_glass", "_dye"), 1));
        return switch (p) {
            case "stick" -> r(id, 4, In.res(ResourceType.WOOD, 2));
            case "chest" -> r(id, 1, In.res(ResourceType.WOOD, 8));
            case "barrel" -> r(id, 1, In.res(ResourceType.WOOD, 7));
            case "crafting_table" -> r(id, 1, In.res(ResourceType.WOOD, 4));
            case "ladder" -> r(id, 3, In.res(ResourceType.WOOD, 4));
            case "composter" -> r(id, 1, In.res(ResourceType.WOOD, 4));
            case "bookshelf" -> r(id, 1, In.res(ResourceType.WOOD, 6), In.item("minecraft:book", 3));
            case "lectern" -> r(id, 1, In.res(ResourceType.WOOD, 2), In.item("minecraft:bookshelf", 1));
            case "cartography_table" -> r(id, 1, In.res(ResourceType.WOOD, 4), In.item("minecraft:paper", 2));
            case "smithing_table" -> r(id, 1, In.res(ResourceType.WOOD, 4), In.res(ResourceType.IRON, 2));
            case "fletching_table" -> r(id, 1, In.res(ResourceType.WOOD, 4), In.item("minecraft:flint", 2));
            case "loom" -> r(id, 1, In.res(ResourceType.WOOD, 2), In.item("minecraft:string", 2));
            case "torch" -> r(id, 4, In.item("minecraft:coal|minecraft:charcoal", 1), In.res(ResourceType.WOOD, 0.5));
            case "lantern" -> r(id, 1, In.res(ResourceType.IRON, 1), In.item("minecraft:torch", 1));
            case "campfire" -> r(id, 1, In.res(ResourceType.WOOD, 13.5), In.item("minecraft:coal|minecraft:charcoal", 1));
            case "glass" -> rs(id, 1, In.item("minecraft:sand|minecraft:red_sand", 1), In.res(ResourceType.WOOD, FUEL));
            case "glass_pane" -> r(id, 16, In.item("minecraft:glass", 6));
            case "furnace" -> r(id, 1, In.res(ResourceType.STONE, 8));
            case "smoker" -> r(id, 1, In.item("minecraft:furnace", 1), In.res(ResourceType.WOOD, 16));
            case "blast_furnace" -> r(id, 1, In.item("minecraft:furnace", 1), In.res(ResourceType.IRON, 5), In.res(ResourceType.STONE, 3));
            case "stonecutter" -> r(id, 1, In.res(ResourceType.IRON, 1), In.res(ResourceType.STONE, 3));
            case "grindstone" -> r(id, 1, In.res(ResourceType.WOOD, 3), In.res(ResourceType.STONE, 1));
            case "anvil" -> r(id, 1, In.res(ResourceType.IRON, 31));
            case "cauldron" -> r(id, 1, In.res(ResourceType.IRON, 7));
            case "iron_bars" -> r(id, 16, In.res(ResourceType.IRON, 6));
            case "chain" -> r(id, 1, In.res(ResourceType.IRON, 1.25));
            case "iron_door" -> r(id, 3, In.res(ResourceType.IRON, 6));
            case "iron_trapdoor" -> r(id, 1, In.res(ResourceType.IRON, 4));
            case "hopper" -> r(id, 1, In.res(ResourceType.IRON, 5), In.item("minecraft:chest", 1));
            case "rail" -> r(id, 16, In.res(ResourceType.IRON, 6), In.res(ResourceType.WOOD, 0.5));
            case "heavy_weighted_pressure_plate" -> r(id, 1, In.res(ResourceType.IRON, 2));
            case "stone_pressure_plate" -> r(id, 1, In.res(ResourceType.STONE, 2));
            case "stone_button" -> r(id, 1, In.res(ResourceType.STONE, 1));
            // o Minecraft não tem receita de sino: o ferreiro funde 4 barras de ouro (o sino do vilarejo)
            case "bell" -> r(id, 1, In.res(ResourceType.GOLD, 4));
            case "bricks" -> r(id, 1, In.item("minecraft:brick", 4));
            case "brick" -> rs(id, 1, In.item("minecraft:clay_ball", 1), In.res(ResourceType.WOOD, FUEL));
            case "flower_pot" -> r(id, 1, In.item("minecraft:brick", 3));
            case "clay" -> r(id, 1, In.item("minecraft:clay_ball", 4));
            case "terracotta" -> rs(id, 1, In.item("minecraft:clay", 1), In.res(ResourceType.WOOD, FUEL));
            case "white_wool" -> r(id, 1, In.item("minecraft:string", 4));
            case "book" -> r(id, 1, In.item("minecraft:paper", 3), In.item("minecraft:leather", 1));
            case "paper" -> r(id, 3, In.item("minecraft:sugar_cane", 3));
            case "sandstone" -> r(id, 1, In.item("minecraft:sand", 4));
            case "mud_bricks" -> r(id, 4, In.item("minecraft:packed_mud", 4));
            case "packed_mud" -> r(id, 1, In.item("minecraft:mud", 1), In.item("minecraft:wheat", 1));
            case "scaffolding" -> r(id, 6, In.item("minecraft:bamboo", 6), In.item("minecraft:string", 1));
            case "armor_stand" -> r(id, 1, In.res(ResourceType.WOOD, 3), In.res(ResourceType.STONE, 0.5));
            default -> null;
        };
    }

    private static Recipe r(String id, int out, In... in) {
        return new Recipe(id, out, List.of(in), false);
    }

    private static Recipe rs(String id, int out, In... in) {
        return new Recipe(id, out, List.of(in), true);
    }

    /** "dark_oak_stairs" → "dark_oak"; null se não for madeira com forma (tábuas e toras são recurso do registro). */
    private static String woodOf(String p) {
        String best = null;
        for (String w : WOODS) if (p.startsWith(w + "_") && (best == null || w.length() > best.length())) best = w;
        return best;
    }

    /** Alternativas de um ingrediente: "#wool" = qualquer lã; "a|b" = a ou b. */
    public static List<String> options(String spec) {
        if (spec.equals("#wool")) {
            List<String> out = new ArrayList<>();
            for (String c : WOOL) out.add("minecraft:" + c + "_wool");
            return out;
        }
        return List.of(spec.split("\\|"));
    }

    // ------------------------------------------------------------------ acabamentos

    /**
     * Acabamento: sem o material a obra não para — o lugar fica como pendência e o construtor instala quando chegar
     * (vidro das janelas, camas, tecidos, sino, bigorna, estantes, iluminação, plantio). O resto é estrutura: sem ele, não se começa.
     */
    public static boolean isFinish(String id) {
        if (id == null) return false;
        String p = id.substring(id.indexOf(':') + 1);
        return p.contains("glass") || p.endsWith("_bed") || p.endsWith("_wool") || p.endsWith("_carpet") || p.endsWith("_banner")
                || p.equals("flower_pot") || p.endsWith("lantern") || p.endsWith("candle") || p.equals("bookshelf") || p.equals("lectern")
                || p.equals("bell") || p.equals("anvil") || p.endsWith("_seeds") || p.equals("carrot") || p.equals("potato") || p.equals("sweet_berries")
                || p.equals("cocoa_beans") || p.equals("hay_block") || p.endsWith("_head") || p.endsWith("_skull") || p.endsWith("glazed_terracotta")
                || p.equals("cake") || p.endsWith("_sapling") || p.endsWith("_leaves") || p.equals("torch") || p.equals("soul_torch")
                || p.equals("campfire") || p.equals("moss_carpet") || p.equals("decorated_pot") || p.equals("blast_furnace") || p.equals("smoker")
                || isFlower(p);
    }

    private static boolean isFlower(String p) {
        return switch (p) {
            case "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
                 "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "torchflower", "pink_petals" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ plano de abastecimento

    /** Resultado de abastecer uma lista de itens a partir do estoque de um reino (nada é aplicado até {@link #apply}). */
    public static final class Plan {
        /** O que sai do registro (madeira, pedra...). */
        public final Map<ResourceType, Double> resources = new EnumMap<>(ResourceType.class);
        /** O que sai dos bens guardados. */
        public final Map<String, Integer> goods = new TreeMap<>();
        /** Sobra dos lotes fabricados: volta ao armazém. */
        public final Map<String, Integer> leftovers = new TreeMap<>();
        /** Quanto de cada item pedido foi conseguido / ficou faltando. */
        public final Map<String, Integer> supplied = new TreeMap<>(), missing = new TreeMap<>();
        /** Matérias-primas que faltaram (ids, ou "RES:WOOD" para recurso do registro). */
        public final Set<String> roots = new TreeSet<>();
        /** "6 vidro → 16 vidraça" (para mostrar ao rei o que foi fabricado). */
        public final List<String> crafted = new ArrayList<>();

        public boolean complete() {
            return missing.isEmpty();
        }

        Plan copy() {
            Plan c = new Plan();
            c.resources.putAll(resources);
            c.goods.putAll(goods);
            c.leftovers.putAll(leftovers);
            c.supplied.putAll(supplied);
            c.missing.putAll(missing);
            c.roots.addAll(roots);
            c.crafted.addAll(crafted);
            return c;
        }

        void restore(Plan s) {
            resources.clear();
            resources.putAll(s.resources);
            goods.clear();
            goods.putAll(s.goods);
            leftovers.clear();
            leftovers.putAll(s.leftovers);
            crafted.clear();
            crafted.addAll(s.crafted);
        }

        /** Madeira/pedra/... totais que o plano tira do registro. */
        public double resource(ResourceType r) {
            return resources.getOrDefault(r, 0.0);
        }
    }

    /** Cópia de trabalho do estoque. */
    static final class Ledger {
        final EnumMap<ResourceType, Double> res = new EnumMap<>(ResourceType.class);
        final Map<String, Integer> goods = new HashMap<>();

        Ledger() {}

        Ledger(Kingdom k) {
            for (ResourceType r : ResourceType.values()) res.put(r, k.get(r));
            goods.putAll(k.goods);
        }

        static Ledger unlimited() {
            Ledger l = new Ledger();
            for (ResourceType r : ResourceType.values()) l.res.put(r, 1e12);
            return l;
        }

        Ledger copy() {
            Ledger l = new Ledger();
            l.res.putAll(res);
            l.goods.putAll(goods);
            return l;
        }

        void restore(Ledger s) {
            res.clear();
            res.putAll(s.res);
            goods.clear();
            goods.putAll(s.goods);
        }

        double get(ResourceType r) {
            return res.getOrDefault(r, 0.0);
        }
    }

    /**
     * Abastece cada item da lista (na ordem dada) com o que o reino tem. Parcial: consegue o que der de cada item.
     * Não muda o reino — use {@link #apply}.
     */
    public static Plan plan(Kingdom k, Map<String, Integer> need) {
        return plan(new Ledger(k), need);
    }

    /** Como {@link #plan(Kingdom, Map)}, mas sem tocar na reserva de comida do povo (feno do celeiro não esvazia a despensa). */
    public static Plan plan(Kingdom k, Map<String, Integer> need, double foodReserve) {
        Ledger l = new Ledger(k);
        l.res.put(ResourceType.FOOD, Math.max(0, l.get(ResourceType.FOOD) - foodReserve));
        l.res.put(ResourceType.WEAPONS, 0.0); // armas do arsenal não viram material de obra
        return plan(l, need);
    }

    static Plan plan(Ledger l, Map<String, Integer> need) {
        Plan p = new Plan();
        for (var e : order(need)) {
            if (e.getValue() <= 0) continue;
            Set<String> roots = new TreeSet<>();
            int got = supply(l, p, e.getKey(), e.getValue(), 0, roots);
            if (got > 0) p.supplied.merge(e.getKey(), got, Integer::sum);
            if (got < e.getValue()) {
                p.missing.merge(e.getKey(), e.getValue() - got, Integer::sum);
                p.roots.addAll(roots.isEmpty() ? Set.of(e.getKey()) : roots);
            }
        }
        return p;
    }

    /** Recursos simples primeiro (tábuas, pedregulho), depois o que é fabricado: sobra mais para os acabamentos. */
    private static List<Map.Entry<String, Integer>> order(Map<String, Integer> need) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(need.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> TreasurySystem.unit(e.getKey()) != null ? 0 : isFinish(e.getKey()) ? 2 : 1)
                .thenComparing(Map.Entry::getKey));
        return list;
    }

    /** Tira do reino o que o plano consumiu e guarda as sobras dos lotes. */
    public static void apply(Kingdom k, Plan p) {
        for (var e : p.resources.entrySet()) k.add(e.getKey(), -e.getValue());
        for (var e : p.goods.entrySet()) k.goods.merge(e.getKey(), -e.getValue(), Integer::sum);
        for (var e : p.leftovers.entrySet()) k.goods.merge(e.getKey(), e.getValue(), Integer::sum);
        k.goods.values().removeIf(v -> v <= 0);
    }

    /** Quantos de {@code item} foi possível conseguir (0..n); o consumo registrado corresponde exatamente ao que foi conseguido. */
    private static int supply(Ledger l, Plan p, String item, int n, int depth, Set<String> roots) {
        if (n <= 0) return 0;
        TreasurySystem.Unit u = TreasurySystem.unit(item);
        if (u != null) {
            double per = u.value();
            int can = (int) Math.floor((l.get(u.resource()) + 1e-6) / per);
            int s = Math.min(n, can);
            if (s > 0) {
                l.res.merge(u.resource(), -s * per, Double::sum);
                p.resources.merge(u.resource(), s * per, Double::sum);
            }
            if (s < n) roots.add("RES:" + u.resource().name());
            return s;
        }
        int have = l.goods.getOrDefault(item, 0);
        int took = Math.min(have, n);
        if (took > 0) {
            l.goods.merge(item, -took, Integer::sum);
            p.goods.merge(item, took, Integer::sum);
        }
        int left = n - took;
        if (left == 0) return n;
        Recipe r = recipe(item);
        if (r == null || depth >= 5) {
            roots.add(item);
            return took;
        }
        int batches = (left + r.out() - 1) / r.out();
        // maior número de lotes que o estoque permite (busca binária: se b lotes dão, b-1 também dão)
        int lo = 0, hi = batches;
        Set<String> causes = new TreeSet<>();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            Ledger sl = l.copy();
            Plan sp = p.copy();
            Set<String> c = new TreeSet<>();
            boolean ok = craft(l, p, r, mid, depth, c);
            l.restore(sl);
            p.restore(sp);
            if (ok) lo = mid;
            else {
                hi = mid - 1;
                causes.addAll(c);
            }
        }
        if (lo < batches) roots.addAll(causes.isEmpty() ? Set.of(item) : causes);
        if (lo == 0) return took;
        craft(l, p, r, lo, depth, new TreeSet<>());
        int made = lo * r.out(), used = Math.min(left, made);
        if (made > used) {
            l.goods.merge(item, made - used, Integer::sum);
            p.leftovers.merge(item, made - used, Integer::sum);
        }
        p.crafted.add(describeCraft(r, lo));
        return took + used;
    }

    private static boolean craft(Ledger l, Plan p, Recipe r, int batches, int depth, Set<String> causes) {
        for (In in : r.in()) {
            if (in.res() != null) {
                double amt = in.amount() * batches;
                if (l.get(in.res()) + 1e-6 < amt) {
                    causes.add("RES:" + in.res().name());
                    return false;
                }
                l.res.merge(in.res(), -amt, Double::sum);
                p.resources.merge(in.res(), amt, Double::sum);
            } else {
                int want = in.count() * batches;
                if (supplyAny(l, p, in.item(), want, depth + 1, causes) < want) return false;
            }
        }
        return true;
    }

    /** Ingrediente com alternativas: primeiro o que já está pronto em qualquer delas, depois fabrica pela primeira que tiver receita. */
    private static int supplyAny(Ledger l, Plan p, String spec, int n, int depth, Set<String> roots) {
        List<String> opts = options(spec);
        if (opts.size() == 1) return supply(l, p, opts.get(0), n, depth, roots);
        int got = 0;
        List<String> byStock = new ArrayList<>(opts);
        byStock.sort(Comparator.comparingInt((String o) -> -l.goods.getOrDefault(o, 0)));
        for (String o : byStock) {
            if (got >= n) break;
            int have = l.goods.getOrDefault(o, 0);
            int t = Math.min(have, n - got);
            if (t > 0) {
                l.goods.merge(o, -t, Integer::sum);
                p.goods.merge(o, t, Integer::sum);
                got += t;
            }
        }
        for (String o : opts) {
            if (got >= n) break;
            if (recipe(o) == null) continue;
            got += supply(l, p, o, n - got, depth, roots);
        }
        if (got < n) roots.add(spec);
        return got;
    }

    private static String describeCraft(Recipe r, int batches) {
        StringBuilder sb = new StringBuilder();
        for (In in : r.in()) {
            if (sb.length() > 0) sb.append(" + ");
            if (in.res() != null) sb.append(fmt(in.amount() * batches)).append(' ').append(in.res().display.toLowerCase());
            else sb.append(in.count() * batches).append(' ').append(in.item().equals("#wool") ? "lã" : name(options(in.item()).get(0)));
        }
        return sb + " → " + batches * r.out() + "× " + name(r.output()) + (r.smelt() ? " (forno)" : "");
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    // ------------------------------------------------------------------ custo de referência

    /** Custo em recursos do registro supondo tudo fabricado do zero; matérias-primas que não são recurso vão em {@code others}. */
    public static Map<ResourceType, Integer> rawCost(Map<String, Integer> bom, Map<String, Integer> others) {
        Ledger l = Ledger.unlimited();
        Plan p = plan(l, bom);
        Map<ResourceType, Integer> out = new EnumMap<>(ResourceType.class);
        for (var e : p.resources.entrySet()) {
            int v = (int) Math.ceil(e.getValue() - 1e-6);
            if (v > 0) out.put(e.getKey(), v);
        }
        if (others != null) {
            // o que não tem receita e não é recurso (areia, lã, livros, sementes...): quanto seria preciso, já descontadas as receitas
            Ledger l2 = Ledger.unlimited();
            Plan p2 = new Plan();
            for (var e : order(bom)) collectRaw(l2, p2, e.getKey(), e.getValue(), 0, others);
        }
        return out;
    }

    private static void collectRaw(Ledger l, Plan p, String item, int n, int depth, Map<String, Integer> others) {
        if (n <= 0) return;
        if (TreasurySystem.unit(item) != null) return;
        Recipe r = recipe(item);
        if (r == null || depth >= 5) {
            others.merge(item, n, Integer::sum);
            return;
        }
        int batches = (n + r.out() - 1) / r.out();
        for (In in : r.in())
            if (in.item() != null) {
                List<String> opts = options(in.item());
                String pick = opts.get(0);
                if (in.item().equals("#wool")) pick = "minecraft:white_wool";
                if (recipe(pick) != null && !pick.endsWith("_wool")) collectRaw(l, p, pick, in.count() * batches, depth + 1, others);
                else others.merge(in.item().equals("#wool") ? "minecraft:white_wool" : pick, in.count() * batches, Integer::sum);
            }
    }

    // ------------------------------------------------------------------ textos

    private static final Map<String, String> SPECIES = Map.ofEntries(Map.entry("oak", "carvalho"), Map.entry("spruce", "abeto"),
            Map.entry("birch", "bétula"), Map.entry("jungle", "selva"), Map.entry("acacia", "acácia"), Map.entry("dark_oak", "carvalho escuro"),
            Map.entry("mangrove", "mangue"), Map.entry("cherry", "cerejeira"), Map.entry("bamboo", "bambu"), Map.entry("crimson", "carmesim"),
            Map.entry("warped", "distorcida"), Map.entry("pale_oak", "carvalho pálido"));
    private static final Map<String, String> STONE_NAMES = Map.ofEntries(Map.entry("cobblestone", "pedregulho"), Map.entry("stone_brick", "tijolo de pedra"),
            Map.entry("stone", "pedra"), Map.entry("mossy_cobblestone", "pedregulho musgoso"), Map.entry("mossy_stone_brick", "tijolo de pedra musgoso"),
            Map.entry("brick", "tijolo"), Map.entry("sandstone", "arenito"), Map.entry("deepslate_brick", "tijolo de ardósia"),
            Map.entry("cobbled_deepslate", "ardósia"), Map.entry("andesite", "andesito"), Map.entry("diorite", "diorito"), Map.entry("granite", "granito"),
            Map.entry("blackstone", "pedra-negra"), Map.entry("mud_brick", "tijolo de barro"), Map.entry("quartz", "quartzo"),
            Map.entry("smooth_stone", "pedra lisa"), Map.entry("tuff", "tufo"));
    private static final Map<String, String> COLORS = Map.ofEntries(Map.entry("white", "branca"), Map.entry("orange", "laranja"),
            Map.entry("magenta", "magenta"), Map.entry("light_blue", "azul-clara"), Map.entry("yellow", "amarela"), Map.entry("lime", "verde-limão"),
            Map.entry("pink", "rosa"), Map.entry("gray", "cinza"), Map.entry("light_gray", "cinza-clara"), Map.entry("cyan", "ciano"),
            Map.entry("purple", "roxa"), Map.entry("blue", "azul"), Map.entry("brown", "marrom"), Map.entry("green", "verde"),
            Map.entry("red", "vermelha"), Map.entry("black", "preta"));
    private static final Map<String, String> NAMES = Map.ofEntries(Map.entry("glass_pane", "vidraça"), Map.entry("glass", "vidro"),
            Map.entry("torch", "tocha"), Map.entry("wall_torch", "tocha"), Map.entry("lantern", "lanterna"), Map.entry("chest", "baú"),
            Map.entry("barrel", "barril"), Map.entry("furnace", "fornalha"), Map.entry("smoker", "defumador"), Map.entry("blast_furnace", "alto-forno"),
            Map.entry("anvil", "bigorna"), Map.entry("bell", "sino"), Map.entry("bookshelf", "estante"), Map.entry("lectern", "atril"),
            Map.entry("hay_block", "fardo de feno"), Map.entry("ladder", "escada de mão"), Map.entry("crafting_table", "bancada"),
            Map.entry("flower_pot", "vaso"), Map.entry("bricks", "bloco de tijolos"), Map.entry("brick", "tijolo"), Map.entry("clay_ball", "argila"),
            Map.entry("sand", "areia"), Map.entry("red_sand", "areia vermelha"), Map.entry("gravel", "cascalho"), Map.entry("coal", "carvão"),
            Map.entry("charcoal", "carvão vegetal"), Map.entry("book", "livro"), Map.entry("paper", "papel"), Map.entry("leather", "couro"),
            Map.entry("sugar_cane", "cana-de-açúcar"), Map.entry("string", "linha"), Map.entry("wheat_seeds", "semente de trigo"),
            Map.entry("wheat", "trigo"), Map.entry("iron_bars", "grade de ferro"), Map.entry("chain", "corrente"), Map.entry("cauldron", "caldeirão"),
            Map.entry("stonecutter", "cortador de pedra"), Map.entry("grindstone", "rebolo"), Map.entry("smithing_table", "mesa de ferraria"),
            Map.entry("cartography_table", "mesa de cartografia"), Map.entry("composter", "composteira"), Map.entry("campfire", "fogueira"),
            Map.entry("stick", "graveto"), Map.entry("stone", "pedra"), Map.entry("cobblestone", "pedregulho"), Map.entry("stone_bricks", "tijolo de pedra"),
            Map.entry("mossy_cobblestone", "pedregulho musgoso"), Map.entry("chiseled_stone_bricks", "tijolo de pedra talhado"),
            Map.entry("cracked_stone_bricks", "tijolo de pedra rachado"), Map.entry("mossy_stone_bricks", "tijolo de pedra musgoso"),
            Map.entry("smooth_stone", "pedra lisa"), Map.entry("dirt", "terra"), Map.entry("iron_ingot", "barra de ferro"),
            Map.entry("gold_ingot", "barra de ouro"), Map.entry("white_wool", "lã branca"), Map.entry("terracotta", "terracota"),
            Map.entry("sandstone", "arenito"), Map.entry("cut_sandstone", "arenito cortado"), Map.entry("quartz_block", "bloco de quartzo"),
            Map.entry("iron_door", "porta de ferro"), Map.entry("armor_stand", "suporte de armadura"), Map.entry("scaffolding", "andaime"),
            Map.entry("water", "água"), Map.entry("dirt_path", "caminho de terra"));

    /** Nome em português para mensagens: "minecraft:spruce_stairs" → "escada de abeto". */
    public static String name(String id) {
        if (id == null) return "?";
        if (id.startsWith("RES:")) {
            ResourceType r = ResourceType.valueOf(id.substring(4));
            return r.display.toLowerCase();
        }
        if (id.equals("#wool")) return "lã";
        String p = id.substring(id.indexOf(':') + 1);
        String n = NAMES.get(p);
        if (n != null) return n;
        String w = woodOf(p);
        if (w != null) {
            String sp = SPECIES.get(w), form = p.substring(w.length() + 1);
            String f = switch (form) {
                case "planks" -> "tábua";
                case "log", "wood", "stem", "hyphae" -> "tora";
                case "stairs" -> "escada";
                case "slab" -> "laje";
                case "fence" -> "cerca";
                case "fence_gate" -> "portão";
                case "door" -> "porta";
                case "trapdoor" -> "alçapão";
                case "pressure_plate" -> "placa de pressão";
                case "button" -> "botão";
                case "sign" -> "placa";
                case "leaves" -> "folhas";
                case "sapling" -> "muda";
                default -> form.replace('_', ' ');
            };
            return f + " de " + sp;
        }
        if (p.startsWith("stripped_")) {
            String w2 = woodOf(p.substring(9));
            if (w2 != null) return "tora descascada de " + SPECIES.get(w2);
        }
        for (String form : new String[]{"stairs", "slab", "wall"})
            if (p.endsWith("_" + form)) {
                String base = p.substring(0, p.length() - form.length() - 1);
                String b = STONE_NAMES.getOrDefault(base, base.replace('_', ' '));
                return (form.equals("stairs") ? "escada de " : form.equals("slab") ? "laje de " : "muro de ") + b;
            }
        for (var c : COLORS.entrySet()) {
            String k = c.getKey();
            if (p.startsWith(k + "_") && !(k.equals("gray") && p.startsWith("light_gray")) && !(k.equals("blue") && p.startsWith("light_blue"))) {
                String rest = p.substring(k.length() + 1);
                String base = switch (rest) {
                    case "bed" -> "cama";
                    case "wool" -> "lã";
                    case "carpet" -> "tapete";
                    case "banner" -> "estandarte";
                    case "stained_glass" -> "vitral";
                    case "stained_glass_pane" -> "vitral fino";
                    case "terracotta" -> "terracota";
                    case "concrete" -> "concreto";
                    case "dye" -> "corante";
                    default -> null;
                };
                if (base != null) {
                    String color = c.getValue();
                    if (base.equals("tapete") || base.equals("estandarte") || base.equals("vitral") || base.equals("vitral fino")
                            || base.equals("concreto") || base.equals("corante")) color = masc(color);
                    return base + " " + color;
                }
            }
        }
        return com.kingdomsai.core.skill.ItemNames.display(id);
    }

    private static String masc(String f) {
        return switch (f) {
            case "branca" -> "branco";
            case "amarela" -> "amarelo";
            case "roxa" -> "roxo";
            case "vermelha" -> "vermelho";
            case "preta" -> "preto";
            case "azul-clara" -> "azul-claro";
            case "cinza-clara" -> "cinza-claro";
            default -> f;
        };
    }

    /** Onde conseguir uma matéria-prima que faltou (frase curta com a ordem a dar). */
    public static String howToGet(String root) {
        if (root.startsWith("RES:")) {
            return switch (ResourceType.valueOf(root.substring(4))) {
                case WOOD -> "madeira: lenhador («produza madeira»; marque o bosque com a Bandeira)";
                case STONE -> "pedra: minerador («trabalhe na mina» ou «vá coletar pedra»)";
                case IRON -> "ferro: minerador («minere ferro») e o ferreiro funde";
                case GOLD -> "ouro: impostos e mercadores (Tesouro)";
                case FOOD -> "comida/trigo: fazendeiros";
                case WEAPONS -> "armas: o ferreiro forja";
            };
        }
        String p = root.substring(root.indexOf(':') + 1);
        if (root.equals("#wool") || p.endsWith("_wool")) return "lã: tosquie ovelhas ou ponha lã nos baús do armazém";
        return switch (p) {
            case "sand", "red_sand", "sand|minecraft:red_sand" -> "areia: «vá buscar areia» (o forno faz vidro)";
            case "clay_ball", "clay" -> "argila: «vá buscar argila» (beira de rio; o forno faz tijolo)";
            case "coal", "charcoal", "coal|minecraft:charcoal" -> "carvão: minerador «minere carvão»";
            case "string" -> "linha: teias de aranha";
            case "book", "paper", "leather", "sugar_cane" -> "livros: papel (cana-de-açúcar) e couro — ou ponha livros no armazém";
            case "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "carrot", "potato" -> "sementes: o fazendeiro planta depois (ou ponha sementes no armazém)";
            case "flint" -> "pederneira: cascalho («vá buscar cascalho»)";
            case "bamboo" -> "bambu: selva";
            case "mud", "packed_mud" -> "barro: terra molhada";
            default -> p.endsWith("_dye") ? "corante: flores" : "ponha " + name(root) + " nos baús do armazém";
        };
    }

    /** "46× tábua de abeto, 3× vidraça" — no máximo {@code max} itens (o resto vira "+ N outros"). */
    public static String list(Map<String, Integer> items, int max) {
        List<String> parts = new ArrayList<>();
        int shown = 0;
        List<Map.Entry<String, Integer>> es = new ArrayList<>(items.entrySet());
        es.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> -e.getValue()));
        for (var e : es) {
            if (e.getValue() <= 0) continue;
            if (shown++ >= max) {
                parts.add("+ " + (es.size() - max) + " outros");
                break;
            }
            parts.add(e.getValue() + "× " + name(e.getKey()));
        }
        return parts.isEmpty() ? "nada" : String.join(", ", parts);
    }
}
