package com.kingdomsai.core.construction;

import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint.Category;
import com.kingdomsai.core.construction.Blueprint.Facing;
import com.kingdomsai.core.construction.Blueprint.Placement;
import com.kingdomsai.core.kingdom.ResourceType;

import java.util.*;

/**
 * Plantas paramétricas: a IA (ou o jogador) escolhe parâmetros validados — tipo, tamanho, andares,
 * telhado, materiais — e o motor gera uma planta que SEMPRE é construível (porta, piso, telhado fechado).
 * A IA nunca escreve blocos soltos.
 */
public final class ParametricBlueprints {
    private ParametricBlueprints() {}

    public enum Kind {
        HOUSE("Casa", Category.HOUSING), BARRACKS("Quartel", Category.MILITARY), SMITHY("Forja", Category.INDUSTRY),
        STORAGE("Armazém", Category.STORAGE), HALL("Salão", Category.CIVIC), TOWER("Torre", Category.MILITARY),
        CHAPEL("Capela", Category.CIVIC), TAVERN("Taverna", Category.CIVIC);
        public final String display;
        public final Category category;

        Kind(String display, Category category) {
            this.display = display;
            this.category = category;
        }

        public static Kind parse(String s) {
            String n = Text.norm(s);
            if (n.isEmpty()) return null;
            if (n.startsWith("cas") || n.startsWith("hous") || n.startsWith("morad") || n.startsWith("resid")) return HOUSE;
            if (n.startsWith("quart") || n.startsWith("barr") || n.startsWith("casern")) return BARRACKS;
            if (n.startsWith("forj") || n.startsWith("smith") || n.startsWith("ferrar")) return SMITHY;
            if (n.startsWith("armaz") || n.startsWith("celeir") || n.startsWith("depos") || n.startsWith("stor")) return STORAGE;
            if (n.startsWith("sal") || n.startsWith("hall") || n.startsWith("prefeit") || n.startsWith("pac") || n.startsWith("castel")) return HALL;
            if (n.startsWith("torr") || n.startsWith("tow") || n.startsWith("vigia")) return TOWER;
            if (n.startsWith("capel") || n.startsWith("templ") || n.startsWith("igrej") || n.startsWith("chap")) return CHAPEL;
            if (n.startsWith("tavern") || n.startsWith("bar") || n.startsWith("estalag")) return TAVERN;
            for (Kind k : values()) if (k.name().equalsIgnoreCase(n)) return k;
            return null;
        }
    }

    public enum Roof {
        FLAT("plano"), PYRAMID("pirâmide"), GABLE("duas águas");
        public final String display;

        Roof(String display) {
            this.display = display;
        }

        public static Roof parse(String s) {
            String n = Text.norm(s);
            if (n.contains("plan") || n.contains("flat") || n.contains("laje")) return FLAT;
            if (n.contains("pira") || n.contains("pyra") || n.contains("quatro")) return PYRAMID;
            if (n.contains("duas") || n.contains("gable") || n.contains("agua") || n.contains("2")) return GABLE;
            for (Roof r : values()) if (r.name().equalsIgnoreCase(n)) return r;
            return null;
        }
    }

    /** Materiais permitidos (whitelist). wall, pilar, escada, porta, é madeira? */
    public record Mat(String key, String display, String wall, String pillar, String stairs, String door, boolean wood) {}

