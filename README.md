# Galactica

A Via Láctea inteira, em escala real, dentro do Minecraft Fabric 26.3: cerca de 1,4 × 10¹¹ estrelas geradas de forma procedural e determinística, um céu fisicamente integrado e compatível com shaderpacks, e um motor de dobra para cruzar a galáxia.

## O que já funciona (M1)

- **Galáxia procedural em escala real.**
  - Disco fino e espesso, barra a 27°, halo estelar e quatro braços log-espirais com pitch de 12°. O Sol fica 0,86 kpc além de Sagitário e 1,9 kpc aquém de Perseu.
  - Estrelas por sector de 10 pc, com a IMF de Kroupa, evolução estelar (gigantes, anãs brancas, estrelas de nêutrons, buracos negros) e gradiente de metalicidade.
  - Nada fica salvo em disco: cada estrela é uma função pura da semente.
- **Céu real perto do Sol.**
  - 9.180 estrelas visíveis a olho nu (o céu real tem ~9.100).
  - Catálogo real: Sol, α Centauri, Próxima, Sirius, Vega, Betelgeuse, Rigel, Deneb, Eta Carinae, TRAPPIST-1, Sagittarius A* e outras.
- **A faixa da Via Láctea é integrada por ray-march** através do modelo de densidade. Inclui:
  - extinção por poeira com a lei de Cardelli (R_V = 3,1), que dá avermelhamento e a Grande Fenda;
  - nuvens estelares e regiões H II.

  O céu é recalculado conforme você viaja. Do bojo, de fora do disco e a 30 kpc acima do plano, você vê a espiral barrada inteira.
- **Sistemas planetários com estatística do Kepler/TESS.** Incluem:
  - vale de raios, Chen–Kipping;
  - jupiteres quentes ~1%;
  - zona habitável de Kopparapu, fotoevaporação e efeito estufa descontrolado;
  - luas.

  O Sistema Solar real usa elementos orbitais J2000.
- **Compatível com shaders.** Todo o céu é desenhado pelo pipeline vanilla `END_SKY`, e o Iris o trata como `gbuffers_skytextured`. Não há shader próprio para quebrar.
- **Precisão em qualquer ponto da galáxia.** As posições são células de 1 UA (`long`) mais metros (`double`), com resolução de ~0,03 mm. A origem flutuante recentraliza o jogador a cada 1.024 blocos.

## Como jogar

| Ação | Como |
|---|---|
| Ir para o espaço | `/galactica launch` (você sai em órbita alta da Terra) |
| Voar / dobrar | voe com pulo duplo; **segure correr** para carregar o motor de dobra (até 10 pc/s); **agachar** corta |
| Onde estou | `/galactica where` |
| Estrelas próximas | `/galactica nearest [n]` |
| Hipersalto | `/galactica jump <n>` (para a n-ésima estrela mais próxima) |
| Ir a uma estrela real | `/galactica goto <nome>`: `Sol`, `Sirius A`, `Betelgeuse`, `Sagittarius A*`… |
| Ir a coordenadas | `/galactica coords <x> <y> <z>` em parsecs galactocêntricos (o Sol está em 8178 0 20.8) |
| Planetas do sistema | `/galactica system` |
| Limite do motor | `/galactica maxwarp <pc/s>` |
| Voltar | `/galactica land` |

O governador do motor reduz a velocidade a metade da distância até a superfície mais próxima por segundo, então a nave sempre desacelera na chegada.

### Mapa 3D e piloto automático

| Ação | Como |
|---|---|
| Abrir o mapa 3D | **M** (a pé, em EVA ou pilotando) |
| Girar a câmera | arrastar com o botão esquerdo |
| Mover o foco | arrastar com o botão direito, ou **WASD** (**Q/E** desce e sobe) |
| Zoom | roda do mouse (escala logarítmica, de 100 km a 80 kpc) |
| Selecionar / voar até | clique / duplo clique num objeto |
| Buscar | caixa de busca: estrelas do catálogo (`Sol`, `Sirius A`), planetas e luas (`Mars`, `Titan`), céu profundo (`M42`), designações (`GX S+0817.-0001.+001-24`) |
| Voltar à minha posição | **Home** |
| Travar o alvo | **Enter** ou o botão *Lock target* |
| Piloto automático | **P**, ou o botão *Engage autopilot* no mapa; o botão ao lado escolhe órbita ou pouso na chegada |
| Por comando | `/galactica target <nome>` (`clear` limpa), `/galactica autopilot orbit`, `land` ou `off` |

