# Kingdoms AI — documento de arquitetura × implementação

Este arquivo mapeia as seções do documento de arquitetura original para o código da versão 0.2.0.
Status: ✅ implementado · 🟡 parcial · ⏳ fase futura.

## MVP (seção 94) — o que esta versão entrega

| Sistema | Status | Onde |
|---|---|---|
| Rei + vila inicial (o jogador já começa como rei) | ✅ | `KingdomsCore.foundKingdom`, `ServerRuntime.autoFound` (config `auto_found_on_join`) |
| NPCs com profissão, necessidades, rotina, casa, relações, personalidade (10 traços) | ✅ | `core/npc`, `ai/NpcScheduler`, `minecraft/entity` |
| Níveis de inteligência BOT → CONTEXTUAL → IMPORTANT → LLM, promoção automática por fama/memórias/cargo | ✅ | `IntelligenceLevel`, `PopulationSystem.promotions` |
| Memória seletiva (importância, tags, recência; só as relevantes vão para a LLM) | ✅ | `Npc.remember`, `ContextBuilder.relevantMemories` |
| Manager Mode (tecla M): HUD esquerdo, eventos à direita, abas embaixo, mapa top-down com pan/zoom | ✅ | `ManagerMode` (câmera livre tipo criativo/espectador, corpo fica como suporte de armadura), `client/ManagerHud`, `client/ManagerScreen` (Alt) |
| Ações primitivas + Action Validator (Schema → Permission → World → Resource) | ✅ | `core/action` |
| CLI com os mesmos serviços da UI + linguagem natural | ✅ | `core/cli/CommandService`, `minecraft/KingdomCommands` |
| Construção real por blueprints (LLM nunca coloca blocos) — construtor parado no canteiro, obra sobe bloco a bloco | ✅ | `construction/*`, `minecraft/ConstructionExecutor`, `MaterialPalette` |
| Plantas híbridas: IA escolhe parâmetros → `ParametricBlueprints` gera; plantas salvas do mundo e `.nbt` importadas | ✅ | `ParametricBlueprints`, `KingdomsExtension` (`blueprint pos1/pos2/save`, import) |
| Prazos: ETA, prazo do rei, aviso de risco/atraso, realocação de construtores por urgência | ✅ | `ConstructionSystem.setDeadline/rebalance/checkDeadline`, `/k deadline`, `/k projects` |
| Configuração em jogo (modelo, provedor, endpoint, simulação) | ✅ | aba ⚙ do Manager, `/k config show|set|models|test|reload` |
| Território em células 32×32, reivindicação, fronteira, "o NPC sabe onde está" | ✅ | `territory/TerritoryMap`, `CommandService.territory` |
| Economia com produção/consumo/impostos/manutenção e cadeias (fazenda→comida, mina→ferro→ferreiro→armas) | ✅ | `economy/EconomySystem` |
| Outros reinos com AI Director (Utility AI, personalidade, prioridades explicáveis) | ✅ | `ai/KingdomDirector` (`/k debug ai`) |
| Diplomacia simples: atitudes, estados (Paz/Tensão/Hostil/Guerra/Armistício), tratados, comércio, presentes, reputação | ✅ | `diplomacy/*` |
| LLM para interação (Ollama/OpenAI/Mock), fallback, retry, rate limit, prompt injection | ✅ | `core/llm/*` |
| Event Bus + log + replay + crônica do mundo | ✅ | `event/*`, `/k replay`, `/k chronicle` |
| Save com UUIDs, versionado, migração, backup | ✅ | `persistence/*` |
| LOD: NPCs viram entidades perto do jogador; obras distantes avançam e "materializam" ao carregar | ✅ | `NpcMaterializer`, `ConstructionSystem.tickSecond` + `ConstructionExecutor` |
| Debug da IA | ✅ | `/k debug npc|ai|events` |
| Cadeias de trabalho: rotina adotada pelo chat, etapas físicas (ir, minerar, entregar no baú, fundir, guardar), validação antes de começar, quebra e retomada da mesma etapa | ✅ | `core/work/*` (`ChainValidator`, `WorkSystem`, `ChainTemplates`), ação `CHAIN`/`STOP_CHAIN`, `/k chains` |
| Ordens com as mãos (quebrar/cavar/túnel, cortar árvore, baús, fabricar com receitas do jogo, entregar ao rei) com validação e planejamento de ingredientes | ✅ | `core/skill/*` (`JobPlanner`, `SkillSystem`, `ItemNames`), `port/PhysicalPort` ↔ `minecraft/McPhysicalPort`, ação `JOB`/`CANCEL_JOB`, [`JOGABILIDADE.md`](JOGABILIDADE.md) |
| Bandeira do Reino: item que marca spawn (moradores chegam, rei renasce), praça, mina e bosque | ✅ | `kingdom/Marker`, ação `MARK`, `minecraft/item/KingdomMarkerItem` |
| Chamar/seguir/dispensar NPC (tecla G) e muralha sob medida da vila | ✅ | `NpcScheduler.summon`, `construction/VillageWall` |
| Biblioteca, livros (escrever/ler vira memória) e cartas entregues em mãos | ✅ | `work/Document`, planta `library`, `/k books` |

