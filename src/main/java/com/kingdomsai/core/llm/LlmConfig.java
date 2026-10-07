package com.kingdomsai.core.llm;

/** Equivalente à seção [ai] do kingdomsai-common.toml. */
public final class LlmConfig {
    public boolean enabled = true;
    /** ollama | openai | mock */
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

    public LlmConfig() {}
}
