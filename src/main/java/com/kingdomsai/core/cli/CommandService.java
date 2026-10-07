package com.kingdomsai.core.cli;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionRequest;
import com.kingdomsai.core.action.ActionResult;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.construction.Building;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.event.GameEvent;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.KingdomPersonality;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.llm.DialogueService;
import com.kingdomsai.core.npc.*;

import java.util.*;

/**
 * CLI do reino. A mesma camada de serviço atende o comando /kingdom, os botões do Manager
 * e a caixa de ordens em linguagem natural — "a CLI usa exatamente os mesmos serviços da UI".
 */
public final class CommandService {
    public interface Notifier {
        void send(UUID player, List<String> lines);
    }

    /** Comandos que dependem do jogo (config, salvar planta do mundo...) — fornecidos pelo adaptador. */
    public interface Extension {
        /** @return true se tratou o comando. */
        boolean handle(UUID player, String playerName, Pos pos, String[] args, List<String> out);

        List<String> help();

        List<String> subcommands();
    }

    private final List<Extension> extensions = new ArrayList<>();

    public void addExtension(Extension e) {
        extensions.add(e);
    }

    public List<String> allSubcommands() {
        List<String> all = new ArrayList<>(SUBCOMMANDS);
        for (Extension e : extensions) for (String c : e.subcommands()) if (!all.contains(c)) all.add(c);
        return all;
    }

    public static final List<String> SUBCOMMANDS = List.of(
            "help", "found", "status", "npc", "say", "assign", "deadline", "cancel", "blueprint", "build", "blueprints", "projects", "army", "economy", "territory",
            "claim", "tax", "law", "diplomacy", "war", "order", "ai", "events", "chronicle", "debug", "replay", "rivals",
            "chains", "chain", "books", "book", "call", "follow", "dismiss", "village", "job", "jobs", "bag", "report", "mark", "marks");

    private final KingdomsCore core;
    private Notifier notifier = (p, l) -> {};
    private final Map<UUID, UUID> selectedNpc = new HashMap<>();

    public CommandService(KingdomsCore core) {
        this.core = core;
    }

    public void setNotifier(Notifier n) {
        this.notifier = n;
    }

    public void select(UUID player, UUID npc) {
        selectedNpc.put(player, npc);
    }

    public UUID selected(UUID player) {
        return selectedNpc.get(player);
    }

    public List<String> execute(UUID player, String playerName, Pos playerPos, String line) {
        List<String> out = new ArrayList<>();
        core.updatePlayerPos(player, playerPos);
        try {
            run(player, playerName, playerPos, line == null ? "" : line.trim(), out);
        } catch (RuntimeException e) {
            out.add("✗ Erro interno: " + e);
        }
        return out;
    }