O mapa mostra a galáxia inteira (disco, bojo, aglomerados, nebulosas, buracos negros), as estrelas em volta do foco (geradas por setor, numa thread à parte) e, perto de uma estrela, o sistema: órbitas a partir dos elementos reais, planetas e luas onde estão agora, a sua posição e a cônica da sua órbita. O painel à direita mostra tipo, classe espectral, massa e raio; para planetas, gravidade, pressão, temperatura e órbita; e a distância e os tempos de viagem (dobra, hipersalto, motor principal).

O alvo travado fica salvo e aparece no HUD (nome, distância, chegada, marcador na tela ou seta na borda) e no painel do cockpit (linha TGT). Os passageiros veem o alvo e o piloto automático do piloto. O plano é do servidor e quem voa são os controles da própria nave (a mesma predição, a mesma validação de Δv e de combustível): hipersaltos encadeados até outro sistema (se a matéria exótica der; senão, dobra), dobra até o alvo com o governador normal, inserção numa órbita circular acima da camada de entrada (assistência de voo desligada) ou, no modo pouso, descida propulsada, entrada atmosférica e pouso no trem. O pouso automático só vale onde o RCS vence a gravidade com folga (Marte, Lua, Mercúrio); na Terra e em Vênus ele só põe em órbita. Qualquer tecla de movimento, as teclas de rolagem ou um movimento firme do mouse devolvem o controle. Em EVA ele usa só a dobra (modo criativo).
### Naves construídas com blocos

Qualquer construção vira nave. Os blocos de peça (aba Galactica, receitas no livro de receitas):

| Bloco | Papel |
|---|---|
| Leme da Nave | assento do piloto e âncora da montagem; o nariz aponta para onde você olhava ao colocá-lo; acima dele precisa haver um bloco vazio para a cabeça |
| Propulsor da Nave | 0,6 MN, empurra oposto ao bocal (o bocal aponta para você ao colocar). Bocal para trás: motor principal (W); para a frente: freio (S); para baixo: sustentação VTOL (pulo) |
| Quadra de Propulsores RCS | 0,5 MN em cada eixo: sustentação, assistência de voo e giro |
| Tanque de Hidrogênio | 100 células de H₂ |
| Núcleo do Hiperpropulsor | habilita o hipersalto: 10 al com um núcleo, +5 al por núcleo extra (até 30), 16 de matéria exótica cada |
| Trem de Pouso | os pés tocam o chão primeiro |
| Assento de Tripulante | um passageiro por assento |
| Blindagem do Casco | pesada, dá pontos de casco |
| Porta de Acoplamento | a face amarela (aponta para você ao colocar) se acopla à porta de outra nave |
| Piso de Hangar | o espaço vazio acima destes blocos (até o teto, 3 a 16 de altura) é um hangar |
| Gerador de Escudo | 60 pontos de escudo cada; o escudo absorve o dano antes do casco |
| Reator de Fusão | recarrega o escudo (4 pontos/s cada, 1 célula de H₂ a cada 200 pontos) e alimenta os sensores |
| Conjunto de Sensores | lista naves, destroços (naves sem ninguém), minério e corpos próximos; 1 024 m, +512 m por conjunto extra (até 4 096); precisa de reator |
| Gerador de Gravidade | no espaço, 1 g no convés (para baixo da nave) enquanto um reator o alimenta e há H₂ a bordo |

Os demais blocos (concreto, vidro, tábuas, barris…) são o casco: a massa vem da dureza do bloco. Baús e barris guardam o conteúdo.

