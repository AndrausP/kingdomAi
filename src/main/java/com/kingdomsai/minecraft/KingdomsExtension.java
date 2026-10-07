package com.kingdomsai.minecraft;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.ParametricBlueprints;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Comandos que dependem do Minecraft: config (modelo, endpoint, provider...), salvar planta do mundo,
 * importar .nbt e selecionar NPC clicado na câmera do Manager.
 */
public final class KingdomsExtension implements CommandService.Extension {
    private final ServerRuntime rt;
    private final Map<UUID, BlockPos> pos1 = new HashMap<>(), pos2 = new HashMap<>();
    private volatile List<String> lastModels = List.of();
    private volatile String lastTest = "";

    public KingdomsExtension(ServerRuntime rt) {
        this.rt = rt;
    }

    public List<String> lastModels() {
        return lastModels;
    }

    public String lastTest() {
        return lastTest;
    }

    @Override
    public List<String> subcommands() {
        return List.of("config", "select");
    }

    @Override
    public List<String> help() {
        return List.of("config · config set <chave> <valor> · config models · config test  (ex.: /k config set ai.model qwen2.5:14b)",
                "blueprint pos1 · blueprint pos2 · blueprint save <nome> — salva uma construção sua como planta · blueprint import (pasta kingdomsai/blueprints, .nbt)");
    }

