package com.kingdomsai.core.construction;

import com.kingdomsai.core.construction.Blueprint.Category;
import com.kingdomsai.core.construction.Blueprint.Facing;
import com.kingdomsai.core.construction.Blueprint.Placement;

import java.util.*;

/**
 * Plantas desenhadas (versão 1 do catálogo): casas de enxaimel, sobrado de pedra e madeira, armazém, forja, quartel,
 * salão real, biblioteca, capela, taverna, torre de vigia, poço, celeiro e mercado.
 *
 * <p>Convenções (as mesmas das plantas antigas): y = 0 é o piso onde se pisa, y = -1 a fundação; a porta (ou a entrada)
 * fica na frente, em x = largura/2, z = 0. Os blocos usam os templates de estilo do {@link Palette} ({plank}, {frame},
 * {roof}, {trim}, {stone}, {stone_s}, {wool}), então cada obra sai num dos três estilos — e a lista de materiais
 * ({@link BillOfMaterials}) sabe exatamente o que cada uma gasta.
 */
public final class BlueprintDesigns {
    private BlueprintDesigns() {}

    // ------------------------------------------------------------------ peças

    /** Especificação de um bloco: material abstrato (com direção) ou bloco explícito (com templates de estilo). */
    record S(Material m, Facing f, String raw, boolean fragile) {}

    static S raw(String block) {
        String b = block.contains(":") ? block : "minecraft:" + block;
        return new S(Material.RAW, Facing.NONE, b, fragileBlock(b));
    }

    static S mat(Material m) {
        return new S(m, Facing.NONE, null, fragileMat(m));
    }

    static S mat(Material m, Facing f) {
        return new S(m, f, null, fragileMat(m));
    }

    private static boolean fragileMat(Material m) {
        return switch (m) {
            case LIGHT, LADDER, DOOR_LOWER, DOOR_UPPER, BED_FOOT, BED_HEAD, BANNER, CROP, BELL, LECTERN -> true;
            default -> false;
        };
    }

    /** Precisa de apoio (vai depois dos blocos firmes da mesma camada). */
    private static boolean fragileBlock(String b) {
        String p = Palette.id(b);
        return p.endsWith("torch") || p.endsWith("lantern") || p.endsWith("ladder") || p.endsWith("_door") || p.endsWith("_bed")
                || p.contains("potted_") || p.endsWith("flower_pot") || p.endsWith("_carpet") || p.endsWith("_pressure_plate")
                || p.endsWith("_button") || p.endsWith("_sign") || p.endsWith("_banner") || p.endsWith("bell") || p.endsWith("candle")
                || p.endsWith("_trapdoor") || p.endsWith("campfire") || p.endsWith("chain");
    }

    // blocos de estilo
    static final S PLANK = raw("{plank}_planks"), FRAME = raw("{frame}_log"), FRAME_X = raw("{frame}_log[axis=x]"),
            FRAME_Z = raw("{frame}_log[axis=z]"), FLOOR = raw("{frame}_planks"), TRIM_PLANK = raw("{trim}_planks"), STONE = raw("{stone}"),
            ROOF_PLANK = raw("{roof}_planks"), RIDGE = raw("{roof}_slab[type=bottom]"), COBBLE = raw("cobblestone"),
            STONE_BRICK = raw("stone_bricks"), FENCE = raw("{trim}_fence"), PLATE = raw("{trim}_pressure_plate"),
            WINDOW = mat(Material.WINDOW), CHEST_S = mat(Material.CHEST, Facing.SOUTH), CHEST_N = mat(Material.CHEST, Facing.NORTH),
            CHEST_E = mat(Material.CHEST, Facing.EAST), CHEST_W = mat(Material.CHEST, Facing.WEST), BARREL = mat(Material.BARREL),
            CRAFT = mat(Material.CRAFTING), ANVIL = mat(Material.ANVIL), BOOKS = mat(Material.BOOKSHELF), BELL = mat(Material.BELL),
            HAY = mat(Material.HAY), PATH = mat(Material.PATH), WATER = mat(Material.WATER), AIR = mat(Material.AIR),
            LANTERN_HANG = raw("lantern[hanging=true]"), LANTERN = raw("lantern[hanging=false]");

    static S stairs(String family, Facing f, boolean top) {
        return raw(family + "_stairs[facing=" + f.name().toLowerCase(Locale.ROOT) + ",half=" + (top ? "top" : "bottom") + "]");
    }

    static S roofStairs(Facing f) {
        return stairs("{roof}", f, false);
    }

    static S trimStairs(Facing f) {
        return stairs("{trim}", f, false);
    }

    static S wallTorch(Facing f) {
        return raw("wall_torch[facing=" + f.name().toLowerCase(Locale.ROOT) + "]");
    }

    static S furnace(Facing f) {
        return mat(Material.FURNACE, f);
    }

    static S lectern(Facing f) {
        return mat(Material.LECTERN, f);
    }

    static S ladder(Facing f) {
        return mat(Material.LADDER, f);
    }

