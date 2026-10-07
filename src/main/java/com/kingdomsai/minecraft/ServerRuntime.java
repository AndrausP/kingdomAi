package com.kingdomsai.minecraft;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.WorldState;
import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.event.EventType;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.IntelligenceLevel;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.persistence.Persistence;
import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import com.kingdomsai.minecraft.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Cola entre o servidor do Minecraft e o Core. Existe uma instância por mundo aberto.
 * Tudo que toca o Core roda aqui, na thread do servidor.
 */
public final class ServerRuntime {
    private static ServerRuntime INSTANCE;

    private final MinecraftServer server;
    private final ServerLevel overworld;
    private final KingdomsCore core;
    private final CommandService cli;
    private final McWorldPort port;
    private final NpcMaterializer materializer = new NpcMaterializer();
    private final ConstructionExecutor construction = new ConstructionExecutor();
    private final Path savePath;
    private KingdomsExtension extension;
    private final ConcurrentLinkedQueue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Long> managerViewers = new HashMap<>();
    private final Map<UUID, Long> pendingAutoFound = new HashMap<>();
    private long ticks;

    public static ServerRuntime get() {
        return INSTANCE;
    }

    public static void start(MinecraftServer server) {
        INSTANCE = new ServerRuntime(server);
    }

    public static void stop() {
        if (INSTANCE != null) {
            INSTANCE.save();
            INSTANCE.materializer.despawnAll(INSTANCE.core);
        }
        INSTANCE = null;
    }

    private ServerRuntime(MinecraftServer server) {
        this.server = server;
        this.overworld = server.overworld();
        this.savePath = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("kingdomsai.json");
        WorldState state = null;
        try {
            state = Persistence.load(savePath);
        } catch (Exception e) {
            KingdomsMod.LOG.error("[KingdomsAI] falha ao carregar {}: {}", savePath, e.toString());
        }
        if (state == null) state = new WorldState();
        this.core = new KingdomsCore(state, KingdomsConfig.core());
        this.port = new McWorldPort(overworld);
        core.setWorld(port);
        core.setPhysical(new McPhysicalPort(overworld, materializer, server));
        core.setMainThread(mainQueue::add);
        core.llm().configure(KingdomsConfig.llm());
        this.cli = new CommandService(core);
        cli.setNotifier((player, lines) -> {
            ServerPlayer p = server.getPlayerList().getPlayer(player);
            if (p != null) for (String l : lines) p.sendSystemMessage(ChatFormat.line(l));
            if (p != null && managerViewers.containsKey(player)) sendManager(p, false, lines);
        });
        core.bus().subscribeAll(this::notifyPlayers);
        this.extension = new KingdomsExtension(this);
        cli.addExtension(extension);
        int imported = extension.importNbt(null);
        if (imported > 0) KingdomsMod.LOG.info("[KingdomsAI] {} planta(s) .nbt importada(s) de {}", imported, KingdomsExtension.blueprintDir());
        KingdomsMod.LOG.info("[KingdomsAI] {} reino(s), {} NPC(s) carregados. {}", state.kingdoms.size(), state.npcs.size(), core.llm().status());
    }

    public MinecraftServer server() {
        return server;
    }

    public KingdomsCore core() {
        return core;
    }

    public KingdomsExtension extension() {
        return extension;
    }