    @Override
    public boolean handle(UUID playerId, String playerName, Pos pos, String[] a, List<String> out) {
        String cmd = a[0].toLowerCase(Locale.ROOT);
        ServerPlayer p = rt.server().getPlayerList().getPlayer(playerId);
        if (p == null) return false;
        switch (cmd) {
            case "config", "configurar", "settings" -> {
                config(p, a, out);
                return true;
            }
            case "select" -> {
                select(p, a, out);
                return true;
            }
            case "blueprint", "planta" -> {
                String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                switch (sub) {
                    case "pos1", "pos2" -> {
                        BlockPos at = target(p);
                        (sub.equals("pos1") ? pos1 : pos2).put(playerId, at);
                        out.add("✓ " + sub + " = " + at.getX() + " " + at.getY() + " " + at.getZ()
                                + (sub.equals("pos1") ? " · agora mire no canto oposto e use /k blueprint pos2" : " · agora /k blueprint save <nome>"));
                        return true;
                    }
                    case "save", "salvar" -> {
                        saveFromWorld(p, a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "", out);
                        return true;
                    }
                    case "import", "importar" -> {
                        int n = importNbt(out);
                        out.add(n > 0 ? "✓ " + n + " planta(s) importada(s) de " + blueprintDir() : "Nenhum .nbt em " + blueprintDir());
                        return true;
                    }
                    default -> {
                        return false; // o Core trata list/design/show/delete
                    }
                }
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ config

    private boolean canConfigure(ServerPlayer p) {
        return p.server.isSingleplayerOwner(p.getGameProfile()) || p.hasPermissions(2);
    }

    private void config(ServerPlayer p, String[] a, List<String> out) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "show";
        switch (sub) {
            case "set" -> {
                if (!canConfigure(p)) {
                    out.add("✗ Só o dono do mundo/operadores mudam a configuração.");
                    return;
                }
                if (a.length < 4) {
                    out.add("Uso: config set <chave> <valor>   (chaves: /k config)");
                    return;
                }
                String key = a[2].toLowerCase(Locale.ROOT);
                String value = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
                String err = KingdomsConfig.set(key, value);
                if (err != null) {
                    out.add("✗ " + err);
                    return;
                }
                apply();
                out.add("✓ " + key + " = " + KingdomsConfig.display(key) + " (salvo em kingdomsai-common.toml)");
                if (key.startsWith("ai.")) out.add(rt.core().llm().status());
            }
            case "models", "modelos" -> {
                out.add("Consultando modelos em " + KingdomsConfig.AI_ENDPOINT.get() + "...");
                UUID id = p.getUUID();
                rt.core().llm().listModels().whenComplete((list, err) -> rt.core().mainThread().execute(() -> {
                    List<String> lines = new ArrayList<>();
                    if (err != null) lines.add("✗ Não consegui listar modelos: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage())
                            + ". O Ollama está rodando? (ollama serve)");
                    else {
                        lastModels = List.copyOf(list);
                        lines.add("# Modelos disponíveis (" + list.size() + ")");
                        for (String m : list) lines.add((m.equals(KingdomsConfig.AI_MODEL.get()) ? "✓ " : "  ") + m);
                        lines.add("Escolher: /k config set ai.model <nome>");
                    }
                    rt.notify(id, lines);
                }));
            }
            case "test", "testar" -> {
                out.add("Testando " + rt.core().llm().status() + "...");
                UUID id = p.getUUID();
                rt.core().llm().test().whenComplete((msg, err) -> rt.core().mainThread().execute(() -> {
                    lastTest = err != null ? "✗ " + err.getMessage() : msg;
                    rt.notify(id, List.of(lastTest));
                }));
            }
            case "reload" -> {
                apply();
                out.add("✓ Configuração recarregada. " + rt.core().llm().status());
            }
            default -> {
                out.add("# Configuração (kingdomsai-common.toml)");
                for (String k : KingdomsConfig.KEYS.keySet()) out.add(k + " = " + KingdomsConfig.display(k));
                out.add("Mudar: /k config set <chave> <valor> · /k config models · /k config test");
            }
        }
    }

    /** Aplica a config salva ao Core em execução (sem reiniciar o mundo). */
    public void apply() {
        CoreConfig fresh = KingdomsConfig.core();
        CoreConfig cur = rt.core().config();
        cur.economicTickSeconds = fresh.economicTickSeconds;
        cur.strategicTickSeconds = fresh.strategicTickSeconds;
        cur.builderBlocksPerSecond = fresh.builderBlocksPerSecond;
        cur.aiKingdomsEnabled = fresh.aiKingdomsEnabled;
        cur.constructionEnabled = fresh.constructionEnabled;
        cur.diplomacyEnabled = fresh.diplomacyEnabled;
        cur.rivalKingdoms = fresh.rivalKingdoms;
        rt.core().llm().configure(KingdomsConfig.llm());
    }

    // ------------------------------------------------------------------ seleção (clique no Manager)

    private void select(ServerPlayer p, String[] a, List<String> out) {
        if (a.length < 2) return;
        Entity e;
        try {
            e = p.serverLevel().getEntity(Integer.parseInt(a[1]));
        } catch (NumberFormatException ex) {
            return;
        }
        if (e instanceof KingdomNpcEntity npcEntity && npcEntity.npc() != null) {
            Npc n = npcEntity.npc();
            rt.cli().select(p.getUUID(), n.id);
            rt.cli().inspect(n, out);
            out.add("Falar: /k say <texto> · Alt abre a ficha no Manager");
        }
    }

    // ------------------------------------------------------------------ plantas do mundo

    private static BlockPos target(ServerPlayer p) {
        HitResult hit = p.pick(24, 1f, false);
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) return bh.getBlockPos();
        return p.blockPosition().below();
    }

    private void saveFromWorld(ServerPlayer p, String name, List<String> out) {
        BlockPos a = pos1.get(p.getUUID()), b = pos2.get(p.getUUID());
        if (a == null || b == null) {
            out.add("✗ Marque os cantos: mire num canto e /k blueprint pos1, depois no oposto e /k blueprint pos2 (inclua o piso).");
            return;
        }
        int x0 = Math.min(a.getX(), b.getX()), y0 = Math.min(a.getY(), b.getY()), z0 = Math.min(a.getZ(), b.getZ());
        int sx = Math.abs(a.getX() - b.getX()) + 1, sy = Math.abs(a.getY() - b.getY()) + 1, sz = Math.abs(a.getZ() - b.getZ()) + 1;
        if (sx > 32 || sz > 32 || sy > 32) {
            out.add("✗ Área grande demais (" + sx + "x" + sy + "x" + sz + "). Máximo 32x32x32.");
            return;
        }
        if (name.isBlank()) name = "Planta " + (rt.core().state().savedBlueprints.size() + 1);
        ServerLevel level = p.serverLevel();
        Map<int[], String> blocks = new LinkedHashMap<>();
        int forbidden = 0;
        for (int y = 0; y < sy; y++)
            for (int x = 0; x < sx; x++)
                for (int z = 0; z < sz; z++) {
                    BlockState s = level.getBlockState(new BlockPos(x0 + x, y0 + y, z0 + z));
                    if (s.isAir()) continue;
                    if (MaterialPalette.isForbidden(s)) {
                        forbidden++;
                        continue;
                    }
                    blocks.put(new int[]{x, y, z}, BlockStateParser.serialize(s));
                }
        String id = "saved_" + Text.norm(name).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        Blueprint bp = ParametricBlueprints.fromBlocks(id, Text.truncate(name, 40), "saved", sx, sy, sz, blocks);
        rt.core().registerSaved(bp);
        out.add("✓ Planta salva: " + bp.id() + " — " + blocks.size() + " blocos, " + sx + "x" + sz + "x" + sy
                + (forbidden > 0 ? " (" + forbidden + " bloco(s) proibido(s) ignorado(s))" : "") + ".");
        out.add("Construir: /k build " + bp.id() + "  · A camada mais baixa vira a fundação; a porta deve ficar no lado norte (z menor).");
    }

    public static Path blueprintDir() {
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("kingdomsai").resolve("blueprints");
    }

    /** Importa estruturas .nbt (formato do Structure Block) da pasta kingdomsai/blueprints. */
    public int importNbt(List<String> out) {
        Path dir = blueprintDir();
        int n = 0;
        try {
            Files.createDirectories(dir);
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".nbt")).toList()) {
                    try {
                        CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
                        ListTag size = root.getList("size", Tag.TAG_INT);
                        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
                        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
                        int sx = size.getInt(0), sy = size.getInt(1), sz = size.getInt(2);
                        if (sx > 48 || sy > 48 || sz > 48) {
                            if (out != null) out.add("⚠ " + f.getFileName() + " ignorado: maior que 48x48x48.");
                            continue;
                        }
                        List<String> states = new ArrayList<>();
                        for (int i = 0; i < palette.size(); i++) {
                            BlockState s = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(i));
                            states.add(MaterialPalette.isForbidden(s) ? "minecraft:air" : BlockStateParser.serialize(s));
                        }
                        Map<int[], String> map = new LinkedHashMap<>();
                        for (int i = 0; i < blocks.size(); i++) {
                            CompoundTag b = blocks.getCompound(i);
                            ListTag bpos = b.getList("pos", Tag.TAG_INT);
                            map.put(new int[]{bpos.getInt(0), bpos.getInt(1), bpos.getInt(2)}, states.get(b.getInt("state")));
                        }
                        String base = f.getFileName().toString().replace(".nbt", "");
                        String id = "nbt_" + Text.norm(base).replaceAll("[^a-z0-9]+", "_");
                        rt.core().registerSaved(ParametricBlueprints.fromBlocks(id, Text.truncate(base, 40), "nbt", sx, sy, sz, map));
                        n++;
                    } catch (Exception ex) {
                        if (out != null) out.add("✗ " + f.getFileName() + ": " + ex.getMessage());
                        KingdomsMod.LOG.warn("[KingdomsAI] falha ao importar {}", f, ex);
                    }
                }
            }
        } catch (Exception e) {
            if (out != null) out.add("✗ " + e.getMessage());
        }
        return n;
    }
}
