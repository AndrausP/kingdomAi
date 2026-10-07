package com.kingdomsai.minecraft;

import com.kingdomsai.core.CoreConfig;
import com.kingdomsai.core.llm.ClaudeCodeProvider;
import com.kingdomsai.core.llm.LlmConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** config/kingdomsai-common.toml — seções equivalentes às do documento de arquitetura. */
public final class KingdomsConfig {
    public static final ModConfigSpec SPEC;

    // [ai]
    public static final ModConfigSpec.BooleanValue AI_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> AI_PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> AI_MODEL;
    public static final ModConfigSpec.ConfigValue<String> AI_ENDPOINT;
    public static final ModConfigSpec.ConfigValue<String> AI_API_KEY;
    public static final ModConfigSpec.DoubleValue AI_TEMPERATURE;
    public static final ModConfigSpec.IntValue AI_MAX_CONTEXT_TOKENS;
    public static final ModConfigSpec.IntValue AI_TIMEOUT_MS;
    public static final ModConfigSpec.IntValue AI_MAX_RETRIES;
    public static final ModConfigSpec.IntValue AI_MAX_CALLS_PER_MINUTE;
    public static final ModConfigSpec.BooleanValue AI_FALLBACK_TO_RULES;
    public static final ModConfigSpec.ConfigValue<String> AI_CLAUDE_COMMAND;
    public static final ModConfigSpec.ConfigValue<String> AI_CLAUDE_MODEL;
    public static final ModConfigSpec.BooleanValue AI_CHAT_ORDERS;

    // [simulation]
    public static final ModConfigSpec.IntValue STRATEGIC_TICK;
    public static final ModConfigSpec.IntValue ECONOMIC_TICK;
    public static final ModConfigSpec.IntValue POPULATION_TICK;
    public static final ModConfigSpec.IntValue NPC_DETAIL_RADIUS;
    public static final ModConfigSpec.IntValue MAX_ACTIVE_NPCS;
    public static final ModConfigSpec.IntValue STARTING_CITIZENS;
    public static final ModConfigSpec.BooleanValue AUTO_FOUND_ON_JOIN;
    public static final ModConfigSpec.BooleanValue AI_KINGDOMS;
    public static final ModConfigSpec.IntValue RIVAL_KINGDOMS;
    public static final ModConfigSpec.IntValue RIVAL_DISTANCE;
    public static final ModConfigSpec.IntValue MAX_NPCS_PER_KINGDOM;
    public static final ModConfigSpec.BooleanValue KEEP_ORDER_CHUNKS;
    public static final ModConfigSpec.IntValue MAX_FORCED_CHUNKS;

    // [construction]
    public static final ModConfigSpec.BooleanValue CONSTRUCTION_ENABLED;
    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_TICK;
    public static final ModConfigSpec.DoubleValue BUILDER_BLOCKS_PER_SECOND;

    // [diplomacy] / [territory]
    public static final ModConfigSpec.BooleanValue DIPLOMACY_ENABLED;
    public static final ModConfigSpec.IntValue CELL_SIZE;
    public static final ModConfigSpec.IntValue INITIAL_CLAIM_RADIUS;

