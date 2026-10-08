# Kingdoms AI — instruções para agentes

- Alvo: Minecraft 1.21.1 + NeoForge 21.1.256, Java 21. Testado contra o perfil TLauncher `mine` do Andraus.
- `com.kingdomsai.core` NUNCA importa `net.minecraft`/`net.neoforged`. Lógica nova vai no Core; o adaptador só traduz.
- Toda mudança de estado do reino passa por `ActionSystem.execute` (validadores). Não altere recursos direto a partir da LLM/UI.
- Sistemas não se chamam em cadeia: publiquem `GameEvent` no `EventBus` e reajam por `subscribe`.
- Obras cobram material real: `construction/Palette` (bloco), `BillOfMaterials` (lista por planta), `Materials` (receitas/abastecimento). Nada de bloco do nada.
- Novo campo salvo → valor padrão no campo + ajuste em `persistence/Migrations` (incremente `WorldState.SCHEMA_VERSION` se mudar formato).
- Textos para o jogador em português. Linhas da CLI: `✓` sucesso, `✗` erro, `⚠` aviso, `# ` título, `«Nome»` fala.
- Testes do Core: `./gradlew coreTest` (ou `javac` + `java com.kingdomsai.core.CoreSelfTest` com gson no classpath).
- Build offline: `build_offline.sh` (precisa das libs do `.minecraft` em `LIBS`).
