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
| C2ME | 0.4.2-alpha.0.89+26.3 | geração de chunks em paralelo (alpha) |
| Krypton | 0.3.2 | rede |
| ModernFix-mVUS | 5.27.20-build.2 | memória e tempo de carregamento (fork do ModernFix para 26.x) |
| Distant Horizons | 3.3.4 | terreno distante (opcional, pesado; integração própria, fora deste teste) |

O ModernFix original não tem versão 26.x; o ModernFix-mVUS (fork com 26.3 no Modrinth) foi testado no lugar dele. Iris funciona junto; shaderpacks custam muito FPS por conta própria. Avisos esperados no log com o pacote: Lithium e ModernFix reescrevem o mesmo cache de temperatura de bioma (inofensivo) e o Sodium avisa que o consumidor de vértices do casco não usa o caminho rápido dele.

Compatibilidade já feita no mod:

- **EntityCulling**: naves e quem está dentro delas nunca são escondidas nem têm o tick pulado. A asa de uma nave pousada atrás de um morro aparecia, mas o casco sumia.
- **Espaço**: a dimensão `galactica:space` agora tem 1 536 blocos de altura (era 4 064) e distância de visão de 10 chunks, que é o alcance em que uma nave já é rastreada. Antes, a cada recentragem da origem flutuante (a cada poucos segundos em velocidade de cruzeiro) o jogo gerava, enviava, salvava e montava cerca de 1 100 colunas de 254 seções. Isso travava o jogo e enchia a memória até dar `OutOfMemoryError` com `-Xmx4G`. Para mudar a distância: `-Dgalactica.spaceViewDistance=N` (2–32).
- **Espaço vazio não vai para o disco**: um chunk de `galactica:space` só com ar, sem block entity e que nunca foi salvo cheio é regenerado em vez de salvo. Em cruzeiro a nave cruza ~400 colunas por segundo, e cada uma era serializada, comprimida e gravada à toa (o save também crescia a cada viagem). Chunks com qualquer bloco, ou que já foram salvos com blocos, são salvos normalmente. Com o C2ME, que reescreve o sistema de chunks, quem salva é ele e essa economia não se aplica.
- **Casco da nave**: os ossos fixos do modelo são montados uma vez numa lista de vértices; só trem de pouso, canopy e bocais são percorridos a cada quadro (~33% menos CPU no casco, mesmos vértices, teste `BakedHullTest`).
- Mundos antigos: blocos colocados no espaço abaixo de y −768 ou acima de y 767 deixam de existir. Naves e jogadores salvos fora dessa faixa voltam sozinhos na primeira recentragem, e no espaço ninguém morre no void.

Desenvolvimento:

- `./gradlew runAutotestPerf`: autoteste completo e depois o benchmark (linhas `[perf]` no log: FPS médio, 1% low, percentis, alocação, draw calls, uploads, MSPT, tempo por seção do mod, amostragem de pilha com os caminhos de chamada mais quentes, e uma cena `planet_chunkgen` que gera terreno novo em Marte). `runAutotestPerfOnly` roda só o benchmark.
- `./gradlew runAutotestPerfStack`: o mesmo com o pacote acima, em `runs/client-perf` (copie o mundo de `runs/client` e deixe o Sodium fora de `mods/`, porque ele já vem do pacote).
- `./gradlew runPlayPerf`: jogo manual em `runs/play` com o pacote. A tarefa `verifyPerfStack` confere o SHA-512 de cada jar contra o Modrinth antes de abrir.

## Desenvolvimento

- Requisitos: JDK 25 (configurado em `gradle.properties`) e Gradle 9.7.1 via wrapper.
- `./gradlew build`: compila e roda os testes científicos.
- `./gradlew runClient`: abre o jogo com o mod.
- `./gradlew runAutotest`: teste visual automático, sem entrada de mouse ou teclado. Os screenshots vão para `runs/client/screenshots/`.
- Diário técnico e armadilhas do 26.3: `MODLOG.md`. Recon das referências e da API: `docs/recon/`.

## Roteiro

- **M2:** planetas renderizados por ray-march (fases, atmosferas com espalhamento Rayleigh e Mie, anéis) e planos orbitais inclinados reais.
- **M3:** naves (física newtoniana 6DOF, motor quântico intrassistema, combustível, suporte de vida) e mapa galáctico.
- **M4:** pouso em planetas (dimensões de superfície procedurais, gravidade real, entrada atmosférica).
- **M5:** ciência e economia: espectrógrafo, descoberta por trânsito, códex.
- **M6:** buracos negros com lente gravitacional, nebulosas, aglomerados e passada de desempenho.

## Créditos

Feito com IA (Claude) a partir de um design próprio. TizioSpaceProject, Cosmic Horizons e CH-Spheromatic foram estudados só como referência de design; nenhum código ou asset deles foi usado. Todas as texturas são geradas por código. Licença MIT.