    // [compat]
    public static final ModConfigSpec.IntValue MINIMAP_RESERVE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("ai");
        AI_ENABLED = b.comment("Liga a LLM. Desligada, tudo funciona por regras (Utility AI + interpretador).").define("enabled", true);
        AI_PROVIDER = b.comment("ollama | openai (qualquer servidor compatível: LM Studio, vLLM...) | claude_code (usa o login do Claude Code do PC) | mock (somente regras)")
                .define("provider", "ollama");
        AI_MODEL = b.define("model", "qwen2.5:7b");
        AI_ENDPOINT = b.comment("Ollama: http://127.0.0.1:11434 · LM Studio: http://127.0.0.1:1234").define("endpoint", "http://127.0.0.1:11434");
        AI_API_KEY = b.comment("Só para provedores online.").define("api_key", "");
        AI_TEMPERATURE = b.defineInRange("temperature", 0.4, 0.0, 2.0);
        AI_MAX_CONTEXT_TOKENS = b.defineInRange("max_context_tokens", 6000, 500, 128000);
        AI_TIMEOUT_MS = b.defineInRange("timeout_ms", 15000, 1000, 120000);
        AI_MAX_RETRIES = b.defineInRange("max_retries", 2, 0, 5);
        AI_MAX_CALLS_PER_MINUTE = b.defineInRange("max_calls_per_minute", 20, 1, 600);
        AI_FALLBACK_TO_RULES = b.comment("Se a LLM falhar, responde pelas regras em vez de ficar mudo.").define("fallback_to_rules", true);
        AI_CLAUDE_COMMAND = b.comment("provider = claude_code: o executável do Claude Code. 'claude' se estiver no PATH; senão o caminho completo",
                        "(ex.: C:\\Users\\voce\\.local\\bin\\claude.exe ou C:\\Users\\voce\\AppData\\Roaming\\npm\\claude.cmd). Só aceita o executável claude.")
                .define("claude_command", "claude");
        AI_CLAUDE_MODEL = b.comment("provider = claude_code: haiku (rápido, recomendado) · sonnet · opus · fable — ou o nome completo do modelo.")
                .define("claude_model", "haiku");
        AI_CHAT_ORDERS = b.comment("Chat comum vira fala com os súditos: \"Rosalind, ataque Eldmark\", \"conselho, construam uma casa\", ou o súdito mais perto. Sem /k.")
                .define("chat_orders", true);
        b.pop();

        b.push("simulation");
        STRATEGIC_TICK = b.defineInRange("strategic_tick_seconds", 30, 5, 600);
        ECONOMIC_TICK = b.defineInRange("economic_tick_seconds", 10, 2, 600);
        POPULATION_TICK = b.defineInRange("population_tick_seconds", 60, 10, 1200);
        NPC_DETAIL_RADIUS = b.comment("NPCs dentro deste raio de um jogador viram entidades; longe disso são simulados como números (LOD).")
                .defineInRange("npc_detail_radius", 96, 32, 256);
        MAX_ACTIVE_NPCS = b.defineInRange("max_active_npcs", 100, 5, 1000);
        STARTING_CITIZENS = b.defineInRange("starting_citizens", 10, 3, 40);
        AUTO_FOUND_ON_JOIN = b.comment("O jogador começa como rei: funda o reino automaticamente ao entrar no mundo pela primeira vez.")
                .define("auto_found_on_join", true);
        AI_KINGDOMS = b.define("ai_kingdoms_enabled", true);
        RIVAL_KINGDOMS = b.defineInRange("rival_kingdoms", 2, 0, 8);
        RIVAL_DISTANCE = b.defineInRange("rival_distance", 320, 160, 3000);
        MAX_NPCS_PER_KINGDOM = b.defineInRange("max_npcs_per_kingdom", 60, 5, 500);
        KEEP_ORDER_CHUNKS = b.comment("Ordens com as mãos (quebrar, baús, fabricar) continuam com o rei longe: mantém carregados só os chunks onde o súdito trabalha.")
                .define("keep_order_chunks_loaded", true);
        MAX_FORCED_CHUNKS = b.comment("Máximo de chunks mantidos carregados à distância (desempenho). Ordens além disso esperam na fila.")
                .defineInRange("max_forced_chunks", 16, 0, 256);
        b.pop();

        b.push("construction");
        CONSTRUCTION_ENABLED = b.define("enabled", true);
        MAX_BLOCKS_PER_TICK = b.comment("Limite de blocos colocados por tick (materialização de obras).").defineInRange("max_blocks_per_tick", 100, 1, 2000);
        BUILDER_BLOCKS_PER_SECOND = b.defineInRange("builder_blocks_per_second", 2.0, 0.1, 40.0);
        b.pop();