## Próximas fases (seção 95)

| Fase | Status | Observação |
|---|---|---|
| 9 Military (campanhas, batalhas agregadas, ocupação, colonos, cativos) | ✅ base | `core/military`: `Campaign` (salva), `MilitarySystem` (marcha 4 b/s, batalha força×defesa, células 3×3, vila cai → cativos, colonos, massacre/escravidão/libertação com obediência e consequências). Ações ATTACK/OCCUPY/RETREAT/SETTLE/PURGE/ENSLAVE/FREE. Falta: batalha materializada (entidades lutando), cerco, logística por rota |
| 10 Diplomacy avançada (coalizões, vassalagem, tributo efetivo) | 🟡 | tratados e atitudes prontos; coalizão ⏳ |
| 11 Religion (dogmas → mecânicas, clero, templos, conversão, cisma) | ⏳ | aba no Manager reservada |
| Rebelião, crimes/justiça, espionagem, sucessão | ⏳ | — |

## Decisões importantes

1. **Core sem Minecraft.** Nada em `com.kingdomsai.core` importa `net.minecraft`. `CoreSelfTest` roda o Core com um mundo falso (276 verificações, incluindo `WorkSelfTest`, `AbilitySelfTest`, `SkillSelfTest`, `PersistenceSelfTest`, `MarkerSelfTest`, `ValidationSelfTest`, `MilitarySelfTest` e `ClaudeCodeSelfTest`).
2. **NPC não é LLM.** Rotina, trabalho e reinos de IA rodam com regras/Utility AI. A LLM é chamada só em conversa/ordem.
3. **A LLM não executa nada.** Ela devolve `{"reply", "actions":[{type, params}]}`; cada ação passa pelo pipeline de validação com as permissões de quem ordenou. Ações inventadas viram `unknown_action`.
4. **Texto do mundo é dado, não instrução.** Entrada do jogador vai dentro de `<untrusted>`, com `<`, `>` e `===` neutralizados.
5. **Nunca congelar o jogo.** HTTP em threads próprias; timeout → regras; JSON inválido → retry; modelo fora → circuito aberto por 60 s.
6. **Entidades não são salvas no chunk.** O estado vive no Core; a entidade é recriada (sem duplicatas) quando o jogador chega perto.
7. **Construção não destrói o que o jogador fez.** Só limpa blocos naturais; terrenos com blocos artificiais são recusados pelo `checkSite`.

## Cadeias de trabalho (`core/work`)

```
fala do rei ──► LLM/regras ──► CHAIN{template | steps JSON}
                                  │
            Validators.SCHEMA ────┤ ChainTemplates.spec: modelo/JSON válido?
            Validators.WORLD  ────┤ ChainValidator: pessoas (vivas, capazes, alfabetizadas),
                                  │   lugares (forja/armazém/fazenda/biblioteca prontos),
                                  │   fluxo (simula 1 ciclo: mão de cada papel + baú de cada prédio)
                                  ▼
                           WorkSystem.start ── cada papel com cursor próprio, em paralelo
```

- Papéis se acoplam pelos **baús** dos prédios (`Building.inventory`); o NPC carrega itens em `Npc.carrying` (máx. 48).
- Estados por papel: a caminho → trabalhando → esperando (insumo, trigo amadurecendo, mãos cheias) → descansando (noite/praça).
- **Quebra** (`CHAIN_BROKEN`) = peça faltando (pessoa morta/remanejada/sem a profissão, prédio inexistente). Cursores são mantidos; a cada 15 s `tryRepair` procura substituto da mesma profissão e retoma (`CHAIN_RESUMED`) da mesma etapa. Esperar mais de 2 min gera `CHAIN_BOTTLENECK`.
- `NpcScheduler` pede o destino a `WorkSystem.intentFor` antes da rotina da profissão; `EconomySystem` não conta em dobro quem está em cadeia (`Npc.onDuty`).
- Save v2 (`Migrations`): `chains`, `documents`, `chainCounter`, `Npc.carrying/dutyChainId/literate/heldItem`, `Building.inventory/cropPlantedTick/seeded`.
- Testes: `WorkSelfTest` (32 verificações, chamado pelo `CoreSelfTest`).

## Números de balanceamento (ajustáveis)

- Ciclo econômico 10 s: fazendeiro +3 comida (×1,6 com fazenda, até 3 por fazenda), todos −1 comida, militar −0,5 comida −0,4 ouro, imposto 0,12 ouro × população × nível.
- Construtor: 2 blocos/s; limpeza de terreno não custa tempo na simulação abstrata.
- Moradia: 6 vagas iniciais + casa pequena 3 / média 5 / quartel 4 / salão 2.
- Recrutar: 15 ouro; reivindicar célula: 40 ouro.
- Cadeias: minerar 6 s/unidade, cortar 4 s, fundir 8 s/barra (1 carvão a cada 2), forjar 15 s/espada (2 barras), plantar 20 s, trigo amadurece em 4 min, colheita 12 trigo + 2 sementes, escrever livro 60 s / carta 20 s, ler 45 s. Fora do ofício: 60% da velocidade.
