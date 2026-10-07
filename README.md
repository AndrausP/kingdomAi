# Minecraft Kingdoms AI

Mod para **NeoForge 1.21.1** que transforma o Minecraft num simulador de reino vivo. Você começa como rei de uma vila; os reinos vizinhos têm governante, economia, território, diplomacia e objetivos próprios. NPCs comuns são bots (Utility AI); personagens importantes ganham memória; a LLM (Ollama local ou qualquer servidor compatível com OpenAI) entra em conversas e ordens — e **o jogo funciona inteiro sem ela**.

## Instalar no TLauncher

1. Copie a pasta `instalar/` para o PC onde está o TLauncher.
2. Dê dois cliques em **`INSTALAR.bat`**. Ele cria uma versão nova chamada **`kingdoms`** reaproveitando o NeoForge 21.1.226 que você já tem (perfil `mine`), com a pasta de mods vazia + o Kingdoms AI. Seus outros perfis não são alterados.
3. Abra o TLauncher, escolha **kingdoms** na lista de versões e clique em Entrar.
4. Crie um **mundo novo**. Em ~3 segundos você vira rei: 10 súditos aparecem, os construtores começam o Salão Real e 2 reinos rivais são fundados a ~300–450 blocos.

**Com modpack** (Xaero's Minimap, AppleSkin, Sophisticated Backpacks, Sodium + Iris com shaders, Better Leaves): rode `instalar/modpack/INSTALAR-MODPACK.bat` — cria a versão separada **`kingdoms-modpack`**. Detalhes em [`instalar/modpack/LEIA-ME.md`](instalar/modpack/LEIA-ME.md).

Instalação manual (se o script falhar): no TLauncher instale "NeoForge 1.21.1" como versão separada, abra a pasta do jogo dessa versão e coloque `kingdomsai-0.2.0.jar` em `mods/`. Não coloque junto com o modpack ATM.

## Como jogar

| Ação | Como |
|---|---|
| Manager Mode — câmera livre (voa, atravessa blocos, WASD + espaço/shift) com HUD | tecla **M** (de novo para voltar ao corpo) |
| Interface do Manager (abas, botões, mapa) | **Alt esquerdo** dentro do Manager |
| Selecionar / conversar com NPC no Manager | clique esquerdo seleciona · direito abre a conversa |
| Conversar com um NPC (fora do Manager) | botão direito nele → o chat abre com `/k say ` |
| Ordem em linguagem natural | caixa de texto do Manager ou `/k order construam 2 casas em 10 minutos` |
| Configurar IA/modelo | aba **⚙ Config** do Manager ou `/k config ...` |
| Todos os comandos | `/k help` (`/kingdom` e `/reino` também funcionam) |

Comandos úteis: `status`, `npc list`, `npc inspect <nome>`, `assign fazendeiro 2`, `army recruit 3`, `claim`, `tax up`, `diplomacy`, `war declare <reino>`, `events`, `chronicle`, `debug ai`.

### Construção

Os construtores ficam **parados no canteiro batendo** e a obra sobe bloco a bloco (de baixo para cima). Longe dos jogadores a obra avança de forma abstrata e é materializada quando alguém chega.

- `build casa 2 prazo 10m` — ordena 2 casas com prazo. Sem prazo, o jogo mostra o **ETA**.
- `projects` — obras com %, construtores, ETA e prazo (vermelho = atrasada/em risco). Obras com prazo apertado puxam mais construtores.
- `deadline <nº> 15m` / `cancel <nº>` — muda prazo / cancela (devolve parte do material).
- Plantas **híbridas**: `blueprint design nome=Torre tipo=torre largura=7 profundidade=7 andares=3 telhado=piramide parede=pedra` (a IA também gera esses parâmetros a partir de ordens em texto). Em `blueprints` ficam a biblioteca, as plantas salvas e as importadas.
- Salvar uma construção sua: `blueprint pos1`, `blueprint pos2`, `blueprint save <nome>` (até 32³).
- Importar `.nbt` (Structure Block): coloque em `versions/kingdoms/kingdomsai/blueprints/` (até 48³). Blocos perigosos (TNT, lava, spawners, command blocks…) são recusados.

## LLM (opcional)

Por padrão o mod tenta o **Ollama** em `http://127.0.0.1:11434` com `qwen2.5:7b`. Se não houver Ollama, tudo continua funcionando pelo interpretador de regras (a resposta vem marcada "IA indisponível — respondido pelas regras").

```
ollama pull qwen2.5:7b
```

Configuração: aba **⚙ Config** do Manager (Listar modelos → escolher → Testar IA) ou pelo chat:

```
/k config show
/k config models            # lista os modelos instalados no Ollama/servidor
/k config set ai.model llama3.2:3b
/k config set ai.provider openai      # LM Studio: ai.endpoint http://127.0.0.1:1234
/k config set ai.provider mock        # desliga a LLM
/k config test
```

As mudanças valem na hora e são gravadas em `versions/kingdoms/config/kingdomsai-common.toml`.

## Arquitetura (resumo)

```
com.kingdomsai.core        ← regras do jogo, sem NENHUM import do Minecraft (testável com java puro)
  action/       ActionType (primitivas), Validators (Schema → Permission → World → Resource), ActionSystem
  ai/           NpcScheduler (rotina), KingdomDirector (Utility AI dos reinos), Advisor (conselheiro determinístico)
  construction/ Blueprint, BlueprintLibrary (plantas procedurais), ConstructionSystem (LOD de obras)
  economy/ population/ diplomacy/ territory/ kingdom/ npc/ event/ persistence/ cli/
  llm/          ContextBuilder (seções + <untrusted>), LlmGateway (rate limit, timeout, retry, fallback),
                HttpProviders (Ollama/OpenAI), MockProvider + RuleInterpreter, DialogueService
com.kingdomsai.minecraft   ← adaptador: entidade NPC, materialização LOD, execução de obras, comandos, rede, save
com.kingdomsai.client      ← renderizador, tecla M, ManagerScreen
```

Regra de ouro: **a IA decide o que quer fazer, o jogo decide se pode, o Action System decide como, o Minecraft executa.**

Detalhes de cada sistema e o que falta: [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md).

## Compilar

- Com internet (recomendado): `gradle wrapper` uma vez, depois `./gradlew build` → `build/libs/kingdomsai-0.2.0.jar`. `./gradlew runClient` abre o jogo de desenvolvimento. `./gradlew coreTest` roda os testes do Core.
- Offline: `build_offline.sh` compila com `javac` contra os jars do NeoForge 21.1.226 copiados do `.minecraft` (foi assim que o jar em `instalar/` foi gerado).

Save do mundo: `<pasta do mundo>/data/kingdomsai.json` (JSON versionado, com backup `.bak` e migrações em `core/persistence/Migrations.java`).
