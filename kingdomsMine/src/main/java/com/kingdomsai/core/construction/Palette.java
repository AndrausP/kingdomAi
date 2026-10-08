package com.kingdomsai.core.construction;

import java.util.Locale;
import java.util.Set;

/**
 * "Uma casa significa esses blocos" — a fonte única da tradução material abstrato → bloco do Minecraft (como texto).
 * O Core usa para saber QUAIS itens cada obra consome; o adaptador usa para colocar o bloco. Assim o que se cobra
 * do estoque é exatamente o que aparece no mundo.
 *
 * <p>Plantas desenhadas usam templates resolvidos pela variante (estilo) da obra: {plank}, {frame}, {roof}, {trim},
 * {stone}, {stone_s}, {wool}. Ex.: "minecraft:{roof}_stairs[facing=north]" → "minecraft:spruce_stairs[facing=north]".
 */
public final class Palette {
    private Palette() {}

    // Padrões das plantas antigas (versão 0): iguais ao MaterialPalette original para não mudar casas já construídas.
    private static final String[] WALLS = {"minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks"};
    private static final String[] PILLARS = {"minecraft:oak_log", "minecraft:spruce_log", "minecraft:stripped_dark_oak_log"};
    private static final String[] ROOFS = {"minecraft:dark_oak_planks", "minecraft:spruce_planks", "minecraft:bricks"};
    private static final String[] ROOF_STAIRS = {"minecraft:dark_oak_stairs", "minecraft:spruce_stairs", "minecraft:brick_stairs"};
    private static final String[] FOUNDATIONS = {"minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:mossy_cobblestone"};
    private static final String[] DOORS = {"minecraft:oak_door", "minecraft:spruce_door", "minecraft:birch_door"};
    private static final String[] BEDS = {"minecraft:red_bed", "minecraft:blue_bed", "minecraft:green_bed"};

    /** Estilo de uma planta desenhada: madeira das paredes, das vigas, do telhado, dos detalhes, pedra e cor dos tecidos. */
    public record Style(String name, String plank, String frame, String roof, String trim, String stone, String stoneS, String wool) {}

    public static final Style[] STYLES = {
            new Style("carvalho e abeto", "oak", "spruce", "dark_oak", "spruce", "cobblestone", "cobblestone", "red"),
            new Style("abeto e pedra", "spruce", "dark_oak", "spruce", "dark_oak", "stone_bricks", "stone_brick", "blue"),
            new Style("bétula e carvalho", "birch", "oak", "dark_oak", "oak", "cobblestone", "cobblestone", "green")};

    public static Style style(int variant) {
        return STYLES[Math.floorMod(variant, STYLES.length)];
    }

    /** Bloco (com propriedades, se houver) que esta colocação põe no mundo. AIR → "minecraft:air". */
    public static String block(Blueprint.Placement p, Blueprint bp, int variant) {
        Material m = p.material();
        if (m == Material.RAW) return resolve(p.block(), variant);
        String override = bp == null || bp.materials() == null ? null : bp.materials().get(m);
        if (override != null && !override.isBlank()) return resolve(override, variant);
        int v = Math.floorMod(variant, 3);
        return switch (m) {
            case AIR, RAW -> "minecraft:air";
            case FOUNDATION -> FOUNDATIONS[v];
            case FLOOR -> WALLS[(v + 1) % 3];
            case WALL -> WALLS[v];
            case PILLAR -> PILLARS[v];
            case ROOF, ROOF_EDGE -> ROOFS[v];
            case ROOF_STAIR -> ROOF_STAIRS[v];
            case WINDOW -> "minecraft:glass_pane";
            case DOOR_LOWER, DOOR_UPPER -> DOORS[v];
            case LIGHT -> "minecraft:torch";
            case BED_FOOT, BED_HEAD -> BEDS[v];
            case CHEST -> "minecraft:chest";
            case BARREL -> "minecraft:barrel";
            case CRAFTING -> "minecraft:crafting_table";
            case FURNACE -> "minecraft:furnace";
            case ANVIL -> "minecraft:anvil";
            case FARMLAND -> "minecraft:farmland";
            case CROP -> "minecraft:wheat";
            case WATER -> "minecraft:water";
            case FENCE -> "minecraft:oak_fence";
            case PATH -> "minecraft:dirt_path";
            case BELL -> "minecraft:bell";
            case HAY -> "minecraft:hay_block";
            case BANNER -> "minecraft:white_banner";
            case LADDER -> "minecraft:ladder";
            case CHIMNEY -> "minecraft:bricks";
            case BOOKSHELF -> "minecraft:bookshelf";
            case LECTERN -> "minecraft:lectern";
        };
    }