    static S shutter(Facing f) {
        return raw("{trim}_trapdoor[facing=" + f.name().toLowerCase(Locale.ROOT) + ",half=top,open=true]");
    }

    /** Montador de plantas desenhadas: grava blocos por posição (o último vence) e ordena a obra camada a camada. */
    static final class D {
        final String id, name;
        final Category cat;
        final int w, d;
        private final Map<Long, Entry> cells = new HashMap<>();
        private int seq;
        private int top;
        private final Map<Material, String> materials = new EnumMap<>(Material.class);

        private record Entry(int x, int y, int z, S s, List<Placement> group, int seq) {}

        D(String id, String name, Category cat, int w, int d) {
            this.id = id;
            this.name = name;
            this.cat = cat;
            this.w = w;
            this.d = d;
            materials.put(Material.DOOR_LOWER, "minecraft:{trim}_door");
            materials.put(Material.DOOR_UPPER, "minecraft:{trim}_door");
            materials.put(Material.BED_FOOT, "minecraft:{wool}_bed");
            materials.put(Material.BED_HEAD, "minecraft:{wool}_bed");
            materials.put(Material.FENCE, "minecraft:{trim}_fence");
        }

        private static long key(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
        }

        D put(int x, int y, int z, S s) {
            cells.put(key(x, y, z), new Entry(x, y, z, s, null, seq++));
            top = Math.max(top, y);
            return this;
        }

