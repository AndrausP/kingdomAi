package com.kingdomsai.core.llm;

/** Equivalente à seção [ai] do kingdomsai-common.toml. */
public final class LlmConfig {
    public boolean enabled = true;
    /** ollama | openai | claude_code | mock */
    public String provider = "ollama";
    public String model = "qwen2.5:7b";
    public String endpoint = "http://127.0.0.1:11434";
    public String apiKey = "";
    public double temperature = 0.4;
    public int maxContextChars = 6000;
    public int timeoutMs = 15000;
    public int maxRetries = 2;
    public int maxCallsPerMinute = 20;
    /** Se a LLM falhar, usa o interpretador por regras (o jogo nunca congela). */
    public boolean fallbackToRules = true;
    /** claude_code: o executável do Claude Code (claude, claude.exe, claude.cmd ou o caminho completo). */
    public String claudeCommand = "claude";
    /** claude_code: haiku (rápido, recomendado) · sonnet · opus · fable. */
    public String claudeModel = "haiku";

    public boolean isClaudeCode() {
        return provider != null && provider.equalsIgnoreCase(ClaudeCodeProvider.NAME);
    }

    /** O Claude Code abre um processo por pergunta: nunca menos de 60 s. */
    public int effectiveTimeoutMs() {
        return isClaudeCode() ? Math.max(timeoutMs, ClaudeCodeProvider.MIN_TIMEOUT_MS) : timeoutMs;
    }

    /** Modelo em uso, conforme o provider. */
    public String activeModel() {
        return isClaudeCode() ? claudeModel : model;
    }

    public LlmConfig() {}
}