    /** Resolve os templates de estilo ({plank}, {roof}...) pela variante da obra. */
    public static String resolve(String s, int variant) {
        if (s == null || s.indexOf('{') < 0) return s;
        Style st = style(variant);
        return s.replace("{plank}", st.plank()).replace("{frame}", st.frame()).replace("{roof}", st.roof()).replace("{trim}", st.trim())
                .replace("{stone_s}", st.stoneS()).replace("{stone}", st.stone()).replace("{wool}", st.wool());
    }

    /** "minecraft:oak_stairs[facing=north]" → "minecraft:oak_stairs". */
    public static String id(String blockState) {
        if (blockState == null) return null;
        int i = blockState.indexOf('[');
        String id = (i < 0 ? blockState : blockState.substring(0, i)).trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** Valor de uma propriedade do estado ("facing" em "...[facing=north,half=top]") ou null. */
    public static String prop(String blockState, String key) {
        if (blockState == null) return null;
        int a = blockState.indexOf('['), b = blockState.lastIndexOf(']');
        if (a < 0 || b < a) return null;
        for (String kv : blockState.substring(a + 1, b).split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).trim().equals(key)) return kv.substring(eq + 1).trim();
        }
        return null;
    }

    /** Item que o bloco consome (e quantos). null = não consome nada (ar, água, terra do próprio terreno, metade de cima de porta...). */
    public record Need(String item, int count) {}

    /** Vegetação e terra natural: não se cobra (nasce sozinha / sai do próprio terreno). */
    private static final Set<String> FREE = Set.of("air", "cave_air", "void_air", "water", "bubble_column", "fire", "soul_fire", "moving_piston",
            "piston_head", "short_grass", "grass", "tall_grass", "fern", "large_fern", "dead_bush", "seagrass", "tall_seagrass", "vine",
            "glow_lichen", "snow", "dirt", "coarse_dirt", "grass_block", "dirt_path", "farmland", "podzol", "mycelium", "rooted_dirt",
            "big_dripleaf_stem", "structure_void", "light", "kelp_plant", "frosted_ice", "nether_portal", "end_portal", "end_gateway", "lava");

    public static Need itemFor(String blockState) {
        String id = id(blockState);
        if (id == null) return null;
        String path = id.substring(id.indexOf(':') + 1);
        if (FREE.contains(path)) return null;
        // metade de cima / cabeceira: o item é um só (porta, cama, flor alta)
        if ("upper".equals(prop(blockState, "half")) && (path.endsWith("_door") || isTallPlant(path))) return null;
        if ("head".equals(prop(blockState, "part")) && path.endsWith("_bed")) return null;
        if (path.endsWith("_slab") && "double".equals(prop(blockState, "type"))) return new Need(id, 2);
        String ns = id.substring(0, id.indexOf(':') + 1);
        String item = switch (path) {
            case "wall_torch" -> "torch";
            case "soul_wall_torch" -> "soul_torch";
            case "redstone_wall_torch" -> "redstone_torch";
            case "wheat" -> "wheat_seeds";
            case "carrots" -> "carrot";
            case "potatoes" -> "potato";
            case "beetroots" -> "beetroot_seeds";
            case "melon_stem", "attached_melon_stem" -> "melon_seeds";
            case "pumpkin_stem", "attached_pumpkin_stem" -> "pumpkin_seeds";
            case "sweet_berry_bush" -> "sweet_berries";
            case "cocoa" -> "cocoa_beans";
            case "redstone_wire" -> "redstone";
            case "tripwire" -> "string";
            case "bamboo_sapling" -> "bamboo";
            case "cave_vines", "cave_vines_plant" -> "glow_berries";
            case "twisting_vines_plant" -> "twisting_vines";
            case "weeping_vines_plant" -> "weeping_vines";
            case "powder_snow" -> "powder_snow_bucket";
            default -> null;
        };
        if (item != null) return new Need(ns + item, 1);
        if (path.startsWith("potted_")) return new Need("minecraft:flower_pot", 1);
        if (path.endsWith("_wall_sign")) return new Need(ns + path.replace("_wall_sign", "_sign"), 1);
        if (path.endsWith("_wall_hanging_sign")) return new Need(ns + path.replace("_wall_hanging_sign", "_hanging_sign"), 1);
        if (path.endsWith("_wall_banner")) return new Need(ns + path.replace("_wall_banner", "_banner"), 1);
        if (path.endsWith("_wall_head")) return new Need(ns + path.replace("_wall_head", "_head"), 1);
        if (path.endsWith("_wall_skull")) return new Need(ns + path.replace("_wall_skull", "_skull"), 1);
        if (path.startsWith("infested_")) return new Need(ns + path.substring(9), 1);
        return new Need(id, 1);
    }

    private static boolean isTallPlant(String path) {
        return path.equals("sunflower") || path.equals("lilac") || path.equals("rose_bush") || path.equals("peony") || path.equals("tall_grass")
                || path.equals("large_fern") || path.equals("pitcher_plant") || path.equals("small_dripleaf") || path.equals("tall_seagrass");
    }
}