    private void run(UUID player, String playerName, Pos pos, String line, List<String> out) {
        String[] a = line.isEmpty() ? new String[]{"status"} : line.split("\\s+");
        String cmd = a[0].toLowerCase(Locale.ROOT);
        Kingdom k = core.kingdomOfPlayer(player);
        if (cmd.equals("help") || cmd.equals("ajuda")) {
            help(out);
            for (Extension e : extensions) out.addAll(e.help());
            return;
        }
        for (Extension e : extensions) if (e.handle(player, playerName, pos, a, out)) return;
        if (cmd.equals("found") || cmd.equals("fundar")) {
            found(player, playerName, pos, rest(a, 1), out);
            return;
        }
        if (k == null) {
            out.add("✗ Você ainda não governa um reino. Use /kingdom found <nome> (ou entre num mundo novo).");
            return;
        }
        switch (cmd) {
            case "status", "s" -> status(k, out);
            case "population", "pop" -> npcList(k, a.length > 1 ? a[1] : null, out);
            case "npc" -> npc(player, playerName, k, a, out);
            case "say", "falar", "dizer" -> {
                UUID sel = selectedNpc.get(player);
                Npc n = core.npc(sel);
                if (n == null || !n.alive) {
                    out.add("✗ Clique com o botão direito em um NPC para conversar, ou use /kingdom npc talk <nome> <texto>.");
                    return;
                }
                talk(player, playerName, n, rest(a, 1), out);
            }
            case "assign", "designar" -> {
                // assign <profissão> [qtd] [de <profissão>]
                if (a.length < 2) {
                    out.add("Uso: assign <profissão> [quantidade] [de <profissão>]");
                    return;
                }
                List<String> kv = new ArrayList<>(List.of("profession", a[1], "amount", a.length > 2 && a[2].matches("\\d+") ? a[2] : "1"));
                int fromIdx = indexOf(a, "de", "from");
                if (fromIdx > 0 && fromIdx + 1 < a.length) kv.addAll(List.of("from", a[fromIdx + 1]));
                act(k, player, ActionType.WORK, out, kv.toArray(String[]::new));
            }
            case "build", "construir" -> {
                if (a.length < 2) {
                    out.add("Uso: build <planta> [qtd] [para <nome>] [prazo <5m|1d|amanha>]");
                    out.add("     build custom tipo=casa largura=9 profundidade=7 andares=2 telhado=duas_aguas parede=pedra [prazo 1d]");
                    return;
                }
                List<String> kv = new ArrayList<>(List.of("blueprint", a[1]));
                if (a.length > 2 && a[2].matches("\\d+")) kv.addAll(List.of("amount", a[2]));
                int forIdx = indexOf(a, "para", "for");
                if (forIdx > 0 && forIdx + 1 < a.length) kv.addAll(List.of("for", a[forIdx + 1]));
                int dIdx = indexOf(a, "prazo", "deadline", "ate");
                if (dIdx > 0 && dIdx + 1 < a.length) kv.addAll(List.of("deadline", a[dIdx + 1]));
                kv.addAll(keyValues(a, 2));
                act(k, player, ActionType.BUILD, out, kv.toArray(String[]::new));
            }
            case "deadline", "prazo" -> {
                if (a.length < 2) {
                    out.add("Uso: deadline [nº da obra] <tempo>   ex.: /k deadline 2 5m · /k prazo amanha");
                    return;
                }
                if (a.length >= 3) act(k, player, ActionType.DEADLINE, out, "building", a[1], "deadline", a[2]);
                else act(k, player, ActionType.DEADLINE, out, "deadline", a[1]);
            }
            case "cancel", "cancelar" -> act(k, player, ActionType.CANCEL_BUILD, out, "building", a.length > 1 ? rest(a, 1) : "");
            case "blueprints", "plantas" -> blueprintList(out);
            case "blueprint", "planta" -> blueprintCmd(k, player, a, out);
            case "projects", "obras" -> projects(k, out);
            case "army", "exercito" -> army(k, player, a, out);
            case "economy", "economia" -> economy(k, out);
            case "territory", "territorio" -> territory(k, pos, out);
            case "claim" -> act(k, player, ActionType.CLAIM, out, "amount", a.length > 1 ? a[1] : "1");
            case "tax", "impostos" -> act(k, player, ActionType.TAX, out, "level", a.length > 1 ? a[1] : "?");
            case "law", "lei" -> {
                if (a.length < 3) {
                    out.add("Uso: law <conscription|migration> <on|off>");
                    return;
                }
                act(k, player, ActionType.LAW, out, "law", a[1], "value", a[2]);
            }
            case "diplomacy", "diplomacia" -> diplomacy(k, player, a, out);
            case "war", "guerra" -> {
                if (a.length < 3) {
                    out.add("Uso: war declare <reino> | war peace <reino>");
                    return;
                }
                act(k, player, a[1].startsWith("p") ? ActionType.MAKE_PEACE : ActionType.DECLARE_WAR, out, "target", rest(a, 2));
            }
            case "order", "ordem", "mande" -> order(player, rest(a, 1), out);
            case "ai", "ia" -> ai(k, player, a, out);
            case "events", "eventos" -> events(k, a.length > 1 ? parseInt(a[1], 15) : 15, out);
            case "chronicle", "cronica", "history" -> {
                out.add("# Crônica do mundo");
                List<String> c = core.state().chronicle;
                for (String s : c.subList(Math.max(0, c.size() - 20), c.size())) out.add(s);
            }
            case "debug" -> debug(k, a, out);
            case "replay" -> replay(k, rest(a, 1), out);
            case "call", "chamar", "chame" -> callNpc(k, player, a, ActionType.SUMMON, out);
            case "follow", "seguir", "siga" -> callNpc(k, player, a, ActionType.FOLLOW, out);
            case "dismiss", "dispensar" -> callNpc(k, player, a, ActionType.DISMISS, out);
            case "village", "vila" -> {
                out.add("# " + k.name);
                out.add(com.kingdomsai.core.construction.VillageWall.describe(core, k));
                out.add("Para cercar tudo: /k build muralha [height=3-6] · ou peça \"construa um muro ao redor da vila\".");
            }
            case "report", "relatorio" -> out.addAll(core.reports().latest(player));
            case "mark", "marcar" -> {
                if (a.length < 2) {
                    out.add("Uso: mark <spawn|praca|mina|bosque> [x y z] · mark <tipo> remover · (ou use a Bandeira do Reino)");
                    return;
                }
                if (a.length >= 3 && a[2].matches("remover|remove|apagar"))
                    act(k, player, ActionType.MARK, out, "kind", a[1], "remove", "true");
                else if (a.length >= 5) act(k, player, ActionType.MARK, out, "kind", a[1], "x", a[2], "y", a[3], "z", a[4]);
                else act(k, player, ActionType.MARK, out, "kind", a[1]);
            }
            case "marks", "marcos" -> {
                out.add("# Marcos de " + k.name);
                for (var m : com.kingdomsai.core.kingdom.Marker.values()) {
                    Pos p = k.markers.get(m);
                    out.add(m.display + ": " + (p == null ? "não marcado (padrão do reino)" : p.toString()));
                }
            }
            case "jobs", "ordens" -> jobs(k, out);
            case "job", "tarefa" -> job(k, player, a, out);
            case "bag", "mochila" -> {
                Npc n = a.length > 1 ? core.findNpc(k.id, rest(a, 1)) : core.npc(selected(player));
                if (n == null) out.add("✗ Uso: bag <nome> (ou selecione alguém).");
                else out.add("Mochila de " + n.name + ": " + com.kingdomsai.core.skill.SkillSystem.summary(n.bag)
                        + " (" + n.bag.values().stream().mapToInt(Integer::intValue).sum() + "/" + com.kingdomsai.core.skill.JobPlanner.BAG_CAPACITY + ")");
            }
            case "chains", "cadeias", "rotinas" -> chains(k, out);
            case "chain", "cadeia", "rotina" -> chain(k, player, a, out);
            case "books", "livros", "biblioteca" -> books(k, out);
            case "book", "livro" -> book(k, rest(a, 1), out);
            case "rivals" -> {
                int n = spawnRivals(k, a.length > 1 ? parseInt(a[1], 1) : 1);
                out.add(n > 0 ? "✓ " + n + " reino(s) rival(is) fundado(s)." : "✗ Não foi possível criar rivais.");
            }
            default -> {
                // Qualquer outra coisa é tratada como ordem em linguagem natural.
                order(player, line, out);
            }
        }
    }

    // ------------------------------------------------------------------ comandos