1. Construa a nave separada do terreno por ar (a montagem para em terra, pedra natural, areia, folhas e troncos). Precisa de leme, pelo menos um propulsor com o bocal para trás e um tanque. Limite: 4 096 blocos, 64 × 48 × 64.
2. Clique com o botão direito no leme: os blocos viram uma entidade (`galactica:built_ship`) com massa, centro de massa, inércia, empuxo por direção, tanques, alcance de salto, caixa e sondas de colisão calculados das peças. Se algo faltar, a mensagem diz o quê e onde.
3. Abasteça com células de hidrogênio e matéria exótica (até 64 por clique), embarque com o botão direito e voe como a Kestrel: W/S, mouse, Q/E, pulo/agachar, C assistência, J hipersalto, M mapa. O mostrador do cockpit aparece à frente do leme.
4. Para desmontar: pouse (ou pare, no espaço), desembarque todos e use a nave agachado com a mão vazia. Os blocos voltam à grade alinhados ao ponto cardeal mais próximo; H₂ e matéria exótica saem como itens.

Sistemas a bordo:
- **Acoplamento (G)**: com as faces das portas de duas naves a até 4 blocos e menos de 4 m/s de diferença, o piloto de qualquer uma aperta G; a nave mais leve é puxada até as portas se encontrarem e passa a ser levada pela mais pesada (recentragens, entrada e saída de planeta, órbita e hipersalto incluídos). G de novo (o piloto da nave acoplada, ou o da nave que carrega) solta: ela sai com a velocidade da outra e um empurrão de 1,5 m/s.
- **Hangar**: uma nave mais leve (a Kestrel também) que entra devagar (menos de 4 m/s em relação) no volume acima do Piso de Hangar é presa no piso, nivelada, e carregada. G solta; por 5 s o hangar não a pega de novo. O casco da nave grande não tem colisão com a outra: o hangar prende quem entra no volume.
- **Escudo e reator**: HUD (terceira pessoa) e o painel do cockpit mostram o escudo; N troca o painel para a página de sistemas (escudo, reator, acoplamento, sensores).
- **Sensores**: a cada 2 s, para quem está a bordo (ou numa nave acoplada nela); página de sistemas do cockpit e painel do mapa (M).
- **Carga em voo (B)**: abre o próximo contêiner da nave (baú, barril, caixa de shulker, dispensador…); clicar num contêiner do casco por fora também abre. O conteúdo fica salvo na nave.
- **Casco a 0**: motores, jatos de sustentação, dobra e hipersalto param. Com `shipDestruction` ligado (padrão, na configuração) a nave se desfaz: a tripulação sai ao lado com queda lenta num planeta, naves acopladas ficam livres, os contêineres se esvaziam e `wreckDropShare` (25 %) dos blocos caem como itens. Desligado, ela fica um destroço para consertar com barras de ferro.
- Os propulsores virados para baixo (sustentação) queimam H₂ como o motor principal: no pulo, ou na parte do pairar que o RCS não segura sozinho. Sem H₂ só o RCS sustenta.
- Naves de 256 blocos ou mais também colidem com o terreno por uma grade de ocupação (todo bloco sólido dentro da caixa do casco é testado contra os blocos da nave), além das sondas.

Andar no convés:
- Os blocos de uma nave construída são sólidos: dá para subir nela, entrar por portas e aberturas e andar por dentro e por cima, com a nave parada, voando, girando, acoplada, atravessando recentragens, saltos e entradas em planetas. Quem pisa na nave passa a andar no referencial dela (é levado com velocidade e rotação, sem escorregar). O cargueiro pronto tem uma porta a bombordo no nível do convés e degraus até o chão.
- Gravidade no convés: num planeta, a do planeta (a nave inclinada até 50° ainda segura você no piso); no espaço, 1 g com um Gerador de Gravidade alimentado; sem ele, flutua (pulo sobe, agachar desce), a menos que esteja com as botas EVA, cujos ímãs prendem ao piso (V liga e desliga).
- Botão direito no leme pilota (se estiver livre), num assento senta, num contêiner abre; numa nave acoplada no hangar, embarca nela. Agachar sentado faz levantar no convés, ao lado do assento (se não houver espaço, sai da nave como antes).
- Sair: ande para fora do casco (ou da borda). No espaço você mantém a velocidade da nave (EVA); agachar freia esse embalo. Itens soltos no convés ficam a bordo.

