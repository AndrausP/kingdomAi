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

1. **Core sem Minecraft.** Nada em `com.kingdomsai.core` importa `net.minecraft`. `CoreSelfTest` roda o Core com um mundo falso (430 verificações, incluindo `WorkSelfTest`, `AbilitySelfTest`, `SkillSelfTest`, `PersistenceSelfTest`, `MarkerSelfTest`, `ValidationSelfTest`, `MilitarySelfTest`, `OrdersSelfTest`, `ClaudeCodeSelfTest`, `LaborSelfTest`, `LifeSelfTest` e `OpeningSelfTest`).
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

## Mochila e trabalho contínuo (`core/skill`)

```
fala do rei ──► regras/LLM ──► JOB{kind=labor, labor=wood|stone|ore|farm, quota}
                                  │
       JobPlanner.laborPlan ──────┤ local (marco Bosque/Mina, fazenda pronta) em terra do reino;
                                  │ 1º lote (árvore | minério/galeria | canteiros); prepareKit:
                                  │ ferramenta real? ração? espaço? → [STORE?, RESUPPLY] antes
                                  ▼
       SkillSystem.tickSecond ── continuousStep: meta → noite (STORE, PAUSED) → LOD (abstractWork)
                                  │               → próximo lote (nextBatch) ou espera 30 s
                                  └─ breaking/farm: revalida território, proteção, alcance, ferramenta,
                                     limite global de quebras/s; collect → mochila; wear → toolBroke;
                                     cheia/fome → tripToStorage (STORE + RESUPPLY inseridos na etapa atual)
```

- `Inventory`: regras da mochila (27 espaços, pilhas, durabilidade/tier/velocidade por material, nutrição). `Kit`: tabela por ofício, `resupply`/`deposit`/`surplus` contra o estoque do reino (`Kingdom.stock` + `Kingdom.goods`), espelhado nos baús pelo `TreasurySystem`.
- Estado no Core: `Npc.bag/wear/gear/skillXp`; `PhysicalJob.continuous/labor/site/radius/quota/produced/trips/oreId/tunnelDir/tunnelLength`. Save v5 (`Migrations`): kits e bens iniciais para saves antigos (`kitsGranted`).
- O adaptador só traduz: `McPhysicalPort.breakBlock` usa a ferramenta real da mochila para os drops; `growth`/`till` (CropBlock, enxada); `KingdomNpcEntity` veste `gear` (cabeça/peito/mão secundária) e segura a ferramenta real.
- `EconomySystem.smithy`: funde `raw_iron` dos bens com carvão e forja a reserva de ferramentas (`TOOL_RESERVE`). `ChunkKeeper` não força chunks de trabalho contínuo (longe da vista ele é simulado).

## Vida dos súditos (`core/life`) — um "PIANO leve"

```
LifeSystem.tickSecond (antes do NpcScheduler)
  ├── necessidades de todos (fome, energia, companhia, medo, saúde) — barato, a cada segundo
  ├── conversas em andamento (uma fala por vez; efeitos no fim: afeto, boato, briga, casal)
  ├── a cada 5 s por súdito (escalonado): humor → objetivo pessoal → ir embora? → intenção (Intention)
  │       corpo primeiro (ferido/exausto/faminto) → refeição → lazer do fim de tarde (personalidade + objetivo + sorteio do dia)
  │       → IA para os importantes (assíncrona, orçamento próprio; validada em applyAgenda) → tenta conversar
  └── a cada 2 s: percepção (PhysicalPort.threatsNear) → fuga, grito, alerta aos guardas
NpcScheduler.decide: chamado do rei > guerra/prisão > FUGA > ordem com as mãos > sono (horário pessoal) > vida (conversa/intenção)
                     > fim de tarde (praça) > rotina/cadeia > ofício (guarda/soldado atendem alerta)
```

- Uma intenção por vez (`Npc.intention`: tipo, alvo, motivo, até quando, fonte rotina/necessidade/personalidade/IA): o que ele fala e faz saem da mesma decisão — o papel do *Cognitive Controller* do PIANO.
- Saída para o corpo: `ChatLine` (adaptador mostra a quem está perto), `NPC_ARGUMENT`, `NPC_COUPLE`, `NPC_HURT`, `THREAT_SPOTTED`; entrada do corpo: `onHurt`, `pickup`, `threatsNear`.
- Save v6: `Npc.social/health/mood/fear/partnerId/intention/goal/recentTalk/lastMealTick` (+ `spilled`, `talkingWith`, `fleeUntil` transitórios); `Kingdom.openingAuto/openingDone/openingNudge/openingFinished`.
- `KingdomsCore.perf()` mede o tick do Core (média e pior do último minuto; `/k perf`).

## Roteiro de início (`core/ai/Opening`)

Seis etapas com condição viva (fazenda + comida ≥ 0; armazém; casas ≥ população; lenhador e minerador em trabalho contínuo/rotina; militares ≥ pop/8; praça + capela/taverna). A cada tick estratégico: marca/anuncia etapas, lembra a da vez (WARN para comida), e — nos 3 primeiros dias, com `openingAuto` — age pela etapa via `ActionSystem` (em nome do rei) ou `KingdomDirector.pursue`. Carência de fome (`grace`) usada pelo `LifeSystem`.

## Números de balanceamento (ajustáveis)

- Ciclo econômico 10 s: fazendeiro +3 comida (×1,6 com fazenda, até 3 por fazenda), todos −1 comida, militar −0,5 comida −0,4 ouro, imposto 0,12 ouro × população × nível.
- Construtor: 2 blocos/s; limpeza de terreno não custa tempo na simulação abstrata.
- Moradia: 6 vagas iniciais + casa pequena 3 / média 5 / quartel 4 / salão 2.
- Recrutar: 15 ouro; reivindicar célula: 40 ouro.
- Vida: fome −0,07/s (×1,3 trabalhando, ×0,5 dormindo); refeição +35 (celeiro vazio +6); energia −0,035/s (−0,075 trabalhando), +0,25 dormindo; companhia −0,04/s (×sociabilidade), +5 por fala; saúde −0,08/s com fome < 5, +0,02/s (+0,08 descansando); conversa a cada ≥ 60 s por pessoa; afeto +1 por conversa (+1 se os dois são muito sociáveis); casal com afeto ≥ 85 e confiança ≥ 60 (40% por conversa romântica); IA da vida 3 chamadas/min (config).
- Trabalho contínuo longe da vista (ferro = 1×; pedra 0,67×; madeira 0,33×; +5%/nível de prática): tora 8 s, pedregulho 3 s, minério 12 s (+2 pedregulho), trigo 6 s. Ração: fome −1,5 e energia −0,5 a cada 30 s; come abaixo de 60. Quebras: 40/s no total.
- Cadeias: minerar 6 s/unidade, cortar 4 s, fundir 8 s/barra (1 carvão a cada 2), forjar 15 s/espada (2 barras), plantar 20 s, trigo amadurece em 4 min, colheita 12 trigo + 2 sementes, escrever livro 60 s / carta 20 s, ler 45 s. Fora do ofício: 60% da velocidade.
