# Análise do XEX de Mortal Kombat

O segundo executável fornecido em 13 de outubro de 2026 identifica **Mortal
Kombat / Mortal Kombat Komplete Edition** pelo Title ID `575207FD`, confirmado
por [Xenia #1548](https://github.com/xenia-project/game-compatibility/issues/1548)
e [Xenia Canary #424](https://github.com/xenia-canary/game-compatibility/issues/424).
O nome original do executável é `MK9Game-Retail.exe`. O SDK concluiu a análise
estática e a geração de C++ para esse arquivo. A biblioteca nativa de MK foi
compilada para Android ARM64 e integrada a um APK experimental. Os testes no
Poco F7 avançaram até a logo e identificaram as chamadas ausentes descritas
abaixo. Não há confirmação de carregamento completo ou APK jogável; a versão
0.1.5 ainda não foi executada no aparelho.

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

Na geração 0.1.2, o arquivo versionado `native/mk9_functions.toml` continha
**13 intervalos de funções**, vinculados ao SHA-256 acima; os dois últimos
foram acrescentados após os testes no Poco F7 descritos abaixo. A correção
0.1.3 acrescenta o intervalo observado em execução e dois outros comprovados
na auditoria das tabelas relacionadas, totalizando 16. A correção 0.1.4 adiciona
dois callbacks de memória comprovados, totalizando 18. A correção 0.1.5
acrescenta três entradas comprovadas de uma tabela do PhysX, totalizando 21.
Os limites foram recuperados por análise estática de fluxo de controle e
lacunas da tabela de funções. Dois intervalos de troca de contexto conservam
os registradores do convidado com
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

Os testes no aparelho comprovaram progresso da inicialização e exibição da
logo. Gráficos completos, áudio, controles, carregamento e uma luta permanecem
dependentes dos demais arquivos da cópia e da validação no aparelho. Uma geração
sem erros e bibliotecas do SDK compiladas não confirmam esses comportamentos.

## Primeiro teste no Poco F7 e correção 0.1.1

O log fornecido pelo usuário em 13 de outubro de 2026 mostra a seleção da
Adreno 825, API de dispositivo Vulkan 1.3.284, criação da swapchain 2772×1280,
mapeamento da memória convidada e inicialização da tabela de funções e do
armazenamento de shaders para o Title ID `575207FD`. A execução foi interrompida
por uma chamada indireta não registrada a `0x829AEB48`. O aviso sobre a ausência
de `gamecontrollerdb.txt` não é a falha fatal reportada.

`0x829AEB48` é uma função independente de 12 bytes, encerrada em `0x829AEB54`,
usada pelo slot `0x82150CA4` da tabela de métodos do alocador do jogo. A função
anterior termina antes dessa entrada. O destino final `0x82AC48D0` já estava
registrado. A ausência de PDATA e de chamadas diretas explica por que a análise
anterior não descobriu essa entrada usada por chamada virtual.

A configuração acrescenta somente seu intervalo real. A geração emitiu o
corpo e o registro dessa função; nenhum stub, alias para função vizinha ou
tratamento para ignorar o erro foi usado. O patch
`native/patches/indirect-call-diagnostics.patch` mantém a falha fatal e amplia o
log com LR, CTR, R1, R3, R4 e ID da thread convidada. LR não é apresentado como
o endereço exato da instrução em execução.

A geração da versão 0.1.1 concluiu com validação em 642,9 segundos: 6 saídas
alteradas e 533 reaproveitadas. Foram conferidos o corpo emitido, a declaração,
o mapeamento e o registro de `0x829AEB48`, além do registro dos 12 intervalos
manuais. A compilação incremental concluiu a ligação da biblioteca MK para
ARM64, preservando a otimização das funções do jogo.

O teste comprova progresso da inicialização do runtime no aparelho, mas não o
carregamento completo do MK. O segundo teste abaixo verificou que essa chamada
foi superada, antes de encontrar outro método ausente.

## Segundo teste no Poco F7 e correção 0.1.2

O usuário relatou a exibição da logo na versão 0.1.1. O log passou pela criação
de pipelines gráficos e não repetiu a chamada ausente `0x829AEB48`. A nova falha
foi `0x827EF090`, com LR `0x827C7BD0`, CTR `0x827EF090` e thread convidada 6.

A chamada em `0x827C7BCC` usa o slot `+0x18` da tabela de métodos em
`0x82147300`. O slot em `0x82147318` aponta para `0x827EF090`, uma função real
de 16 bytes terminando em `0x827EF0A0`. Ela lê dois campos e retorna um flag.
Os outros 34 métodos dessa tabela já estavam registrados. Metadados de exceções
adjacentes não foram tratados como funções virtuais nem adicionados por palpite.

A geração 0.1.2 validou e emitiu o método em 539,8 segundos, alterando 5 saídas e
reaproveitando 534. Foram conferidos seu corpo, declaração, mapeamento e registro
e o registro dos 13 intervalos manuais. A compilação incremental ARM64 concluiu.

O usuário confirmou a importação de todas as pastas da ISO. A versão 0.1.2
acrescenta uma auditoria somente de leitura da pasta montada e da presença de
`Config/Xe-MK9Engine.ini` pelas rotas `game:` e `D:`, respeitando o VFS real e sua
busca sem distinção de maiúsculas/minúsculas. Não cria configurações nem altera
os dados do jogo. Os avisos sobre `UPDATE:` ou `ShaderDumpxe:` não são a falha
fatal identificada.

Os exports KeSaveFloatingPointState e KeRestoreFloatingPointState continuam
explicitamente não implementados. O patch `floating-point-stub-logging.patch`
preserva o contexto e os registros e limita os avisos à primeira chamada e a
cada 4096 chamadas por API, com contador, LR, R3 e ID da thread. O helper real
foi verificado em testes de limites, isolamento de contadores e concorrência.
Não foi presumido um formato Windows KFLOATING_SAVE nem inventado um retorno
de sucesso. A limitação de avisos não implementa a preservação de estado FP.

A execução após a segunda correção chegou a outra entrada virtual ausente,
descrita a seguir. O carregamento completo permanece não verificado.

## Terceiro teste no Poco F7 e correção 0.1.3

O log da versão 0.1.2 avançou além das duas chamadas anteriormente corrigidas
e reportou uma chamada indireta não registrada a `0x832BB848`, com LR
`0x832E0198`. A chamada em `0x832E0194` usa o slot `+0x08` da tabela de métodos.

O método `0x832BB850` escreve as referências às tabelas `0x822922E8`,
`0x822922D4` e `0x822922C0` nos offsets `+0`, `+0x20` e `+0x24` do objeto,
respectivamente. Essas referências delimitam as tabelas auditadas. A auditoria
dos 25 slots encontrou somente três entradas omitidas; seus destinos finais
já estavam registrados:

| Entrada | Fim exclusivo | Ajuste do receptor | Destino final |
| --- | --- | --- | --- |
| `0x832BB358` | `0x832BB360` | −36 bytes | `0x832BE350` |
| `0x832BB360` | `0x832BB368` | −32 bytes | `0x832BB190` |
| `0x832BB848` | `0x832BB850` | −36 bytes | `0x832BE380` |

Cada entrada é uma função independente de 8 bytes. A correção acrescenta
somente esses intervalos reais para geração dos corpos e registros. Não
substitui as entradas por aliases dos destinos, pois isso perderia os ajustes
do receptor, nem por stubs.

A verificação das bibliotecas pré-compiladas passa a exigir `sub_832BB358`,
`sub_832BB360` e `sub_832BB848`, além de `sub_829AEB48` e `sub_827EF090`, e
preserva as exigências dos markers de diagnóstico do SDK. A validação estática
e a inspeção desses símbolos não demonstram execução bem-sucedida.

O log da versão 0.1.3 superou essas chamadas e encontrou o callback ausente
descrito a seguir. Carregamento completo, partidas, áudio e desempenho ainda
não foram confirmados.

## Quarto teste no Poco F7 e correção 0.1.4

O usuário relatou que a versão 0.1.3 exibiu a logo do Mortal Kombat e fechou
antes do vídeo da NetherRealm. O log registrou uma chamada não registrada a `0x82FE62C8`, com
LR `0x83164BBC`, `R3=0` e `R4=0x82279918`. O argumento R4 identifica a string
de diagnóstico `PxcArray.h`, do PhysX 2.8.4. A rotina `0x83164B50` expande um
array e libera seu buffer antigo pela chamada a `0x831235F8` em `0x83164BB8`.
Esse wrapper lê o callback global em `0x834C0E84` e transfere a execução sem
alterar o LR.

O callback real em `0x82FE62C8` testa o ponteiro e retorna imediatamente quando
ele é nulo, antes de ler o alocador global em `0x8344D120`. Portanto, `R3=0`
é permitido nessa chamada. A falha de registro impediu executar essa lógica.
Para um ponteiro não nulo, o callback delega ao slot `+0x14` do alocador.

A rotina `0x82FE9438` instala um descritor de cinco callbacks, com quatro
endereços distintos: `0x82FE6278`, `0x82FE62C8`, `0x82FE62F0`, `0x82FE6298` e
novamente `0x82FE62C8`. O descritor é passado a `0x83123590` em `0x82FE95C0`.
Essa instalação comprova também o callback de alocação com diagnóstico em
`0x82FE6298`, que estava omitido; os outros dois endereços já estavam registrados.

| Entrada | Fim exclusivo | Tamanho | Comportamento |
| --- | --- | --- | --- |
| `0x82FE6298` | `0x82FE62C8` | 48 bytes | Delega a alocação ao slot `+0` do alocador |
| `0x82FE62C8` | `0x82FE62EC` | 36 bytes | Aceita ponteiro nulo; delega a liberação ao slot `+0x14` |

Os dois fluxos são fechados. O `blr` isolado em `0x82FE62EC` não integra esses
fluxos nem o descritor comprovado e não recebeu uma função inventada. Os novos
intervalos são vinculados ao SHA-256 exato, com validação completa do ReXGlue.
A verificação dos pré-compilados exige os dois novos símbolos, além das cinco
correções anteriores e dos diagnósticos do SDK.

A geração 0.1.4 concluiu as fases de validação e escrita em aproximadamente
609,1 segundos: 6 arquivos escritos, 533 reaproveitados e nenhum removido. Os
18 intervalos manuais estão gerados e registrados. A inspeção do callback de
liberação confirmou o retorno para ponteiro nulo antes de qualquer leitura de
memória do alocador.

O novo log da versão 0.1.4 superou esses callbacks e encontrou a entrada
virtual ausente descrita a seguir. Os avisos sobre arquivos e APIs não
implementadas continuam sendo limitações distintas; o log não comprova que
causaram esta chamada não registrada.

## Quinto teste no Poco F7 e correção 0.1.5

O log da versão 0.1.4 registrou uma chamada indireta não registrada a
`0x83128D48`, com LR `0x83126A80`, `R3=0x44A44B70` e `R4=8`. A chamada em
`0x83126A7C` usa o slot `+0x60` da tabela de métodos do PhysX.

A tabela ocupa o intervalo `0x822790A8`–`0x82279150`, com 42 slots. O método
`0x8312BD88` escreve sua referência no offset `+0` do objeto em `0x8312BDAC`;
o método `0x8312C188` reinstala a referência em `0x8312C1B0`. A auditoria
independente confirmou 39 entradas já registradas e somente três omitidas:

| Entrada | Fim exclusivo | Tamanho | Comportamento |
| --- | --- | --- | --- |
| `0x83128D38` | `0x83128D40` | 8 bytes | Lê o ponteiro armazenado em `this+0x38` e transfere para `0x8312F560` |
| `0x83128D40` | `0x83128D48` | 8 bytes | Lê o ponteiro armazenado em `this+0x38` e transfere para `0x8312F6B0` |
| `0x83128D48` | `0x83128D50` | 8 bytes | Retorna o ponteiro armazenado em `this+0x34` |

Os três fluxos têm duas instruções alcançáveis e limites fechados. Os destinos
das duas transferências já estavam registrados. As outras três referências
ao getter não foram usadas para acrescentar funções sem evidência a esta
correção. Foram adicionados somente os três intervalos reais, vinculados ao
mesmo SHA-256, para gerar os corpos e registros originais.

A verificação dos pré-compilados exige esses três símbolos junto aos sete
anteriores e preserva as exigências dos diagnósticos do SDK. A geração 0.1.5
concluiu a validação e escrita em aproximadamente 612,3 segundos: 9 arquivos
escritos, 530 reaproveitados e nenhum removido. Os 21 intervalos manuais foram
gerados e registrados. A compilação ARM64, assinatura e verificação do conteúdo
do APK também concluíram.

A execução da versão 0.1.5 no Poco F7 permanece pendente. Essa análise e os
checks de build não confirmam carregamento completo, partidas, áudio ou
desempenho após a correção.

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
