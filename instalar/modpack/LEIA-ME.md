# Kingdoms AI — Modpack

Versão **separada** do TLauncher chamada **`kingdoms-modpack`** (NeoForge 1.21.1): Kingdoms AI + os mods abaixo. Os perfis `kingdoms` e `mine` não são alterados.

## Instalar

1. Copie a pasta `instalar/` inteira (com esta subpasta `modpack/`) para o PC do TLauncher.
2. Dê dois cliques em **`modpack/INSTALAR-MODPACK.bat`**. Ele cria a versão, coloca o Kingdoms AI e baixa os mods do Modrinth (API pública) com as dependências obrigatórias (ex.: Sophisticated Core).
3. Se algo aparecer em "Download manual necessário", baixe a versão **NeoForge 1.21.1** pelo atalho em `links/`, coloque em `extras/mods` (ou `extras/resourcepacks` / `extras/shaderpacks`) e rode o `.bat` de novo.
4. No TLauncher escolha **kingdoms-modpack** e clique em Entrar.

## O que vem

| Pedido | O que entra | Link |
|---|---|---|
| Xaero's Minimap | mod | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap) |
| AppleSkin | mod | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/appleskin) |
| Sophisticated Backpacks | mod + Sophisticated Core | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/sophisticated-backpacks) |
| Motschen's Better Leaves | resource pack (já ativado) | [CurseForge](https://www.curseforge.com/minecraft/texture-packs/motschens-better-leaves) |
| Epic Shaders | **Sodium + Iris** + shaders Complementary Reimagined (ativado) e BSL | [CurseForge](https://www.curseforge.com/minecraft/modpacks/epic-shaders) |
| Epic Hunt | mod — **ver aviso** | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/epic-hunt) |

**Epic Shaders** é um *modpack*, não um mod, e só sai para 1.20.6 / 1.21.8 / 26.1 — não dá para colocar um modpack dentro de outro nem misturar versões. Por isso entra o mesmo "motor" dele (Sodium + Iris) para 1.21.1 com shaders bons. Troque de shader em Opções → Vídeo → Shader Packs (tecla **O** com o Iris).

**Epic Hunt** até a última verificação só tinha arquivos para Forge/Fabric 1.20.1. O NeoForge 1.21.1 não carrega mods de Forge 1.20.1. Se o autor lançar a versão NeoForge 1.21.1, coloque em `extras/mods` (ela precisa do Architectury API — baixe também).

## Compatibilidade com o Kingdoms AI

- **Xaero's Minimap**: o minimapa fica no canto superior esquerdo, onde o HUD do Manager desenhava a barra e as obras. Agora o HUD detecta o Xaero e deixa esse canto livre. Ajuste com `/k config set compat.minimap_reserve <px>` (`-1` automático, `0` desliga). *Precisa do jar do mod recompilado (`./gradlew build` ou `build_offline.sh`) — o jar 0.2.0 desta pasta ainda é o antigo.*
- **Sophisticated Backpacks**: mochilas colocadas no chão são block entities — os construtores do reino nunca destroem esses blocos.
- **Sodium/Iris**: os NPCs usam o renderizador humanoide padrão; funcionam com shaders.
- **AppleSkin / Better Leaves**: só visuais, sem conflito.
- **Teclas**: Kingdoms usa **M** (Manager) e **Alt esquerdo** (interface) — nenhum destes mods usa. Atenção: o Xaero (**B** = novo waypoint) e o Sophisticated Backpacks (**B** = abrir mochila) disputam o **B**. Troque um deles em Opções → Controles.