    private void help(List<String> out) {
        out.add("# Kingdoms AI — comandos (/kingdom ou /k)");
        out.add("found <nome> · status · events · chronicle");
        out.add("npc list [profissão] · npc inspect <nome> · npc talk <nome> <texto>");
        out.add("npc promote <nome> <cargo> · npc demote <nome> · npc job <nome> <profissão>");
        out.add("assign <profissão> [qtd] [de <profissão>] — remaneja trabalhadores");
        out.add("say <texto> — fala com o NPC selecionado (botão direito nele)");
        out.add("build <planta> [qtd] [para <nome>] [prazo 5m|1d] · build custom tipo=casa largura=9 andares=2 parede=pedra");
        out.add("projects · deadline <nº> <tempo> · cancel <nº> · blueprints · blueprint design|show|delete|materials");
        out.add("army · army recruit <n> · army release <n> [profissão]");
        out.add("economy · territory · claim [n] · tax <0-4|up|down> · law <conscription|migration> <on|off>");
        out.add("diplomacy list · diplomacy treaty <reino> <tipo> · diplomacy trade <reino> <qtd> <recurso> <qtd> <recurso>");
        out.add("diplomacy gift <reino> <qtd> <recurso> · war declare|peace <reino>");
        out.add("chains · chain <nº> [stop|resume] · chain new <modelo> [npc=Nome] [k=v...] — rotinas/cadeias de trabalho");
        out.add("  modelos: minerar_ferreiro, plantar_colher, lenha, pedra, escrever topic=..., ler, carta to=Nome text=...");
        out.add("books · book <nº> — livros e cartas do reino");
        out.add("job <nome> break|dig [3x3x3]|tunnel [n]|clear [5x5]|chop — mire no bloco/árvore antes");
        out.add("job <nome> take <qtd> <item> [armazem] · put [qtd item] · craft <qtd> <item> [entregar] · give · job cancel <nº> · jobs · bag <nome>");
        out.add("mark <spawn|praca|mina|bosque> [x y z] · marks — pontos do reino (ou use a Bandeira do Reino: /k bandeira)");
        out.add("report — o que aconteceu enquanto você esteve fora (as ordens continuam mesmo longe)");
        out.add("call [nome] · follow [nome] [minutos] · dismiss [nome] — chama até onde você está (no Manager: tecla G)");
        out.add("village — tamanho da vila · build muralha [height=4] — muro sob medida ao redor da vila");
        out.add("order <texto livre> · ai explain · ai ask <pergunta> · ai status");
        out.add("debug npc <nome> · debug ai [reino] · debug events · replay <nome> · rivals [n]");
        out.add("Tecla M: Manager Mode. Exemplo: /k order construam 2 casas e recrutem 3 soldados");
    }

    // ------------------------------------------------------------------ cadeias de trabalho, livros e cartas

    private void chains(Kingdom k, List<String> out) {
        List<com.kingdomsai.core.work.WorkChain> list = core.work().chains(k.id);
        out.add("# Cadeias de trabalho de " + k.name);
        if (list.isEmpty()) {
            out.add("Nenhuma. Fale com um súdito (\"minere ferro e leve ao ferreiro\") ou use /k chain new <modelo>.");
            return;
        }
        for (var c : list) {
            if (!c.live() && c.createdTick < core.tick() - 24000L * 3) continue;
            StringBuilder who = new StringBuilder();
            for (var r : c.roles.values()) {
                Npc n = core.npc(r.npcId);
                if (n != null) who.append(who.length() == 0 ? "" : ", ").append(n.name).append(c.live() ? " " + r.state.display : "");
            }
            String head = (c.status == com.kingdomsai.core.work.WorkChain.Status.BROKEN ? "⚠ " : "") + "#" + c.number + " «" + c.name + "» — "
                    + c.status.display + " · ciclos " + c.cycles + " · " + who;
            out.add(c.status == com.kingdomsai.core.work.WorkChain.Status.BROKEN ? head + " · " + c.brokenReason : head);
        }
        out.add("Detalhes: /k chain <nº>");
    }

    private void chain(Kingdom k, UUID player, String[] a, List<String> out) {
        if (a.length < 2) {
            out.add("Uso: chain <nº> [stop|resume] · chain new <modelo> [npc=Nome] [amount=8] [forge=true] [topic=...] [to=Nome] [text=...]");
            return;
        }
        if (a[1].equalsIgnoreCase("new") || a[1].equalsIgnoreCase("nova")) {
            if (a.length < 3) {
                out.add("Modelos:");
                com.kingdomsai.core.work.ChainTemplates.TEMPLATES.forEach((id, d) -> out.add("  " + id + " — " + d));
                return;
            }
            List<String> kv = new ArrayList<>(List.of("template", a[2]));
            StringBuilder free = new StringBuilder();
            String lastKey = null;
            for (int i = 3; i < a.length; i++) {
                int eq = a[i].indexOf('=');
                if (eq > 0) {
                    lastKey = a[i].substring(0, eq).toLowerCase(Locale.ROOT);
                    kv.add(lastKey);
                    kv.add(a[i].substring(eq + 1));
                } else if (lastKey != null) {
                    // valores com espaço: text=a colheita foi boa
                    int idx = kv.size() - 1;
                    kv.set(idx, kv.get(idx) + " " + a[i]);
                } else free.append(a[i]).append(' ');
            }
            if (free.length() > 0 && !kv.contains("npc")) {
                kv.add("npc");
                kv.add(free.toString().trim());
            }
            act(k, player, ActionType.CHAIN, out, kv.toArray(new String[0]));
            return;
        }
        var c = core.work().find(k.id, a[1]);
        if (c == null) {
            out.add("✗ Cadeia não encontrada: " + a[1] + ". Veja /k chains.");
            return;
        }
        String op = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "show";
        switch (op) {
            case "stop", "parar", "encerrar" -> act(k, player, ActionType.STOP_CHAIN, out, "chain", String.valueOf(c.number));
            case "resume", "retomar" -> out.add((c.status == com.kingdomsai.core.work.WorkChain.Status.ACTIVE ? "✓ " : "⚠ ") + core.work().resume(c));
            default -> out.addAll(core.work().describe(c));
        }
    }

    private void jobs(Kingdom k, List<String> out) {
        out.add("# Ordens com as mãos — " + k.name);
        var list = core.skills().jobs(k.id);
        if (list.isEmpty()) out.add("Nenhuma. Mire num bloco e diga a um súdito: \"quebre esse bloco\", \"corte essa árvore\", \"faça 4 tochas\".");
        for (var j : list) {
            if (!j.status.live() && j.createdTick < core.tick() - 24000L) continue;
            Npc n = core.npc(j.npcId);
            out.add((j.status == com.kingdomsai.core.skill.PhysicalJob.Status.FAILED ? "⚠ " : "") + "#" + j.number + " " + (n == null ? "?" : n.name)
                    + " — " + j.name + " · " + j.status.display + (j.reason.isBlank() ? "" : ": " + j.reason));
        }
    }

