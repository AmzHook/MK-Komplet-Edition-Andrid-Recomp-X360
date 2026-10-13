# Análise do XEX de Mortal Kombat

O segundo executável fornecido em 13 de outubro de 2026 identifica **Mortal
Kombat / Mortal Kombat Komplete Edition** pelo Title ID `575207FD`, confirmado
por [Xenia #1548](https://github.com/xenia-project/game-compatibility/issues/1548)
e [Xenia Canary #424](https://github.com/xenia-canary/game-compatibility/issues/424).
O nome original do executável é `MK9Game-Retail.exe`. O SDK concluiu a análise
estática e a geração de C++ para esse arquivo. A biblioteca nativa de MK foi
compilada para Android ARM64 e integrada a um APK experimental. Não há validação
de execução no aparelho nem confirmação de APK jogável.

## Identificação do arquivo correto

| Campo | Valor |
|---|---|
| Formato | XEX2 |
| Tamanho do arquivo | 21.282.816 bytes |
| SHA-256 | `e51751328751287b2a6113c815b99f470d1ea0c88f5d9d9f9b1942a28b3e7f6d` |
| Title ID | `575207FD` |
| Media ID | `6579DAFA` |
| Versão / versão-base, valores compactados | `00000008` / `00000008` |
| Versão apresentada pelo SDK | `0.0.0.8` |
| Disco | 1 de 1 |
| Endereço-base | `82000000` |
| Ponto de entrada | `8236E580` |
| Nome original do PE | `MK9Game-Retail.exe` |
| Imagem descomprimida declarada | `01560000` (22.413.312 bytes) |
| Offset do payload | `4000` |
| Criptografia / compressão declaradas | normal / básica, com 4 blocos |
| Dados / preenchimento declarados | 21.266.432 / 1.114.112 bytes |
| Data interna apresentada pelo SDK | 5 de dezembro de 2011, 17:57:30 UTC |

O inspetor lê o arquivo como dado, verifica limites do cabeçalho e calcula seu
SHA-256. Ele não descriptografa, verifica assinaturas, executa instruções ou
confirma jogabilidade. Title ID, Media ID, versão e hash identificam o executável
utilizado; o Title ID sozinho não determina uma variante exata nem comprova
autenticidade.

O primeiro arquivo recebido era de **Gears of War 2**, Title ID `4D53082D`,
Media ID `63AF211E`, SHA-256
`417b6ff4cf8ed70c5b08ee591bcaeddce35ccd0d14fe82511814c7807d35d4f5`.
A identificação foi confirmada por
[Xenia #163](https://github.com/xenia-project/game-compatibility/issues/163).
Ele não é entrada válida para a geração de código de MK. O comando de preparação
recusa outro Title ID antes de criar o projeto ou invocar o SDK.

## Resultado da análise estática e geração

O SDK oficial Linux x64 **ReXGlue 0.10.0** foi instalado com verificação do SHA-256
publicado no [release](https://github.com/rexglue/rexglue-sdk/releases/tag/v0.10.0).
O log final local, `out/native/mk-codegen-final-output.log`, confirma o Title ID,
Media ID, versão e nome do módulo correto. As fases Register, Scan, Discover,
GapFill, Merge, Validate e Write concluíram. Não há chamadas não resolvidas ou
falha de validação nesse resultado final.

O resumo do SDK contabiliza **539 saídas**: 8 escritas, 531 reaproveitadas e
nenhuma removida. A geração final levou aproximadamente 489,7 segundos. As
fontes derivadas permanecem em `out/native/mk-komplete/generated/default`, fora
do Git. Esse resultado demonstra geração estática concluída, não execução.

O arquivo versionado `native/mk9_functions.toml` contém apenas **11 intervalos
de funções**, vinculados ao SHA-256 acima. Os limites foram recuperados por
análise estática de fluxo de controle e lacunas da tabela de funções. Dois
intervalos de troca de contexto conservam os registradores do convidado com
`share_registers = true`. Não foram adicionadas instruções do jogo, funções
fictícias ou stubs para eliminar erros de validação. Não há XEX, chaves,
disassembly ou fontes derivadas do jogo neste relatório.

## Estado da integração Android

O runtime do SDK e o plugin gráfico Xenos/Vulkan foram compilados para Android
ARM64. O log `out/native/mk-native-final-build.log` registra a ligação de
`librexruntime.so`, `librexgpu-xenos.so` e `libmk_komplete.so`, seguida pelo
empacotamento preparado. As bibliotecas são ELF AArch64 com segmentos LOAD
alinhados para páginas de 16 KiB. O APK experimental também inclui
`libc++_shared.so`, a Activity SDL, os avisos de licenças e a associação ao XEX.

As 98.570 chamadas do registro de funções foram divididas, em uma cópia usada
somente na compilação, em 198 funções auxiliares. O script verifica a preservação
de todas as chamadas e sua ordem; as funções recompiladas do jogo continuam com
otimização `-O3`. Essa divisão evita a compilação muito lenta de uma única função
de registro gigante.

Há duas questões distintas sobre fibers:

- O patch `native/patches/android-fiber.patch` adapta as fibers do SDK ao Android
  AArch64. Ele armazena explicitamente o estado exigido pelo AAPCS64, incluindo
  registradores preservados, pilha e controle de ponto flutuante. Evita depender
  de campos internos ou posições presumidas de `jmp_buf` da Bionic. O código
  compilou. Três probes com o emulador de instruções Unicorn verificaram a
  preservação dos registradores, da pilha e do estado de ponto flutuante, a troca
  inversa, o registro x18 pertencente à thread e a entrada inicial alinhada.
  Esses probes não executam MK nem verificam Bionic ou hardware do Poco F7.
- As fibers e a troca de contexto do próprio jogo ainda precisam ser validadas
  em execução. Nenhum hook de troca de contexto específico da família de jogos
  foi aplicado. Os limites de funções e `share_registers` usados na geração não
  comprovam por si só que a semântica desses mecanismos está correta.

Inicialização, gráficos, áudio, controles, carregamento e uma luta permanecem
dependentes dos demais arquivos da cópia e da validação no aparelho. Uma geração
sem erros e bibliotecas do SDK compiladas não confirmam esses comportamentos.

## Reproduzir as etapas locais

```bash
python3 tools/inspect_xex.py /local/path/default.xex --expected-title-id 575207FD
python3 -m unittest discover -s tests -p 'test_*.py' -v
bash tools/prepare_mk_codegen.sh /local/path/default.xex
```

A inspeção deve aceitar o segundo arquivo identificado acima e rejeitar o
primeiro com status `title_mismatch`, preservando os metadados no relatório.
A geração utiliza o executável privado como entrada e preserva seus resultados
em caminhos ignorados pelo Git. Ela prepara código nativo; não constitui uma
validação de APK ou de execução de Mortal Kombat no Android.
