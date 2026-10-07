package com.kingdomsai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kingdomsai.minecraft.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.*;

import static com.kingdomsai.client.UiKit.*;

/**
 * Interface do MANAGER MODE (Alt enquanto voa). Translúcida: o mundo continua visível atrás.
 * Todo botão vira um comando da CLI no servidor — a UI e o /kingdom usam os mesmos serviços.
 */
public class ManagerScreen extends Screen {
    private enum Tab {
        MAP("Mapa"), BUILD("Obras"), ARMY("Exército"), ECONOMY("Economia"), DIPLOMACY("Diplomacia"),
        POPULATION("Povo"), TERRITORY("Terra"), LAWS("Leis"), RELIGION("Fé"), CONFIG("⚙ Config");
        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    // estado que sobrevive entre aberturas
    private static Tab tab = Tab.MAP;
    private static double camX = Double.NaN, camZ, scale = 1.6;
    private static String selectedNpc;
    private static boolean designer;
    private static int deadlineChoice; // 0 sem, 1 5m, 2 1d, 3 3d
    private static final String[] DEADLINES = {"sem prazo", "5 min", "1 dia (20 min)", "3 dias"};
    private static final String[] DEADLINE_ARGS = {"", "5m", "1d", "3d"};
    // projetista
    private static int dKind, dWidth = 7, dDepth = 7, dFloors = 1, dRoof = 2, dWall, dRoofMat = 3;
    private static boolean dChimney;
    private static final String[] KINDS = {"casa", "quartel", "forja", "armazem", "salao", "torre", "capela", "taverna"};
    private static final String[] KIND_NAMES = {"Casa", "Quartel", "Forja", "Armazém", "Salão", "Torre", "Capela", "Taverna"};
    private static final String[] ROOFS = {"plano", "piramide", "duas_aguas"};
    private static final String[] ROOF_NAMES = {"plano", "pirâmide", "duas águas"};
    private static final String[] MATS = {"oak", "spruce", "birch", "dark_oak", "acacia", "jungle", "cherry", "mangrove", "stone",
            "cobblestone", "brick", "sandstone", "deepslate", "mud", "quartz"};
    private static final String[] MAT_NAMES = {"carvalho", "abeto", "bétula", "carvalho escuro", "acácia", "selva", "cerejeira", "mangue",
            "pedra", "pedregulho", "tijolo", "arenito", "ardósia", "barro", "quartzo"};
    private static String savedOrder = "", savedEndpoint, savedModel, configProvider;
    private static int bpScroll;

    private JsonObject data;
    private EditBox orderBox, endpointBox, modelBox;
    private int refresh, listScroll;
    private final List<int[]> npcRows = new ArrayList<>();
    private final List<String> npcRowNames = new ArrayList<>();
    private int leftW, cx0, cx1, cy0, cy1;

    public ManagerScreen(JsonObject data) {
        super(Component.literal("Manager do Reino"));
        this.data = data;
        if (Double.isNaN(camX)) recenter();
    }

    public void update(JsonObject d) {
        String before = structureKey(this.data);
        this.data = d;
        boolean typing = (orderBox != null && orderBox.isFocused()) || (endpointBox != null && endpointBox.isFocused())
                || (modelBox != null && modelBox.isFocused());
        if (!typing && !before.equals(structureKey(d))) rebuildWidgets();
    }

    /** Só reconstrói os botões quando muda algo que afeta botões (evita piscar e perder foco). */
    private String structureKey(JsonObject d) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(d.has("hasKingdom") && d.get("hasKingdom").getAsBoolean());
        sb.append(arr(d, "projects").size()).append('|').append(arr(d, "blueprints").size()).append('|').append(arr(d, "models").size());
        sb.append('|').append(str(d, "selected"));
        JsonObject cfg = obj(d, "config");
        if (cfg != null) sb.append(cfg);
        JsonObject k = obj(d, "kingdom");
        if (k != null) sb.append(k.get("conscription")).append(k.get("migration"));
        for (JsonElement e : arr(d, "kingdoms")) sb.append(str(e.getAsJsonObject(), "state"));
        return sb.toString();
    }