    private void job(Kingdom k, UUID player, String[] a, List<String> out) {
        if (a.length < 2) {
            out.add("Uso: job <nome> <break|dig|tunnel|clear|chop|take|put|craft|give> ... · job <nº> · job cancel <nº|nome>");
            return;
        }
        if (a[1].equalsIgnoreCase("cancel") || a[1].equalsIgnoreCase("cancelar")) {
            act(k, player, ActionType.CANCEL_JOB, out, "job", a.length > 2 ? rest(a, 2) : "");
            return;
        }
        if (a[1].matches("#?\\d+")) {
            var j = core.skills().find(k.id, a[1]);
            if (j == null) out.add("✗ Ordem não encontrada.");
            else out.addAll(core.skills().describe(j));
            return;
        }
        if (a.length < 3) {
            out.add("✗ Diga o que fazer: job " + a[1] + " dig 3x3x3");
            return;
        }
        List<String> kv = new ArrayList<>(List.of("npc", a[1], "kind", a[2]));
        String kind = a[2].toLowerCase(Locale.ROOT);
        List<String> rest = new ArrayList<>(Arrays.asList(a).subList(3, a.length));
        for (Iterator<String> it = rest.iterator(); it.hasNext(); ) {
            String w = it.next().toLowerCase(Locale.ROOT);
            if (w.matches("entregar|entregue|give|pra-mim")) {
                kv.addAll(List.of("give", "true"));
                it.remove();
            } else if (w.matches("armazem|armazém|storage")) {
                kv.addAll(List.of(kind.startsWith("put") || kind.startsWith("guard") ? "to" : "from", "storage"));
                it.remove();
            } else if (w.matches("mira|look|esse")) {
                kv.addAll(List.of(kind.startsWith("put") || kind.startsWith("guard") ? "to" : "from", "look"));
                it.remove();
            } else if (w.matches("\\d+x\\d+(x\\d+)?")) {
                kv.addAll(List.of("size", w));
                it.remove();
            }
        }
        if (!rest.isEmpty() && rest.get(0).matches("\\d+")) {
            kv.addAll(List.of(kind.startsWith("tun") ? "length" : "count", rest.remove(0)));
        }
        if (!rest.isEmpty()) kv.addAll(List.of("item", String.join(" ", rest)));
        act(k, player, ActionType.JOB, out, kv.toArray(new String[0]));
    }

    /** call/follow/dismiss: sem nome usa o súdito selecionado (clique no Manager ou botão direito). */
    private void callNpc(Kingdom k, UUID player, String[] a, ActionType type, List<String> out) {
        String name = null;
        String minutes = null;
        if (a.length > 1) {
            if (type == ActionType.FOLLOW && a[a.length - 1].matches("\\d+")) {
                minutes = a[a.length - 1];
                name = a.length > 2 ? rest(Arrays.copyOf(a, a.length - 1), 1) : null;
            } else name = rest(a, 1);
        }
        if (name == null || name.isBlank()) {
            Npc sel = core.npc(selected(player));
            if (sel == null) {
                out.add("✗ Diga quem (ex.: /k call Aldren) ou selecione alguém no Manager.");
                return;
            }
            name = sel.name;
        }
        if (minutes != null) act(k, player, type, out, "npc", name, "minutes", minutes);
        else act(k, player, type, out, "npc", name);
    }

    private List<com.kingdomsai.core.work.Document> documents(Kingdom k) {
        List<com.kingdomsai.core.work.Document> list = new ArrayList<>();
        for (var d : core.state().documents.values()) if (k.id.equals(d.kingdomId)) list.add(d);
        list.sort(Comparator.comparingLong(d -> d.tick));
        return list;
    }

    private void books(Kingdom k, List<String> out) {
        List<com.kingdomsai.core.work.Document> list = documents(k);
        out.add("# Biblioteca e cartas de " + k.name);
        if (list.isEmpty()) out.add("Nenhum livro ainda. Construa uma biblioteca e peça a um estudioso: \"escreva um livro sobre o reino\".");
        for (int i = 0; i < list.size(); i++) {
            var d = list.get(i);
            Npc to = core.npc(d.recipientId);
            out.add((i + 1) + ". " + (d.kind == com.kingdomsai.core.work.Document.Kind.BOOK ? "📖 " : "✉ ") + "«" + d.title + "» — " + d.authorName
                    + (d.kind == com.kingdomsai.core.work.Document.Kind.BOOK ? " · lido por " + d.readers.size()
                    : " · " + (d.delivered ? "entregue a " : "a caminho de ") + (to == null ? "?" : to.name)));
        }
    }

    private void book(Kingdom k, String ref, List<String> out) {
        List<com.kingdomsai.core.work.Document> list = documents(k);
        com.kingdomsai.core.work.Document d = null;
        int n = parseInt(ref.trim(), -1);
        if (n >= 1 && n <= list.size()) d = list.get(n - 1);
        else for (var x : list) if (!ref.isBlank() && Text.norm(x.title).contains(Text.norm(ref))) d = x;
        if (d == null) {
            out.add("✗ Livro não encontrado. Veja /k books.");
            return;
        }
        out.add("# " + d.title);
        for (String line : d.text.split("\n")) out.add(line.isBlank() ? " " : line);
    }

    private void found(UUID player, String playerName, Pos pos, String name, List<String> out) {
        if (core.kingdomOfPlayer(player) != null) {
            out.add("✗ Você já é rei de " + core.kingdomOfPlayer(player).name + ".");
            return;
        }
        if (core.state().territory.ownerAt(pos) != null) {
            out.add("✗ Esta terra já pertence a " + core.kingdom(core.state().territory.ownerAt(pos)).name + ".");
            return;
        }
        if (name.isBlank()) name = "Reino de " + playerName;
        Kingdom k = core.foundKingdom(ContextSafe.name(name), player, playerName, pos, KingdomPersonality.balanced(), core.config().startingCitizens);
        out.add("✓ " + k.name + " foi fundado! Você é o rei de " + core.population(k.id) + " súditos.");
        int rivals = 0;
        if (core.config().aiKingdomsEnabled) {
            long existing = core.state().kingdoms.values().stream().filter(x -> !x.isPlayerKingdom()).count();
            if (existing < core.config().rivalKingdoms) rivals = spawnRivals(k, (int) (core.config().rivalKingdoms - existing));
        }
        if (rivals > 0) out.add("⚠ " + rivals + " reino(s) vizinho(s) já existem nesta região. Eles têm seus próprios planos.");
        out.add("Pressione M para abrir o Manager, ou /k help. Seus construtores já começaram o Salão Real.");
    }

