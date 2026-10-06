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