        D box(int x0, int y0, int z0, int x1, int y1, int z1, S s) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
                    for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) put(x, y, z, s);
            return this;
        }

        /** Fundação (anel) e piso de y = -1. */
        D base(S ring, S inner) {
            for (int x = 0; x < w; x++)
                for (int z = 0; z < d; z++) put(x, -1, z, x == 0 || z == 0 || x == w - 1 || z == d - 1 ? ring : inner);
            return this;
        }

        /** Paredes do perímetro de y0 a y1 (cantos com outro bloco). */
        D walls(int y0, int y1, S fill, S corner) {
            for (int y = y0; y <= y1; y++)
                for (int x = 0; x < w; x++)
                    for (int z = 0; z < d; z++) {
                        boolean ex = x == 0 || x == w - 1, ez = z == 0 || z == d - 1;
                        if (!ex && !ez) continue;
                        put(x, y, z, ex && ez ? corner : fill);
                    }
            return this;
        }

        /** Laje interna (piso do andar de cima) em y. */
        D storey(int y, S s) {
            return box(1, y, 1, w - 2, y, d - 2, s);
        }

        /** Porta (as duas metades seguidas na obra) virada para fora. */
        D door(int x, int y, int z, Facing f) {
            List<Placement> g = List.of(new Placement(x, y, z, Material.DOOR_LOWER, f), new Placement(x, y + 1, z, Material.DOOR_UPPER, f));
            cells.remove(key(x, y + 1, z));
            cells.put(key(x, y, z), new Entry(x, y, z, mat(Material.DOOR_LOWER, f), g, seq++));
            top = Math.max(top, y + 1);
            return this;
        }

        /** Cama: pé em (x, z), cabeceira na direção {@code toHead}. */
        D bed(int x, int y, int z, Facing toHead) {
            int hx = x + (toHead == Facing.EAST ? 1 : toHead == Facing.WEST ? -1 : 0);
            int hz = z + (toHead == Facing.SOUTH ? 1 : toHead == Facing.NORTH ? -1 : 0);
            List<Placement> g = List.of(new Placement(x, y, z, Material.BED_FOOT, toHead), new Placement(hx, y, hz, Material.BED_HEAD, toHead));
            cells.remove(key(hx, y, hz));
            cells.put(key(x, y, z), new Entry(x, y, z, mat(Material.BED_FOOT, toHead), g, seq++));
            return this;
        }

        /** Telhado de duas águas com a cumeeira ao longo de X (caimento para a frente e para o fundo). */
        D gableX(int y0, S gable) {
            for (int x = 1; x < w - 1; x++) {
                put(x, y0, 0, gable);
                put(x, y0, d - 1, gable);
            }
            for (int layer = 0; ; layer++) {
                int zlo = -1 + layer, zhi = d + 0 - layer, y = y0 + layer;
                if (zlo > zhi) break;
                for (int x = -1; x <= w; x++) {
                    if (zlo == zhi) put(x, y, zlo, RIDGE);
                    else {
                        put(x, y, zlo, roofStairs(Facing.SOUTH));
                        put(x, y, zhi, roofStairs(Facing.NORTH));
                    }
                }
                for (int z = Math.max(0, zlo + 1); z <= Math.min(d - 1, zhi - 1); z++) {
                    put(0, y, z, gable);
                    put(w - 1, y, z, gable);
                }
            }
            return this;
        }

        /** Telhado de duas águas com a cumeeira ao longo de Z (oitão na frente e no fundo). */
        D gableZ(int y0, S gable) {
            for (int z = 1; z < d - 1; z++) {
                put(0, y0, z, gable);
                put(w - 1, y0, z, gable);
            }
            for (int layer = 0; ; layer++) {
                int xlo = -1 + layer, xhi = w - layer, y = y0 + layer;
                if (xlo > xhi) break;
                for (int z = -1; z <= d; z++) {
                    if (xlo == xhi) put(xlo, y, z, RIDGE);
                    else {
                        put(xlo, y, z, roofStairs(Facing.EAST));
                        put(xhi, y, z, roofStairs(Facing.WEST));
                    }
                }
                for (int x = Math.max(0, xlo + 1); x <= Math.min(w - 1, xhi - 1); x++) {
                    put(x, y, 0, gable);
                    put(x, y, d - 1, gable);
                }
            }
            return this;
        }

        /** Telhado em pirâmide de escadas (para torres e poços quadrados). */
        D hip(int y0, int ov, S cap) {
            for (int layer = 0; ; layer++) {
                int lo = -ov + layer, hiX = w - 1 + ov - layer, hiZ = d - 1 + ov - layer, y = y0 + layer;
                if (lo > hiX || lo > hiZ) break;
                if (lo == hiX || lo == hiZ) {
                    box(lo, y, lo, hiX, y, hiZ, cap);
                    break;
                }
                for (int x = lo; x <= hiX; x++) {
                    put(x, y, lo, roofStairs(Facing.SOUTH));
                    put(x, y, hiZ, roofStairs(Facing.NORTH));
                }
                for (int z = lo + 1; z < hiZ; z++) {
                    put(lo, y, z, roofStairs(Facing.EAST));
                    put(hiX, y, z, roofStairs(Facing.WEST));
                }
            }
            return this;
        }

        /**
         * Camada desenhada: rows[z] (z = 0 é a frente), cada caractere um x. ' ' = não mexe; o resto vem da legenda.
         * Camas: 'b' = pé e 'B' = cabeceira (a direção sai da posição); portas: 'D' (virada para fora da parede).
         */
        D plan(int y, Map<Character, S> legend, String... rows) {
            if (rows.length != d) throw new IllegalStateException(id + ": plano com " + rows.length + " linhas (profundidade " + d + ")");
            for (String r : rows) if (r.length() != w) throw new IllegalStateException(id + ": linha \"" + r + "\" não tem " + w + " colunas");
            for (int z = 0; z < rows.length; z++) {
                String row = rows[z];
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == ' ' || c == 'B') continue;
                    if (c == 'b') {
                        Facing f = null;
                        if (at(rows, x + 1, z) == 'B') f = Facing.EAST;
                        else if (at(rows, x - 1, z) == 'B') f = Facing.WEST;
                        else if (at(rows, x, z + 1) == 'B') f = Facing.SOUTH;
                        else if (at(rows, x, z - 1) == 'B') f = Facing.NORTH;
                        if (f == null) throw new IllegalStateException(id + ": cama sem cabeceira em " + x + "," + z);
                        bed(x, y, z, f);
                        continue;
                    }
                    S s = legend.get(c);
                    if (s == null) throw new IllegalStateException(id + ": legenda sem '" + c + "'");
                    put(x, y, z, s);
                }
            }
            return this;
        }

        private static char at(String[] rows, int x, int z) {
            if (z < 0 || z >= rows.length || x < 0 || x >= rows[z].length()) return ' ';
            return rows[z].charAt(x);
        }

        D material(Material m, String block) {
            materials.put(m, block);
            return this;
        }

        /**
         * Monta a planta: limpa o terreno (caixa + 1 de folga), depois sobe camada a camada — primeiro o que é firme,
         * depois o que precisa de apoio (tochas, portas, camas). Portas e camas saem com as duas metades seguidas.
         */
        Blueprint build(int housing) {
            int h = top + 1;
            List<Placement> p = new ArrayList<>();
            for (int y = 0; y < h; y++)
                for (int x = -1; x <= w; x++)
                    for (int z = -1; z <= d; z++) p.add(new Placement(x, y, z, Material.AIR, Facing.NONE));
            List<Entry> es = new ArrayList<>(cells.values());
            es.removeIf(e -> e.s().m() == Material.AIR && e.y() >= 0); // o ar já foi limpo
            // lanterna pendurada vai depois da camada de cima (a viga onde ela se prende)
            es.sort(Comparator.comparingInt((Entry e) -> e.y() + (e.s().raw() != null && e.s().raw().contains("hanging=true") ? 1 : 0))
                    .thenComparing(e -> e.s().fragile()).thenComparingInt(Entry::seq));
            for (Entry e : es) {
                if (e.group() != null) {
                    p.addAll(e.group());
                    continue;
                }
                S s = e.s();
                p.add(s.m() == Material.RAW ? new Placement(e.x(), e.y(), e.z(), Material.RAW, Facing.NONE, s.raw())
                        : new Placement(e.x(), e.y(), e.z(), s.m(), s.f()));
            }
            dedupe(p);
            Blueprint draft = new Blueprint(id, name, cat, w, h, d, Map.of(), housing, List.copyOf(p), "builtin", Map.copyOf(materials));
            int beds = BillOfMaterials.beds(draft);
            int housingFinal = housing >= 0 ? housing : beds;
            return new Blueprint(id, name, cat, w, h, d, Collections.unmodifiableMap(BillOfMaterials.cost(draft)), housingFinal,
                    draft.placements(), "builtin", draft.materials());
        }

        /** Mantém a última colocação de cada posição (a ordem de construção é a da primeira ocorrência válida). */
        private static void dedupe(List<Placement> p) {
            Map<Long, Integer> last = new HashMap<>();
            for (int i = 0; i < p.size(); i++) last.put(key(p.get(i).x(), p.get(i).y(), p.get(i).z()), i);
            List<Placement> out = new ArrayList<>();
            for (int i = 0; i < p.size(); i++) if (last.get(key(p.get(i).x(), p.get(i).y(), p.get(i).z())) == i) out.add(p.get(i));
            p.clear();
            p.addAll(out);
        }
    }

    private static Map<Character, S> legend(Object... kv) {
        Map<Character, S> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((Character) kv[i], (S) kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ catálogo

    /** Todas as plantas desenhadas, na ordem do catálogo. */
    public static List<Blueprint> all() {
        return List.of(houseSmall(), houseMedium(), storage(), smithy(), barracks(), townHall(), library(), chapel(), tavern(), watchtower(),
                well(), barn(), market());
    }

    /** Casa pequena (chalé de enxaimel): 7×7, 3 camas, cozinha com fornalha, baú e bancada. */
    static Blueprint houseSmall() {
        D b = new D("house_small", "Casa pequena", Category.HOUSING, 7, 7);
        b.base(STONE, FLOOR);
        b.walls(0, 2, PLANK, FRAME);
        b.put(1, 1, 0, WINDOW).put(5, 1, 0, WINDOW).put(0, 1, 3, WINDOW).put(6, 1, 3, WINDOW).put(3, 1, 6, WINDOW);
        b.door(3, 0, 0, Facing.NORTH);
        b.gableX(3, PLANK);
        // interior: 3 camas no fundo, cozinha na frente
        b.plan(0, legend('C', CHEST_S, 'T', CRAFT, 'F', furnace(Facing.SOUTH)),
                "       ",
                " C   F ",
                "     T ",
                "       ",
                " b b b ",
                " B B B ",
                "       ");
        b.put(1, 2, 2, wallTorch(Facing.EAST)).put(5, 2, 4, wallTorch(Facing.WEST));
        b.put(2, 1, -1, wallTorch(Facing.NORTH));
        b.put(3, -1, -1, PATH);
        return b.build(-1);
    }

    /** Sobrado (casa média): térreo de pedra, andar de madeira, 5 camas em cima, cozinha e mesa embaixo. */
    static Blueprint houseMedium() {
        D b = new D("house_medium", "Casa média", Category.HOUSING, 9, 7);
        b.base(STONE, FLOOR);
        b.walls(0, 2, STONE, FRAME);
        b.storey(3, FLOOR);
        b.walls(3, 3, FRAME_X, FRAME);
        for (int z = 1; z < 6; z++) {
            b.put(0, 3, z, FRAME_Z);
            b.put(8, 3, z, FRAME_Z);
        }
        b.walls(4, 6, PLANK, FRAME);
        // janelas: térreo e andar
        b.put(2, 1, 0, WINDOW).put(6, 1, 0, WINDOW).put(0, 1, 3, WINDOW).put(8, 1, 3, WINDOW).put(4, 1, 6, WINDOW);
        b.put(2, 5, 0, WINDOW).put(4, 5, 0, WINDOW).put(6, 5, 0, WINDOW).put(0, 5, 3, WINDOW).put(8, 5, 3, WINDOW).put(2, 5, 6, WINDOW).put(6, 5, 6, WINDOW);
        b.put(1, 5, -1, shutter(Facing.NORTH)).put(3, 5, -1, shutter(Facing.NORTH)).put(5, 5, -1, shutter(Facing.NORTH)).put(7, 5, -1, shutter(Facing.NORTH));
        b.door(4, 0, 0, Facing.NORTH);
        // escada de mão do térreo ao andar (vão no piso)
        for (int y = 0; y <= 3; y++) b.put(1, y, 5, ladder(Facing.EAST));
        b.put(1, 4, 5, AIR);
        b.gableX(7, PLANK);
        // térreo: cozinha, mesa com cadeiras, baú
        b.plan(0, legend('C', CHEST_S, 'T', CRAFT, 'F', furnace(Facing.SOUTH), 'R', BARREL, 'f', FENCE,
                        'w', trimStairs(Facing.WEST), 'e', trimStairs(Facing.EAST)),
                "         ",
                " C R  FT ",
                "         ",
                "   wfe   ",
                "         ",
                "         ",
                "         ");
        b.put(4, 1, 3, PLATE);
        // andar: 5 camas
        b.plan(4, legend(),
                "         ",
                "   B  B  ",
                "   b  b  ",
                "         ",
                "   b b b ",
                "   B B B ",
                "         ");
        b.put(1, 2, 2, wallTorch(Facing.EAST)).put(7, 2, 4, wallTorch(Facing.WEST)).put(1, 6, 2, wallTorch(Facing.EAST)).put(7, 6, 3, wallTorch(Facing.WEST));
        b.put(3, 1, -1, wallTorch(Facing.NORTH)).put(5, 1, -1, wallTorch(Facing.NORTH));
        b.put(4, -1, -1, PATH);
        return b.build(-1);
    }

    /** Armazém: depósito comprido com baús e barris nas paredes (o estoque do reino mora aqui). */
    static Blueprint storage() {
        D b = new D("storage", "Armazém", Category.STORAGE, 9, 7);
        b.base(STONE, STONE);
        b.walls(0, 0, STONE, FRAME);
        b.walls(1, 2, PLANK, FRAME);
        b.put(2, 1, 0, WINDOW).put(6, 1, 0, WINDOW).put(0, 2, 3, WINDOW).put(8, 2, 3, WINDOW);
        b.door(4, 0, 0, Facing.NORTH);
        b.gableX(3, PLANK);
        b.plan(0, legend('C', CHEST_N, 'c', CHEST_E, 'k', CHEST_W, 'R', BARREL, 'T', CRAFT),
                "         ",
                " T       ",
                " c     k ",
                " R     R ",
                " c     k ",
                " RCRCRCR ",
                "         ");
        b.put(4, 2, 1, wallTorch(Facing.SOUTH)).put(4, 2, 5, wallTorch(Facing.NORTH));
        b.put(3, 1, -1, wallTorch(Facing.NORTH)).put(5, 1, -1, wallTorch(Facing.NORTH));
        b.put(4, -1, -1, PATH);
        return b.build(0);
    }

    /** Forja: oficina de pedra com frente aberta, fornalhas, bigorna, rebolo e chaminé. */
    static Blueprint smithy() {
        D b = new D("smithy", "Forja", Category.INDUSTRY, 7, 7);
        b.base(STONE, STONE);
        b.walls(0, 2, STONE, FRAME);
        // frente aberta: só os pilares e uma mureta
        b.box(1, 0, 0, 5, 2, 0, AIR);
        b.put(1, 0, 0, raw("{stone_s}_wall")).put(5, 0, 0, raw("{stone_s}_wall"));
        b.put(0, 1, 3, WINDOW).put(6, 1, 3, WINDOW);
        b.gableX(3, PLANK);
        b.box(1, 3, 0, 5, 3, 0, FRAME_X);
        // chaminé de pedra no canto do fundo
        b.box(1, 0, 6, 1, 7, 6, STONE);
        b.plan(0, legend('F', furnace(Facing.NORTH), 'A', ANVIL, 'G', raw("grindstone[face=floor,facing=north]"), 'C', CHEST_N, 'T', CRAFT,
                        'S', raw("smithing_table")),
                "       ",
                "       ",
                "     S ",
                "   A   ",
                " G     ",
                " FF CT ",
                "       ");
        b.box(1, 3, 1, 5, 3, 1, FRAME_X);
        b.put(3, 2, 5, wallTorch(Facing.NORTH));
        b.put(2, 2, 1, LANTERN_HANG).put(4, 2, 1, LANTERN_HANG);
        b.put(3, -1, -1, PATH);
        return b.build(0);
    }

    /** Quartel: alojamento de pedra com 4 camas, arsenal (baús e barris), mesa de comando e estandartes. */
    static Blueprint barracks() {
        D b = new D("barracks", "Quartel", Category.MILITARY, 11, 9);
        b.base(STONE, FLOOR);
        b.walls(0, 3, STONE, raw("stone_bricks"));
        b.put(2, 1, 0, WINDOW).put(8, 1, 0, WINDOW).put(0, 2, 2, WINDOW).put(0, 2, 6, WINDOW).put(10, 2, 2, WINDOW).put(10, 2, 6, WINDOW);
        b.door(5, 0, 0, Facing.NORTH);
        b.gableX(4, PLANK);
        b.plan(0, legend('C', CHEST_S, 'R', BARREL, 'T', CRAFT, 'f', FENCE, 'n', trimStairs(Facing.NORTH), 's', trimStairs(Facing.SOUTH),
                        'G', raw("grindstone[face=floor,facing=north]")),
                "           ",
                " CRC   RCG ",
                "           ",
                "    nnn    ",
                "    fff    ",
                "    sss    ",
                "  b b b b  ",
                "  B B B B  ",
                "           ");
        b.put(4, 1, 4, PLATE).put(5, 1, 4, PLATE).put(6, 1, 4, PLATE);
        b.put(1, 2, 4, raw("{wool}_wall_banner[facing=east]")).put(9, 2, 4, raw("{wool}_wall_banner[facing=west]"));
        b.put(1, 3, 2, wallTorch(Facing.EAST)).put(9, 3, 2, wallTorch(Facing.WEST)).put(1, 3, 6, wallTorch(Facing.EAST)).put(9, 3, 6, wallTorch(Facing.WEST));
        b.put(4, 2, -1, wallTorch(Facing.NORTH)).put(6, 2, -1, wallTorch(Facing.NORTH));
        b.put(5, -1, -1, PATH);
        return b.build(-1);
    }

    /** Salão Real: nave de pedra com oitão na frente, trono, sino, baús do tesouro e o quarto do rei. */
    static Blueprint townHall() {
        D b = new D("town_hall", "Salão Real", Category.CIVIC, 11, 11);
        b.base(STONE, FLOOR);
        b.walls(0, 4, STONE, FRAME);
        for (int y = 0; y <= 4; y++) { // pilares no meio das laterais
            b.put(0, y, 5, FRAME);
            b.put(10, y, 5, FRAME);
        }
        for (int z : new int[]{2, 3, 7, 8})
            for (int y = 1; y <= 3; y++) {
                b.put(0, y, z, WINDOW);
                b.put(10, y, z, WINDOW);
            }
        b.put(3, 2, 0, WINDOW).put(7, 2, 0, WINDOW).put(5, 6, 0, WINDOW).put(5, 7, 0, WINDOW);
        b.door(5, 0, 0, Facing.NORTH);
        b.gableZ(5, STONE);
        // tesouras do telhado (de onde pendem as lanternas)
        b.box(1, 5, 2, 9, 5, 2, FRAME_X).box(1, 5, 8, 9, 5, 8, FRAME_X);
        // corredor com tapete até o trono; baús do tesouro nas laterais; quarto do rei no fundo
        b.plan(0, legend('C', CHEST_E, 'k', CHEST_W, 'R', BARREL, 'L', BELL, 't', stairs("{trim}", Facing.SOUTH, false),
                        'p', raw("{wool}_carpet"), 'T', CRAFT, 'q', CHEST_N),
                "           ",
                " C   L   k ",
                " R   p   R ",
                " C   p   k ",
                " R   p   R ",
                "     p     ",
                "     p     ",
                " bb  p   T ",
                " BB  p     ",
                "    ptp  q ",
                "           ");
        b.put(4, 2, 9, raw("{wool}_wall_banner[facing=north]")).put(6, 2, 9, raw("{wool}_wall_banner[facing=north]"));
        b.put(3, 4, 2, LANTERN_HANG).put(7, 4, 2, LANTERN_HANG).put(3, 4, 8, LANTERN_HANG).put(7, 4, 8, LANTERN_HANG);
        b.put(4, 2, -1, wallTorch(Facing.NORTH)).put(6, 2, -1, wallTorch(Facing.NORTH));
        b.put(5, -1, -1, PATH);
        return b.build(-1);
    }

    /** Biblioteca: estantes nas paredes, atril no centro, mesas de leitura e luz de lanterna. */
    static Blueprint library() {
        D b = new D("library", "Biblioteca", Category.CIVIC, 9, 9);
        b.base(STONE, FLOOR);
        b.walls(0, 3, PLANK, FRAME);
        b.walls(0, 0, STONE, FRAME);
        b.put(2, 2, 0, WINDOW).put(6, 2, 0, WINDOW).put(0, 2, 4, WINDOW).put(8, 2, 4, WINDOW).put(4, 2, 8, WINDOW);
        b.door(4, 0, 0, Facing.NORTH);
        b.gableX(4, PLANK);
        b.box(2, 4, 1, 2, 4, 7, FRAME_Z).box(6, 4, 1, 6, 4, 7, FRAME_Z);
        // estantes (cada uma pede 3 livros): parede do fundo e duas de cada lado; barris guardam os pergaminhos
        for (int x = 2; x <= 6; x++)
            for (int y = 0; y <= 1; y++) if (x != 4 || y == 0) b.put(x, y, 7, BOOKS);
        for (int z : new int[]{3, 6}) {
            b.put(1, 0, z, BOOKS);
            b.put(7, 0, z, BOOKS);
        }
        b.put(1, 0, 1, BARREL).put(7, 0, 1, BARREL);
        b.put(4, 0, 4, lectern(Facing.NORTH));
        b.plan(0, legend('f', FENCE, 'e', trimStairs(Facing.EAST), 'w', trimStairs(Facing.WEST)),
                "         ",
                "         ",
                "  wf fe  ",
                "         ",
                "         ",
                "  wf fe  ",
                "         ",
                "         ",
                "         ");
        b.put(3, 1, 2, PLATE).put(5, 1, 2, PLATE).put(3, 1, 5, PLATE).put(5, 1, 5, PLATE);
        b.put(2, 3, 3, LANTERN_HANG).put(6, 3, 3, LANTERN_HANG).put(2, 3, 6, LANTERN_HANG).put(6, 3, 6, LANTERN_HANG);
        b.put(3, 1, -1, wallTorch(Facing.NORTH)).put(5, 1, -1, wallTorch(Facing.NORTH));
        b.put(4, -1, -1, PATH);
        return b.build(0);
    }

    /** Capela: nave de pedra, bancos de madeira voltados para o altar, sino e janelas altas. */
    static Blueprint chapel() {
        D b = new D("chapel", "Capela", Category.CIVIC, 7, 11);
        b.base(STONE, FLOOR);
        b.walls(0, 4, STONE, raw("stone_bricks"));
        for (int z : new int[]{3, 5, 7})
            for (int y = 1; y <= 3; y++) {
                b.put(0, y, z, WINDOW);
                b.put(6, y, z, WINDOW);
            }
        b.put(3, 6, 0, WINDOW).put(3, 7, 0, WINDOW).put(3, 3, 10, WINDOW).put(3, 2, 10, WINDOW);
        b.door(3, 0, 0, Facing.NORTH);
        b.gableZ(5, STONE);
        b.plan(0, legend('n', trimStairs(Facing.NORTH), 'A', raw("{stone}"), 'L', BELL, 'c', raw("{wool}_carpet")),
                "       ",
                "   c   ",
                "   c   ",
                " nncnn ",
                "   c   ",
                " nncnn ",
                "   c   ",
                " nncnn ",
                "   c   ",
                "  LA   ",
                "       ");
        b.put(3, 1, 9, LANTERN).put(1, 3, 9, wallTorch(Facing.EAST)).put(5, 3, 9, wallTorch(Facing.WEST))
                .put(1, 3, 2, wallTorch(Facing.EAST)).put(5, 3, 2, wallTorch(Facing.WEST));
        b.put(2, 2, -1, wallTorch(Facing.NORTH)).put(4, 2, -1, wallTorch(Facing.NORTH));
        b.put(3, -1, -1, PATH);
        return b.build(0);
    }

    /** Taverna: balcão de barris, mesas com bancos, lareira com chaminé e o quarto de quem toca o lugar. */
    static Blueprint tavern() {
        D b = new D("tavern", "Taverna", Category.CIVIC, 11, 9);
        b.base(STONE, FLOOR);
        b.walls(0, 0, STONE, FRAME);
        b.walls(1, 3, PLANK, FRAME);
        b.put(2, 2, 0, WINDOW).put(8, 2, 0, WINDOW).put(0, 2, 2, WINDOW).put(10, 2, 2, WINDOW).put(0, 2, 6, WINDOW);
        b.put(1, 2, -1, shutter(Facing.NORTH)).put(3, 2, -1, shutter(Facing.NORTH)).put(7, 2, -1, shutter(Facing.NORTH)).put(9, 2, -1, shutter(Facing.NORTH));
        b.door(5, 0, 0, Facing.NORTH);
        b.gableX(4, PLANK);
        b.box(3, 4, 1, 3, 4, 7, FRAME_Z).box(7, 4, 1, 7, 4, 7, FRAME_Z);
        // lareira e chaminé na parede leste
        b.box(10, 0, 4, 10, 10, 4, STONE);
        b.put(9, 0, 4, raw("campfire[lit=true]"));
        b.plan(0, legend('R', BARREL, 'f', FENCE, 'e', trimStairs(Facing.EAST), 'w', trimStairs(Facing.WEST), 'n', trimStairs(Facing.NORTH),
                        'C', CHEST_N, 'T', CRAFT),
                "           ",
                " wfe   wfe ",
                "           ",
                " wfe   wfe ",
                " nnnnnnn   ",
                " RRRRRRR   ",
                " bb        ",
                " BB  T  C  ",
                "           ");
        b.put(2, 1, 1, PLATE).put(8, 1, 1, PLATE).put(2, 1, 3, PLATE).put(8, 1, 3, PLATE);
        b.put(3, 3, 2, LANTERN_HANG).put(7, 3, 2, LANTERN_HANG).put(3, 3, 6, LANTERN_HANG).put(7, 3, 6, LANTERN_HANG);
        b.put(4, 1, -1, wallTorch(Facing.NORTH)).put(6, 1, -1, wallTorch(Facing.NORTH));
        b.put(5, -1, -1, PATH);
        return b.build(-1);
    }

    /** Torre de vigia: pedra, escada de mão por dentro, plataforma com ameias e luz no topo. */
    static Blueprint watchtower() {
        D b = new D("watchtower", "Torre de vigia", Category.MILITARY, 5, 5);
        b.base(STONE, STONE);
        b.walls(0, 8, STONE, raw("stone_bricks"));
        // seteiras (nunca na parede do fundo, onde a escada se apoia)
        b.put(2, 4, 0, AIR).put(0, 4, 2, AIR).put(4, 4, 2, AIR).put(2, 7, 0, AIR).put(0, 7, 2, AIR).put(4, 7, 2, AIR);
        b.door(2, 0, 0, Facing.NORTH);
        for (int y = 0; y <= 9; y++) b.put(2, y, 3, ladder(Facing.NORTH));
        b.box(-1, 9, -1, 5, 9, 5, FLOOR);
        b.put(2, 9, 3, ladder(Facing.NORTH));
        for (int x = -1; x <= 5; x++)
            for (int z = -1; z <= 5; z++) {
                boolean ring = x == -1 || x == 5 || z == -1 || z == 5;
                if (ring) b.put(x, 10, z, Math.floorMod(x + z, 2) == 0 ? raw("{stone_s}_wall") : FENCE);
            }
        b.put(-1, 11, -1, LANTERN).put(5, 11, -1, LANTERN).put(-1, 11, 5, LANTERN).put(5, 11, 5, LANTERN);
        b.put(1, 2, 2, wallTorch(Facing.EAST)).put(3, 6, 2, wallTorch(Facing.WEST));
        b.put(2, -1, -1, PATH);
        return b.build(0);
    }

    /** Poço: anel de pedra, água funda, postes de cerca e cobertura de laje com lanterna. */
    static Blueprint well() {
        D b = new D("well", "Poço", Category.CIVIC, 5, 5);
        b.box(0, -1, 0, 4, -1, 4, raw("{stone}"));
        b.put(2, -1, 2, WATER).put(2, -2, 2, WATER).put(2, -3, 2, WATER);
        b.box(1, 0, 1, 3, 0, 3, raw("{stone_s}_wall"));
        b.put(2, 0, 2, AIR);
        b.put(1, 1, 1, FENCE).put(3, 1, 1, FENCE).put(1, 1, 3, FENCE).put(3, 1, 3, FENCE);
        b.put(1, 2, 1, FENCE).put(3, 2, 1, FENCE).put(1, 2, 3, FENCE).put(3, 2, 3, FENCE);
        b.box(0, 3, 0, 4, 3, 4, RIDGE);
        b.box(1, 3, 1, 3, 3, 3, ROOF_PLANK);
        b.put(2, 2, 2, LANTERN_HANG);
        b.put(2, -1, -1, PATH);
        return b.build(0);
    }

    /** Celeiro: portão largo, oitão na frente, feno empilhado, baús e composteira. */
    static Blueprint barn() {
        D b = new D("barn", "Celeiro", Category.FARM, 9, 11);
        b.base(STONE, raw("{frame}_planks"));
        b.walls(0, 0, STONE, FRAME);
        b.walls(1, 3, PLANK, FRAME);
        for (int z = 3; z < 10; z += 3)
            for (int y = 0; y <= 3; y++) {
                b.put(0, y, z, FRAME);
                b.put(8, y, z, FRAME);
            }
        b.box(3, 0, 0, 5, 2, 0, AIR); // portão aberto de 3 blocos
        b.box(3, 3, 0, 5, 3, 0, FRAME_X);
        b.put(0, 2, 2, WINDOW).put(8, 2, 2, WINDOW).put(0, 2, 7, WINDOW).put(8, 2, 7, WINDOW);
        b.gableZ(4, PLANK);
        b.box(1, 4, 2, 7, 4, 2, FRAME_X).box(1, 4, 7, 7, 4, 7, FRAME_X);
        b.put(4, 6, 0, WINDOW);
        b.plan(0, legend('H', HAY, 'C', CHEST_E, 'k', CHEST_W, 'R', BARREL, 'M', raw("composter")),
                "         ",
                "         ",
                " C     k ",
                " R     R ",
                "         ",
                "         ",
                "         ",
                " HH   HH ",
                " HH M HH ",
                "         ",
                "         ");
        b.put(1, 1, 8, HAY).put(7, 1, 8, HAY);
        b.put(4, 3, 2, LANTERN_HANG).put(4, 3, 7, LANTERN_HANG);
        b.put(2, 2, -1, wallTorch(Facing.NORTH)).put(6, 2, -1, wallTorch(Facing.NORTH));
        for (int x = 3; x <= 5; x++) b.put(x, -1, -1, PATH);
        return b.build(0);
    }

    /** Mercado: praça calçada com quatro bancas cobertas, barris, baús e bancada de negócios. */
    static Blueprint market() {
        D b = new D("market", "Mercado", Category.CIVIC, 9, 9);
        b.box(0, -1, 0, 8, -1, 8, raw("{stone}"));
        b.box(3, -1, 0, 5, -1, 8, PATH);
        b.box(0, -1, 3, 8, -1, 5, PATH);
        int[][] stalls = {{0, 0}, {6, 0}, {0, 6}, {6, 6}};
        for (int[] s : stalls) {
            int x0 = s[0], z0 = s[1];
            for (int[] c : new int[][]{{x0, z0}, {x0 + 2, z0}, {x0, z0 + 2}, {x0 + 2, z0 + 2}})
                for (int y = 0; y <= 2; y++) b.put(c[0], y, c[1], FENCE);
            b.box(x0, 3, z0, x0 + 2, 3, z0 + 2, raw("{roof}_slab[type=bottom]"));
            b.put(x0 + 1, 4, z0 + 1, raw("{wool}_carpet"));
            boolean front = z0 == 0;
            b.put(x0 + 1, 0, front ? z0 + 2 : z0, BARREL);
            b.put(x0 + (x0 == 0 ? 2 : 0), 0, z0 + 1, front ? CHEST_S : CHEST_N);
            b.put(x0 + 1, 2, z0 + 1, LANTERN_HANG);
        }
        b.put(4, 0, 4, CRAFT);
        b.put(4, -1, -1, PATH);
        return b.build(0);
    }
}