    public int spawnRivals(Kingdom near, int n) {
        int made = 0;
        Random r = core.rng();
        for (int i = 0; i < n * 6 && made < n; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            int dist = core.config().rivalDistance + r.nextInt(120);
            Pos c = near.center.offset((int) (Math.cos(ang) * dist), 0, (int) (Math.sin(ang) * dist));
            boolean free = true;
            for (Kingdom o : core.state().kingdoms.values()) if (o.center.distXZ(c) < core.config().rivalDistance * 0.8) free = false;
            if (!free) continue;
            int y = core.world().surfaceY(c.x(), c.z());
            if (y != Integer.MIN_VALUE) c = new Pos(c.x(), y, c.z());
            String name = NameGenerator.kingdom(r);
            while (core.findKingdom(name) != null) name = NameGenerator.kingdom(r);
            Kingdom k = core.foundKingdom(name, null, null, c, KingdomPersonality.random(r), 8 + r.nextInt(5));
            core.director().bootstrap(k);
            made++;
        }
        return made;
    }

    private void status(Kingdom k, List<String> out) {
        Map<ResourceType, Double> d = core.economy().projected(k);
        out.add("# " + k.name.toUpperCase() + " — Dia " + core.day());
        out.add("População " + core.population(k.id) + "/" + core.housingCapacity(k.id) + " · Exército " + core.military(k.id)
                + " · Território " + String.format(Locale.ROOT, "%.2f", core.state().territory.areaKm2(k.id)) + " km²");
        StringBuilder sb = new StringBuilder();
        for (ResourceType r : ResourceType.values())
            sb.append(r.display).append(' ').append(Text.fmt(k.get(r))).append(delta(d.get(r))).append(" · ");
        out.add(sb.substring(0, sb.length() - 3));
        out.add("Moral " + (int) k.morale + " · Estabilidade " + (int) k.stability + " · Legitimidade " + (int) k.legitimacy
                + " · Impostos " + k.laws.taxLevel + "/4");
        List<Building> proj = core.construction().projects(k.id);
        if (!proj.isEmpty()) {
            StringBuilder p = new StringBuilder("Obras: ");
            for (Building b : proj) p.append(b.blueprint().displayName()).append(' ').append((int) b.percent()).append("%, ");
            out.add(p.substring(0, p.length() - 2));
        }
        for (GameEvent e : core.bus().log().recent(4, e -> k.id.equals(e.kingdomId()) && e.severity() != GameEvent.Severity.INFO))
            out.add(e.icon() + " " + e.message());
    }

    private static String delta(double v) {
        if (Math.abs(v) < 0.05) return "";
        return " (" + (v > 0 ? "+" : "") + Text.fmt(v) + ")";
    }

    private void npcList(Kingdom k, String filter, List<String> out) {
        Profession pf = filter == null ? null : Profession.parse(filter);
        List<Npc> list = core.citizens(k.id);
        list.sort(Comparator.comparing((Npc n) -> n.office == Office.NONE).thenComparing(n -> n.profession).thenComparing(n -> n.name));
        out.add("# Súditos de " + k.name + " (" + list.size() + ")");
        for (Npc n : list) {
            if (pf != null && n.profession != pf) continue;
            out.add(n.displayName() + " · " + n.activity.display + " · lealdade " + n.loyalty
                    + (n.level.ordinal() >= IntelligenceLevel.IMPORTANT.ordinal() ? " ★" : ""));
        }
    }

    private void npc(UUID player, String playerName, Kingdom k, String[] a, List<String> out) {
        if (a.length < 2) {
            out.add("Uso: npc list|inspect|talk|promote|demote|job ...");
            return;
        }
        String sub = a[1].toLowerCase();
        if (sub.equals("list")) {
            npcList(k, a.length > 2 ? a[2] : null, out);
            return;
        }
        if (a.length < 3) {
            out.add("✗ Informe o nome do NPC.");
            return;
        }
        Npc n = core.findNpc(k.id, a[2]);
        if (n == null && (sub.equals("inspect") || sub.equals("talk"))) n = core.findNpc(null, a[2]);
        switch (sub) {
            case "inspect", "info" -> {
                if (n == null) {
                    out.add("✗ Não encontrei " + a[2] + ".");
                    return;
                }
                selectedNpc.put(player, n.id);
                inspect(n, out);
            }
            case "talk", "falar" -> {
                if (n == null) {
                    out.add("✗ Não encontrei " + a[2] + ".");
                    return;
                }
                selectedNpc.put(player, n.id);
                talk(player, playerName, n, rest(a, 3), out);
            }
            case "promote", "nomear" -> act(k, player, ActionType.PROMOTE, out, "npc", a[2], "office", rest(a, 3));
            case "demote" -> act(k, player, ActionType.DEMOTE, out, "npc", a[2]);
            case "job", "work", "profissao" -> act(k, player, ActionType.WORK, out, "npc", a[2], "profession", a.length > 3 ? a[3] : "?");
            default -> out.add("✗ Subcomando desconhecido: " + sub);
        }
    }

    public void inspect(Npc n, List<String> out) {
        Kingdom k = core.kingdom(n.kingdomId);
        out.add("# " + n.displayName() + (k != null ? " — " + k.name : ""));
        out.add(n.personalitySummary());
        out.add("Nível de IA: " + n.level + " · Lealdade " + n.loyalty + " · Fama " + n.fame + " · Fome " + (int) n.hunger + " · Energia " + (int) n.energy);
        out.add("Agora: " + n.activity.display + (n.currentTask.isBlank() ? "" : " — " + n.currentTask));
        Building home = n.homeId == null ? null : core.state().buildings.get(n.homeId);
        out.add("Casa: " + (home == null ? "sem casa" : home.blueprint().displayName() + " em " + home.origin.x() + ", " + home.origin.z()));
        StringBuilder rel = new StringBuilder("Relações: ");
        n.relations.entrySet().stream().limit(5).forEach(e -> {
            Npc o = core.npc(e.getKey());
            String who = o != null ? o.name : (k != null && e.getKey().equals(k.rulerPlayer) ? "o Rei" : null);
            if (who != null) rel.append(who).append(" (").append(e.getValue().label()).append("), ");
        });
        if (rel.length() > 11) out.add(rel.substring(0, rel.length() - 2));
        if (!n.memories.isEmpty()) {
            out.add("Memórias:");
            n.memories.stream().sorted(Comparator.comparingInt(m -> -m.importance())).limit(4)
                    .forEach(m -> out.add("  · " + m.text() + " [" + m.importance() + "]"));
        }
    }

