package com.kingdomsai.core.llm;

import com.kingdomsai.core.KingdomsCore;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gateway: limita chamadas, aplica timeout, refaz se o JSON vier inválido e cai para as regras
 * quando o modelo está indisponível. A LLM é uma camada adicional — o jogo nunca congela.
 */
public final class LlmGateway {
    public record Result(Plan plan, String provider, boolean fallback, String note) {}

    private final KingdomsCore core;
    private final MockProvider mock;
    private LlmConfig config = new LlmConfig();
    private LlmProvider provider;
    private final ArrayDeque<Long> calls = new ArrayDeque<>();
    private volatile long downUntil;
    private volatile String lastError = "";
    private volatile String lastPrompt = "";
    private volatile String lastResponse = "";

    public LlmGateway(KingdomsCore core) {
        this.core = core;
        this.mock = new MockProvider(core);
        configure(config);
    }

    public void configure(LlmConfig cfg) {
        this.config = cfg;
        this.provider = switch (cfg.provider.toLowerCase()) {
            case "ollama" -> new HttpProviders.Ollama();
            case "openai", "lmstudio", "custom" -> new HttpProviders.OpenAiCompatible();
            default -> mock;
        };
        downUntil = 0;
    }

    public LlmConfig config() {
        return config;
    }

    public String status() {
        if (!config.enabled) return "LLM desligada (modo regras)";
        if (provider == mock) return "Provider: regras (mock)";
        long now = System.currentTimeMillis();
        String s = "Provider: " + provider.name() + " · modelo " + config.model + " · " + config.endpoint;
        if (now < downUntil) s += " · indisponível (tentando de novo em " + (downUntil - now) / 1000 + "s; usando regras)";
        if (!lastError.isEmpty()) s += " · último erro: " + lastError;
        return s;
    }

    /** Lista modelos do servidor configurado (não bloqueia). */
    public CompletableFuture<java.util.List<String>> listModels() {
        if (provider == mock) return CompletableFuture.completedFuture(java.util.List.of("(modo regras: sem modelos)"));
        return HttpProviders.listModels(config);
    }

    /** Teste de ponta a ponta: manda um prompt mínimo e mede a latência. */
    public CompletableFuture<String> test() {
        if (!config.enabled || provider == mock) return CompletableFuture.completedFuture("LLM desligada/mock: o jogo usa as regras.");
        long t0 = System.currentTimeMillis();
        LlmRequest req = new LlmRequest("test", "Responda apenas com JSON.", "Responda exatamente: {\"reply\":\"ok\",\"actions\":[]}",
                null, null, "teste");
        return provider.complete(req, config).orTimeout(config.timeoutMs + 500L, TimeUnit.MILLISECONDS).handle((raw, err) -> {
            long ms = System.currentTimeMillis() - t0;
            if (err != null) {
                Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                return "✗ Falhou em " + ms + " ms: " + (c instanceof TimeoutException ? "timeout" : c.getMessage());
            }
            try {
                Plan.parse(raw);
                downUntil = 0;
                return "✓ " + provider.name() + " / " + config.model + " respondeu JSON válido em " + ms + " ms.";
            } catch (RuntimeException e) {
                return "⚠ Respondeu em " + ms + " ms, mas não em JSON válido: " + raw.substring(0, Math.min(120, raw.length()));
            }
        });
    }

    public String lastPrompt() {
        return lastPrompt;
    }

    public String lastResponse() {
        return lastResponse;
    }

    public CompletableFuture<Result> submit(LlmRequest req) {
        if (!config.enabled || provider == mock) return CompletableFuture.completedFuture(new Result(mock.interpret(req), mock.name(), false, ""));
        long now = System.currentTimeMillis();
        if (now < downUntil) return fallback(req, "modelo indisponível");
        while (!calls.isEmpty() && now - calls.peekFirst() > 60_000) calls.pollFirst();
        if (calls.size() >= config.maxCallsPerMinute) return fallback(req, "limite de chamadas por minuto");
        calls.addLast(now);
        lastPrompt = "SYSTEM:\n" + req.system() + "\n\nUSER:\n" + req.user();
        return attempt(req, 0);
    }

    private CompletableFuture<Result> attempt(LlmRequest req, int tryNo) {
        LlmRequest effective = tryNo == 0 ? req : new LlmRequest(req.purpose(), req.system(),
                req.user() + "\n\nATENÇÃO: sua resposta anterior não era JSON válido. Responda SOMENTE o objeto JSON.",
                req.kingdomId(), req.npcId(), req.rawPlayerText());
        return provider.complete(effective, config)
                .orTimeout(config.timeoutMs + 500L, TimeUnit.MILLISECONDS)
                .handle((raw, err) -> {
                    if (err != null) {
                        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                        lastError = cause instanceof TimeoutException ? "timeout" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                        downUntil = System.currentTimeMillis() + 60_000;
                        return fallback(req, lastError);
                    }
                    lastResponse = raw;
                    try {
                        Plan p = Plan.parse(raw);
                        lastError = "";
                        return CompletableFuture.completedFuture(new Result(p, provider.name(), false, ""));
                    } catch (RuntimeException parseError) {
                        lastError = "JSON inválido";
                        if (tryNo < config.maxRetries) return attempt(req, tryNo + 1);
                        return fallback(req, "JSON inválido após " + (tryNo + 1) + " tentativa(s)");
                    }
                }).thenCompose(f -> f);
    }

    private CompletableFuture<Result> fallback(LlmRequest req, String why) {
        if (!config.fallbackToRules)
            return CompletableFuture.completedFuture(new Result(new Plan("(Sem resposta: " + why + ")", java.util.List.of()), "nenhum", true, why));
        // O interpretador lê o estado do jogo: precisa rodar na thread principal.
        CompletableFuture<Result> f = new CompletableFuture<>();
        core.mainThread().execute(() -> {
            try {
                f.complete(new Result(mock.interpret(req), mock.name(), true, why));
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }
}
