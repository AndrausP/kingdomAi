package com.kingdomsai.core.llm;

import com.kingdomsai.core.KingdomsCore;
import com.kingdomsai.core.action.ActionType;
import com.kingdomsai.core.common.Text;
import com.kingdomsai.core.construction.Blueprint;
import com.kingdomsai.core.construction.BlueprintLibrary;
import com.kingdomsai.core.diplomacy.Diplomacy;
import com.kingdomsai.core.kingdom.Kingdom;
import com.kingdomsai.core.kingdom.ResourceType;
import com.kingdomsai.core.npc.*;
import com.kingdomsai.core.skill.ItemNames;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpretador por regras (português/inglês). É o "modelo" do modo offline e o fallback quando
 * a LLM falha. Produz exatamente o mesmo formato de Plan que a LLM.
 */
public final class RuleInterpreter {
    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("um", 1), Map.entry("uma", 1), Map.entry("dois", 2), Map.entry("duas", 2), Map.entry("tres", 3),
            Map.entry("quatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("sete", 7), Map.entry("oito", 8),
            Map.entry("nove", 9), Map.entry("dez", 10), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5));

    private final KingdomsCore core;

    public RuleInterpreter(KingdomsCore core) {
        this.core = core;
    }

    public Plan interpret(Kingdom k, Npc speaker, String text) {
        String t = Text.norm(text);
        List<Plan.PlannedAction> acts = new ArrayList<>();
        String reply = null;

        // --- chamar / seguir / dispensar ("venha aqui", "me siga", "pode ir")
        Npc callee = npcMentioned(k, t, speaker != null && speaker.office == Office.ADVISOR ? speaker : null);
        if (callee == null) callee = speaker;
        String callReply = null;
        if (t.matches(".*\\b(me siga|siga me|siga-me|venha comigo|me acompanhe|acompanhe me|follow me)\\b.*") && callee != null) {
            acts.add(new Plan.PlannedAction(ActionType.FOLLOW, "FOLLOW", params("npc", callee.name)));
            callReply = "Sigo Vossa Majestade aonde for.";
        } else if (callee != null && (t.matches(".*\\b(venha aqui|vem aqui|vem ca|venha ca|venha ate mim|chegue aqui|aproxime se|aproxime-se|come here)\\b.*")
                // "chame o Aldren" / "traga a Bruna aqui": só com alguém citado pelo nome
                || (callee != speaker && t.matches(".*\\b(chame|chama|traga|traz|mande vir)\\b.*")))) {
            acts.add(new Plan.PlannedAction(ActionType.SUMMON, "SUMMON", params("npc", callee.name)));
            callReply = "Estou indo, Majestade!";
        } else if (t.matches(".*\\b(pode ir|esta dispensad\\w*|dispensad\\w*|volte ao trabalho|pode voltar|dismissed)\\b.*") && callee != null) {
            acts.add(new Plan.PlannedAction(ActionType.DISMISS, "DISMISS", params("npc", callee.name)));
            callReply = "Com sua licença, Majestade. Volto ao trabalho.";
        }

        // --- marcos ("marque aqui como spawn", "a mina é aqui")
        if (t.matches(".*\\b(marque|marca|marcar|defina|define|fica aqui|sera aqui|e aqui)\\b.*")
                && t.matches(".*\\b(aqui|ali|esse|este|nesse|neste|onde estou|onde eu estou)\\b.*")) {
            String[] kinds = {"spawn", "praca", "mina", "bosque", "floresta", "renascer"};
            for (String kind : kinds)
                if (t.contains(kind)) {
                    acts.add(new Plan.PlannedAction(ActionType.MARK, "MARK", params("kind", kind)));
                    break;
                }
        }

        // --- ordens físicas ("quebre esse bloco", "corte essa árvore", "pegue 3 ferro do baú", "faça uma picareta e me entregue")
        Map<String, String> job = acts.isEmpty() ? physicalOrder(k, speaker, t, text) : null;
        if (job != null) acts.add(new Plan.PlannedAction(ActionType.JOB, "JOB", job));

        // --- rotinas / cadeias de trabalho ("minere ferro e leve ao ferreiro", "plante e colha", "escreva um livro")
        Map<String, String> chain = job != null ? null : chainOrder(k, speaker, t, text);
        if (chain != null) acts.add(new Plan.PlannedAction(ActionType.CHAIN, "CHAIN", chain));
        else if (job == null && t.matches(".*\\b(pare|parar|para de|chega de|largue|abandone|cancele|stop)\\b.*")
                && t.matches(".*\\b(rotina|tarefa|cadeia|isso|trabalho|ordem|de minerar|de plantar|de escrever|de ler|de cortar|de cavar|de quebrar)\\b.*")) {
            Npc who = npcMentioned(k, t, null);
            if (who == null && speaker != null && speaker.office != Office.ADVISOR) who = speaker;
            if (who != null) {
                boolean hasJob = who.jobId != null && core.state().jobs.containsKey(who.jobId) && core.state().jobs.get(who.jobId).status.live();
                acts.add(hasJob ? new Plan.PlannedAction(ActionType.CANCEL_JOB, "CANCEL_JOB", params("npc", who.name))
                        : new Plan.PlannedAction(ActionType.STOP_CHAIN, "STOP_CHAIN", params("npc", who.name)));
            }
        }

        // --- muralha ao redor da vila (o jogo mede a vila)
        boolean wall = chain == null && job == null && t.matches(".*\\b(muro|muros|muralha|muralhas|palicada|fortifiqu\\w*|cerque|cercar|wall)\\b.*")
                && t.matches(".*\\b(constru|ergu|levant|faca|facam|crie|cerque|cercar|fortifiqu|build|quero)\\w*.*");
        if (wall) {
            Map<String, String> p = params("blueprint", "muralha");
            if (t.matches(".*\\b(alt[oa]|grande|imponente)\\b.*")) p.put("height", "5");
            else if (t.matches(".*\\b(baix[oa]|simples|pequen[oa])\\b.*")) p.put("height", "3");
            String dl = deadlineIn(t);
            if (dl != null) p.put("deadline", dl);
            acts.add(new Plan.PlannedAction(ActionType.BUILD, "BUILD", p));
        }

        // --- construção
        if (chain == null && job == null && !wall && t.matches(".*\\b(constru|ergu|levant|faca |facam |crie |criem |build|erect|mande construir).*")) {
            Map<String, String> custom = customSpec(t);
            Blueprint bp = custom != null ? null : findBlueprint(t);
            if (custom != null && !t.contains("espada") && !t.contains("arma")) {
                custom.put("amount", String.valueOf(numberBefore(t, bpWordIndex(t), 1)));
                String dl = deadlineIn(t);
                if (dl != null) custom.put("deadline", dl);
                acts.add(new Plan.PlannedAction(ActionType.BUILD, "BUILD", custom));
            } else if (bp != null && !t.contains("espada") && !t.contains("arma")) {
                Map<String, String> p = params("blueprint", bp.id(), "amount", String.valueOf(numberBefore(t, bpWordIndex(t), 1)));
                String dl = deadlineIn(t);
                if (dl != null) p.put("deadline", dl);
                Npc target = npcMentioned(k, t, speaker);
                Profession profFor = professionAfter(t, "para o ", "para a ", "pro ", "pra ", "for the ");
                if (target == null && profFor != null)
                    target = core.citizens(k.id).stream().filter(n -> n.profession == profFor).findFirst().orElse(null);
                if (target != null && (t.contains("para") || t.contains("pra") || t.contains(" for "))) p.put("for", target.name);
                acts.add(new Plan.PlannedAction(ActionType.BUILD, "BUILD", p));
            }
        }
        // --- prazo para obra existente ("termine a casa até amanhã")
        if (acts.isEmpty() && t.matches(".*\\b(termin|acab|conclu|apress|finish).*")) {
            String dl = deadlineIn(t);
            if (dl != null) acts.add(new Plan.PlannedAction(ActionType.DEADLINE, "DEADLINE", params("deadline", dl)));
        }
        // --- exército
        if (t.matches(".*\\b(recrut|alist|convoc|recruit|treine novos).*")) {
            acts.add(new Plan.PlannedAction(ActionType.RECRUIT, "RECRUIT", params("amount", String.valueOf(firstNumber(t, 1)))));
        }
        if (t.matches(".*\\b(liber|dispens|desmobiliz|release|devolv).*") && t.contains("soldad")) {
            Map<String, String> p = params("amount", String.valueOf(firstNumber(t, 1)));
            Profession to = professionAnywhere(t, Profession.SOLDIER);
            if (t.contains("fazend") || t.contains("lavour") || t.contains("campo")) to = Profession.FARMER;
            if (to != null) p.put("profession", to.name());
            acts.add(new Plan.PlannedAction(ActionType.RELEASE, "RELEASE", p));
        }
        // --- cargos
        if (t.matches(".*\\b(promov|nomei|nomeie|torne|faca de|appoint|promote).*")) {
            Npc n = npcMentioned(k, t, null);
            Office o = officeMentioned(t);
            if (n != null && o != null && o != Office.KING)
                acts.add(new Plan.PlannedAction(ActionType.PROMOTE, "PROMOTE", params("npc", n.name, "office", o.name())));
        }
        if (t.matches(".*\\b(demit|destitu|exoner|remova do cargo|dismiss).*")) {
            Npc n = npcMentioned(k, t, null);
            if (n != null) acts.add(new Plan.PlannedAction(ActionType.DEMOTE, "DEMOTE", params("npc", n.name)));
        }
        // --- profissões
        if (acts.isEmpty() && t.matches(".*\\b(vir[ea]|ser |trabalh|vai ser|como |mova|coloque|mande|ponha|assign|work as|para a lavoura|para a mina).*")) {
            Profession p = professionAnywhere(t, null);
            if (t.contains("lavour") || t.contains("plant")) p = Profession.FARMER;
            if (t.contains(" mina") || t.contains("minera")) p = p == null ? Profession.MINER : p;
            if (t.contains("floresta") || t.contains("lenha") || t.contains("madeir")) p = p == null ? Profession.LUMBERJACK : p;
            Npc n = npcMentioned(k, t, null);
            if (p != null) {
                if (n != null) acts.add(new Plan.PlannedAction(ActionType.WORK, "WORK", params("npc", n.name, "profession", p.name())));
                else acts.add(new Plan.PlannedAction(ActionType.WORK, "WORK", params("amount", String.valueOf(firstNumber(t, 1)), "profession", p.name())));
            }
        }
        // --- leis e impostos
        if (t.contains("impost") || t.contains("tax")) {
            String dir = t.matches(".*\\b(aument|sub|elev|raise|increase).*") ? "up" : t.matches(".*\\b(baix|reduz|diminu|cort|lower|reduce).*") ? "down" : null;
            if (dir != null) acts.add(new Plan.PlannedAction(ActionType.TAX, "TAX", params("level", dir)));
        }
        if (t.contains("servico militar") || t.contains("conscri") || t.contains("alistamento obrigatorio")) {
            boolean off = t.matches(".*\\b(abol|acab|revog|fim|termin|off).*");
            acts.add(new Plan.PlannedAction(ActionType.LAW, "LAW", params("law", "conscription", "value", off ? "off" : "on")));
        }
        // --- território
        if (t.matches(".*\\b(expand|reivindic|anex|claim|aument[ae] o territ).*")) {
            acts.add(new Plan.PlannedAction(ActionType.CLAIM, "CLAIM", params("amount", String.valueOf(Math.min(5, firstNumber(t, 1))))));
        }
        // --- diplomacia
        Kingdom other = kingdomMentioned(k, t);
        if (other != null) {
            if (t.matches(".*\\b(declar.* guerra|guerra contra|ataqu|invad|declare war).*")) {
                acts.add(new Plan.PlannedAction(ActionType.DECLARE_WAR, "DECLARE_WAR", params("target", other.name)));
            } else if (t.matches(".*\\b(paz|armisti|peace).*")) {
                acts.add(new Plan.PlannedAction(ActionType.MAKE_PEACE, "MAKE_PEACE", params("target", other.name)));
            } else if (t.matches(".*\\b(pacto|tratado|acordo|alianc|treaty|pact|alliance).*")) {
                Diplomacy.TreatyType tt = Diplomacy.TreatyType.parse(t);
                acts.add(new Plan.PlannedAction(ActionType.NEGOTIATE, "NEGOTIATE",
                        params("target", other.name, "treaty", (tt == null ? Diplomacy.TreatyType.NON_AGGRESSION : tt).name())));
            } else if (t.matches(".*\\b(troc|comerci|trade|vend).*")) {
                Matcher m = Pattern.compile("(\\d+)\\s+(?:de\\s+)?(\\p{L}+).*?(?:por|for)\\s+(\\d+)\\s+(?:de\\s+)?(\\p{L}+)").matcher(t);
                if (m.find()) {
                    ResourceType give = ResourceType.parse(m.group(2)), want = ResourceType.parse(m.group(4));
                    if (give != null && want != null)
                        acts.add(new Plan.PlannedAction(ActionType.NEGOTIATE, "NEGOTIATE", params("target", other.name,
                                "give", give.name(), "give_amount", m.group(1), "want", want.name(), "want_amount", m.group(3))));
                }
            } else if (t.matches(".*\\b(presente|envi|doe|doar|gift|send).*")) {
                Matcher m = Pattern.compile("(\\d+)\\s+(?:de\\s+)?(\\p{L}+)").matcher(t);
                while (m.find()) {
                    ResourceType r = ResourceType.parse(m.group(2));
                    if (r != null) {
                        acts.add(new Plan.PlannedAction(ActionType.GIVE, "GIVE", params("target", other.name, "resource", r.name(), "amount", m.group(1))));
                        break;
                    }
                }
            }
        }

        // --- pedido de produção (ex.: "Aldren, preciso de 30 espadas até amanhã")
        Matcher prod = Pattern.compile("(\\d+)\\s+(espadas|armas|swords|weapons)").matcher(t);
        if (prod.find()) reply = productionAnswer(k, speaker, Integer.parseInt(prod.group(1)));

        if (reply == null && callReply != null && speaker != null && callee == speaker) reply = callReply;
        if (reply == null && job != null && speaker != null && speaker.office != Office.ADVISOR)
            reply = acknowledge(speaker) + " Deixe comigo.";
        if (reply == null && chain != null && speaker != null && speaker.office != Office.ADVISOR)
            reply = acknowledge(speaker) + " " + chainPromise(chain);
        if (reply == null) {
            if (!acts.isEmpty()) reply = acknowledge(speaker);
            else if (speaker != null && speaker.office != Office.ADVISOR && !looksLikeQuestion(t)) reply = chat(k, speaker, t);
            else reply = core.advisor().answer(k, text);
        }
        return new Plan(reply, acts);
    }

    // ------------------------------------------------------------------ respostas

    /**
     * Ordens com as mãos. Lugares vêm da mira do rei ("esse bloco", "essa árvore", "desse baú");
     * quem faz é o NPC com quem ele fala ou alguém citado pelo nome (senão o planejador escolhe).
     */
    private Map<String, String> physicalOrder(Kingdom k, Npc speaker, String t, String original) {
        Map<String, String> p = null;
        String here = "(esse|este|aquele|essa|esta|aquela|o|a|aqui|ali)";
        if (t.matches(".*\\b(corte|corta|derrube|derruba|cortar)\\s+" + here + "\\s+arvore\\b.*"))
            p = params("kind", "chop");
        else if (t.matches(".*\\b(quebre|quebra|destrua|destroi|remova|tire)\\s+" + here + "\\s+bloco\\b.*"))
            p = params("kind", "break");
        else if (t.matches(".*\\b(tunel|galeria)\\b.*") && t.matches(".*\\b(abra|abre|cave|cava|escave|faca|cavar)\\w*.*")) {
            p = params("kind", "tunnel");
            Matcher m = Pattern.compile("(\\d+)\\s*blocos").matcher(t);
            if (m.find()) p.put("length", m.group(1));
        } else if (t.matches(".*\\b(cave|cava|escave|cavar|buraco|escavar)\\b.*") && !t.contains("ferro") && !t.contains("pedra")) {
            p = params("kind", "dig");
        } else if (t.matches(".*\\b(limpe|limpa|aplaine|aplana|limpar)\\s+" + here + "?\\s*(area|terreno|espaco|lugar)\\b.*")) {
            p = params("kind", "clear");
        }
        if (p != null) {
            Matcher sz = Pattern.compile("(\\d+)\\s*x\\s*(\\d+)(?:\\s*x\\s*(\\d+))?").matcher(t);
            if (sz.find()) p.put("size", sz.group(1) + "x" + sz.group(2) + (sz.group(3) != null ? "x" + sz.group(3) : ""));
        }
        String give = ".*\\b(me (de|da|entregue|entrega|traga|traz)|e me (de|entregue|traga)|traga (pra|para) mim|para mim|pra mim|me passe)\\b.*";
        if (p == null) {
            Matcher m = Pattern.compile("\\b(pegue|pega|tire|retire|busque|pegar|traga)\\s+(?:(\\d+|um|uma|dois|duas|tres|cinco|dez)\\s+)?(.+?)\\s+(do|no|desse|deste|daquele|naquele|nesse|neste|dentro do)\\s+(bau|barril|armazem)\\b").matcher(t);
            if (m.find() && ItemNames.resolve(m.group(3)) != null) {
                p = params("kind", "take", "item", m.group(3).trim());
                if (m.group(2) != null) p.put("count", String.valueOf(Objects.requireNonNullElse(toNumber(m.group(2)), 1)));
                p.put("from", m.group(5).equals("armazem") ? "storage" : m.group(4).matches("desse|deste|daquele|naquele|nesse|neste") ? "look" : "");
                if (t.matches(give)) p.put("give", "true");
            }
        }
        if (p == null) {
            Matcher m = Pattern.compile("\\b(guarde|guarda|coloque|coloca|deposite|bote|ponha)\\s+(.+?)\\s+(no|nesse|neste|naquele|num|em um|dentro do)\\s+(bau|barril|armazem)\\b").matcher(t);
            if (m.find()) {
                String what = m.group(2).trim();
                p = params("kind", "put", "to", m.group(4).equals("armazem") ? "storage" : m.group(3).matches("nesse|neste|naquele") ? "look" : "");
                if (!what.matches("tudo|tudo que (tem|tiver)|o que (tem|tiver|pegou|juntou)|isso")) {
                    Matcher c = Pattern.compile("^(\\d+)\\s+(.+)$").matcher(what);
                    if (c.find()) {
                        p.put("count", c.group(1));
                        what = c.group(2);
                    }
                    if (ItemNames.resolve(what) != null) p.put("item", what);
                }
            }
        }
        if (p == null) {
            Matcher m = Pattern.compile("\\b(faca|fabrique|fabrica|crie|cria|forje|forja|produza|prepare|monte|construa)\\s+(?:(\\d+|um|uma|dois|duas|tres|quatro|cinco|seis|oito|dez)\\s+)?(.+)$").matcher(t);
            if (m.find()) {
                String phrase = m.group(3).replaceAll("\\s+(e me|e traga|e entregue|para mim|pra mim|com |na bancada|no forno|na fornalha|agora|por favor|rapido).*$", "").trim();
                String item = resolveCraftable(phrase);
                if (item != null) {
                    p = params("kind", "craft", "item", item);
                    if (m.group(2) != null) p.put("count", String.valueOf(Objects.requireNonNullElse(toNumber(m.group(2)), 1)));
                    if (t.matches(give)) p.put("give", "true");
                }
            }
        }
        if (p == null) return null;
        Npc who = speaker != null && speaker.office != Office.ADVISOR ? speaker : npcMentioned(k, t, null);
        if (who != null) p.put("npc", who.name);
        return p;
    }

    /** Só vira ordem de fabricar se o item existe e tem receita (senão "faça uma casa" seria um item). */
    private String resolveCraftable(String phrase) {
        String[] w = phrase.split("\\s+");
        for (int len = Math.min(w.length, 4); len >= 1; len--) {
            String cand = String.join(" ", Arrays.copyOfRange(w, 0, len));
            String id = ItemNames.resolve(cand);
            if (id == null || id.startsWith("#")) continue;
            if (!core.physical().itemExists(id) || core.physical().recipes(id).isEmpty()) continue;
            return id;
        }
        return null;
    }

    /**
     * Reconhece ordens de rotina e escolhe o modelo de cadeia. Quem executa: o NPC com quem o rei fala
     * (se não for o conselheiro) ou alguém citado pelo nome; senão o modelo escolhe pela profissão.
     */
    private Map<String, String> chainOrder(Kingdom k, Npc speaker, String t, String original) {
        boolean mine = t.matches(".*\\b(miner|minere|mina|minar|cave|cavar|extrai|extraia|extrair)\\w*.*");
        boolean smith = t.matches(".*\\b(ferreiro|forja|ferraria|derret|fund[ae]|fundir|fornalha)\\w*.*");
        String template = null;
        if ((mine && smith) || (smith && t.contains("ferro") && t.matches(".*\\b(derret|fund|fundir)\\w*.*"))) template = "minerar_ferreiro";
        else if (t.matches(".*\\b(plant|semei|semear|plante)\\w*.*") && t.matches(".*\\b(colh)\\w*.*")) template = "plantar_colher";
        else if (t.matches(".*\\b(escrev)\\w*.*") && t.contains("carta")) template = "carta";
        else if (t.matches(".*\\b(escrev)\\w*.*") && t.matches(".*\\b(livro|cronica|historia|tratado|registro)\\w*.*")) template = "escrever";
        else if (t.matches(".*\\b(mande|envie|leve)\\b.*") && t.matches(".*\\buma carta\\b.*")) template = "carta";
        else if (t.matches(".*\\b(leia|ler|leitura|estude|estudar)\\b.*") && t.matches(".*\\b(livro|livros|biblioteca)\\b.*")) template = "ler";
        else if (t.matches(".*\\b(cort|derrub)\\w*.*") && t.matches(".*\\b(lenha|arvore|arvores|madeira|toras)\\b.*")) template = "lenha";
        else if (mine && t.contains("pedra") && !t.contains("ferro")) template = "pedra";
        if (template == null) return null;

        Map<String, String> p = params("template", template);
        Npc who = speaker != null && speaker.office != Office.ADVISOR ? speaker : null;
        Npc named = npcMentioned(k, t, who);
        switch (template) {
            case "carta" -> {
                Npc to = named;
                if (to == null) {
                    for (Npc o : core.allAlive())
                        if (who == null || !o.id.equals(who.id))
                            if (Pattern.compile("\\b" + Pattern.quote(Text.norm(o.name)) + "\\b").matcher(t).find()) to = o;
                }
                if (to == null) return null;
                p.put("to", to.name);
                Matcher m = Pattern.compile("(?i)(dizendo que|dizendo|falando que|contando que|avisando que|:)\\s*(.+)$").matcher(original);
                if (m.find()) p.put("text", m.group(2).trim());
                else {
                    Matcher sobre = Pattern.compile("(?i)sobre\\s+(.+)$").matcher(original);
                    if (sobre.find()) p.put("topic", sobre.group(1).trim());
                }
            }
            case "escrever" -> {
                Matcher sobre = Pattern.compile("(?i)sobre\\s+(.+)$").matcher(original);
                if (sobre.find()) p.put("topic", sobre.group(1).replaceAll("[.!?]+$", "").trim());
                if (who == null && named != null) who = named;
            }
            case "ler" -> {
                Matcher q = Pattern.compile("[«\"“]([^»\"”]+)[»\"”]").matcher(original);
                if (q.find()) p.put("title", q.group(1));
                if (who == null && named != null) who = named;
            }
            default -> {
                if (who == null && named != null) who = named;
                Matcher amt = Pattern.compile("(\\d+)\\s+(?:de\\s+)?(ferro|minerio|toras|lenha|pedras?|madeira)").matcher(t);
                if (amt.find()) p.put("amount", amt.group(1));
                if (template.equals("minerar_ferreiro") && t.matches(".*\\b(espada|espadas|arma|armas)\\b.*")) p.put("forge", "true");
            }
        }
        if (who != null) p.put("npc", who.name);
        if (t.matches(".*\\b(so uma vez|uma vez so|apenas uma vez|uma unica vez)\\b.*")) p.put("repeat", "false");
        else if (t.matches(".*\\b(daqui pra frente|daqui para frente|dai pra frente|dali pra frente|dali para frente|sempre|todo dia|todos os dias|continu\\w*|rotina)\\b.*"))
            p.put("repeat", "true");
        return p;
    }

    private static String chainPromise(Map<String, String> chain) {
        boolean once = "false".equals(chain.get("repeat"));
        return switch (chain.get("template")) {
            case "minerar_ferreiro" -> (once ? "Vou" : "Daqui em diante vou") + " cuidar do ferro: da mina à forja, e as barras vão para o armazém.";
            case "plantar_colher" -> (once ? "Vou" : "Daqui em diante vou") + " plantar, esperar o trigo amadurecer, colher e guardar no armazém.";
            case "lenha" -> (once ? "Vou" : "Daqui em diante vou") + " cortar lenha e levar ao armazém.";
            case "pedra" -> (once ? "Vou" : "Daqui em diante vou") + " extrair pedra e levar ao armazém.";
            case "escrever" -> "Vou à biblioteca escrever" + (chain.get("topic") == null ? " a crônica do reino." : " sobre " + chain.get("topic") + ".");
            case "ler" -> "Vou à biblioteca ler.";
            case "carta" -> "Escreverei a carta para " + chain.get("to") + " e a entregarei em mãos.";
            default -> "";
        };
    }

    private String acknowledge(Npc speaker) {
        if (speaker == null) return "Às suas ordens, Majestade.";
        int obedience = speaker.loyalty + speaker.trait(Trait.DISCIPLINE) / 2;
        if (obedience > 110) return "Imediatamente, Majestade. Considere feito.";
        if (obedience > 80) return "Sim, Majestade. Cuidarei disso.";
        return "...Como Vossa Majestade desejar.";
    }

    private String productionAnswer(Kingdom k, Npc speaker, int requested) {
        int smiths = core.count(k.id, Profession.BLACKSMITH);
        double perCycle = smiths * (core.completedOf(k.id, "smithy") > 0 ? 0.5 : 0.25);
        double perDay = perCycle * 24000.0 / 20 / core.config().economicTickSeconds;
        double iron = k.get(ResourceType.IRON);
        double stock = k.get(ResourceType.WEAPONS);
        int ironLimited = (int) (iron / 2);
        String who = speaker != null && speaker.profession == Profession.BLACKSMITH ? "" : "Pelos números da forja: ";
        if (smiths == 0) return who + "Majestade, não temos nenhum ferreiro. Sem ferreiros, nenhuma arma será feita.";
        int canMake = (int) Math.min(perDay, ironLimited);
        StringBuilder sb = new StringBuilder(who + "Majestade, temos " + (int) stock + " no estoque e consigo produzir cerca de "
                + canMake + " por dia");
        if (ironLimited < perDay) sb.append(" — o ferro (").append((int) iron).append(") é o limite");
        sb.append(". ");
        if (stock + canMake >= requested) sb.append("Entregaremos as ").append(requested).append(" a tempo.");
        else sb.append("Para entregar ").append(requested).append(" precisaremos de mais ferreiros")
                .append(ironLimited < requested ? " ou importar ferro." : ".");
        return sb.toString();
    }

    private String chat(Kingdom k, Npc n, String t) {
        StringBuilder sb = new StringBuilder();
        Relation toKing = k.rulerPlayer == null ? null : n.relations.get(k.rulerPlayer);
        boolean warm = n.loyalty > 65 || (toKing != null && toKing.affection > 60);
        if (t.matches(".*\\b(ola|oi|bom dia|boa tarde|boa noite|saudac|hello|hi)\\b.*"))
            sb.append(warm ? "Majestade! Que honra. " : "Majestade. ");
        else sb.append(warm ? "Majestade, " : "Pois não, Majestade. ");
        if (t.contains("como") && (t.contains("vai") || t.contains("esta"))) {
            if (n.hunger < 30) sb.append("Confesso que a fome aperta. ");
            else if (n.energy < 30) sb.append("Estou exausto, mas sigo firme. ");
            else sb.append("Vou bem, graças à paz do reino. ");
        }
        sb.append("Estou ").append(n.activity.display).append(" — ").append(n.currentTask.isBlank() ? n.profession.display.toLowerCase() : n.currentTask.toLowerCase()).append(". ");
        Memory strong = n.memories.stream().filter(m -> m.importance() >= 60).max(Comparator.comparingLong(Memory::tick)).orElse(null);
        if (strong != null) sb.append("Ainda penso nisso: ").append(lowerFirst(strong.text())).append(' ');
        if (n.trait(Trait.AMBITION) > 75 && n.office == Office.NONE) sb.append("Se me permite, acredito que poderia servir ao reino em um cargo maior. ");
        if (n.trait(Trait.RELIGIOSITY) > 80) sb.append("Que os deuses guardem Vossa Majestade.");
        return sb.toString().trim();
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean looksLikeQuestion(String t) {
        return t.contains("?") || t.matches("^(por que|porque|quanto|quantos|qual|quais|como|onde|quem|o que|why|how|what).*");
    }

    // ------------------------------------------------------------------ extração

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** "até amanhã", "em 5 minutos", "prazo de 2 dias" → "1d", "5m", "2d". */
    static String deadlineIn(String t) {
        if (t.matches(".*\\b(ate|para) amanha\\b.*") || t.contains("ate amanha")) return "amanha";
        if (t.matches(".*\\bate hoje\\b.*") || t.contains("ainda hoje")) return "hoje";
        Matcher m = Pattern.compile("(?:em|ate|prazo de|dentro de|in)\\s+(\\d+|um|uma|dois|duas|tres|cinco|dez)\\s*(segundos?|s|minutos?|min|m|horas?|h|dias?|d)\\b").matcher(t);
        if (!m.find()) return null;
        Integer n = toNumber(m.group(1));
        if (n == null) return null;
        String u = m.group(2);
        char unit = u.startsWith("s") ? 's' : u.startsWith("h") ? 'h' : u.startsWith("d") ? 'd' : 'm';
        return n + String.valueOf(unit);
    }

    /** Detecta pedido de construção personalizada ("casa grande de pedra com 2 andares e telhado de duas águas"). */
    static Map<String, String> customSpec(String t) {
        boolean cues = t.matches(".*\\b(\\d+|dois|duas|tres) andares\\b.*") || t.contains("duas aguas") || t.contains("piramide")
                || t.matches(".*\\b(torre|capela|taverna|templo|igreja)\\b.*")
                || t.matches(".*\\bde (pedra|tijolo|tijolos|arenito|ardosia|quartzo|barro|abeto|betula|carvalho escuro|acacia|cerejeira|mangue|pedregulho)\\b.*")
                || t.matches(".*\\b(grande|enorme|pequena|pequeno)\\b.*") && t.matches(".*\\b(casa|salao|quartel|armazem|forja)\\b.*");
        if (!cues) return null;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("blueprint", "custom");
        for (String kind : new String[]{"torre", "capela", "templo", "igreja", "taverna", "quartel", "forja", "armazem", "salao", "casa"})
            if (t.contains(kind)) {
                p.put("kind", kind);
                break;
            }
        Matcher fl = Pattern.compile("\\b(\\d+|dois|duas|tres) andares").matcher(t);
        if (fl.find()) {
            Integer n = toNumber(fl.group(1));
            if (n != null) p.put("floors", String.valueOf(Math.min(3, n)));
        }
        if (t.contains("enorme")) {
            p.put("width", "13");
            p.put("depth", "11");
        } else if (t.contains("grande")) {
            p.put("width", "11");
            p.put("depth", "9");
        } else if (t.contains("pequen")) {
            p.put("width", "5");
            p.put("depth", "5");
        }
        Matcher mat = Pattern.compile("\\bde (pedra|tijolos?|arenito|ardosia|quartzo|barro|abeto|betula|carvalho escuro|carvalho|acacia|cerejeira|mangue|pedregulho)\\b").matcher(t);
        if (mat.find()) p.put("wall", mat.group(1).replace("tijolos", "tijolo"));
        if (t.contains("duas aguas")) p.put("roof", "duas_aguas");
        else if (t.contains("piramide")) p.put("roof", "piramide");
        else if (t.contains("telhado plano") || t.contains("laje")) p.put("roof", "plano");
        Matcher rm = Pattern.compile("telhado de (pedra|tijolos?|ardosia|abeto|carvalho escuro|carvalho|quartzo|arenito)").matcher(t);
        if (rm.find()) p.put("roof_material", rm.group(1).replace("tijolos", "tijolo"));
        if (t.contains("chamine")) p.put("chimney", "true");
        return p;
    }

    private Blueprint findBlueprint(String t) {
        for (String w : t.split("[^\\p{L}_]+")) {
            if (w.length() < 4) continue;
            if (t.contains("casa media") || t.contains("casas medias") || t.contains("casa grande")) return BlueprintLibrary.get("house_medium");
            Blueprint b = BlueprintLibrary.find(w);
            if (b != null) return b;
        }
        return null;
    }

    private int bpWordIndex(String t) {
        String[] keys = {"casa", "fazend", "quartel", "forja", "armaz", "salao", "bibliot", "house", "farm", "barrack", "celeiro", "prefeit", "library"};
        int best = -1;
        for (String key : keys) {
            int i = t.indexOf(key);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static int numberBefore(String t, int idx, int def) {
        if (idx <= 0) return firstNumber(t, def);
        String before = t.substring(0, idx).trim();
        String[] words = before.split("\\s+");
        for (int i = words.length - 1; i >= Math.max(0, words.length - 3); i--) {
            Integer n = toNumber(words[i]);
            if (n != null) return n;
        }
        return def;
    }

    private static int firstNumber(String t, int def) {
        for (String w : t.split("[^\\p{L}0-9]+")) {
            Integer n = toNumber(w);
            if (n != null) return n;
        }
        return def;
    }

    private static Integer toNumber(String w) {
        if (w.matches("\\d{1,4}")) return Integer.parseInt(w);
        return NUMBERS.get(w);
    }

    private Npc npcMentioned(Kingdom k, String t, Npc exclude) {
        Npc best = null;
        for (Npc n : core.citizens(k.id)) {
            if (exclude != null && n.id.equals(exclude.id)) continue;
            String name = Text.norm(n.name);
            if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(t).find()) {
                if (best == null || name.length() > Text.norm(best.name).length()) best = n;
            }
        }
        return best;
    }

    private Kingdom kingdomMentioned(Kingdom self, String t) {
        for (Kingdom o : core.state().kingdoms.values()) {
            if (o == self) continue;
            if (t.contains(Text.norm(o.name))) return o;
        }
        return null;
    }

    private static Office officeMentioned(String t) {
        String[] words = t.split("[^\\p{L}-]+");
        for (int i = 0; i < words.length; i++) {
            if (i + 1 < words.length) {
                Office two = Office.parse(words[i] + " " + words[i + 1]);
                if (two != null) return two;
            }
            Office o = Office.parse(words[i]);
            if (o != null && o != Office.NONE) return o;
        }
        return null;
    }

    private static Profession professionAnywhere(String t, Profession exclude) {
        for (String w : t.split("[^\\p{L}]+")) {
            Profession p = Profession.parse(w);
            if (p != null && p != exclude) return p;
        }
        return null;
    }

    private static Profession professionAfter(String t, String... markers) {
        for (String m : markers) {
            int i = t.indexOf(m);
            if (i < 0) continue;
            String rest = t.substring(i + m.length()).trim();
            String w = rest.split("[^\\p{L}]+")[0];
            Profession p = Profession.parse(w);
            if (p != null) return p;
        }
        return null;
    }
}