        b.push("diplomacy");
        DIPLOMACY_ENABLED = b.define("enabled", true);
        b.pop();

        b.push("territory");
        CELL_SIZE = b.defineInRange("cell_size", 32, 8, 256);
        INITIAL_CLAIM_RADIUS = b.defineInRange("initial_claim_radius", 3, 1, 10);
        b.pop();

        b.push("compat");
        MINIMAP_RESERVE = b.comment("Espaço (px da GUI) que o HUD do Manager deixa livre no canto superior esquerdo para o minimapa.",
                        "-1 = automático (reserva só se o Xaero's Minimap estiver instalado) · 0 = nunca reservar.")
                .defineInRange("minimap_reserve", -1, -1, 400);
        b.pop();

        // Military (batalhas), Religion, Rebellion e Espionage chegam nas próximas fases.
        SPEC = b.build();
    }

    /** Chaves editáveis pelo jogo (/k config set &lt;chave&gt; &lt;valor&gt; e aba Config do Manager). */
    public static final java.util.Map<String, ModConfigSpec.ConfigValue<?>> KEYS = new java.util.LinkedHashMap<>();

    static {
        KEYS.put("ai.enabled", AI_ENABLED);
        KEYS.put("ai.provider", AI_PROVIDER);
        KEYS.put("ai.model", AI_MODEL);
        KEYS.put("ai.endpoint", AI_ENDPOINT);
        KEYS.put("ai.api_key", AI_API_KEY);
        KEYS.put("ai.temperature", AI_TEMPERATURE);
        KEYS.put("ai.max_context_tokens", AI_MAX_CONTEXT_TOKENS);
        KEYS.put("ai.timeout_ms", AI_TIMEOUT_MS);
        KEYS.put("ai.max_retries", AI_MAX_RETRIES);
        KEYS.put("ai.max_calls_per_minute", AI_MAX_CALLS_PER_MINUTE);
        KEYS.put("ai.fallback_to_rules", AI_FALLBACK_TO_RULES);
        KEYS.put("ai.claude_command", AI_CLAUDE_COMMAND);
        KEYS.put("ai.claude_model", AI_CLAUDE_MODEL);
        KEYS.put("ai.chat_orders", AI_CHAT_ORDERS);
        KEYS.put("simulation.npc_detail_radius", NPC_DETAIL_RADIUS);
        KEYS.put("simulation.max_active_npcs", MAX_ACTIVE_NPCS);
        KEYS.put("simulation.auto_found_on_join", AUTO_FOUND_ON_JOIN);
        KEYS.put("simulation.ai_kingdoms_enabled", AI_KINGDOMS);
        KEYS.put("simulation.rival_kingdoms", RIVAL_KINGDOMS);
        KEYS.put("simulation.economic_tick_seconds", ECONOMIC_TICK);
        KEYS.put("simulation.strategic_tick_seconds", STRATEGIC_TICK);
        KEYS.put("simulation.keep_order_chunks_loaded", KEEP_ORDER_CHUNKS);
        KEYS.put("simulation.max_forced_chunks", MAX_FORCED_CHUNKS);
        KEYS.put("construction.enabled", CONSTRUCTION_ENABLED);
        KEYS.put("construction.builder_blocks_per_second", BUILDER_BLOCKS_PER_SECOND);
        KEYS.put("construction.max_blocks_per_tick", MAX_BLOCKS_PER_TICK);
        KEYS.put("diplomacy.enabled", DIPLOMACY_ENABLED);
        KEYS.put("compat.minimap_reserve", MINIMAP_RESERVE);
    }

    /** Define uma chave a partir de texto, validando tipo e faixa. Retorna null se ok, senão o erro. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String set(String key, String raw) {
        ModConfigSpec.ConfigValue v = KEYS.get(key);
        if (v == null) return "Chave desconhecida: " + key + ". Veja /k config.";
        Object cur = v.get();
        Object val;
        try {
            if (cur instanceof Boolean) {
                String r = raw.toLowerCase();
                if (!r.matches("true|false|on|off|sim|nao|não|1|0")) return "Use true/false.";
                val = r.matches("true|on|sim|1");
            } else if (cur instanceof Integer) val = Integer.parseInt(raw.trim());
            else if (cur instanceof Double) val = Double.parseDouble(raw.trim().replace(',', '.'));
            else val = raw.trim();
        } catch (NumberFormatException e) {
            return "Valor inválido para " + key + ": " + raw;
        }
        if (key.equals("ai.provider") && !String.valueOf(val).matches("ollama|openai|claude_code|mock"))
            return "Provider deve ser ollama, openai, claude_code ou mock.";
        if (key.equals("ai.claude_command") && !ClaudeCodeProvider.isClaudeCommand(String.valueOf(val)))
            return "Só o executável do Claude Code: claude, claude.exe, claude.cmd ou o caminho completo até ele.";
        if (key.equals("ai.claude_model") && !String.valueOf(val).matches("[A-Za-z0-9._\\-\\[\\]]{1,64}"))
            return "Modelo inválido. Use haiku, sonnet, opus ou fable.";
        if (!v.getSpec().test(val)) return "Valor fora da faixa permitida para " + key + ".";
        v.set(val);
        v.save();
        return null;
    }

    public static String display(String key) {
        Object v = KEYS.get(key).get();
        if (key.equals("ai.api_key")) return String.valueOf(v).isBlank() ? "(vazia)" : "••••";
        return String.valueOf(v);
    }

    private KingdomsConfig() {}

    public static CoreConfig core() {
        CoreConfig c = new CoreConfig();
        c.strategicTickSeconds = STRATEGIC_TICK.get();
        c.economicTickSeconds = ECONOMIC_TICK.get();
        c.populationTickSeconds = POPULATION_TICK.get();
        c.cellSize = CELL_SIZE.get();
        c.initialClaimRadius = INITIAL_CLAIM_RADIUS.get();
        c.startingCitizens = STARTING_CITIZENS.get();
        c.rivalKingdoms = RIVAL_KINGDOMS.get();
        c.rivalDistance = RIVAL_DISTANCE.get();
        c.maxNpcsPerKingdom = MAX_NPCS_PER_KINGDOM.get();
        c.builderBlocksPerSecond = BUILDER_BLOCKS_PER_SECOND.get();
        c.aiKingdomsEnabled = AI_KINGDOMS.get();
        c.constructionEnabled = CONSTRUCTION_ENABLED.get();
        c.diplomacyEnabled = DIPLOMACY_ENABLED.get();
        c.keepOrderChunksLoaded = KEEP_ORDER_CHUNKS.get();
        c.maxForcedChunks = MAX_FORCED_CHUNKS.get();
        return c;
    }

    public static LlmConfig llm() {
        LlmConfig c = new LlmConfig();
        c.enabled = AI_ENABLED.get();
        c.provider = AI_PROVIDER.get();
        c.model = AI_MODEL.get();
        c.endpoint = AI_ENDPOINT.get();
        c.apiKey = AI_API_KEY.get();
        c.temperature = AI_TEMPERATURE.get();
        c.maxContextChars = AI_MAX_CONTEXT_TOKENS.get() * 3;
        c.timeoutMs = AI_TIMEOUT_MS.get();
        c.maxRetries = AI_MAX_RETRIES.get();
        c.maxCallsPerMinute = AI_MAX_CALLS_PER_MINUTE.get();
        c.fallbackToRules = AI_FALLBACK_TO_RULES.get();
        c.claudeCommand = AI_CLAUDE_COMMAND.get();
        c.claudeModel = AI_CLAUDE_MODEL.get();
        return c;
    }
}