    private void talk(UUID player, String playerName, Npc n, String text, List<String> out) {
        if (text.isBlank()) {
            out.add("Fale com " + n.name + ": /kingdom say <texto>");
            return;
        }
        out.add("‹Você → " + n.name + "› " + text);
        core.dialogue().talk(player, playerName, n, text, reply -> notifier.send(player, render(reply)));
    }

    private void order(UUID player, String text, List<String> out) {
        if (text.isBlank()) {
            out.add("Uso: order <ordem em linguagem natural>");
            return;
        }
        out.add("‹Ordem real› " + text);
        core.dialogue().order(player, text, reply -> notifier.send(player, render(reply)));
    }

    public static List<String> render(DialogueService.Reply r) {
        List<String> lines = new ArrayList<>();
        lines.add("«" + r.speaker() + "» " + r.text());
        lines.addAll(r.actionLines());
        if (r.fallback() && !r.note().isBlank()) lines.add("(IA indisponível: " + r.note() + " — respondido pelas regras)");
        return lines;
    }

    private void projects(Kingdom k, List<String> out) {
        List<Building> ps = core.construction().projects(k.id);
        out.add("# Obras (" + ps.size() + ")");
        int i = 1;
        for (Building b : ps) {
            List<Npc> active = core.construction().activeBuilders(b);
            String prazo = b.deadlineTick <= 0 ? "sem prazo"
                    : b.deadlineTick < core.tick() ? "ATRASADA"
                    : "prazo em " + com.kingdomsai.core.construction.ConstructionSystem.formatDuration((b.deadlineTick - core.tick()) / 20.0);
            out.add("#" + (i++) + " " + b.blueprint().displayName() + " " + (int) b.percent() + "% · ETA "
                    + core.construction().formatEta(b) + " · " + prazo + " · " + active.size() + " construtor(es)"
                    + (active.isEmpty() ? "" : " (" + String.join(", ", active.stream().map(n -> n.name).toList()) + ")")
                    + " · em " + b.origin.x() + ", " + b.origin.z());
        }
        if (!ps.isEmpty()) out.add("Prazo: /k deadline <nº> <5m|1d|amanha> · Cancelar: /k cancel <nº>");
        long done = core.buildings(k.id).stream().filter(Building::isComplete).count();
        out.add("Prédios concluídos: " + done + " · capacidade de moradia " + core.housingCapacity(k.id));
    }

    private void army(Kingdom k, UUID player, String[] a, List<String> out) {
        if (a.length >= 2 && (a[1].startsWith("recr") || a[1].startsWith("alist"))) {
            act(k, player, ActionType.RECRUIT, out, "amount", a.length > 2 ? a[2] : "1");
            return;
        }
        if (a.length >= 2 && (a[1].startsWith("rel") || a[1].startsWith("lib"))) {
            List<String> kv = new ArrayList<>(List.of("amount", a.length > 2 ? a[2] : "1"));
            if (a.length > 3) kv.addAll(List.of("profession", a[3]));
            act(k, player, ActionType.RELEASE, out, kv.toArray(String[]::new));
            return;
        }
        out.add("# Exército de " + k.name);
        out.add("Soldados " + core.count(k.id, Profession.SOLDIER) + " · Guardas " + core.count(k.id, Profession.GUARD)
                + " · Armas " + Text.fmt(k.get(ResourceType.WEAPONS)) + " · Quartéis " + core.completedOf(k.id, "barracks"));
        for (Npc n : core.citizens(k.id)) if (n.profession.isMilitary()) out.add("  " + n.displayName() + " · " + n.activity.display);
        out.add("(Batalhas, legiões e logística: Fase 9.) army recruit <n> · army release <n> [profissão]");
    }

    private void economy(Kingdom k, List<String> out) {
        Map<ResourceType, Double> d = core.economy().projected(k);
        out.add("# Economia de " + k.name + " (por ciclo de " + core.config().economicTickSeconds + "s)");
        for (ResourceType r : ResourceType.values())
            out.add(r.display + ": " + Text.fmt(k.get(r)) + delta(d.get(r)));
        StringBuilder jobs = new StringBuilder("Trabalho: ");
        for (Profession p : Profession.values()) {
            int c = core.count(k.id, p);
            if (c > 0) jobs.append(p.display).append(' ').append(c).append(", ");
        }
        out.add(jobs.substring(0, jobs.length() - 2));
        double ft = core.economy().foodTicksLeft(k);
        if (ft != Double.POSITIVE_INFINITY) out.add("⚠ Comida acaba em ~" + Text.fmt(ft * core.config().economicTickSeconds / 60.0) + " min.");
    }

    private void territory(Kingdom k, Pos pos, List<String> out) {
        var t = core.state().territory;
        out.add("# Território de " + k.name + ": " + t.countOwned(k.id) + " células de " + t.cellSize + "x" + t.cellSize
                + " (" + String.format(Locale.ROOT, "%.2f", t.areaKm2(k.id)) + " km²)");
        UUID here = t.ownerAt(pos);
        Kingdom hk = core.kingdom(here);
        out.add("Você está em: " + (hk == null ? "terra sem dono" : hk.name)
                + (hk == k ? " · " + (int) t.distanceToBorder(k.id, pos) + " blocos da fronteira" : ""));
        out.add("Fronteira livre para expansão: " + t.claimableFrontier(k.id, k.center).size() + " células (claim custa 40 de ouro).");
        for (Kingdom o : core.state().kingdoms.values())
            if (o != k) out.add(o.name + " fica a " + (int) o.center.distXZ(k.center) + " blocos ("
                    + direction(k.center, o.center) + ")" + (t.bordersTouch(k.id, o.id) ? " — FRONTEIRA COMUM" : ""));
    }