    /** Envia linhas para o chat do jogador (e atualiza o Manager se estiver aberto). */
    public void notify(UUID player, List<String> lines) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p == null) return;
        for (String l : lines) p.sendSystemMessage(ChatFormat.line(l));
        if (managerViewers.containsKey(player)) sendManager(p, false, lines);
    }

    public CommandService cli() {
        return cli;
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        ticks++;
        Runnable r;
        int n = 0;
        while ((r = mainQueue.poll()) != null && n++ < 200) {
            try {
                r.run();
            } catch (RuntimeException e) {
                KingdomsMod.LOG.error("[KingdomsAI] tarefa falhou", e);
            }
        }
        try {
            core.step();
            if (ticks % 20 == 5) materializer.tick(overworld, core);
            construction.tick(overworld, core, port, materializer);
        } catch (RuntimeException e) {
            // Um bug na simulação nunca deve derrubar o servidor.
            if (ticks % 200 == 0 || ticks < 40) KingdomsMod.LOG.error("[KingdomsAI] erro na simulação", e);
        }
        if (ticks % 20 == 0) trackPlayers();
        if (ticks % 40 == 0) refreshManagers();
        if (ticks % 20 == 0) autoFound();
        if (ticks % (20 * 60 * 5) == 0) save();
    }

    /** O Core sabe onde cada rei está ("venha aqui", "me siga"). No Manager é a posição da câmera. */
    private void trackPlayers() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            track(p);
            if (p.level() != overworld) continue;
            // voltou para a vila depois de um tempo fora: relatório do que os súditos fizeram
            List<String> report = core.reports().tick(p.getUUID(), new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ()));
            if (!report.isEmpty()) notify(p.getUUID(), report);
        }
    }

    public void onPlayerLeave(ServerPlayer p) {
        core.playerLeft(p.getUUID());
    }

    /** Posição e mira do rei: "venha aqui", "quebre esse bloco", "pegue desse baú". */
    private void track(ServerPlayer p) {
        if (p.level() != overworld) return;
        core.updatePlayerPos(p.getUUID(), new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ()));
        net.minecraft.world.phys.HitResult hit = p.pick(24, 1f, false);
        Pos look = hit instanceof net.minecraft.world.phys.BlockHitResult bh && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? new Pos(bh.getBlockPos().getX(), bh.getBlockPos().getY(), bh.getBlockPos().getZ()) : null;
        core.updatePlayerLook(p.getUUID(), look, p.getDirection().getName());
    }

    public void save() {
        try {
            Persistence.save(core.snapshotForSave(), savePath);
        } catch (Exception e) {
            KingdomsMod.LOG.error("[KingdomsAI] falha ao salvar", e);
        }
    }

    // ------------------------------------------------------------------ jogadores

    public void onPlayerJoin(ServerPlayer p) {
        Kingdom k = core.kingdomOfPlayer(p.getUUID());
        if (k == null && KingdomsConfig.AUTO_FOUND_ON_JOIN.get()) {
            pendingAutoFound.put(p.getUUID(), ticks + 60);
        } else if (k != null) {
            p.sendSystemMessage(Component.literal("👑 Bem-vindo de volta, rei de " + k.name + ". ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("Pressione M para o Manager · /k help").withStyle(ChatFormatting.GRAY)));
            for (String l : core.reports().onLogin(p.getUUID())) p.sendSystemMessage(ChatFormat.line(l));
        }
    }

    private void autoFound() {
        for (Iterator<Map.Entry<UUID, Long>> it = pendingAutoFound.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (ticks < e.getValue()) continue;
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || core.kingdomOfPlayer(p.getUUID()) != null || p.level() != overworld) continue;
            Pos pos = new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
            if (core.state().territory.ownerAt(pos) != null) {
                p.sendSystemMessage(ChatFormat.line("⚠ Esta terra já tem dono. Ande até uma área livre e use /kingdom found <nome>."));
                continue;
            }
            p.sendSystemMessage(Component.literal("👑 Você é o rei de uma pequena vila.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            for (String l : cli.execute(p.getUUID(), p.getGameProfile().getName(), pos, "found Reino de " + p.getGameProfile().getName()))
                p.sendSystemMessage(ChatFormat.line(l));
        }
    }

    public List<String> runCommand(ServerPlayer p, String line) {
        track(p); // a mira de AGORA (o jogador acabou de mirar e digitar)
        Pos pos = new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
        return cli.execute(p.getUUID(), p.getGameProfile().getName(), pos, line);
    }

    public void onNpcInteract(ServerPlayer player, KingdomNpcEntity e) {
        Npc n = e.npc();
        if (n == null) return;
        cli.select(player.getUUID(), n.id);
        n.level = n.level.atLeast(IntelligenceLevel.CONTEXTUAL);
        Kingdom k = core.kingdom(n.kingdomId);
        boolean mine = k != null && player.getUUID().equals(k.rulerPlayer);
        player.sendSystemMessage(ChatFormat.line("« " + n.displayName() + " »" + (mine ? " Majestade?" : " (súdito de " + (k == null ? "?" : k.name) + ")")));
        player.sendSystemMessage(ChatFormat.line("(" + n.personalitySummary() + " Agora: " + n.activity.display + ". Lealdade " + n.loyalty + ")"));
        PacketDistributor.sendToPlayer(player, new Payloads.OpenChat("/k say "));
    }

    public void onNpcDied(KingdomNpcEntity e, DamageSource source) {
        Npc n = e.npc();
        if (n == null || !n.alive) return;
        n.alive = false;
        n.materialized = false;
        Kingdom k = core.kingdom(n.kingdomId);
        String cause = source.getEntity() instanceof ServerPlayer sp ? "morto por " + sp.getGameProfile().getName() : source.getMsgId();
        core.bus().publish(core.tick(), EventType.NPC_DIED, GameEvent.Severity.DANGER, n.kingdomId, n.id,
                n.displayName() + " morreu (" + cause + ").");
        if (n.level.ordinal() >= IntelligenceLevel.IMPORTANT.ordinal() && k != null) core.chronicle(n.displayName() + " de " + k.name + " morreu.");
        if (source.getEntity() instanceof ServerPlayer killer && k != null) {
            if (killer.getUUID().equals(k.rulerPlayer)) {
                k.stability = Math.max(0, k.stability - 8);
                k.legitimacy = Math.max(0, k.legitimacy - 6);
                for (Npc o : core.citizens(k.id)) o.loyalty = Math.max(0, o.loyalty - 3);
            } else {
                Kingdom kk = core.kingdomOfPlayer(killer.getUUID());
                if (kk != null) {
                    var att = core.diplomacy().attitude(k.id, kk.id);
                    att.hostility = Math.min(100, att.hostility + 15);
                    att.trust = Math.max(0, att.trust - 10);
                }
            }
        }
    }

    /** Eventos importantes do reino do jogador viram mensagens no chat. */
    private void notifyPlayers(GameEvent e) {
        if (e.kingdomId() == null) return;
        Kingdom k = core.kingdom(e.kingdomId());
        if (k == null || k.rulerPlayer == null) return;
        boolean important = e.severity() != GameEvent.Severity.INFO
                || e.type() == EventType.BORDER_CONTACT || e.type() == EventType.NPC_BECAME_IMPORTANT
                || e.type() == EventType.BUILDING_STARTED || e.type() == EventType.NPC_ARRIVED
                || e.type() == EventType.CHAIN_STARTED || e.type() == EventType.DOCUMENT_WRITTEN || e.type() == EventType.LETTER_DELIVERED
                || e.type() == EventType.JOB_DONE;
        if (!important || e.type() == EventType.PLAYER_ORDER || e.type() == EventType.KINGDOM_FOUNDED) return;
        ServerPlayer p = server.getPlayerList().getPlayer(k.rulerPlayer);
        if (p != null) p.sendSystemMessage(ChatFormat.prefixed(e.icon() + " " + e.message()));
    }

    // ------------------------------------------------------------------ Manager

    public void sendManager(ServerPlayer p, boolean open, List<String> feedback) {
        managerViewers.put(p.getUUID(), ticks);
        PacketDistributor.sendToPlayer(p, new Payloads.ManagerSync(ManagerSnapshot.build(this, p, feedback), open));
    }

    public void managerAction(ServerPlayer p, String command) {
        List<String> out = runCommand(p, command);
        for (String l : out) p.sendSystemMessage(ChatFormat.line(l));
        sendManager(p, false, out);
    }

    private void refreshManagers() {
        // O cliente renova o interesse a cada ~2s enquanto a tela está aberta.
        managerViewers.entrySet().removeIf(e -> ticks - e.getValue() > 100);
    }
}