Colisão entre naves:
- Naves construídas e a Kestrel colidem entre si pelos blocos (a Kestrel pelos blocos do modelo dela): impulso com restituição e atrito pela massa e inércia de cada uma, empurrão para fora da sobreposição, giro num golpe de raspão, e dano ao escudo e ao casco acima de 3 m/s de aproximação, proporcional à energia do impacto. O servidor resolve e avisa o piloto; uma nave rápida não atravessa outra (a varredura é subdividida a cada 0,5 bloco).
- Uma nave que pousa por cima de outra mais pesada fica apoiada nela e, parada por 2 s, estaciona: passa a ser levada por ela até o piloto decolar (pulo ou W/S) ou apertar G. Naves acopladas ou no hangar não colidem entre si.

Naves prontas (as mesmas peças, desmontáveis): `/galactica ship <kestrel|shuttle|freighter|explorer>` implanta, abastece e embarca; os itens "Planta de Nave" implantam onde você olha; `/galactica shipblocks <classe>` coloca a nave como blocos para modificar. `/galactica ship` sem classe continua implantando a Kestrel modelada.

## Instalação

1. Fabric Loader 0.19.5+ e Fabric API para Minecraft 26.3.
2. Coloque `galactica-<versão>.jar` em `mods/`.
3. Opcional: Sodium e Iris 26.3 com qualquer shaderpack.

## Desempenho

Pacote recomendado (todos testados com o Galactica 26.3, autoteste completo PASS, versões do Modrinth):

| Mod | Versão | Para quê |
|---|---|---|
| Sodium | 0.9.2+mc26.3 | renderização de chunks (o maior ganho de FPS) |
| Lithium | 0.26.2+mc26.3 | tick do servidor e física |
| FerriteCore | 9.0.0 | memória (estados de bloco e modelos) |
| ImmediatelyFast | 1.17.1+26.3 | HUD, texto e entidades em lote |
| EntityCulling | 1.11.2 | não desenha entidades escondidas atrás de blocos |
| C2ME | 0.4.2-alpha.0.90+26.3 | geração de chunks em paralelo (alpha; o 0.89 foi o testado no jogo, o 0.90 só compila e passa no `verifyPerfStack`) |
| ScalableLux | 0.3.0-alpha.0.6+26.3 | luz em paralelo para as colunas altas dos planetas (alpha; ainda não rodado no jogo) |
| Krypton | 0.3.2 | rede |
| ModernFix-mVUS | 5.27.20-build.2 | memória e tempo de carregamento (fork do ModernFix para 26.x) |
| Distant Horizons | 3.3.4 | terreno distante (opcional, pesado; integração própria, fora deste teste) |

O ModernFix original não tem versão 26.x; o ModernFix-mVUS (fork com 26.3 no Modrinth) foi testado no lugar dele. Iris funciona junto; shaderpacks custam muito FPS por conta própria. Avisos esperados no log com o pacote: Lithium e ModernFix reescrevem o mesmo cache de temperatura de bioma (inofensivo) e o Sodium avisa que o consumidor de vértices do casco não usa o caminho rápido dele.

Compatibilidade já feita no mod:

- **EntityCulling**: naves e quem está dentro delas nunca são escondidas nem têm o tick pulado. A asa de uma nave pousada atrás de um morro aparecia, mas o casco sumia.
- **Espaço**: a dimensão `galactica:space` agora tem 1 536 blocos de altura (era 4 064) e distância de visão de 10 chunks, que é o alcance em que uma nave já é rastreada. Antes, a cada recentragem da origem flutuante (a cada poucos segundos em velocidade de cruzeiro) o jogo gerava, enviava, salvava e montava cerca de 1 100 colunas de 254 seções. Isso travava o jogo e enchia a memória até dar `OutOfMemoryError` com `-Xmx4G`. Para mudar a distância: `-Dgalactica.spaceViewDistance=N` (2–32). O limite vale também em servidor dedicado (aplicado em `ChunkMap.setServerViewDistance`, inclusive na criação do nível com o `view-distance` do `server.properties`).
- **Origem flutuante vertical**: com a dimensão mais baixa, uma nave (ou um jogador em EVA) é recentrada na vertical ao passar de y ±192. Essa recentragem só move a própria nave, então ela espera enquanto houver outro jogador, outra nave ou qualquer bloco num raio de 176 blocos na horizontal (em qualquer altura); nesse caso só acontece perto do limite do mundo (y ±704). Uma nave sem jogador a bordo fica sempre entre y −640 e 640, então dá para alcançá-la subindo ou descendo.
- **Espaço vazio não vai para o disco**: um chunk de `galactica:space` só com ar, sem block entity e que nunca foi salvo cheio é regenerado em vez de salvo. Em cruzeiro a nave cruza ~400 colunas por segundo, e cada uma era serializada, comprimida e gravada à toa (o save também crescia a cada viagem). Chunks com qualquer bloco, ou que já foram salvos com blocos, são salvos normalmente. Com o C2ME, que reescreve o sistema de chunks, quem salva é ele e essa economia não se aplica.
- **Casco da nave**: os ossos fixos do modelo são montados uma vez numa lista de vértices; só trem de pouso, canopy e bocais são percorridos a cada quadro (mesmos vértices, teste `BakedHullTest`). Num microbenchmark fora do jogo o desenho do casco caiu de 37,2 para 24,8 µs; dentro do jogo o ganho não foi isolado, e `BakedBones.render` continua sendo o maior custo próprio nas cenas da nave.
- Mundos antigos: blocos colocados no espaço abaixo de y −768 ou acima de y 767 deixam de existir, sem migração; faça backup do mundo antes de atualizar se houver construções no espaço fora dessa faixa. Naves sem jogador salvas fora dela são trazidas para y ±640 na primeira vez que fazem tick; um jogador (ou a nave que ele pilota) volta na primeira recentragem. Jogadores, naves e quem está nelas não morrem nem somem abaixo do mundo no espaço; os demais objetos (flechas, itens, mobs) somem no void como no vanilla.

Desenvolvimento:

- `./gradlew runAutotestPerf`: autoteste completo e depois o benchmark (linhas `[perf]` no log: FPS médio, 1% low, percentis, alocação, draw calls, uploads, MSPT, tempo por seção do mod, amostragem de pilha com os caminhos de chamada mais quentes, e uma cena `planet_chunkgen` que gera terreno novo em Marte). `runAutotestPerfOnly` roda só o benchmark.
- `./gradlew runAutotestPerfStack`: o mesmo com o pacote acima, em `runs/client-perf` (copie o mundo de `runs/client` e deixe o Sodium fora de `mods/`, porque ele já vem do pacote).
- `./gradlew runPlayPerf`: jogo manual em `runs/play` com o pacote. A tarefa `verifyPerfStack` confere o SHA-512 de cada jar contra o Modrinth antes de abrir.

## Terreno

### Planetas mais altos

As dimensões de pouso (`galactica:surface_N`) agora vão de y −512 a 1279 (1 792 blocos, 112 seções; antes −256 a 511). O nível do mar continua em 96, e as linhas de entrada e decolagem continuam em y 1500 e 1700, acima de todo terreno possível.

O relevo segue uma lei física, com compressão vertical declarada:

- **Altura máxima** que uma crosta sustenta antes de escoar: σ/(ρg) (Melosh 2011), ou seja, o Everest (8 848 m) dividido pela gravidade em g. Limitada ao Olympus Mons (21,9 km, o maior edifício conhecido) e a 8,5 % do raio do corpo (o pico de Rheasilvia em Vesta).
- **Escala verdadeira até 900 blocos.** O mapa usa 1 bloco = 10 m num planeta do tamanho da Terra, 5,3 m em Marte, 2,7 m na Lua, 1 m em corpos pequenos, e o céu usa a mesma escala para a altitude. Até 900 blocos de pico o terreno é desenhado nessa escala (vertical = horizontal): um mundo terrestre de 1 g tem picos de ~885 blocos, como o Everest a 10 m/bloco.
- **Acima disso, um fator único κ** comprime todo o relevo do corpo, preservando as formas: Marte κ ≈ 0,22 (um bloco vertical vale ~24 m), a Lua κ ≈ 0,11, Mercúrio 0,16. Um joelho suave de 760 a 1 120 blocos acima do mar só pega o empilhamento raro de montanha, vulcão e detalhe.
- **Profundidade**: fundos de mar até 1,25× o pico (a Fossa das Marianas tem 1,25 Everest) e bacias secas até 0,6× (Hellas, a bacia SPA), limitados a 480 blocos com joelho até 560.

Técnicas trazidas do Tectonic (sem dependência: o gerador é nosso):

- escalas separadas para continentes (o campo macro, o mesmo que se vê da órbita), erosão (liga e desliga cordilheiras por região, deixando planícies entre elas) e cristas (ruído "ridged" ao quadrado);
- escala vertical separada acima e abaixo do mar (montanhas mais altas não aprofundam os mares);
- a largura das cordilheiras cresce com a altura delas (√(A/40)), então as encostas continuam escaláveis em qualquer corpo;
- uma faixa 3D de ±24 blocos em volta da superfície, só em encostas de montanha e paredes de cânion: gradiente vertical mais ruído 3D, como o `final_density` do vanilla/Tectonic, para penhascos, saliências e arcos. O resto da coluna continua 2,5D;
- cânions de fundo chato em mundos desérticos (tipo Valles Marineris, até 1/3 do pico de profundidade) e vulcões-escudo com caldeira em mundos desérticos, de lava e estufa (até 0,9 do pico).

Distant Horizons e o terreno visto de longe (`SurfaceClipmap`, `FarTerrainRenderer`) leem as mesmas funções; o `LodColumnPlan` inclui a faixa 3D, coluna a coluna igual ao gerador de chunks (teste `LodColumnPlanTest`).

Mundos antigos: na primeira inicialização com esta versão, os chunks já gerados das dimensões de pouso são apagados e regenerados (o planeta de cada slot é mantido). Construções em planetas se perdem; faça backup antes.

Custo medido (PerfBench `planet_chunkgen`: espectador sobre terreno de Marte nunca gerado, 1 681 chunks em 10 s; uma rodada de cada, mesma máquina, outro agente podia estar usando a GPU):

| | antes (768 blocos) | depois (1 792 blocos) |
|---|---|---|
| geração por chunk (`PLANET_CHUNK`) | 7,85 ms | 3,67 ms |
| MSPT médio / máximo | 1,93 / 16,2 ms | 4,28 / 71,9 ms |
| FPS médio / 1% low | 2 420 / 318 | 1 636 / 191 |
| heap na cena | 1,4 GB | 2,6 GB |

A geração ficou mais barata apesar da coluna 2,3× mais alta: a crosta funda que todas as colunas do chunk compartilham é escrita como seções inteiras de um só bloco (paleta de valor único: nenhuma escrita por bloco, quase nada no disco e na rede), e só a casca perto da superfície é escrita bloco a bloco. O que pesa é o resto do caminho de um chunk alto: luz, envio e memória (o MSPT dobrou e a heap quase dobrou). Por isso a altura ficou em 1 792 e não em 2 048 (que ainda empurraria as linhas de entrada para cima). O ScalableLux entrou no pacote de desempenho para a luz das colunas altas, mas não foi medido nesta rodada.

### Terra com Tectonic

A Terra é o Overworld, então mods de terreno do Overworld funcionam nela. Testado: **Tectonic 3.0.31-fabric-26.3** com **Lithostitched 2.0.4-fabric-26.3** (MIT, no Modrinth). **Terralith 2.6.5+26.3** é sugerido junto (licença Stardust Labs, nunca empacotado). Nada disso vem dentro do Galactica (`suggests` no `fabric.mod.json`).

