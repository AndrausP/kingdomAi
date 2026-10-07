package com.kingdomsai.minecraft;

import com.kingdomsai.core.cli.CommandService;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Office;
import com.kingdomsai.core.npc.Profession;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * /kingdom (alias /k) — encaminha para a CommandService do Core, a mesma usada pelo Manager.
 * Exemplos: /k status · /k build casa 2 · /k order construam 3 casas perto do rio
 */
public final class KingdomCommands {
    private KingdomCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        for (String root : new String[]{"kingdom", "k", "reino"}) {
            d.register(Commands.literal(root)
                    .executes(ctx -> run(ctx, "status"))
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .suggests(KingdomCommands::suggest)
                            .executes(ctx -> run(ctx, StringArgumentType.getString(ctx, "args")))));
        }
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String line) {
        ServerRuntime rt = ServerRuntime.get();
        if (rt == null) return 0;
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(ChatFormat.line("✗ Use este comando como jogador."));
            return 0;
        }
        List<String> out = rt.runCommand(p, line);
        for (String l : out) p.sendSystemMessage(ChatFormat.line(l));
        return 1;
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        ServerRuntime rt = ServerRuntime.get();
        String typed = b.getRemaining();
        int lastSpace = typed.lastIndexOf(' ');
        String current = typed.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
        String[] parts = typed.trim().isEmpty() ? new String[0] : typed.trim().split("\\s+");
        int tokenIndex = lastSpace < 0 ? 0 : (typed.endsWith(" ") ? parts.length : parts.length - 1);
        List<String> options = new ArrayList<>();
        if (tokenIndex == 0) options.addAll(rt != null ? rt.cli().allSubcommands() : CommandService.SUBCOMMANDS);
        else if (rt != null) {
            String first = parts[0].toLowerCase(Locale.ROOT);
            ServerPlayer p = ctx.getSource().getPlayer();
            Kingdom k = p == null ? null : rt.core().kingdomOfPlayer(p.getUUID());
            switch (first) {
                case "npc" -> {
                    if (tokenIndex == 1) options.addAll(List.of("list", "inspect", "talk", "promote", "demote", "job"));
                    else if (tokenIndex == 2 && k != null) for (Npc n : rt.core().citizens(k.id)) options.add(n.name.split(" ")[0]);
                    else if (tokenIndex == 3 && parts.length > 1 && parts[1].equalsIgnoreCase("promote"))
                        for (Office o : Office.values()) if (o != Office.NONE && o != Office.KING) options.add(o.name().toLowerCase(Locale.ROOT));
                    else if (tokenIndex == 3 && parts.length > 1 && parts[1].equalsIgnoreCase("job"))
                        for (Profession pr : Profession.values()) options.add(pr.name().toLowerCase(Locale.ROOT));
                }
                case "build", "construir" -> {
                    if (tokenIndex == 1) for (Blueprint bp : BlueprintLibrary.all()) options.add(bp.id());
                }
                case "army" -> {
                    if (tokenIndex == 1) options.addAll(List.of("recruit", "release"));
                }
                case "ai" -> {
                    if (tokenIndex == 1) options.addAll(List.of("explain", "ask", "status"));
                }
                case "debug" -> {
                    if (tokenIndex == 1) options.addAll(List.of("npc", "ai", "events"));
                    else if (tokenIndex == 2 && k != null) for (Npc n : rt.core().citizens(k.id)) options.add(n.name.split(" ")[0]);
                }
                case "diplomacy" -> {
                    if (tokenIndex == 1) options.addAll(List.of("list", "treaty", "trade", "gift"));
                    else if (tokenIndex == 2) for (Kingdom o : rt.core().state().kingdoms.values()) if (o != k) options.add(o.name.split(" ")[0]);
                    else if (tokenIndex == 3 && parts[1].equalsIgnoreCase("treaty")) options.addAll(List.of("nap", "trade", "alliance", "borders"));
                }
                case "war" -> {
                    if (tokenIndex == 1) options.addAll(List.of("declare", "peace"));
                    else if (tokenIndex == 2) for (Kingdom o : rt.core().state().kingdoms.values()) if (o != k) options.add(o.name.split(" ")[0]);
                }
                case "tax" -> options.addAll(List.of("up", "down", "0", "1", "2", "3", "4"));
                case "law" -> {
                    if (tokenIndex == 1) options.addAll(List.of("conscription", "migration"));
                    else options.addAll(List.of("on", "off"));
                }
                case "replay" -> {
                    if (k != null) for (Npc n : rt.core().citizens(k.id)) options.add(n.name.split(" ")[0]);
                }
                default -> {
                }
            }
        }
        SuggestionsBuilder off = b.createOffset(b.getStart() + lastSpace + 1);
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(current)) off.suggest(o);
        return off.buildFuture();
    }
}
