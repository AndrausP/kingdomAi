# Ordens com as mãos — regras de jogo e validação

Os súditos sabem **quebrar blocos, cortar árvores, abrir baús, fabricar itens com as receitas do Minecraft e entregar ao rei**.
O Core (`core/skill`) decide e valida; o adaptador (`McPhysicalPort`) só executa no mundo.
Todos os itens são **reais**: saem de baús, caem dos blocos, gastam ingredientes.

## Como mandar

Mire no lugar (o jogo acompanha a sua mira, inclusive no Manager) e fale com o súdito, ou use `/k job`:

| Fala | O que acontece |
|---|---|
| "quebre esse bloco" | quebra o bloco da mira |
| "cave um buraco 3x3x3 aqui" | escava de cima para baixo e **deixa uma escada** num canto para sair |
| "abra um túnel de 10 blocos" | túnel 1×2 na direção para onde o rei olha |
| "limpe essa área 5x5" | tira o que estiver acima do chão (até 4 de altura) |
| "corte essa árvore" | tronco + folhas; **replanta** a muda se alguma cair das folhas |
| "pegue 5 barras de ferro desse baú" | abre o baú (tampa anima), tira os itens reais |
| "guarde tudo no armazém" | leva ao baú do armazém |
| "faça uma picareta de ferro e me entregue" | planeja: pega ferro no baú, faz tábuas → gravetos, fabrica na bancada e entrega na sua mão |
| "pare com isso" | cancela; o que já juntou fica na mochila dele (`/k bag <nome>`) |

`/k jobs` lista as ordens · `/k job <nº>` mostra etapa por etapa · painel ROTINAS do Manager mostra ⚒ em azul.

## Regras (validadas antes de começar e de novo a cada bloco)

| Regra | Por quê (design) | Teste |
|---|---|---|
| Nunca quebra blocos de construções (de qualquer reino); muralha protege só o anel | anti-grief e anti-acidente | `não quebra construção do reino` |
| Nunca quebra baús, fornalhas e outros block entities | itens dentro se perderiam | `baú no meio da área fica de pé` |
| Pula blocos colados em água/lava | evita inundar a vila/mina | `bloco colado na água fica` |
| Terra de outro reino: recusa ("seria invasão"); baú de outro reino: recusa ("seria roubo") | diplomacia — isso será ação de guerra no futuro | `seria invasão`, `seria roubo` |
| Só trabalha a até 64 blocos do rei, em área carregada; máx. 343 blocos e 32 de lado por ordem | o rei supervisiona; sem grief remoto em servidor; custo de CPU limitado | `longe do rei`, `área grande demais` |
| Tronco sem folhas não é árvore | vigas de casas de jogadores não viram lenha | `tronco sem folhas não é árvore` |
| Ferramenta pelo ofício (minerador: picareta/pá; lenhador: machado; construtor: os três; ou a que estiver na mochila). Sem a ferramenta certa: 5× mais lento e **não rende** (regra do Minecraft) | progressão justa, avisada no plano | `sem picareta: avisa` |
| Ferramentas, armas e armaduras de metal só o ferreiro faz | especialização dos súditos | `fazendeiro não forja espada` |
| Receitas do próprio jogo (inclusive de mods); bancada para receitas 3×3, fornalha + carvão para fundir | sem receitas inventadas | `sem bancada por perto` |
| Ingrediente que falta vira tarefa: buscar no baú da mira/armazém ou fabricar antes (3 níveis) | o jogador pede o objetivo, não a lista de passos | `planejou buscar e fabricar` |
| Faltando algo de verdade: diz exatamente o quê ("Faltam 3 barra de ferro") | erro útil | `sem ferro em lugar nenhum` |
| Nada é criado do nada: ingredientes saem do baú, sobras ficam na mochila | sem duplicação | `ingredientes saíram do baú`, `sobras ficam com o ferreiro` |
| Mochila de 320 itens; cheia → esvazia no baú do armazém e volta. Sem armazém e muito volume → recusa antes de começar | sem perda de itens, sem travar | `esvaziou a mochila no armazém` |
| Bloco que mudou no meio do trabalho (alguém pôs um baú) é poupado | o mundo é dinâmico | `baú colocado no meio do trabalho é poupado` |
| Bloco inalcançável por 20 s é pulado e anotado | nunca trava | `bloco inalcançável é pulado` |
| Chamado (tecla G) **pausa** a ordem; dispensado, ela continua | o rei manda mais que a tarefa | `chamado do rei PAUSA a ordem` |
| Ordem direta continua à noite (a rotina não) | ordem do rei é prioridade | `ordem direta segue de noite` |
| Longe do rei a área descarrega: a ordem espera (não falha) | LOD — nada acontece sem o mundo carregado | (SkillSystem `waitFor(-1)`) |
| Prioridade: chamado > ordem com as mãos > cadeia/rotina > profissão | previsível para o jogador | `cumprindo uma ordem do rei` na cadeia |

## Limites conhecidos

- O NPC usa ferramentas "imaginárias" do ofício (não gastam durabilidade); ferramentas na mochila contam como se ele as tivesse.
- Itens com encantamento/nome perdem esses dados ao passar pela mochila (ela guarda só id + quantidade).
- Buracos muito largos e fundos (> largura) ficam com a escada incompleta.
- A animação de rachadura usa o id da entidade: se o NPC não estiver materializado (longe), o bloco quebra sem a animação.
