# Galactica — MODLOG

Mod de exploração espacial em escala real da Via Láctea para Minecraft Fabric 26.3.

## Intake (2026-10-06)
- Jogo: Minecraft Java, Fabric, versão-alvo 26.3 (sem ofuscação, nomes Mojang, Java 25, Gradle 9.1+).
- Ideia: a Via Láctea inteira gerada proceduralmente em escala real, renderização fotorrealista compatível com shaders (Iris), naves e viagem no estilo Star Citizen / No Man's Sky.
- Single-player/offline e servidores próprios. Sem anti-cheat envolvido.
- Referências (só design, nenhum código/asset copiado): TizioSpaceProject (Forge 1.20.1), CosmicHorizons 0.1.0.0, CH-Spheromatic 0.0.1.

## Stack local encontrada
- Prism: instância `Voxyris` = MC 26.2 + Fabric Loader 0.19.5. Nenhuma instância 26.3 ainda.
- JDKs: Microsoft 21 (PATH), Temurin 17/25 em `C:\Program Files\Eclipse Adoptium\`, JBR 21/25 via scoop.
- `C:\Users\Manec\minecraft-claude\repos\MultiLoader-Template` tem branch 26.3.
- MCPs: modlens, mcdev, minecraft-modding, mcpfabric (teste in-game 1.21.1–26.3), blockbench.
- Docs da stack: `C:\Users\Manec\minecraft-claude\STACK-MODDING-2026.md`, `LEIAME.md`.

## Rota
Loader API (Fabric) + Mixins pontuais no render do céu. O núcleo científico (galáxia, estrelas, planetas, órbitas) é Java puro, sem dependência do Minecraft, testado com JUnit.

## Log
- 2026-10-06: recon dos três jars de referência e do toolchain 26.3 disparado (workflow `space-mod-recon`).
- 2026-10-06: build Fabric-only a partir do MultiLoader-Template 26.3 (Loom `net.fabricmc.fabric-loom` 1.18.1, Gradle 9.7.1, fabric-api 0.161.0+26.3 do cache, loader 0.19.5). O Gradle precisa rodar no JDK 25 (`org.gradle.java.home` em gradle.properties), senão o Loom não resolve.
- Núcleo científico em Java puro, com 12 testes JUnit:
  - modelo galáctico (disco fino e espesso, barra a 27°, halo, 4 braços log-espirais com pitch de 12°, poeira);
  - Kroupa IMF e evolução estelar com remanescentes;
  - catálogo real (Sol, α Cen, Sirius, Betelgeuse, Sgr A*…);
  - sistemas planetários (estatística do Kepler, Chen-Kipping, zona habitável);
  - órbitas keplerianas, com o Sistema Solar real (J2000).
- Validações contra o céu real:
  - censo perto do Sol: M 76%, K 12%, A 0,5%, B 0,1%;
  - 9.180 estrelas até mag 6,5 (real: ~9.100);
  - Sirius a −1,43 mag (real: −1,46);
  - densidade local de 0,10 pc⁻³ e ~1,4×10¹¹ estrelas no total.

## Fatos do 26.3 descobertos (detalhes em docs/recon/api_*_verified.md)
- A GPU agora é `com.mojang.renderpearl` (com backends GL e Vulkan). `ResourceLocation` virou `Identifier` e `GuiGraphics` virou `GuiGraphicsExtractor`.
- Não existe hook de céu na Fabric API. O `SkyRenderer.render` só roda se o dimension_type tiver `"skybox"` diferente de `"none"`.
- O Iris mapeia os pipelines vanilla por identidade: `END_SKY` vira `SKY_TEXTURED`. O Iris não injeta em `SkyRenderer.render`, então o HEAD-cancel é seguro e o desenho roda na fase `CUSTOM_SKY`.
- Criar uma `DynamicTexture` faz upload, e upload com render pass aberto causa crash ("Close the existing render pass…"). Todas as texturas são resolvidas antes de abrir o pass.
- O céu é desenhado em cascas de raio ~100 (o depthFar pode cair a 128 em render distance 2).

## Gotchas (numerados)
1. Um offset em `double` dentro de um sector de 10 pc resolve só 64 m. Por isso a `GalacticPos` usa células de 1 UA em `long` mais metros em `double` (~0,03 mm).
2. O ray-march com passo geométrico gera anéis de aliasing quando visto de fora do disco. O passo agora também é limitado pela variação vertical.
3. O mapa de `armStrength` é enganoso a olho: meça numericamente. A fase de 25° põe Sagitário 0,86 kpc para dentro e Perseu 1,9 kpc para fora.
4. Num dimension_type com `has_fixed_time`, sem `default_clock` o `/time` quebra.

## Simplificações conhecidas (próximos marcos)
- (resolvido em M2: SystemFrame dá a eclíptica real a 60,19° e orientação isotrópica aos demais sistemas)
- Planetas são discos com cor por tipo: sem fase, atmosfera nem pouso (M2–M4).
- Vizinhança densa (bojo): só o sector atual é varrido para achar a estrela mais próxima.

## Como testar
- `./gradlew runAutotest`: abre o mundo `runs/client/saves/galactica_test`, executa o roteiro em `client/dev/AutoTest.java` e salva screenshots em `runs/client/screenshots/galactica_*.png`. Não usa mouse nem teclado.
- `./gradlew test`: testes científicos.

## Nave v4 (2026-10-06)
- Kestrel em escala 2,5× (13,6 m de comprimento, 12,2 m de envergadura): o piloto senta dentro do cockpit, capacete no vidro. `ShipDimensions` concentra escala, pivô, assentos (tandem) e caixa 6 × 3,2; o atlas cresceu para 1024² (1 texel = 1/16 de bloco).
- Interior do cockpit em primeira pessoa: painel, consoles, manche lateral, manete, vidro interno e display ao vivo (ADI, velocidade, corpo/altitude, H2, exótica, casco, modo), pintado na CPU e enviado no fim do tick.
- Tripulantes giram com a atitude do casco; o olho do piloto gira junto com o assento (corrige a câmera fora do cockpit com a nave invertida).
- Revisão: tripulação sentada num poço oco na fuselagem (quadril em SEAT_Y, capacete 0,13 bloco abaixo do vidro, espinhas do canopy por fora); sondas de colisão do casco além da caixa (`ShipHullProbes`, geradas); desembarque com `DismountHelper`; câmera de perseguição puxada pelo terreno; display com pipeline próprio sem neblina, bola ADI por frame, repintura só quando muda, acima da hotbar; `ShipHud` some no cockpit.

## Referencial orbital / esfera de influência (2026-10-07)
- Bug relatado: depois de sair da Terra, voltar era impossível ("conforme aperto ela vai se afastando"). Causa: a origem do espaço ficava parada na galáxia. A nave levava só a velocidade relativa ao solo (≤ 300 m/s), enquanto a Terra seguia a 29,8 km/s em volta do Sol (1,5 km por tick). E no espaço nenhuma gravidade planetária agia sobre o jogador. No log do jogo manual há `Take-off ... 23:51:49` e nenhum `Entry:` depois.
- `nav/OrbitalFrame` (cônicas remendadas): dentro da esfera de influência de Laplace (Terra ~924 000 km, Lua ~66 000 km, aninhada) o referencial acompanha o corpo dominante (o deslocamento do corpo entra na origem a cada tick). Ele carrega a velocidade inercial relativa ao corpo (`NavState.frameV*`, `anchor`) e cai livremente com GM/r², integrado por Euler semi-implícito (simplético). Nave, tripulação e EVA caem juntos. Servidor e predição do piloto não divergem, porque a gravidade age só no referencial.
- A troca de esfera conserva a velocidade inercial (v' = v + v_antigo − v_novo). Com o warp ligado, o referencial fica em repouso relativo ao corpo dominante: o jogador sai do warp parado em relação ao planeta. Teleportes (`goto`, `orbit`, hiperjump, `launch`) zeram o referencial. A decolagem entrega a rotação do solo ao referencial, e a reentrada usa a velocidade relativa ao corpo.
- Krakensbane: acima de 285 m/s (0,95 × MAX_SPEED), o excesso de velocidade da nave passa para o referencial no cliente do piloto. Ele segue no `ShipControlPayload` (`rebase`) e o servidor o valida junto com a velocidade. Assim as queimas passam de 300 m/s (órbita baixa ~7,7 km/s) sem blocos rápidos.
- HUD: linhas "In SOI of Earth alt … v … vr …" e "Pe … Ap …". O display do cockpit mostra a velocidade relativa ao corpo e PE/AP em km. `/galactica circularize` põe a nave em órbita circular onde ela está.
- O relógio do espaço agora anda em tempo real (antes, parado e sem warp, avançava 1 tick por segundo). O `OriginShiftPayload` leva o relógio junto com a origem, e o céu interpola o movimento do referencial entre ticks.
- Autotest `./gradlew runAutotestSoi` (também no fim do `runAutotest`): órbita circular a 400 km por 60 s (±5 %), aproximação a partir de 2 000 km só com o motor principal e com warp, decolagem sem comandos, EVA caindo −v₀t + ½ g t².