    public static final List<Mat> MATERIALS = List.of(
            new Mat("oak", "carvalho", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:oak_stairs", "minecraft:oak_door", true),
            new Mat("spruce", "abeto", "minecraft:spruce_planks", "minecraft:spruce_log", "minecraft:spruce_stairs", "minecraft:spruce_door", true),
            new Mat("birch", "bétula", "minecraft:birch_planks", "minecraft:birch_log", "minecraft:birch_stairs", "minecraft:birch_door", true),
            new Mat("dark_oak", "carvalho escuro", "minecraft:dark_oak_planks", "minecraft:dark_oak_log", "minecraft:dark_oak_stairs", "minecraft:dark_oak_door", true),
            new Mat("acacia", "acácia", "minecraft:acacia_planks", "minecraft:acacia_log", "minecraft:acacia_stairs", "minecraft:acacia_door", true),
            new Mat("jungle", "selva", "minecraft:jungle_planks", "minecraft:jungle_log", "minecraft:jungle_stairs", "minecraft:jungle_door", true),
            new Mat("cherry", "cerejeira", "minecraft:cherry_planks", "minecraft:cherry_log", "minecraft:cherry_stairs", "minecraft:cherry_door", true),
            new Mat("mangrove", "mangue", "minecraft:mangrove_planks", "minecraft:mangrove_log", "minecraft:mangrove_stairs", "minecraft:mangrove_door", true),
            new Mat("stone", "pedra", "minecraft:stone_bricks", "minecraft:chiseled_stone_bricks", "minecraft:stone_brick_stairs", "minecraft:spruce_door", false),
            new Mat("cobblestone", "pedregulho", "minecraft:cobblestone", "minecraft:stripped_spruce_log", "minecraft:cobblestone_stairs", "minecraft:oak_door", false),
            new Mat("brick", "tijolo", "minecraft:bricks", "minecraft:stripped_dark_oak_log", "minecraft:brick_stairs", "minecraft:dark_oak_door", false),
            new Mat("sandstone", "arenito", "minecraft:sandstone", "minecraft:cut_sandstone", "minecraft:sandstone_stairs", "minecraft:jungle_door", false),
            new Mat("deepslate", "ardósia", "minecraft:deepslate_bricks", "minecraft:polished_deepslate", "minecraft:deepslate_brick_stairs", "minecraft:dark_oak_door", false),
            new Mat("mud", "barro", "minecraft:mud_bricks", "minecraft:stripped_oak_log", "minecraft:mud_brick_stairs", "minecraft:oak_door", false),
            new Mat("quartz", "quartzo", "minecraft:quartz_block", "minecraft:quartz_pillar", "minecraft:quartz_stairs", "minecraft:birch_door", false));

    public static Mat material(String s) {
        if (s == null) return null;
        String n = Text.norm(s).replace(' ', '_');
        for (Mat m : MATERIALS) if (m.key.equals(n) || Text.norm(m.display).replace(' ', '_').equals(n)) return m;
        if (n.equals("madeira") || n.equals("wood")) return MATERIALS.get(0);
        if (n.startsWith("pedr") && !n.startsWith("pedreg")) return mat("stone");
        if (n.startsWith("pedreg") || n.startsWith("cobble")) return mat("cobblestone");
        if (n.startsWith("tijol") || n.startsWith("brick")) return mat("brick");
        if (n.startsWith("pinh") || n.startsWith("abet")) return mat("spruce");
        if (n.startsWith("betul") || n.startsWith("birch")) return mat("birch");
        if (n.contains("escur")) return mat("dark_oak");
        if (n.startsWith("arenit") || n.startsWith("sand") || n.startsWith("areia")) return mat("sandstone");
        if (n.startsWith("ardos") || n.startsWith("deeps")) return mat("deepslate");
        if (n.startsWith("barr") || n.startsWith("mud")) return mat("mud");
        if (n.startsWith("quartz")) return mat("quartz");
        if (n.startsWith("cerej")) return mat("cherry");
        return null;
    }

    private static Mat mat(String key) {
        for (Mat m : MATERIALS) if (m.key.equals(key)) return m;
        return MATERIALS.get(0);
    }

    /** Parâmetros validados. Use {@link #spec(Map)} para montar a partir de texto (CLI/LLM). */
    public record Spec(String name, Kind kind, int width, int depth, int floors, Roof roof, String wall, String roofMaterial, boolean chimney) {}

    public static final int MIN_SIZE = 5, MAX_SIZE = 15, MAX_FLOORS = 3;

    /**
     * Monta e valida o Spec a partir de parâmetros (aceita pt/en: tipo|kind, largura|width, profundidade|depth,
     * andares|floors, telhado|roof, parede|wall, telhado_material|roof_material, chamine|chimney, nome|name).
     *
     * @throws IllegalArgumentException com mensagem em português se algo estiver fora do permitido.
     */
    public static Spec spec(Map<String, String> p) {
        Kind kind = Kind.parse(first(p, "tipo", "kind", "type"));
        if (kind == null) kind = Kind.HOUSE;
        int width = intOf(first(p, "largura", "width", "w"), kind == Kind.TOWER ? 5 : kind == Kind.HALL ? 11 : 7);
        int depth = intOf(first(p, "profundidade", "depth", "d", "comprimento"), kind == Kind.TOWER ? 5 : kind == Kind.HALL ? 9 : 7);
        int floors = intOf(first(p, "andares", "floors", "pisos"), kind == Kind.TOWER ? 3 : 1);
        if (width < MIN_SIZE || width > MAX_SIZE || depth < MIN_SIZE || depth > MAX_SIZE)
            throw new IllegalArgumentException("Largura e profundidade devem ficar entre " + MIN_SIZE + " e " + MAX_SIZE + ".");
        if (floors < 1 || floors > MAX_FLOORS) throw new IllegalArgumentException("Andares: 1 a " + MAX_FLOORS + ".");
        String roofText = first(p, "telhado", "roof");
        Roof roof = roofText == null ? (kind == Kind.TOWER ? Roof.FLAT : Roof.GABLE) : Roof.parse(roofText);
        if (roof == null) throw new IllegalArgumentException("Telhado desconhecido: " + roofText + " (plano, piramide, duas_aguas).");
        String wallText = first(p, "parede", "wall", "material");
        Mat wall = wallText == null ? mat(kind == Kind.TOWER || kind == Kind.BARRACKS ? "stone" : "oak") : material(wallText);
        if (wall == null) throw new IllegalArgumentException("Material de parede desconhecido: " + wallText + ". Opções: " + materialList() + ".");
        String roofMatText = first(p, "telhado_material", "roof_material", "cobertura");
        Mat roofMat = roofMatText == null ? mat(wall.wood ? "dark_oak" : "spruce") : material(roofMatText);
        if (roofMat == null) throw new IllegalArgumentException("Material de telhado desconhecido: " + roofMatText + ".");
        boolean chimney = "true".equalsIgnoreCase(first(p, "chamine", "chimney")) || "sim".equalsIgnoreCase(first(p, "chamine", "chimney"));
        String name = first(p, "nome", "name");
        if (name == null || name.isBlank())
            name = kind.display + " " + width + "x" + depth + (floors > 1 ? " (" + floors + " andares)" : "") + " de " + wall.display;
        name = Text.truncate(name.replaceAll("[<>{}\\[\\]=§]", "").trim(), 40);
        return new Spec(name, kind, width, depth, floors, roof, wall.key, roofMat.key, chimney);
    }

    public static String materialList() {
        StringBuilder sb = new StringBuilder();
        for (Mat m : MATERIALS) sb.append(m.display).append(", ");
        return sb.substring(0, sb.length() - 2);
    }

    private static String first(Map<String, String> p, String... keys) {
        for (String k : keys) {
            String v = p.get(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static int intOf(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String idFor(Spec s) {
        String slug = Text.norm(s.name()).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return "custom_" + (slug.isEmpty() ? "planta" : Text.truncate(slug, 40).replace("…", ""));
    }

    public static Blueprint generate(Spec s) {
        Mat wall = mat(s.wall()), roofMat = mat(s.roofMaterial());
        int w = s.width(), d = s.depth(), floors = s.floors();
        int fh = 4;
        int wallH = floors * fh - 1;
        int roofLayers = switch (s.roof()) {
            case FLAT -> 2;
            case PYRAMID -> (Math.min(w, d) + 3) / 2;
            case GABLE -> (d + 3) / 2;
        };
        int height = wallH + roofLayers + 2;
        List<Placement> p = new ArrayList<>();
        // 1) limpar terreno
        for (int y = 0; y < height; y++)
            for (int x = -1; x <= w; x++)
                for (int z = -1; z <= d; z++) p.add(new Placement(x, y, z, Material.AIR, Facing.NONE));
        // 2) fundação e piso
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                p.add(new Placement(x, -1, z, edge ? Material.FOUNDATION : Material.FLOOR, Facing.NONE));
            }
        int doorX = w / 2;
        int ladderX = 1, ladderZ = d - 2;
        // 3) paredes, janelas e pisos intermediários — camada por camada (a casa "sobe")
        for (int y = 0; y < wallH; y++) {
            boolean windowRow = y % fh == 1;
            for (int x = 0; x < w; x++)
                for (int z = 0; z < d; z++) {
                    boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                    if (!edgeX && !edgeZ) {
                        if (y % fh == fh - 1 && !(x == ladderX && z == ladderZ))
                            p.add(new Placement(x, y, z, Material.FLOOR, Facing.NONE)); // piso do andar de cima
                        continue;
                    }
                    if (z == 0 && x == doorX && y < 2) continue;
                    Material m;
                    if (edgeX && edgeZ) m = Material.PILLAR;
                    else if (y % fh == fh - 1) m = Material.PILLAR; // viga entre andares
                    else if (windowRow && isWindow(x, z, w, d, doorX, y)) m = Material.WINDOW;
                    else m = Material.WALL;
                    p.add(new Placement(x, y, z, m, Facing.NONE));
                }
        }
        // 4) escada de mão entre andares
        if (floors > 1)
            for (int y = 0; y < wallH; y++) p.add(new Placement(ladderX, y, ladderZ, Material.LADDER, Facing.EAST));
        // 5) telhado
        switch (s.roof()) {
            case FLAT -> {
                for (int x = -1; x <= w; x++)
                    for (int z = -1; z <= d; z++) p.add(new Placement(x, wallH, z, Material.ROOF, Facing.NONE));
                for (int x = -1; x <= w; x++)
                    for (int z = -1; z <= d; z++) {
                        boolean ring = x == -1 || x == w || z == -1 || z == d;
                        if (ring && (x + z) % 2 == 0) p.add(new Placement(x, wallH + 1, z, Material.WALL, Facing.NONE)); // ameias
                    }
            }
            case PYRAMID -> {
                for (int layer = 0; layer < roofLayers; layer++) {
                    int x0 = -1 + layer, x1 = w - layer, z0 = -1 + layer, z1 = d - layer;
                    if (x0 > x1 || z0 > z1) break;
                    for (int x = x0; x <= x1; x++)
                        for (int z = z0; z <= z1; z++) {
                            boolean ring = x == x0 || x == x1 || z == z0 || z == z1;
                            if (!ring && layer < roofLayers - 1 && layer > 0) continue;
                            p.add(new Placement(x, wallH + layer, z, layer == 0 ? Material.ROOF_EDGE : Material.ROOF, Facing.NONE));
                        }
                }
            }
            case GABLE -> {
                for (int layer = 0; ; layer++) {
                    int zlo = -1 + layer, zhi = d - layer;
                    if (zlo > zhi) break;
                    int y = wallH + layer;
                    for (int x = -1; x <= w; x++) {
                        if (zlo == zhi) p.add(new Placement(x, y, zlo, Material.ROOF, Facing.NONE));
                        else {
                            p.add(new Placement(x, y, zlo, Material.ROOF_STAIR, Facing.SOUTH));
                            p.add(new Placement(x, y, zhi, Material.ROOF_STAIR, Facing.NORTH));
                        }
                    }
                    for (int z = zlo + 1; z <= zhi - 1; z++) {
                        if (z < 0 || z > d - 1) continue;
                        p.add(new Placement(0, y, z, Material.WALL, Facing.NONE)); // oitão
                        p.add(new Placement(w - 1, y, z, Material.WALL, Facing.NONE));
                        if (layer == 0) for (int x = 1; x < w - 1; x++) p.add(new Placement(x, y, z, Material.FLOOR, Facing.NONE)); // forro
                    }
                }
            }
        }
        // 6) chaminé
        if (s.chimney())
            for (int y = 0; y < height - 1; y++) p.add(new Placement(w - 2, y, d - 2, Material.CHIMNEY, Facing.NONE));
        // 7) porta
        p.add(new Placement(doorX, 0, 0, Material.DOOR_LOWER, Facing.NORTH));
        p.add(new Placement(doorX, 1, 0, Material.DOOR_UPPER, Facing.NORTH));
        p.add(new Placement(doorX, -1, -1, Material.PATH, Facing.NONE));
        // 8) interior por andar
        int beds = 0;
        for (int f = 0; f < floors; f++) {
            int y0 = f * fh;
            p.add(new Placement(w - 2, y0, d / 2, Material.LIGHT, Facing.NONE));
            if (w >= 7) p.add(new Placement(2, y0, 1, Material.LIGHT, Facing.NONE));
            boolean sleepFloor = switch (s.kind()) {
                case HOUSE, TAVERN -> floors == 1 || f > 0;
                case BARRACKS -> true;
                case TOWER -> f == floors - 1;
                default -> false;
            };
            if (sleepFloor) {
                int max = s.kind() == Kind.BARRACKS ? (w - 2) / 2 : s.kind() == Kind.TOWER ? 1 : Math.max(1, (w - 3) / 3);
                int placed = 0;
                for (int bx = 2; bx < w - 1 && placed < max; bx += 2) {
                    if (f == 0 && Math.abs(bx - doorX) < 1) continue;
                    if (bx == ladderX) continue;
                    if (d - 3 < 1) break;
                    if (bx == w - 2 && d - 3 <= d / 2 && d - 2 >= d / 2) continue;
                    p.add(new Placement(bx, y0, d - 3, Material.BED_FOOT, Facing.SOUTH));
                    p.add(new Placement(bx, y0, d - 2, Material.BED_HEAD, Facing.SOUTH));
                    placed++;
                }
                beds += placed;
            }
            if (f == 0) {
                switch (s.kind()) {
                    case SMITHY -> {
                        p.add(new Placement(1, 0, 1, Material.FURNACE, Facing.SOUTH));
                        p.add(new Placement(w - 2, 0, 1, Material.ANVIL, Facing.NONE));
                    }
                    case STORAGE -> {
                        for (int x = 1; x < w - 1; x++)
                            if (x != doorX) p.add(new Placement(x, 0, 1, x % 2 == 0 ? Material.CHEST : Material.BARREL, Facing.SOUTH));
                    }
                    case HALL, CHAPEL -> p.add(new Placement(w / 2, 0, d - 2, Material.BELL, Facing.NONE));
                    case TAVERN -> {
                        p.add(new Placement(w - 2, 0, 1, Material.BARREL, Facing.SOUTH));
                        p.add(new Placement(w - 3, 0, 1, Material.BARREL, Facing.SOUTH));
                        p.add(new Placement(1, 0, 1, Material.CRAFTING, Facing.NONE));
                    }
                    default -> {
                        p.add(new Placement(w - 2, 0, 1, Material.CRAFTING, Facing.NONE));
                        if (doorX != 1) p.add(new Placement(1, 0, 1, Material.CHEST, Facing.SOUTH));
                    }
                }
            }
        }
        dedupe(p);

        Map<Material, String> mats = new EnumMap<>(Material.class);
        mats.put(Material.WALL, wall.wall);
        mats.put(Material.PILLAR, wall.pillar);
        mats.put(Material.FLOOR, wall.wood ? wall.wall : "minecraft:spruce_planks");
        mats.put(Material.FOUNDATION, wall.wood ? "minecraft:cobblestone" : wall.wall);
        mats.put(Material.ROOF, roofMat.wall);
        mats.put(Material.ROOF_EDGE, roofMat.wall);
        mats.put(Material.ROOF_STAIR, roofMat.stairs);
        mats.put(Material.DOOR_LOWER, wall.door);
        mats.put(Material.DOOR_UPPER, wall.door);
        mats.put(Material.CHIMNEY, "minecraft:bricks");

        int wood = 0, stone = 0;
        for (Placement pl : p) {
            String block = mats.get(pl.material());
            if (block == null) {
                if (pl.material() == Material.CHEST || pl.material() == Material.BARREL || pl.material() == Material.CRAFTING
                        || pl.material() == Material.LADDER || pl.material() == Material.FENCE) wood++;
                continue;
            }
            if (isWoodBlock(block)) wood++;
            else stone++;
        }
        Map<ResourceType, Integer> cost = new EnumMap<>(ResourceType.class);
        cost.put(ResourceType.WOOD, Math.max(10, (int) Math.ceil(wood / 4.0)));
        cost.put(ResourceType.STONE, Math.max(5, (int) Math.ceil(stone / 4.0)));
        if (s.kind() == Kind.SMITHY || s.kind() == Kind.BARRACKS) cost.put(ResourceType.IRON, 5);
        int housing = s.kind() == Kind.HALL ? 2 : beds;
        return new Blueprint(idFor(s), s.name(), s.kind().category, w, height, d, Collections.unmodifiableMap(cost), housing,
                List.copyOf(p), "param", Collections.unmodifiableMap(mats));
    }

    public static boolean isWoodBlock(String id) {
        return id.contains("planks") || id.contains("_log") || id.contains("_wood") || id.contains("door")
                || (id.contains("stairs") && (id.contains("oak") || id.contains("spruce") || id.contains("birch") || id.contains("acacia")
                || id.contains("jungle") || id.contains("cherry") || id.contains("mangrove")));
    }

    private static boolean isWindow(int x, int z, int w, int d, int doorX, int y) {
        if (z == 0) return x != doorX && Math.abs(x - doorX) >= 2 && x % 2 == doorX % 2;
        if (z == d - 1) return x > 1 && x < w - 2 && x % 2 == 0;
        return z > 1 && z < d - 2 && z % 2 == 0;
    }

    private static void dedupe(List<Placement> p) {
        Map<Long, Integer> last = new HashMap<>();
        for (int i = 0; i < p.size(); i++) last.put(key(p.get(i)), i);
        List<Placement> out = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) if (last.get(key(p.get(i))) == i) out.add(p.get(i));
        p.clear();
        p.addAll(out);
    }

    private static long key(Placement pl) {
        return ((long) (pl.x() + 512) << 40) | ((long) (pl.y() + 512) << 20) | (pl.z() + 512);
    }

    /**
     * Converte blocos capturados do mundo (ou de um .nbt) numa planta: limpa a área, depois coloca
     * camada por camada, com blocos frágeis (tochas, portas, camas) por último em cada camada.
     *
     * @param blocks x,y,z relativos (y = 0 é a camada do piso/fundação) → estado do bloco ("minecraft:air" é ignorado).
     */
    public static Blueprint fromBlocks(String id, String name, String source, int sx, int sy, int sz, Map<int[], String> blocks) {
        List<Placement> p = new ArrayList<>();
        for (int y = 0; y < sy; y++)
            for (int x = -1; x <= sx; x++)
                for (int z = -1; z <= sz; z++) p.add(new Placement(x, y - 1, z, Material.AIR, Facing.NONE));
        List<Map.Entry<int[], String>> list = new ArrayList<>(blocks.entrySet());
        list.removeIf(e -> e.getValue() == null || e.getValue().startsWith("minecraft:air") || e.getValue().startsWith("minecraft:cave_air")
                || e.getValue().startsWith("minecraft:void_air") || e.getValue().startsWith("minecraft:structure_void"));
        list.sort(Comparator.<Map.Entry<int[], String>>comparingInt(e -> e.getKey()[1])
                .thenComparing(e -> isFragile(e.getValue()))
                .thenComparingInt(e -> e.getKey()[0]).thenComparingInt(e -> e.getKey()[2]));
        int wood = 0, stone = 0;
        for (var e : list) {
            int[] k = e.getKey();
            p.add(new Placement(k[0], k[1] - 1, k[2], Material.RAW, Facing.NONE, e.getValue()));
            if (isWoodBlock(e.getValue())) wood++;
            else stone++;
        }
        dedupe(p);
        Map<ResourceType, Integer> cost = new EnumMap<>(ResourceType.class);
        cost.put(ResourceType.WOOD, Math.max(5, (int) Math.ceil(wood / 4.0)));
        cost.put(ResourceType.STONE, Math.max(5, (int) Math.ceil(stone / 4.0)));
        int beds = 0;
        for (var e : list) if (e.getValue().contains("_bed") && e.getValue().contains("part=head")) beds++;
        return new Blueprint(id, name, beds > 0 ? Category.HOUSING : Category.CIVIC, sx, sy, sz, Collections.unmodifiableMap(cost), beds,
                List.copyOf(p), source, Map.of());
    }

    private static boolean isFragile(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        return n.contains("torch") || n.contains("door") || n.contains("_bed") || n.contains("lantern") || n.contains("carpet")
                || n.contains("rail") || n.contains("button") || n.contains("lever") || n.contains("sign") || n.contains("flower")
                || n.contains("ladder") || n.contains("vine") || n.contains("pressure_plate") || n.contains("banner") || n.contains("candle")
                || n.contains("painting") || n.contains("item_frame") || n.contains("bell") || n.contains("water") || n.contains("lava");
    }
}