    public static String direction(Pos from, Pos to) {
        double ang = Math.toDegrees(Math.atan2(to.z() - from.z(), to.x() - from.x()));
        String[] dirs = {"leste", "sudeste", "sul", "sudoeste", "oeste", "noroeste", "norte", "nordeste"};
        int idx = (int) Math.round(((ang % 360) + 360) % 360 / 45.0) % 8;
        return dirs[idx];
    }

    private void diplomacy(Kingdom k, UUID player, String[] a, List<String> out) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        switch (sub) {
            case "treaty", "tratado" -> {
                if (a.length < 4) {
                    out.add("Uso: diplomacy treaty <reino> <nap|trade|alliance|borders>");
                    return;
                }
                act(k, player, ActionType.NEGOTIATE, out, "target", a[2], "treaty", rest(a, 3));
            }
            case "trade", "troca" -> {
                if (a.length < 7) {
                    out.add("Uso: diplomacy trade <reino> <qtd> <recurso que dou> <qtd> <recurso que quero>");
                    return;
                }
                act(k, player, ActionType.NEGOTIATE, out, "target", a[2], "give_amount", a[3], "give", a[4], "want_amount", a[5], "want", a[6]);
            }
            case "gift", "presente" -> {
                if (a.length < 5) {
                    out.add("Uso: diplomacy gift <reino> <qtd> <recurso>");
                    return;
                }
                act(k, player, ActionType.GIVE, out, "target", a[2], "amount", a[3], "resource", a[4]);
            }
            default -> {
                out.add("# Diplomacia — honra " + (int) k.honor + ", confiabilidade " + (int) k.reliability);
                for (Kingdom o : core.state().kingdoms.values()) {
                    if (o == k) continue;
                    Diplomacy.Link l = core.diplomacy().link(k.id, o.id);
                    Diplomacy.Attitude att = core.diplomacy().attitude(o.id, k.id);
                    out.add(o.name + " [" + l.state.display + "] — " + att.label() + " · confiança " + (int) att.trust + " · medo "
                            + (int) att.fear + " · hostilidade " + (int) att.hostility + " · " + o.personality.summary());
                    for (Diplomacy.Treaty t : core.diplomacy().treaties(k.id, o.id))
                        out.add("   ↳ " + t.type.display + " (expira no dia " + (t.expiresTick / 24000 + 1) + ")");
                }
                if (core.state().kingdoms.size() == 1) out.add("Nenhum outro reino conhecido. (/k rivals para criar um)");
            }
        }
    }

    private void ai(Kingdom k, UUID player, String[] a, List<String> out) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "status";
        switch (sub) {
            case "explain", "explicar" -> out.add("«Conselho» " + core.advisor().explain(k));
            case "ask", "perguntar" -> order(player, rest(a, 2), out);
            default -> {
                out.add("# IA");
                out.add(core.llm().status());
                out.add("Reinos de IA usam Utility AI (sem LLM). NPCs comuns são bots; a LLM entra em conversas e ordens.");
            }
        }
    }

    private void events(Kingdom k, int n, List<String> out) {
        out.add("# Eventos recentes");
        for (GameEvent e : core.bus().log().recent(n, e -> k.id.equals(e.kingdomId()) || e.kingdomId() == null))
            out.add("[dia " + (e.tick() / 24000 + 1) + "] " + e.icon() + " " + e.message());
    }

    private void debug(Kingdom k, String[] a, List<String> out) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        switch (sub) {
            case "npc" -> {
                Npc n = a.length > 2 ? core.findNpc(null, a[2]) : null;
                if (n == null) {
                    out.add("✗ Uso: debug npc <nome>");
                    return;
                }
                out.add("# DEBUG " + n.name + " " + n.id);
                StringBuilder tr = new StringBuilder("Personality: ");
                for (Trait t : Trait.values()) tr.append(t.display).append(' ').append(n.trait(t)).append(", ");
                out.add(tr.toString());
                out.add("Needs: hunger " + (int) n.hunger + ", energy " + (int) n.energy + " · Level " + n.level + " · materialized " + n.materialized);
                out.add("Current task: " + n.currentTask + " · activity " + n.activity + " · pos " + n.pos);
                out.add("Relationships: " + n.relations.size() + " · memories " + n.memories.size());
                out.add("Last LLM call: " + (n.lastLlmCall.isBlank() ? "(nenhuma)" : n.lastLlmCall));
                out.add("Last decision: " + Text.truncate(n.lastDecision, 300));
            }
            case "ai" -> {
                Kingdom target = a.length > 2 ? core.findKingdom(rest(a, 2)) : null;
                for (Kingdom o : core.state().kingdoms.values()) {
                    if (o.isPlayerKingdom() || (target != null && o != target)) continue;
                    out.add("# Director " + o.name + " (" + o.personality.summary() + ")");
                    for (var p : core.director().evaluate(o))
                        out.add(String.format(Locale.ROOT, "  %s %.2f — %s", p.goal(), p.score(), p.reason()));
                }
                out.add("LLM: " + core.llm().status());
                if (!core.llm().lastPrompt().isBlank()) out.add("Último prompt: " + Text.truncate(core.llm().lastPrompt().replace('\n', ' '), 300));
            }
            case "events" -> {
                for (GameEvent e : core.bus().log().recent(20, e -> true))
                    out.add("#" + e.seq() + " t" + e.tick() + " " + e.type() + " " + e.message());
            }
            default -> out.add("Uso: debug npc <nome> | debug ai [reino] | debug events");
        }
    }

    private void replay(Kingdom k, String name, List<String> out) {
        Npc n = core.findNpc(null, name);
        if (n == null) {
            out.add("✗ Uso: replay <nome do NPC>");
            return;
        }
        out.add("# Por que " + n.name + " é assim? (memórias + eventos)");
        List<String> timeline = new ArrayList<>();
        for (Memory m : n.memories) timeline.add(String.format("%08d", m.tick()) + "[dia " + (m.tick() / 24000 + 1) + "] (memória " + m.importance() + ") " + m.text());
        for (GameEvent e : core.bus().log().recent(200, e -> n.id.equals(e.actorId())))
            timeline.add(String.format("%08d", e.tick()) + "[dia " + (e.tick() / 24000 + 1) + "] (evento) " + e.message());
        Collections.sort(timeline);
        for (String s : timeline.subList(Math.max(0, timeline.size() - 15), timeline.size())) out.add("  ↓ " + s.substring(8));
        out.add("Lealdade atual: " + n.loyalty);
    }

    // ------------------------------------------------------------------ helpers

    private void act(Kingdom k, UUID player, ActionType type, List<String> out, String... kv) {
        ActionResult r = core.actions().execute(ActionRequest.of(k.id, player, ActionRequest.ActorKind.PLAYER, type, ActionRequest.Source.CLI, kv));
        String[] parts = (r.message() == null ? "" : r.message()).split("\n");
        out.add(r.ok() ? "✓ " + parts[0] : "✗ [" + r.code() + "] " + parts[0]);
        for (int i = 1; i < parts.length; i++) out.add(parts[i]);
    }

    public ActionResult managerAction(UUID player, ActionType type, String... kv) {
        Kingdom k = core.kingdomOfPlayer(player);
        if (k == null) return ActionResult.reject("no_kingdom", "Sem reino.");
        return core.actions().execute(ActionRequest.of(k.id, player, ActionRequest.ActorKind.PLAYER, type, ActionRequest.Source.MANAGER, kv));
    }

    /** Extrai pares chave=valor (ex.: largura=9 parede=pedra). */
    private static List<String> keyValues(String[] a, int from) {
        List<String> kv = new ArrayList<>();
        for (int i = from; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq > 0 && eq < a[i].length() - 1) {
                kv.add(a[i].substring(0, eq).toLowerCase(Locale.ROOT));
                kv.add(a[i].substring(eq + 1));
            }
        }
        return kv;
    }

    private void blueprintList(List<String> out) {
        out.add("# Plantas do reino");
        for (Blueprint b : BlueprintLibrary.all())
            out.add(b.id() + " — " + b.displayName() + " " + b.sizeX() + "x" + b.sizeZ() + "x" + b.sizeY() + " · custo " + costText(b.cost())
                    + (b.housing() > 0 ? " · abriga " + b.housing() : "") + (b.source().equals("builtin") ? "" : " · [" + b.source() + "]"));
        out.add("Criar: /k blueprint design nome=Casa_do_Ferreiro tipo=casa largura=9 andares=2 parede=pedra telhado=duas_aguas");
    }

    private void blueprintCmd(Kingdom k, UUID player, String[] a, List<String> out) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "design", "projetar", "criar" -> {
                Map<String, String> p = new LinkedHashMap<>();
                List<String> kv = keyValues(a, 2);
                for (int i = 0; i + 1 < kv.size(); i += 2) p.put(kv.get(i), kv.get(i + 1).replace('_', ' ').replace("\"", ""));
                try {
                    var spec = com.kingdomsai.core.construction.ParametricBlueprints.spec(p);
                    Blueprint bp = core.registerSpec(spec);
                    out.add("✓ Planta criada: " + bp.id() + " — " + bp.displayName() + " · " + bp.sizeX() + "x" + bp.sizeZ() + "x" + bp.sizeY()
                            + " · custo " + costText(bp.cost()) + (bp.housing() > 0 ? " · abriga " + bp.housing() : ""));
                    out.add("Construir: /k build " + bp.id() + " [prazo 1d]");
                } catch (IllegalArgumentException e) {
                    out.add("✗ [invalid_param] " + e.getMessage());
                }
            }
            case "show", "ver" -> {
                Blueprint bp = a.length > 2 ? BlueprintLibrary.find(a[2]) : null;
                if (bp == null) {
                    out.add("✗ Uso: blueprint show <id>");
                    return;
                }
                out.add("# " + bp.displayName() + " (" + bp.id() + ", " + bp.source() + ")");
                out.add("Tamanho " + bp.sizeX() + "x" + bp.sizeZ() + ", altura " + bp.sizeY() + " · " + bp.solidTotal() + " blocos · custo "
                        + costText(bp.cost()) + (bp.housing() > 0 ? " · abriga " + bp.housing() : ""));
                out.add("Tempo com 1 construtor: " + com.kingdomsai.core.construction.ConstructionSystem.formatDuration(
                        bp.solidTotal() / core.config().builderBlocksPerSecond));
                if (!bp.materials().isEmpty()) out.add("Materiais: " + bp.materials().values().stream().distinct().map(m -> m.replace("minecraft:", "")).toList());
            }
            case "delete", "apagar" -> {
                if (a.length < 3) {
                    out.add("✗ Uso: blueprint delete <id>");
                    return;
                }
                out.add(core.deleteBlueprint(a[2]) ? "✓ Planta apagada." : "✗ Só plantas criadas no mundo podem ser apagadas.");
            }
            case "materials", "materiais" ->
                    out.add("Materiais: " + com.kingdomsai.core.construction.ParametricBlueprints.materialList()
                            + " · Tipos: casa, quartel, forja, armazem, salao, torre, capela, taverna · Telhados: plano, piramide, duas_aguas");
            default -> blueprintList(out);
        }
    }

    private static String rest(String[] a, int from) {
        if (from >= a.length) return "";
        return String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    private static int indexOf(String[] a, String... words) {
        for (int i = 0; i < a.length; i++) for (String w : words) if (a[i].equalsIgnoreCase(w)) return i;
        return -1;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String costText(Map<ResourceType, Integer> cost) {
        StringBuilder sb = new StringBuilder();
        for (var e : cost.entrySet()) sb.append(e.getValue()).append(' ').append(e.getKey().display.toLowerCase()).append(", ");
        return sb.isEmpty() ? "grátis" : sb.substring(0, sb.length() - 2);
    }

    /** Nomes vindos de jogadores são dados não confiáveis: limita tamanho e remove marcadores. */
    static final class ContextSafe {
        static String name(String s) {
            String n = s.replaceAll("[<>{}\\[\\]=§]", "").trim();
            return Text.truncate(n.isEmpty() ? "Reino" : n, 32);
        }
    }
}