- As linhas de entrada e decolagem da Terra seguem a altura real do Overworld (`getMaxY()` lido ao vivo): 1500/1700 enquanto o topo do mundo estiver abaixo de y 1372, e acima disso sobem junto (topo + 128 e mais 200). Com o Tectonic configurado até y 2031 a chegada fica sempre acima do limite de construção, sem tela de carregamento.
- O nível do mar da Terra também é lido do servidor (63 no vanilla e no Tectonic).
- Distant Horizons: o Galactica só registra gerador próprio para `galactica:surface_*`. O Overworld fica sempre com o gerador do DH, que roda o gerador real, então o terreno do Tectonic aparece nos LODs.
- O céu da Terra não depende da altura do terreno.

Desenvolvimento: `./gradlew runAutotestTectonic` (mundo em `runs/client-tectonic`, Overworld a regenerar; `config/tectonic.json` de teste com `max_y` 1536 e `vertical_scale` 2,5) e `./gradlew runPlayTectonic`. `-Pgalactica.terralith=true` acrescenta o Terralith. A tarefa `verifyTectonicStack` confere o SHA-512 de cada jar contra o Modrinth.

### Mods de renderização

| Mod | Versão | Situação |
|---|---|---|
| Distant Horizons | 3.3.4-26.3 (API 7.2.0) | gerador de LOD próprio para os planetas (agora 1 792 blocos); Overworld com o gerador do DH |
| Sodium Extra | mc26.3-0.9.4+fabric | funciona; deixe os botões Sky e Stars ligados, senão o céu real some |
| Bobby | 5.2.16+mc26.3 | funciona; limpe o cache dele para `galactica:surface_*` se um slot mudar de planeta |
| Nvidium | 0.4.4-beta7-26.3 | opcional (`-Pgalactica.nvidium=true`); só age em NVIDIA Turing+ e sem shaderpack, desliga-se sozinho no resto |
| ScalableLux | 0.3.0-alpha.0.6+26.3 | no pacote de desempenho: luz em paralelo para as colunas altas |
| Cosmos | 1.0.3-26.3 | conflito esperado (desenha suas próprias estrelas no Overworld); o Galactica avisa no log. Não testado em jogo |

`./gradlew runAutotestDh` roda o passeio de terreno (Marte, um mundo terrestre de outro sistema, a Lua, a Terra) com DH, Sodium Extra e Bobby, em `runs/client-dh`; `runAutotestTerrain` faz o mesmo sem DH. `verifyDhStack` confere os SHA-512.

## Desenvolvimento

- Requisitos: JDK 25 (configurado em `gradle.properties`) e Gradle 9.7.1 via wrapper.
- `./gradlew build`: compila e roda os testes científicos.
- `./gradlew runClient`: abre o jogo com o mod.
- `./gradlew runAutotest`: teste visual automático, sem entrada de mouse ou teclado. Os screenshots vão para `runs/client/screenshots/`.
- `./gradlew runAutotestDeck`: só andar no convés e colisão entre naves (o `runAutotestShips` termina com eles). `./gradlew runGameTest`: testes de servidor (naves, convés, colisões) com jogadores falsos.
- Diário técnico e armadilhas do 26.3: `MODLOG.md`. Recon das referências e da API: `docs/recon/`.

## Roteiro

- **M2:** planetas renderizados por ray-march (fases, atmosferas com espalhamento Rayleigh e Mie, anéis) e planos orbitais inclinados reais.
- **M3:** naves (física newtoniana 6DOF, motor quântico intrassistema, combustível, suporte de vida) e mapa galáctico.
- **M4:** pouso em planetas (dimensões de superfície procedurais, gravidade real, entrada atmosférica).
- **M5:** ciência e economia: espectrógrafo, descoberta por trânsito, códex.
- **M6:** buracos negros com lente gravitacional, nebulosas, aglomerados e passada de desempenho.

## Créditos

Feito com IA (Claude) a partir de um design próprio. TizioSpaceProject, Cosmic Horizons e CH-Spheromatic foram estudados só como referência de design; nenhum código ou asset deles foi usado. Todas as texturas são geradas por código. Licença MIT.