    private void recenter() {
        JsonObject k = obj(data, "kingdom");
        camX = k != null ? k.get("cx").getAsDouble() : num(data, "px");
        camZ = k != null ? k.get("cz").getAsDouble() : num(data, "pz");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ================================================================== layout & widgets

    @Override
    protected void init() {
        leftW = Math.min(150, Math.max(112, width / 5));
        cx0 = leftW + 10;
        cx1 = width - 6;
        cy0 = 24;
        cy1 = height - 48;

        Tab[] tabs = Tab.values();
        int tw = (width - 8) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            addRenderableWidget(new FlatButton(4 + i * tw, height - 42, tw - 2, 16, Component.literal(t.label), b -> {
                tab = t;
                listScroll = 0;
                rebuildWidgets();
            }, t == Tab.CONFIG ? BLUE : ACCENT).selected(t == tab));
        }
        orderBox = new EditBox(font, 4, height - 22, width - 8 - 128, 16, Component.literal("Ordem"));
        orderBox.setMaxLength(300);
        orderBox.setHint(Component.literal("Ordem ao conselho: \"construam uma casa de pedra com 2 andares até amanhã\"").withStyle(s -> s.withColor(0x707a84)));
        orderBox.setValue(savedOrder);
        orderBox.setResponder(v -> savedOrder = v);
        addRenderableWidget(orderBox);
        addRenderableWidget(new FlatButton(width - 122, height - 22, 58, 16, Component.literal("Enviar"), b -> sendOrder(), GOOD));
        addRenderableWidget(new FlatButton(width - 62, height - 22, 58, 16, Component.literal(ClientState.managerOn ? "Voltar (M)" : "Fechar"),
                b -> {
                    if (ClientState.managerOn) PacketDistributor.sendToServer(new Payloads.ManagerToggle(false));
                    onClose();
                }, BAD));

        if (data == null || !data.get("hasKingdom").getAsBoolean()) {
            addRenderableWidget(new FlatButton((cx0 + cx1) / 2 - 80, (cy0 + cy1) / 2, 160, 20, Component.literal("Fundar meu reino aqui"),
                    b -> send("found"), GOOD));
            return;
        }
        if (!str(data, "pending").isBlank()) {
            addRenderableWidget(new FlatButton(cx1 - 168, cy0, 80, 15, Component.literal("Confirmar"), b -> send("confirm"), BAD));
            addRenderableWidget(new FlatButton(cx1 - 84, cy0, 80, 15, Component.literal("Desistir"), b -> send("abort"), GOOD));
        }
        switch (tab) {
            case BUILD -> buildTab();
            case ARMY -> {
                int y = cy0 + 20;
                JsonObject k = obj(data, "kingdom");
                btn("Convocar 1", "army recruit 1", cx0 + 4, y, 84, ACCENT);
                btn("Convocar 3", "army recruit 3", cx0 + 92, y, 84, ACCENT);
                btn("O comandante decide", "army recruit", cx0 + 180, y, 124, ACCENT);
                btn("Liberar 1 → fazenda", "army release 1 fazendeiro", cx0 + 4, y + 20, 128, DIM);
                btn("Liberar 3 → fazenda", "army release 3 fazendeiro", cx0 + 136, y + 20, 128, DIM);
                boolean live = false;
                for (JsonElement e : arr(k, "campaigns")) live |= e.getAsJsonObject().get("live").getAsBoolean();
                if (live) btn("Recuar a tropa", "retreat", cx0 + 268, y + 20, 100, WARN);
                btn("Treinar a tropa", "train", cx0 + 308, y, 100, GOOD);
                // vizinhos: atacar (o comandante escolhe quem vai e o objetivo)
                int ny = cy1 - 44;
                int nx = cx0 + 4;
                for (JsonElement e : arr(data, "kingdoms")) {
                    JsonObject o = e.getAsJsonObject();
                    if (o.get("me").getAsBoolean() || o.get("pop").getAsInt() == 0) continue;
                    String first = o.get("name").getAsString();
                    int w = Math.min(150, font.width("Atacar " + first) + 14);
                    btn("Atacar " + first, "attack " + first, nx, ny, w, BAD);
                    nx += w + 4;
                    if (nx > cx1 - 120) break;
                }
                if (k.get("captives").getAsInt() > 0) {
                    btn("Escravizar cativos → mina", "enslave cativos minerador", cx0 + 4, cy1 - 24, 160, WARN);
                    btn("Libertar cativos", "free cativos", cx0 + 168, cy1 - 24, 110, GOOD);
                } else if (k.get("enslaved").getAsInt() > 0)
                    btn("Libertar escravizados", "free todos", cx0 + 4, cy1 - 24, 140, GOOD);
            }
            case ECONOMY -> {
                String[][] jobs = {{"Fazendeiro", "farmer"}, {"Lenhador", "lumberjack"}, {"Minerador", "miner"},
                        {"Construtor", "builder"}, {"Ferreiro", "blacksmith"}, {"Mercador", "merchant"}};
                int bw = Math.min(104, (cx1 - cx0 - 12) / 3);
                for (int i = 0; i < jobs.length; i++)
                    btn("+1 " + jobs[i][0], "assign " + jobs[i][1] + " 1", cx0 + 4 + (i % 3) * (bw + 3), cy1 - 44 + (i / 3) * 20, bw, GOOD);
            }
            case DIPLOMACY -> diplomacyTab();
            case POPULATION -> populationTab();
            case TERRITORY -> {
                btn("Reivindicar 1 (grátis)", "claim 1", cx0 + 4, cy0 + 60, 140, ACCENT);
                btn("Reivindicar 3", "claim 3", cx0 + 148, cy0 + 60, 96, ACCENT);
                btn("Colonizar onde estou (3)", "settle 3", cx0 + 248, cy0 + 60, 150, GOOD);
                int ny = cy0 + 116;
                for (JsonElement e : arr(data, "kingdoms")) {
                    JsonObject o = e.getAsJsonObject();
                    if (o.get("me").getAsBoolean() || o.get("pop").getAsInt() == 0) continue;
                    btn("Invadir", "attack " + o.get("name").getAsString(), cx0 + 250, ny - 3, 60, BAD);
                    ny += 12;
                    if (ny > cy1 - 20) break;
                }
            }
            case LAWS -> {
                JsonObject k = obj(data, "kingdom");
                int y = cy0 + 28;
                btn("Impostos −", "tax down", cx0 + 4, y, 86, GOOD);
                btn("Impostos +", "tax up", cx0 + 94, y, 86, WARN);
                boolean consc = k.get("conscription").getAsBoolean();
                btn(consc ? "Abolir serviço militar" : "Instituir serviço militar", "law conscription " + (consc ? "off" : "on"), cx0 + 4, y + 36, 176, ACCENT);
                boolean mig = k.get("migration").getAsBoolean();
                btn(mig ? "Fechar imigração" : "Abrir imigração", "law migration " + (mig ? "off" : "on"), cx0 + 4, y + 72, 176, ACCENT);
            }
            case CONFIG -> configTab();
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- aba Obras

    private void buildTab() {
        int mid = cx0 + (cx1 - cx0) / 2;
        addRenderableWidget(new FlatButton(cx0 + 2, cy0 + 2, 90, 14, Component.literal(designer ? "◂ Plantas" : "✎ Projetar"), b -> {
            designer = !designer;
            rebuildWidgets();
        }, BLUE).selected(designer));
        addRenderableWidget(new FlatButton(cx0 + 96, cy0 + 2, 120, 14, Component.literal("Prazo: " + DEADLINES[deadlineChoice]), b -> {
            deadlineChoice = (deadlineChoice + 1) % DEADLINES.length;
            rebuildWidgets();
        }, WARN));
        int left1 = designer ? mid - 4 : mid - 4;
        if (!designer) {
            JsonArray bps = arr(data, "blueprints");
            int bw = (left1 - cx0 - 6) / 2;
            int rows = Math.max(1, (cy1 - cy0 - 40) / 18);
            int per = rows * 2;
            bpScroll = Math.max(0, Math.min(bpScroll, Math.max(0, bps.size() - per)));
            for (int i = bpScroll, slot = 0; i < bps.size() && slot < per; i++, slot++) {
                JsonObject b = bps.get(i).getAsJsonObject();
                String src = str(b, "source");
                FlatButton btn = new FlatButton(cx0 + 2 + (slot % 2) * (bw + 2), cy0 + 22 + (slot / 2) * 18, bw, 16,
                        Component.literal(b.get("name").getAsString()), x -> send("build " + b.get("id").getAsString() + withDeadline()),
                        src.equals("builtin") || src.isEmpty() ? ACCENT : src.equals("param") ? BLUE : GOOD);
                btn.setTooltip(Tooltip.create(Component.literal(b.get("name").getAsString() + "\nCusto: " + b.get("cost").getAsString()
                        + "\nOrigem: " + (src.isEmpty() ? "builtin" : src) + "\n" + b.get("id").getAsString())));
                addRenderableWidget(btn);
            }
        } else {
            designerWidgets(cx0 + 2, cy0 + 22, left1 - cx0 - 4);
        }
        // obras em andamento (direita): prazo e cancelar por obra
        int y = projectsTop();
        for (JsonElement e : arr(data, "projects")) {
            JsonObject p = e.getAsJsonObject();
            if (y > cy1 - 56) break;
            int n = p.get("n").getAsInt();
            int bx = mid + 4;
            addRenderableWidget(new FlatButton(bx, y + 24, 44, 14, Component.literal("prazo 5m"), b -> send("deadline " + n + " 5m"), WARN));
            addRenderableWidget(new FlatButton(bx + 46, y + 24, 44, 14, Component.literal("1 dia"), b -> send("deadline " + n + " 1d"), WARN));
            addRenderableWidget(new FlatButton(bx + 92, y + 24, 44, 14, Component.literal("3 dias"), b -> send("deadline " + n + " 3d"), WARN));
            addRenderableWidget(new FlatButton(bx + 138, y + 24, 20, 14, Component.literal("✕"), b -> send("cancel " + n), BAD));
            y += 44;
        }
    }

    private int projectsTop() {
        return cy0 + 30 + (designer ? 64 : 0);
    }

    private String withDeadline() {
        return deadlineChoice == 0 ? "" : " prazo " + DEADLINE_ARGS[deadlineChoice];
    }

    private void designerWidgets(int x, int y, int w) {
        int lw = 70, bw = Math.max(80, w - lw - 4);
        cycle(x, y, lw, bw, "Tipo", KIND_NAMES[dKind], () -> dKind = (dKind + 1) % KINDS.length, () -> dKind = (dKind + KINDS.length - 1) % KINDS.length);
        y += 16;
        cycle(x, y, lw, bw, "Largura", String.valueOf(dWidth), () -> dWidth = Math.min(15, dWidth + 1), () -> dWidth = Math.max(5, dWidth - 1));
        y += 16;
        cycle(x, y, lw, bw, "Profundidade", String.valueOf(dDepth), () -> dDepth = Math.min(15, dDepth + 1), () -> dDepth = Math.max(5, dDepth - 1));
        y += 16;
        cycle(x, y, lw, bw, "Andares", String.valueOf(dFloors), () -> dFloors = Math.min(3, dFloors + 1), () -> dFloors = Math.max(1, dFloors - 1));
        y += 16;
        cycle(x, y, lw, bw, "Telhado", ROOF_NAMES[dRoof], () -> dRoof = (dRoof + 1) % ROOFS.length, () -> dRoof = (dRoof + ROOFS.length - 1) % ROOFS.length);
        y += 16;
        cycle(x, y, lw, bw, "Parede", MAT_NAMES[dWall], () -> dWall = (dWall + 1) % MATS.length, () -> dWall = (dWall + MATS.length - 1) % MATS.length);
        y += 16;
        cycle(x, y, lw, bw, "Mat. telhado", MAT_NAMES[dRoofMat], () -> dRoofMat = (dRoofMat + 1) % MATS.length, () -> dRoofMat = (dRoofMat + MATS.length - 1) % MATS.length);
        y += 16;
        addRenderableWidget(new FlatButton(x + lw, y, bw, 14, Component.literal("Chaminé: " + (dChimney ? "sim" : "não")), b -> {
            dChimney = !dChimney;
            rebuildWidgets();
        }, DIM));
        y += 17;
        int half = (w - 2) / 2;
        addRenderableWidget(new FlatButton(x, y, half, 16, Component.literal("⚒ Construir"), b -> send("build custom " + designArgs() + withDeadline()), GOOD));
        addRenderableWidget(new FlatButton(x + half + 2, y, half, 16, Component.literal("Salvar planta"), b -> send("blueprint design " + designArgs()), BLUE));
    }

    private String designArgs() {
        return "tipo=" + KINDS[dKind] + " largura=" + dWidth + " profundidade=" + dDepth + " andares=" + dFloors
                + " telhado=" + ROOFS[dRoof] + " parede=" + MATS[dWall] + " telhado_material=" + MATS[dRoofMat] + " chamine=" + dChimney;
    }

    private void cycle(int x, int y, int lw, int bw, String label, String value, Runnable next, Runnable prev) {
        addRenderableWidget(new FlatButton(x + lw, y, 14, 14, Component.literal("◂"), b -> {
            prev.run();
            rebuildWidgets();
        }, DIM));
        addRenderableWidget(new FlatButton(x + lw + 16, y, bw - 32, 14, Component.literal(value), b -> {
            next.run();
            rebuildWidgets();
        }, ACCENT));
        addRenderableWidget(new FlatButton(x + lw + bw - 14, y, 14, 14, Component.literal("▸"), b -> {
            next.run();
            rebuildWidgets();
        }, DIM));
        designerLabels.add(new Object[]{label, x, y});
    }

    private final List<Object[]> designerLabels = new ArrayList<>();

    @Override
    protected void rebuildWidgets() {
        designerLabels.clear();
        super.rebuildWidgets();
    }

    // ---------------------------------------------------------------- aba Config

    private void configTab() {
        JsonObject cfg = obj(data, "config");
        if (cfg == null) return;
        boolean can = data.has("canConfigure") && data.get("canConfigure").getAsBoolean();
        int x = cx0 + 92, y = cy0 + 18, w = Math.min(220, cx1 - x - 70);
        String provider = str(cfg, "ai.provider");
        boolean enabled = "true".equals(str(cfg, "ai.enabled"));
        FlatButton toggle = new FlatButton(x, y, 70, 14, Component.literal(enabled ? "Ligada" : "Desligada"),
                b -> send("config set ai.enabled " + !enabled), enabled ? GOOD : BAD);
        toggle.active = can;
        addRenderableWidget(toggle);
        String[] providers = {"ollama", "openai", "claude_code", "mock"};
        int pi = Math.max(0, Arrays.asList(providers).indexOf(provider));
        boolean claude = provider.equals("claude_code");
        boolean canClaude = data.has("canClaude") && data.get("canClaude").getAsBoolean();
        String nextProvider = providers[(pi + 1) % providers.length];
        if (nextProvider.equals("claude_code") && !canClaude) nextProvider = providers[(pi + 2) % providers.length];
        String np = nextProvider;
        FlatButton prov = new FlatButton(x + 74, y, 90, 14, Component.literal((claude ? "Claude Code" : provider) + " ▸"),
                b -> send("config set ai.provider " + np), claude ? ACCENT : BLUE);
        prov.active = can;
        prov.setTooltip(Tooltip.create(Component.literal("ollama = local · openai = qualquer servidor compatível (LM Studio, vLLM, OpenAI) · "
                + "claude_code = usa o Claude Code instalado no PC (login da sua conta, sem MCP) · mock = só regras")));
        addRenderableWidget(prov);
        // Os campos mudam com o provider: Ollama/OpenAI usam endereço + modelo; o Claude Code usa o comando + apelido do modelo.
        if (!provider.equals(configProvider)) {
            savedEndpoint = null;
            savedModel = null;
            configProvider = provider;
        }
        String endpointKey = claude ? "ai.claude_command" : "ai.endpoint";
        String modelKey = claude ? "ai.claude_model" : "ai.model";
        boolean canField = can && (!claude || canClaude);
        y += 20;
        endpointBox = new EditBox(font, x, y, w, 14, Component.literal(claude ? "Comando" : "Endpoint"));
        endpointBox.setMaxLength(260);
        endpointBox.setValue(savedEndpoint != null ? savedEndpoint : str(cfg, endpointKey));
        endpointBox.setResponder(v -> savedEndpoint = v);
        endpointBox.setEditable(canField);
        if (claude) endpointBox.setTooltip(Tooltip.create(Component.literal(
                "\"claude\" se ele abre no terminal. Senão o caminho completo: C:\\Users\\voce\\.local\\bin\\claude.exe (instalador) "
                        + "ou C:\\Users\\voce\\AppData\\Roaming\\npm\\claude.cmd (npm).")));
        addRenderableWidget(endpointBox);
        FlatButton se = new FlatButton(x + w + 4, y, 60, 14, Component.literal("Salvar"), b -> {
            send("config set " + endpointKey + " " + endpointBox.getValue().trim());
            savedEndpoint = null;
        }, GOOD);
        se.active = canField;
        addRenderableWidget(se);
        y += 20;
        modelBox = new EditBox(font, x, y, w, 14, Component.literal("Modelo"));
        modelBox.setMaxLength(120);
        modelBox.setValue(savedModel != null ? savedModel : str(cfg, modelKey));
        modelBox.setResponder(v -> savedModel = v);
        modelBox.setEditable(canField);
        addRenderableWidget(modelBox);
        FlatButton sm = new FlatButton(x + w + 4, y, 60, 14, Component.literal("Salvar"), b -> {
            send("config set " + modelKey + " " + modelBox.getValue().trim());
            savedModel = null;
        }, GOOD);
        sm.active = canField;
        addRenderableWidget(sm);
        y += 18;
        if (claude) {
            FlatButton login = new FlatButton(x, y, 110, 14, Component.literal("Login do Claude"), b -> send("config login"), ACCENT);
            boolean local = data.has("localHost") && data.get("localHost").getAsBoolean();
            login.active = canField && local;
            login.setTooltip(Tooltip.create(Component.literal(local
                    ? "Abre uma janela do terminal FORA do jogo com \"claude auth login\" (o navegador abre para entrar na sua conta). Só precisa uma vez."
                    : "Servidor dedicado: rode \"claude auth login\" no terminal do servidor.")));
            addRenderableWidget(login);
        } else addRenderableWidget(new FlatButton(x, y, 110, 14, Component.literal("Listar modelos"), b -> send("config models"), BLUE));
        addRenderableWidget(new FlatButton(x + 114, y, 90, 14, Component.literal("Testar IA"), b -> send("config test"), WARN));
        y += 18;
        // modelos disponíveis (clique escolhe). Claude Code: os apelidos fixos, sem consultar nada.
        JsonArray models = new JsonArray();
        if (claude) for (String m : new String[]{"haiku", "sonnet", "opus", "fable"}) models.add(m);
        else models = arr(data, "models");
        String currentModel = str(cfg, modelKey);
        int mx = x;
        for (JsonElement e : models) {
            String m = e.getAsString();
            int mw = Math.min(140, font.width(m) + 12);
            if (mx + mw > cx1 - 4) {
                mx = x;
                y += 16;
            }
            if (y > cy0 + 120) break;
            FlatButton mb = new FlatButton(mx, y, mw, 14, Component.literal(m), b -> {
                savedModel = null;
                send("config set " + modelKey + " " + m);
            }, m.equals(currentModel) ? GOOD : DIM).selected(m.equals(currentModel));
            mb.active = canField;
            if (claude) mb.setTooltip(Tooltip.create(Component.literal(switch (m) {
                case "haiku" -> "Rápido e econômico (~5 s): o melhor para conversa com súditos.";
                case "sonnet" -> "Equilibrado: ordens complexas e cadeias de trabalho (~10–20 s).";
                case "opus" -> "O mais capaz e o mais lento: conselho estratégico.";
                default -> "Fable: modelo de alto nível; gasta mais da sua cota.";
            })));
            addRenderableWidget(mb);
            mx += mw + 3;
        }
        y = Math.max(y + 22, cy0 + 124);
        int sx0 = cx0 + 4, sx1 = cx0 + 154;
        stepper(sx0, y, "ai.temperature", 0.1, can);
        stepper(sx1, y, "ai.timeout_ms", 5000, can);
        y += 17;
        stepper(sx0, y, "construction.builder_blocks_per_second", 0.5, can);
        stepper(sx1, y, "simulation.npc_detail_radius", 16, can);
        y += 17;
        stepper(sx0, y, "simulation.rival_kingdoms", 1, can);
        boolean auto = "true".equals(str(cfg, "simulation.auto_found_on_join"));
        FlatButton af = new FlatButton(sx1, y, 140, 14, Component.literal("Fundar ao entrar: " + (auto ? "sim" : "não")),
                b -> send("config set simulation.auto_found_on_join " + !auto), DIM);
        af.active = can;
        addRenderableWidget(af);
        configRows = y;
    }

    private int configRows;

    private void stepper(int x, int y, String key, double step, boolean can) {
        JsonObject cfg = obj(data, "config");
        String raw = str(cfg, key);
        double v;
        try {
            v = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return;
        }
        boolean integer = !raw.contains(".");
        String minus = integer ? String.valueOf((long) (v - step)) : String.format(Locale.ROOT, "%.2f", v - step);
        String plus = integer ? String.valueOf((long) (v + step)) : String.format(Locale.ROOT, "%.2f", v + step);
        FlatButton m = new FlatButton(x + 84, y, 14, 14, Component.literal("−"), b -> send("config set " + key + " " + minus), DIM);
        FlatButton p = new FlatButton(x + 128, y, 14, 14, Component.literal("+"), b -> send("config set " + key + " " + plus), DIM);
        m.active = p.active = can;
        addRenderableWidget(m);
        addRenderableWidget(p);
    }

    // ---------------------------------------------------------------- abas Diplomacia / Povo

    private void diplomacyTab() {
        int y = cy0 + 22;
        for (JsonElement e : arr(data, "kingdoms")) {
            JsonObject k = e.getAsJsonObject();
            if (k.get("me").getAsBoolean()) continue;
            String first = k.get("name").getAsString().split(" ")[0];
            boolean war = "Guerra".equals(str(k, "state"));
            int x = cx0 + 4;
            btn("Pacto", "diplomacy treaty " + first + " nap", x, y + 22, 50, GOOD);
            btn("Acordo comercial", "diplomacy treaty " + first + " trade", x + 52, y + 22, 96, BLUE);
            btn("Presente 50 ouro", "diplomacy gift " + first + " 50 ouro", x + 150, y + 22, 96, ACCENT);
            btn(war ? "Propor paz" : "Declarar guerra", "war " + (war ? "peace " : "declare ") + first, x + 248, y + 22, 92, war ? GOOD : BAD);
            y += 52;
            if (y > cy1 - 40) break;
        }
    }

    private void populationTab() {
        if (selectedNpc == null) selectedNpc = str(data, "selected").isEmpty() ? null : str(data, "selected");
        if (selectedNpc == null) return;
        String[][] jobs = {{"Fazendeiro", "farmer"}, {"Construtor", "builder"}, {"Lenhador", "lumberjack"}, {"Minerador", "miner"},
                {"Guarda", "guard"}, {"Soldado", "soldier"}, {"Ferreiro", "blacksmith"}, {"Mercador", "merchant"}};
        String[][] offices = {{"Capitão", "captain"}, {"General", "general"}, {"Conselheiro", "advisor"}, {"Arquiteto", "architect"},
                {"Tesoureiro", "treasurer"}, {"Chanceler", "chancellor"}};
        int x0 = (cx0 + cx1) / 2 + 4;
        int bw = Math.max(56, (cx1 - x0 - 6) / 2);
        int y = cy0 + 56;
        String first = selectedNpc.split(" ")[0];
        for (int i = 0; i < jobs.length; i++) {
            String cmd = "npc job " + first + " " + jobs[i][1];
            addRenderableWidget(new FlatButton(x0 + (i % 2) * (bw + 2), y + (i / 2) * 15, bw, 14, Component.literal(jobs[i][0]),
                    b -> send(cmd), ACCENT));
        }
        y += 4 * 15 + 3;
        for (int i = 0; i < offices.length; i++) {
            String cmd = "npc promote " + first + " " + offices[i][1];
            addRenderableWidget(new FlatButton(x0 + (i % 2) * (bw + 2), y + (i / 2) * 15, bw, 14, Component.literal("Nomear " + offices[i][0]),
                    b -> send(cmd), BLUE));
        }
        y += 3 * 15 + 3;
        addRenderableWidget(new FlatButton(x0, y, bw * 2 + 2, 16, Component.literal("💬 Falar com " + first), b -> {
            onClose();
            ClientHooks.openChat("/k npc talk " + first + " ");
        }, GOOD));
        y += 18;
        addRenderableWidget(new FlatButton(x0, y, bw, 14, Component.literal("📣 Chamar"), b -> send("call " + first), BLUE));
        addRenderableWidget(new FlatButton(x0 + bw + 2, y, bw, 14, Component.literal("👣 Seguir-me"), b -> send("follow " + first), BLUE));
        y += 15;
        addRenderableWidget(new FlatButton(x0, y, bw * 2 + 2, 14, Component.literal("✋ Dispensar"), b -> send("dismiss " + first), WARN));
    }

    private void btn(String label, String command, int x, int y, int w, int accent) {
        addRenderableWidget(new FlatButton(x, y, w, 16, Component.literal(label), b -> send(command), accent));
    }

    private void send(String command) {
        PacketDistributor.sendToServer(new Payloads.ManagerAction(command));
    }

    private void sendOrder() {
        String v = orderBox.getValue().trim();
        if (v.isEmpty()) return;
        send("order " + v);
        orderBox.setValue("");
        savedOrder = "";
    }

    // ================================================================== entrada

    @Override
    public void tick() {
        super.tick();
        if (++refresh % 40 == 0) PacketDistributor.sendToServer(new Payloads.ManagerRequest(false));
    }

    private boolean typing() {
        return (orderBox != null && orderBox.isFocused()) || (endpointBox != null && endpointBox.isFocused())
                || (modelBox != null && modelBox.isFocused());
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (orderBox.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            sendOrder();
            return true;
        }
        if (!typing() && ClientSetup.UI_KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        if (!typing() && ClientSetup.MANAGER_KEY.matches(key, scan)) {
            if (ClientState.managerOn) PacketDistributor.sendToServer(new Payloads.ManagerToggle(false));
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (inCenter(mx, my)) {
            if (tab == Tab.MAP) scale = Math.max(0.2, Math.min(10, scale * (sy > 0 ? 1.2 : 1 / 1.2)));
            else if (tab == Tab.POPULATION) listScroll = Math.max(0, listScroll - (int) Math.signum(sy) * 3);
            else if (tab == Tab.BUILD && !designer) {
                bpScroll = Math.max(0, bpScroll - (int) Math.signum(sy) * 2);
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (tab == Tab.MAP && inCenter(mx, my) && button == 0) {
            camX -= dx / scale;
            camZ -= dy / scale;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (tab == Tab.POPULATION) {
            for (int i = 0; i < npcRows.size(); i++) {
                int[] r = npcRows.get(i);
                if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                    selectedNpc = npcRowNames.get(i);
                    rebuildWidgets();
                    return true;
                }
            }
        }
        if (tab == Tab.MAP && inCenter(mx, my)) {
            JsonObject n = npcAt(mx, my);
            if (n != null && n.get("mine").getAsBoolean()) {
                selectedNpc = n.get("name").getAsString();
                tab = Tab.POPULATION;
                rebuildWidgets();
                return true;
            }
        }
        return false;
    }

    private boolean inCenter(double mx, double my) {
        return mx >= cx0 && mx < cx1 && my >= cy0 && my < cy1;
    }

    // ================================================================== desenho

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // Sem fundo opaco: o mundo continua visível. Só um leve escurecimento.
        g.fill(0, 0, width, height, 0x30000000);
        if (data == null) return;
        topBar(g);
        panel(g, 2, cy0 - 2, leftW + 4, cy1 + 2);
        drawLeft(g);
        if (tab != Tab.MAP) panel(g, cx0 - 4, cy0 - 2, cx1 + 2, cy1 + 2);
        if (!data.get("hasKingdom").getAsBoolean()) {
            g.drawCenteredString(font, "Você ainda não governa um reino.", (cx0 + cx1) / 2, (cy0 + cy1) / 2 - 20, TEXT);
        } else {
            g.enableScissor(cx0 - 3, cy0 - 1, cx1 + 1, cy1 + 1);
            switch (tab) {
                case MAP -> drawMap(g, mx, my);
                case BUILD -> drawBuild(g);
                case ARMY -> drawArmy(g);
                case ECONOMY -> drawEconomy(g);
                case DIPLOMACY -> drawDiplomacy(g);
                case POPULATION -> drawPopulation(g, mx, my);
                case TERRITORY -> drawTerritory(g);
                case LAWS -> drawLaws(g);
                case RELIGION -> drawReligion(g);
                case CONFIG -> drawConfig(g);
            }
            drawPending(g);
            g.disableScissor();
        }
        // feedback do último comando
        JsonArray fb = arr(data, "feedback");
        if (!fb.isEmpty()) {
            String last = fb.get(fb.size() - 1).getAsString();
            for (JsonElement e : fb) {
                String s = e.getAsString();
                if (s.startsWith("✓") || s.startsWith("✗") || s.startsWith("«")) last = s;
            }
            int color = last.startsWith("✗") ? BAD : last.startsWith("✓") ? GOOD : TEXT;
            g.fill(cx0 - 4, cy1 - 12, cx1 + 2, cy1 + 2, 0xD0000000);
            g.drawString(font, font.plainSubstrByWidth(last, cx1 - cx0), cx0, cy1 - 10, color);
        }
    }

    private void topBar(GuiGraphics g) {
        panel(g, 0, 0, width, 20);
        JsonObject k = obj(data, "kingdom");
        if (k == null) {
            g.drawString(font, "MANAGER", 6, 6, ACCENT);
            return;
        }
        String title = "👑 " + k.get("name").getAsString() + " · Dia " + k.get("day").getAsLong();
        g.drawString(font, title, 6, 6, ACCENT);
        int x = 14 + font.width(title);
        for (JsonElement e : arr(data, "res")) {
            JsonObject r = e.getAsJsonObject();
            double v = r.get("value").getAsDouble(), dl = r.get("delta").getAsDouble();
            String label = r.get("name").getAsString();
            String val = String.format(Locale.ROOT, "%.0f", v) + (Math.abs(dl) < 0.05 ? "" : String.format(Locale.ROOT, "%+.1f", dl));
            if (x + font.width(label + val) + 14 > width) break;
            g.drawString(font, label, x, 6, MUTED);
            x += font.width(label) + 3;
            g.drawString(font, val, x, 6, dl < -0.05 ? WARN : TEXT);
            x += font.width(val) + 10;
        }
    }

    private void drawLeft(GuiGraphics g) {
        int x = 7, y = cy0 + 3;
        JsonObject k = obj(data, "kingdom");
        if (k == null) return;
        y = stat(g, x, y, "População", k.get("pop").getAsInt() + "/" + k.get("cap").getAsInt(), k.get("pop").getAsInt() > k.get("cap").getAsInt() ? WARN : TEXT);
        y = stat(g, x, y, "Exército", String.valueOf(k.get("military").getAsInt()), TEXT);
        y = stat(g, x, y, "Território", String.format(Locale.ROOT, "%.2f km²", k.get("area").getAsDouble()), TEXT);
        y += 2;
        g.drawString(font, "Moral", x, y, DIM);
        bar(g, x + 52, y + 2, leftW - 56, 4, k.get("morale").getAsDouble() / 100, levelColor(k.get("morale").getAsDouble()));
        y += 10;
        g.drawString(font, "Estabilid.", x, y, DIM);
        bar(g, x + 52, y + 2, leftW - 56, 4, k.get("stability").getAsDouble() / 100, levelColor(k.get("stability").getAsDouble()));
        y += 10;
        g.drawString(font, "Legitim.", x, y, DIM);
        bar(g, x + 52, y + 2, leftW - 56, 4, k.get("legitimacy").getAsDouble() / 100, levelColor(k.get("legitimacy").getAsDouble()));
        y += 14;
        g.drawString(font, "Conselho", x, y, ACCENT);
        y += 11;
        String roteiro = k.has("roteiro") ? k.get("roteiro").getAsString() : "";
        if (!roteiro.isBlank())
            for (FormattedCharSequence line : font.split(Component.literal("▶ " + roteiro), leftW - 6)) {
                if (y > cy1 - 54) break;
                g.drawString(font, line, x, y, WARN);
                y += 9;
            }
        for (FormattedCharSequence line : font.split(Component.literal(k.get("advice").getAsString()), leftW - 6)) {
            if (y > cy1 - 54) break;
            g.drawString(font, line, x, y, DIM);
            y += 9;
        }
        // eventos recentes no rodapé do painel
        int ey = cy1 - 44;
        g.drawString(font, "Eventos", x, ey, ACCENT);
        ey += 10;
        JsonArray ev = arr(data, "events");
        for (int i = ev.size() - 1; i >= 0 && ey < cy1 - 2; i--) {
            JsonObject e = ev.get(i).getAsJsonObject();
            int c = switch (e.get("sev").getAsString()) {
                case "GOOD" -> GOOD;
                case "WARN" -> WARN;
                case "DANGER" -> BAD;
                default -> DIM;
            };
            g.drawString(font, font.plainSubstrByWidth(e.get("icon").getAsString() + " " + e.get("msg").getAsString(), leftW - 6), x, ey, c);
            ey += 9;
        }
    }

    private int stat(GuiGraphics g, int x, int y, String label, String value, int color) {
        g.drawString(font, label, x, y, DIM);
        g.drawString(font, value, x + leftW - 6 - font.width(value), y, color);
        return y + 10;
    }

    // ---------------------------------------------------------------- mapa

    private int sx(double wx) {
        return (int) Math.round((cx0 + cx1) / 2.0 + (wx - camX) * scale);
    }

    private int sz(double wz) {
        return (int) Math.round((cy0 + cy1) / 2.0 + (wz - camZ) * scale);
    }

    private void drawMap(GuiGraphics g, int mx, int my) {
        g.fill(cx0 - 3, cy0 - 1, cx1 + 1, cy1 + 1, 0xE015202A);
        JsonArray ks = arr(data, "kingdoms");
        int cs = data.get("cellSize").getAsInt();
        for (JsonElement e : arr(data, "cells")) {
            JsonArray c = e.getAsJsonArray();
            int ki = c.get(2).getAsInt();
            int col = ki >= 0 && ki < ks.size() ? ks.get(ki).getAsJsonObject().get("color").getAsInt() : 0x888888;
            int x0 = sx(c.get(0).getAsInt() * (double) cs), z0 = sz(c.get(1).getAsInt() * (double) cs);
            int x1 = sx((c.get(0).getAsInt() + 1) * (double) cs), z1 = sz((c.get(1).getAsInt() + 1) * (double) cs);
            if (x1 < cx0 || x0 > cx1 || z1 < cy0 || z0 > cy1) continue;
            g.fill(x0, z0, x1, z1, 0x40000000 | (col & 0xFFFFFF));
            g.fill(x0, z0, x1, z0 + 1, 0x30000000 | (col & 0xFFFFFF));
            g.fill(x0, z0, x0 + 1, z1, 0x30000000 | (col & 0xFFFFFF));
        }
        for (JsonElement e : arr(data, "buildings")) {
            JsonObject b = e.getAsJsonObject();
            int x0 = sx(b.get("x").getAsInt()), z0 = sz(b.get("z").getAsInt());
            int x1 = Math.max(x0 + 2, sx(b.get("x").getAsInt() + b.get("w").getAsInt())), z1 = Math.max(z0 + 2, sz(b.get("z").getAsInt() + b.get("d").getAsInt()));
            boolean done = b.get("done").getAsBoolean();
            g.fill(x0, z0, x1, z1, done ? 0xFFB08A5A : 0xFF6A5A3A);
            if (!done) g.fill(x0, z1 - 2, x0 + (int) ((x1 - x0) * b.get("pct").getAsDouble() / 100.0), z1, ACCENT);
            if (scale >= 2.5) g.drawString(font, b.get("name").getAsString(), x0, z0 - 9, 0xFFCFC2A8);
        }
        for (JsonElement e : ks) {
            JsonObject k = e.getAsJsonObject();
            String label = k.get("name").getAsString() + (k.get("me").getAsBoolean() ? "" : " · " + str(k, "state"));
            g.drawCenteredString(font, label, sx(k.get("cx").getAsInt()), sz(k.get("cz").getAsInt()) - 22, 0xFF000000 | k.get("color").getAsInt());
        }
        for (JsonElement e : arr(data, "npcs")) {
            JsonObject n = e.getAsJsonObject();
            int x = sx(n.get("x").getAsInt()), z = sz(n.get("z").getAsInt());
            if (x < cx0 || x > cx1 || z < cy0 || z > cy1) continue;
            boolean mine = n.get("mine").getAsBoolean();
            g.fill(x - 2, z - 2, x + 2, z + 2, mine ? 0xFF000000 : 0xFFFF3030);
            g.fill(x - 1, z - 1, x + 1, z + 1, profColor(n.get("prof").getAsString()));
            if (n.get("name").getAsString().equals(selectedNpc)) {
                g.fill(x - 4, z - 4, x + 4, z - 3, 0xFFFFFFFF);
                g.fill(x - 4, z + 3, x + 4, z + 4, 0xFFFFFFFF);
            }
        }
        int px = sx(num(data, "px")), pz = sz(num(data, "pz"));
        g.fill(px - 3, pz - 3, px + 3, pz + 3, 0xFFFFFFFF);
        g.fill(px - 2, pz - 2, px + 2, pz + 2, BLUE);
        g.drawString(font, "Arraste = mover · roda = zoom · clique num súdito", cx0, cy0 + 1, MUTED);
        JsonObject hover = npcAt(mx, my);
        if (hover != null) {
            List<Component> tip = List.of(
                    Component.literal(hover.get("name").getAsString() + " — " + hover.get("title").getAsString()),
                    Component.literal(hover.get("act").getAsString() + (hover.get("task").getAsString().isBlank() ? "" : ": " + hover.get("task").getAsString())).withStyle(s -> s.withColor(0xAAAAAA)),
                    Component.literal("Lealdade " + hover.get("loyalty").getAsInt() + " · nível " + hover.get("level").getAsString()
                            + (hover.has("moodWord") ? " · " + hover.get("moodWord").getAsString() : "")).withStyle(s -> s.withColor(0xAAAAAA)));
            g.renderComponentTooltip(font, tip, mx, my);
        }
    }

    private JsonObject npcAt(double mx, double my) {
        JsonObject best = null;
        double bd = 25;
        for (JsonElement e : arr(data, "npcs")) {
            JsonObject n = e.getAsJsonObject();
            double dx = sx(n.get("x").getAsInt()) - mx, dz = sz(n.get("z").getAsInt()) - my;
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    private static int profColor(String prof) {
        return switch (prof) {
            case "FARMER" -> 0xFF7FD34E;
            case "BUILDER" -> 0xFFE8C15A;
            case "LUMBERJACK" -> 0xFF8B5A2B;
            case "MINER" -> 0xFF9A9A9A;
            case "BLACKSMITH" -> 0xFFE07A30;
            case "GUARD", "SOLDIER" -> 0xFFE04040;
            case "MERCHANT" -> 0xFF40C0A0;
            case "PRIEST" -> 0xFFF0F0F0;
            default -> 0xFFC0B090;
        };
    }

    // ---------------------------------------------------------------- conteúdo das abas

    private void drawBuild(GuiGraphics g) {
        int mid = cx0 + (cx1 - cx0) / 2;
        if (designer) {
            for (Object[] l : designerLabels) g.drawString(font, (String) l[0], (int) l[1], (int) l[2] + 3, DIM);
            drawElevation(g, mid + 4, cy0 + 30, cx1 - mid - 8, 58);
        } else {
            g.drawString(font, "Clique numa planta para construir · roda = rolar", cx0 + 4, cy1 - 24, MUTED);
        }
        // obras
        int x = mid + 4, y = projectsTop() - 12, cw = cx1 - x - 4;
        g.drawString(font, "Obras em andamento", x, y, ACCENT);
        y += 12;
        JsonArray ps = arr(data, "projects");
        if (ps.isEmpty()) g.drawString(font, "Nenhuma obra.", x, y, MUTED);
        for (JsonElement e : ps) {
            if (y > cy1 - 56) break;
            JsonObject p = e.getAsJsonObject();
            boolean late = p.get("late").getAsBoolean();
            String pct = (int) p.get("pct").getAsDouble() + "%";
            g.drawString(font, font.plainSubstrByWidth("#" + p.get("n").getAsInt() + " " + p.get("name").getAsString(), cw - 26), x, y, TEXT);
            g.drawString(font, pct, x + cw - font.width(pct), y, late ? BAD : DIM);
            bar(g, x, y + 10, cw, 3, p.get("pct").getAsDouble() / 100, late ? BAD : ACCENT);
            String sub = "ETA " + p.get("eta").getAsString()
                    + (p.get("deadline").getAsString().isEmpty() ? "" : " · prazo " + p.get("deadline").getAsString())
                    + " · " + p.get("builders").getAsInt() + "/" + p.get("need").getAsInt() + " constr.";
            g.drawString(font, font.plainSubstrByWidth(sub, cw), x, y + 14, late ? BAD : MUTED);
            y += 44;
        }
    }

    /** Desenho simples da fachada do projeto (largura × andares × telhado). */
    private void drawElevation(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x50000000);
        int unit = Math.max(2, Math.min((w - 10) / (dWidth + 2), (h - 6) / (dFloors * 4 + dWidth / 2 + 2)));
        int bw = dWidth * unit, bh = dFloors * 4 * unit;
        int bx = x + (w - bw) / 2, by = y + h - 3 - bh;
        g.fill(bx, by, bx + bw, by + bh, matColor(MATS[dWall]));
        for (int f = 0; f < dFloors; f++) for (int i = 1; i < dWidth - 1; i += 2)
            g.fill(bx + i * unit, by + bh - (f * 4 + 2) * unit, bx + (i + 1) * unit, by + bh - (f * 4 + 1) * unit, 0xFFA8D8F0);
        g.fill(bx + bw / 2 - unit / 2, by + bh - 2 * unit, bx + bw / 2 + unit / 2 + 1, by + bh, 0xFF5A3A1A);
        int rc = matColor(MATS[dRoofMat]);
        switch (dRoof) {
            case 0 -> {
                g.fill(bx - unit, by - unit, bx + bw + unit, by, rc);
                for (int i = -1; i <= dWidth; i += 2) g.fill(bx + i * unit, by - 2 * unit, bx + (i + 1) * unit, by - unit, matColor(MATS[dWall]));
            }
            default -> {
                int layers = (dWidth + 3) / 2;
                for (int l = 0; l < layers; l++) {
                    int x0 = bx - unit + l * unit, x1 = bx + bw + unit - l * unit;
                    if (x0 >= x1) break;
                    g.fill(x0, by - (l + 1) * unit, x1, by - l * unit, rc);
                }
            }
        }
        if (dChimney) g.fill(bx + bw - 2 * unit, by - (dWidth / 2 + 2) * unit, bx + bw - unit, by, 0xFF9A4A3A);
        g.drawString(font, KIND_NAMES[dKind] + " " + dWidth + "×" + dDepth + " · " + dFloors + " andar(es)", x + 3, y + 3, DIM);
    }

    private static int matColor(String m) {
        return switch (m) {
            case "oak" -> 0xFFB08850;
            case "spruce" -> 0xFF7A5A36;
            case "birch" -> 0xFFD8C890;
            case "dark_oak" -> 0xFF4A3018;
            case "acacia" -> 0xFFB8603A;
            case "jungle" -> 0xFFA87850;
            case "cherry" -> 0xFFE8B8B0;
            case "mangrove" -> 0xFF7A3A30;
            case "stone" -> 0xFF8A8A8A;
            case "cobblestone" -> 0xFF7A7A7A;
            case "brick" -> 0xFF9A4A3A;
            case "sandstone" -> 0xFFE0D098;
            case "deepslate" -> 0xFF4A4A50;
            case "mud" -> 0xFF8A6A50;
            case "quartz" -> 0xFFEAE4DA;
            default -> 0xFF999999;
        };
    }

    private int title(GuiGraphics g, String t) {
        g.drawString(font, t, cx0, cy0 + 2, ACCENT);
        return cy0 + 16;
    }

    private void drawArmy(GuiGraphics g) {
        title(g, "Exército — não custa ouro, custa comida");
        int y = cy0 + 64;
        JsonObject k = obj(data, "kingdom");
        g.drawString(font, "Militares: " + k.get("military").getAsInt() + String.format(Locale.ROOT, "  ·  comem %.0f por ciclo", k.get("armyFood").getAsDouble())
                + (str(k, "commander").isBlank() ? "" : "  ·  decide: " + str(k, "commander")), cx0, y, TEXT);
        y += 12;
        for (JsonElement e : arr(data, "npcs")) {
            JsonObject n = e.getAsJsonObject();
            String p = n.get("prof").getAsString();
            if (!n.get("mine").getAsBoolean() || !(p.equals("SOLDIER") || p.equals("GUARD"))) continue;
            if (y > cy0 + 120) break;
            g.drawString(font, font.plainSubstrByWidth("  " + n.get("name").getAsString() + " — " + n.get("title").getAsString() + " · " + n.get("act").getAsString(), cx1 - cx0), cx0, y, DIM);
            y += 10;
        }
        y += 4;
        for (JsonElement e : arr(k, "campaigns")) {
            JsonObject c = e.getAsJsonObject();
            boolean live = c.get("live").getAsBoolean();
            String line = (live ? "⚔ " : "") + "#" + c.get("n").getAsInt() + " " + c.get("kind").getAsString() + " → " + c.get("target").getAsString()
                    + " · " + c.get("status").getAsString() + (c.get("eta").getAsLong() > 0 ? " (" + c.get("eta").getAsLong() + " s)" : "")
                    + " · " + c.get("people").getAsInt() + " pessoas";
            g.drawString(font, font.plainSubstrByWidth(line, cx1 - cx0), cx0, y, live ? WARN : MUTED);
            y += 10;
            String res = c.get("result").getAsString();
            if (!res.isBlank() && y < cy1 - 60) {
                g.drawString(font, font.plainSubstrByWidth("   " + res, cx1 - cx0), cx0, y, res.startsWith("✓") ? GOOD : BAD);
                y += 10;
            }
            if (y > cy1 - 60) break;
        }
        int cap = k.get("captives").getAsInt(), sl = k.get("enslaved").getAsInt();
        if (cap + sl > 0)
            g.drawString(font, "Cativos: " + cap + " · Escravizados: " + sl + String.format(Locale.ROOT, " · Infâmia %.0f", k.get("infamy").getAsDouble()),
                    cx0, cy1 - 36, WARN);
    }

    /** Ordem irreversível esperando "confirmo" (massacre, ataque sem piedade). */
    private void drawPending(GuiGraphics g) {
        String p = str(data, "pending");
        if (p.isBlank()) return;
        g.fill(cx0 - 3, cy0 - 1, cx1 + 1, cy0 + 16, 0xEE3a0d0d);
        g.drawString(font, font.plainSubstrByWidth(p, cx1 - cx0 - 176), cx0 + 2, cy0 + 4, BAD);
    }

    private void drawEconomy(GuiGraphics g) {
        int y = title(g, "Economia — produção por ciclo");
        for (JsonElement e : arr(data, "res")) {
            JsonObject r = e.getAsJsonObject();
            double v = r.get("value").getAsDouble(), d = r.get("delta").getAsDouble();
            g.drawString(font, r.get("name").getAsString(), cx0, y, DIM);
            g.drawString(font, String.format(Locale.ROOT, "%.0f", v), cx0 + 80, y, TEXT);
            g.drawString(font, String.format(Locale.ROOT, "%+.1f", d), cx0 + 124, y, d < -0.05 ? BAD : d > 0.05 ? GOOD : DIM);
            g.fill(cx0 + 166, y + 2, cx0 + 166 + (int) Math.min(140, Math.abs(d) * 12), y + 7, d < 0 ? BAD : GOOD);
            y += 11;
        }
        y += 4;
        Map<String, Integer> jobs = new TreeMap<>();
        for (JsonElement e : arr(data, "npcs")) {
            JsonObject n = e.getAsJsonObject();
            if (n.get("mine").getAsBoolean()) jobs.merge(n.get("profName").getAsString(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("Trabalho: ");
        jobs.forEach((j, c) -> sb.append(j).append(' ').append(c).append("  "));
        for (FormattedCharSequence line : font.split(Component.literal(sb.toString()), cx1 - cx0)) {
            g.drawString(font, line, cx0, y, TEXT);
            y += 10;
        }
        g.drawString(font, "Remanejar (tira primeiro camponeses e funções menos essenciais):", cx0, cy1 - 56, DIM);
    }

    private void drawDiplomacy(GuiGraphics g) {
        title(g, "Diplomacia — os outros reinos têm agência e memória");
        int y = cy0 + 22;
        for (JsonElement e : arr(data, "kingdoms")) {
            JsonObject k = e.getAsJsonObject();
            if (k.get("me").getAsBoolean()) continue;
            g.fill(cx0, y, cx0 + 6, y + 7, 0xFF000000 | k.get("color").getAsInt());
            g.drawString(font, font.plainSubstrByWidth(k.get("name").getAsString() + " [" + str(k, "state") + "] — " + str(k, "label") + " · " + str(k, "summary"), cx1 - cx0 - 12), cx0 + 10, y, TEXT);
            g.drawString(font, font.plainSubstrByWidth(String.format(Locale.ROOT, "confiança %.0f · medo %.0f · hostilidade %.0f · pop %d · militares %d %s",
                    k.get("trust").getAsDouble(), k.get("fear").getAsDouble(), k.get("hostility").getAsDouble(), k.get("pop").getAsInt(),
                    k.get("mil").getAsInt(), str(k, "treaties").isBlank() ? "" : "· " + str(k, "treaties")), cx1 - cx0 - 12), cx0 + 10, y + 10, DIM);
            y += 52;
            if (y > cy1 - 40) break;
        }
        if (arr(data, "kingdoms").size() <= 1) g.drawString(font, "Nenhum outro reino conhecido.", cx0, y, DIM);
    }

    private void drawPopulation(GuiGraphics g, int mx, int my) {
        title(g, "Povo — clique em um súdito");
        npcRows.clear();
        npcRowNames.clear();
        List<JsonObject> mine = new ArrayList<>();
        for (JsonElement e : arr(data, "npcs")) if (e.getAsJsonObject().get("mine").getAsBoolean()) mine.add(e.getAsJsonObject());
        int listX1 = (cx0 + cx1) / 2 - 4;
        int y = cy0 + 16;
        listScroll = Math.min(listScroll, Math.max(0, mine.size() - 5));
        for (int i = listScroll; i < mine.size(); i++) {
            if (y > cy1 - 24) break;
            JsonObject n = mine.get(i);
            String name = n.get("name").getAsString();
            boolean sel = name.equals(selectedNpc);
            boolean hover = mx >= cx0 && mx < listX1 && my >= y - 1 && my < y + 10;
            if (sel || hover) g.fill(cx0 - 2, y - 1, listX1, y + 10, sel ? 0x603070C0 : 0x30FFFFFF);
            String star = "IMPORTANT".equals(n.get("level").getAsString()) || "LLM".equals(n.get("level").getAsString()) ? "★ " : "";
            g.drawString(font, font.plainSubstrByWidth(star + name + " — " + n.get("title").getAsString(), listX1 - cx0 - 24), cx0, y, TEXT);
            String loy = String.valueOf(n.get("loyalty").getAsInt());
            g.drawString(font, loy, listX1 - 4 - font.width(loy), y, levelColor(n.get("loyalty").getAsInt()));
            npcRows.add(new int[]{cx0 - 2, y - 1, listX1, y + 10});
            npcRowNames.add(name);
            y += 11;
        }
        int x0 = (cx0 + cx1) / 2 + 4;
        JsonObject s = null;
        for (JsonObject n : mine) if (n.get("name").getAsString().equals(selectedNpc)) s = n;
        if (s == null) {
            g.drawString(font, "Nenhum súdito selecionado.", x0, cy0 + 16, DIM);
            return;
        }
        int yy = cy0 + 16;
        g.drawString(font, s.get("name").getAsString(), x0, yy, ACCENT);
        yy += 10;
        g.drawString(font, font.plainSubstrByWidth(s.get("title").getAsString() + " · lealdade " + s.get("loyalty").getAsInt() + " · fama " + s.get("fame").getAsInt(), cx1 - x0), x0, yy, TEXT);
        yy += 10;
        g.drawString(font, font.plainSubstrByWidth(s.get("act").getAsString() + (s.get("task").getAsString().isBlank() ? "" : ": " + s.get("task").getAsString()), cx1 - x0), x0, yy, DIM);
        yy += 10;
        if (s.has("mood")) {
            int mood = s.get("mood").getAsInt();
            g.drawString(font, font.plainSubstrByWidth("Humor " + mood + " (" + s.get("moodWord").getAsString() + ") · " + s.get("needs").getAsString(), cx1 - x0), x0, yy,
                    mood < 35 ? 0xFFE06060 : mood < 55 ? 0xFFE0C060 : 0xFF80D080);
            yy += 10;
            if (!s.get("life").getAsString().isBlank()) {
                g.drawString(font, font.plainSubstrByWidth(s.get("life").getAsString(), cx1 - x0), x0, yy, DIM);
                yy += 10;
            }
        }
        for (FormattedCharSequence line : font.split(Component.literal(s.get("summary").getAsString()), cx1 - x0)) {
            if (yy > cy0 + 46) break;
            g.drawString(font, line, x0, yy, DIM);
            yy += 9;
        }
    }

    private void drawTerritory(GuiGraphics g) {
        int y = title(g, "Território — células de " + data.get("cellSize").getAsInt() + "×" + data.get("cellSize").getAsInt() + " blocos");
        JsonObject k = obj(data, "kingdom");
        g.drawString(font, "Células: " + k.get("cells").getAsInt() + "/" + k.get("claimLimit").getAsInt()
                + String.format(Locale.ROOT, "  ·  Área: %.2f km²", k.get("area").getAsDouble()), cx0, y, TEXT);
        y += 12;
        g.drawString(font, font.plainSubstrByWidth("Terra livre é grátis até o limite (30 + 2 por morador + 3 por militar). Além disso, se toma:", cx1 - cx0), cx0, y, DIM);
        y += 10;
        g.drawString(font, font.plainSubstrByWidth("colonos vão morar lá (Colonizar) ou tropas invadem (Invadir) — inclusive terra de outro reino.", cx1 - cx0), cx0, y, DIM);
        y += 60;
        for (JsonElement e : arr(data, "kingdoms")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("me").getAsBoolean()) continue;
            double dx = o.get("cx").getAsInt() - k.get("cx").getAsInt(), dz = o.get("cz").getAsInt() - k.get("cz").getAsInt();
            if (o.get("pop").getAsInt() == 0) continue;
            g.drawString(font, o.get("name").getAsString() + String.format(Locale.ROOT, " — a %.0f blocos · %d militares", Math.hypot(dx, dz), o.get("mil").getAsInt()), cx0, y, TEXT);
            y += 12;
        }
    }

    private void drawLaws(GuiGraphics g) {
        int y = title(g, "Leis");
        JsonObject k = obj(data, "kingdom");
        String[] tax = {"isento", "baixo", "normal", "alto", "extorsivo"};
        g.drawString(font, "Impostos: " + tax[Math.max(0, Math.min(4, k.get("tax").getAsInt()))] + "  (mais ouro, menos estabilidade)", cx0, y, TEXT);
        g.drawString(font, "Serviço militar obrigatório: " + (k.get("conscription").getAsBoolean() ? "sim" : "não"), cx0, y + 36, TEXT);
        g.drawString(font, "Imigração: " + (k.get("migration").getAsBoolean() ? "aberta" : "fechada"), cx0, y + 72, TEXT);
    }

    private void drawReligion(GuiGraphics g) {
        int y = title(g, "Religião");
        for (FormattedCharSequence line : font.split(Component.literal(
                "Fase 11 do roadmap: religiões (deidade, dogmas, rituais, tabus), clero, templos, conversão gradual, cismas e guerras santas. "
                        + "Os dogmas viram mecânicas (ex.: MARTIAL_HONOR = moral militar). A arquitetura de eventos já está pronta."), cx1 - cx0)) {
            g.drawString(font, line, cx0, y, DIM);
            y += 10;
        }
    }

    private void drawConfig(GuiGraphics g) {
        JsonObject cfg = obj(data, "config");
        if (cfg == null) return;
        boolean can = data.has("canConfigure") && data.get("canConfigure").getAsBoolean();
        title(g, "Configuração da IA e do jogo" + (can ? "" : " — só o dono do mundo pode alterar"));
        int x = cx0 + 4, y = cy0 + 21;
        boolean claude = "claude_code".equals(str(cfg, "ai.provider"));
        g.drawString(font, "IA / Provider", x, y, DIM);
        g.drawString(font, claude ? "Comando" : "Endpoint", x, y + 23, DIM);
        g.drawString(font, "Modelo", x, y + 43, DIM);
        g.drawString(font, "Ações", x, y + 61, DIM);
        String test = str(data, "lastTest");
        if (!test.isBlank())
            g.drawString(font, font.plainSubstrByWidth(test, cx1 - x - 4), x, cy0 + 112, test.startsWith("✓") ? GOOD : test.startsWith("✗") ? BAD : WARN);
        else if (claude)
            g.drawString(font, font.plainSubstrByWidth("Sem MCP: o jogo abre o claude em segundo plano, sem ferramentas. 1ª vez: Login do Claude → Testar IA.",
                    cx1 - x - 4), x, cy0 + 112, MUTED);
        int ry = configRows - 34;
        label(g, cx0 + 4, ry, "Temperatura", str(cfg, "ai.temperature"));
        label(g, cx0 + 154, ry, "Timeout ms", str(cfg, "ai.timeout_ms"));
        label(g, cx0 + 4, ry + 17, "Construtor bl/s", str(cfg, "construction.builder_blocks_per_second"));
        label(g, cx0 + 154, ry + 17, "Raio NPCs", str(cfg, "simulation.npc_detail_radius"));
        label(g, cx0 + 4, ry + 34, "Reinos rivais", str(cfg, "simulation.rival_kingdoms"));
        g.drawString(font, font.plainSubstrByWidth("Salvo em config/kingdomsai-common.toml · CLI: /k config set <chave> <valor>", cx1 - x), x, ry + 52, MUTED);
    }

    private void label(GuiGraphics g, int x, int y, String label, String value) {
        g.drawString(font, font.plainSubstrByWidth(label, 80), x, y + 3, DIM);
        String v = value.length() > 6 ? value.substring(0, 6) : value;
        g.drawCenteredString(font, v, x + 113, y + 3, TEXT);
    }

    // ---------------------------------------------------------------- json helpers

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        return o != null && o.has(k) ? o.get(k).getAsDouble() : 0;
    }
}
