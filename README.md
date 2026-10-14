# MK Komplete · Android experimental

Integração experimental de Mortal Kombat Komplete Edition para Xbox 360 com
ReXGlue em Android ARM64. O aplicativo importa os arquivos do jogo por ZIP ou
ISO e oferece um botão para testar a inicialização do runtime nativo. No teste da
versão 0.1.3, o usuário relatou a logo do Mortal Kombat antes do vídeo da
NetherRealm, seguida pelo fechamento. **Carregamento completo, partidas, áudio
e desempenho ainda não foram verificados.**

O APK experimental contém código nativo gerado para um executável específico.
O usuário fornece a ISO ou um ZIP com o XEX e as demais pastas do jogo; o
aplicativo não baixa esses arquivos. O APK `MK-Importador` continua disponível
como variante que apenas importa arquivos, sem execução.

## Atualização experimental 0.1.11

O launcher agora usa um menu MK em vermelho e preto, inspirado na referência
fornecida: botões **JOGAR**, **OPÇÕES**, **ARQUIVOS DO JOGO** e **SAIR**, estado
da biblioteca e painel de opções separado em paisagem. As escolhas ficam
salvas; toque, foco por direcional e botões A/B do controle são aceitos no menu.

Em **OPÇÕES**, estão disponíveis ajustes reais de preenchimento da tela,
resolução interna 1×/2×, sincronização vertical e registro limitado de arquivos.
Os ajustes são enviados ao runtime ao iniciar o jogo. Não foram adicionados
controles de toque ou um editor de controles.

**Driver gráfico** permite escolher **Padrão do aparelho** ou um driver ARM64
importado. Use **Drivers personalizados → Importar driver ZIP** para selecionar
um pacote AdrenoTools/Turnip com `meta.json` e sua biblioteca. A importação
valida caminhos, CRC, limites, metadados e ELF ARM64; ela não executa a biblioteca.
A cópia do jogo e a instalação dos drivers ficam em pastas privadas separadas.
Remover um driver preserva o jogo.

O carregamento customizado usa o AdrenoTools, com seus hooks empacotados e os
namespaces necessários aos drivers Android. O runtime tenta o driver do sistema
quando não consegue preparar o loader customizado ou inicializar sua instância
Vulkan. O log distingue o driver solicitado das propriedades do dispositivo
efetivamente inicializado: um handle de loader não comprova uso do Turnip.
Compatibilidade de cada pacote com Adreno 825, criação do dispositivo Vulkan e
jogabilidade ainda exigem teste no Poco F7. Nenhum driver Turnip é incluído.

AdrenoTools está fixado em `8fae8ce254dfc1344527e05301e43f37dea2df80` e o helper
de namespaces em `aa3975893d83ef1bc84c321ec60c65fbf1287887`, com downloads por
HTTPS e SHA-256 verificado. Os dois usam BSD de duas cláusulas; avisos e fontes
correspondentes estão distribuídos com o runtime. A build contém nove bibliotecas
ARM64. Os 90 intervalos manuais e as correções da 0.1.10 foram preservados.

Os testes de importação de drivers estão em `bash tools/test_driver_importer.sh`:
63 casos passaram, incluindo cancelamento, fontes inválidas, arquivos maliciosos
e preservação de instalações anteriores. Também passaram os 29 testes Python
e os 97 testes de importação do jogo. A build nativa, a assinatura do APK e a
inspeção das nove bibliotecas ARM64 com alinhamento de 16 KiB foram verificadas.
A aparência no F7 e o carregamento de um driver importado seguem pendentes de
teste no aparelho. Consulte
[opções e drivers](docs/options-and-drivers.md) para uso e limitações.

## Atualização anterior 0.1.10

A pesquisa no GitHub identificou duas correções do Shiftglue que correspondem
ao trecho do Reddit fornecido pelo usuário. Foram adaptadas no patch
[android-idle-waits.patch](native/patches/android-idle-waits.patch):

- [SDL, commit 26824c6](https://github.com/deivid22srk/shiftglue-sdk/commit/26824c612a829c6ef7dc3baa507a4e1ce4a52222):
  desativa o evento interno `SDL_EVENT_POLL_SENTINEL` no Android. O contexto usa
  `SDL_WaitEvent`; esse sentinel acordava a própria fila sem eventos reais.
  Eventos de controle, janela, pintura e encerramento continuam habilitados.
- [Espera POSIX, commit 0d85db4](https://github.com/deivid22srk/shiftglue-sdk/commit/0d85db454648df3838265d1b53b803df7a1af4b6):
  preserva o restante inferior a 1 ms antes de dormir. Antes, o arredondamento
  para milissegundos inteiros transformava essas pausas em `sleep_for(0)`;
  as esperas alertáveis do worker de áudio usam fatias de 1 ms.

Essas correções tratam trabalho ocioso de CPU. A temperatura, o uso de CPU e o
áudio do MK no Poco F7 ainda precisam ser medidos. O perfil offline continua
disponível; as mudanças de espera não representam uma correção de login.

A auditoria sistemática recuperou **20 corpos originais ausentes**, elevando
os intervalos manuais de 70 para 90 em
[mk9_functions.toml](native/mk9_functions.toml):

| Família | Adições | Evidência |
| --- | --- | --- |
| Proxy associado aos comandos de vídeo | 1 | `0x82B4CBC8`–`0x82B4CBDC`, 20 bytes, tabela secundária `0x82160E30` instalada por `0x829D0CB0`. |
| Thunks PhysX de ajuste de receptor | 4 | Corpos de 8 bytes em duas tabelas secundárias instaladas, `0x822920B0` e `0x82292120`. |
| Encaminhadores de interface | 8 | Corpos de 24 bytes na tabela `0x82229C18`, instalada por `0x82E9F260`. |
| Encaminhadores D3D | 7 | Tabelas realmente instaladas `0x821B43A0`, `0x821B4628` e `0x821BB978`, cada uma com 162 slots. |

Uma revisão independente conferiu os limites e as instruções originais,
incluindo a ordem dos argumentos, os saltos finais e a preservação do LR.
Nenhum novo intervalo se sobrepõe a PDATA, aos 70 anteriores ou a registros
interiores. A varredura de 4.217.369 instruções não encontrou saltos diretos
para o interior desses corpos. A orientação oficial e as fontes consultadas
estão na [revisão dos runtimes](docs/reference-runtime-review.md).

O [observador genérico de arquivos](native/patches/file-open-observer.patch)
do Shiftglue também foi adaptado. O log mostra `MK file request` com os nomes
completos das primeiras 128 tentativas de abertura e até 64 pedidos posteriores
de vídeos. São tentativas, não confirmação de leitura. O observador não altera
o resultado da abertura nem os arquivos do jogo. Os testes concorrentes do
limite de logs estão em `bash tools/test_native_diagnostics.sh`.

**Esses 20 endereços não foram identificados em um log fatal da 0.1.9.**
O usuário relatou que ela ainda fecha na logo, mas não forneceu o novo endereço.
A 0.1.10 corrige omissões comprovadas e os dois ciclos de espera; avançar além
da logo e jogar uma partida continuam dependendo do teste físico. Não há um
bypass de vídeo aplicado: o MK usa `MwyMoviePlayer`, diferente do XMedia do
Pinyon. ZIP/ISO e os diagnósticos anteriores são preservados.

A geração completa passou por Validate/Write em 693,3 segundos: 29 arquivos
escritos, 510 preservados e nenhum removido. O código gerado foi conferido
independentemente contra as 114 instruções originais dos 20 corpos. São
98.649 registros, com todos os 98.629 anteriores preservados e os 90
intervalos manuais gerados e registrados. A compilação ARM64 passou, assim
como 29 testes Python, 97 de importação Java e os testes nativos de logs e
esperas. Na comparação controlada de duas esperas de 250 ms na nuvem, o código
original consumiu 481,5 ms de CPU em 500 ms; o corrigido, 7,6 ms de CPU em
500,1 ms. Isso mede a espera POSIX no host, não temperatura ou desempenho no F7.
As verificações de assinatura e conteúdo do APK passaram: 4 bibliotecas ARM64
e 47 avisos. O arquivo de fontes públicas corresponde aos 9 arquivos de SDK
modificados e aos 5 patches, sem XEX, ISO ou binários.

## Atualização anterior 0.1.9

Corrige a entrada virtual omitida `0x8243FB30`, encontrada no log real da versão
0.1.8 com LR `0x82408F90`. O corpo original de 16 bytes,
`0x8243FB30`–`0x8243FB40` com fim exclusivo, carrega o ponteiro armazenado em
`this+0x68`, retorna se ele for nulo e, caso contrário, transfere a execução
para `0x82A98820`, já registrado. O wrapper `0x8243FB20` usa o slot virtual
`+0x44` e preserva o LR do chamador.

A auditoria das tabelas `0x82020C00` e `0x8202A9E0`, com 23 slots cada,
e das tabelas relacionadas `0x82000978`, com 3, e `0x820292A8`, com 2,
encontrou somente essa função ausente. A correção também incorpora 41 thunks
originais comprovados em uma revisão mais ampla de entradas indiretas:

- 28 thunks reordenam argumentos e transferem a execução para destinos já
  registrados. Pertencem à tabela de base `0x822608E8`, cuja extensão de 76
  slots é inferida até a próxima base comprovada, `0x82260A18`.
- 13 thunks ajustam `this` em −4 e transferem a execução para destinos já
  registrados. Estão distribuídos pelas bases `0x82291D68`, `0x82291DD8`,
  `0x82291E54`, `0x82291E80`, `0x82291EF0`, `0x82291F6C` e `0x82291F98`.
  A região contínua de 171 ponteiros contém várias tabelas adjacentes.

Os corpos preservam a ordem original dos movimentos de registradores,
incluindo o temporário `R11` em `0x82FE69D0`, e os saltos finais sem alteração
do LR. Os limites foram conferidos com o código original e os destinos
registrados, sem sobreposição com os intervalos anteriores.

São 42 adições nesta versão, elevando o total de limites manuais de 28 para
70. Os intervalos completos estão em
[mk9_functions.toml](native/mk9_functions.toml). O verificador das bibliotecas
pré-compiladas agora exige os símbolos de todos os intervalos desse arquivo.
O suporte a ZIP e ISO e os diagnósticos do runtime são preservados.

Geração completa com validação concluída em 572,7 segundos: 88 arquivos escritos,
451 preservados e nenhum removido. Os 70 intervalos possuem corpos e registros,
com 98.629 registros no total e todos os 98.587 anteriores preservados. Uma
revisão independente confirmou os 42 novos corpos, incluindo os 87 movimentos
de registradores na ordem original. A compilação ARM64 e os 29 testes Python
passaram. O verificador rejeitou a biblioteca antiga pelos 42 corpos ausentes
e aprovou os 70 símbolos exigidos na biblioteca nova.
As verificações de assinatura e conteúdo do APK passaram, com 4 bibliotecas
ARM64 e 47 avisos de licença e fontes correspondentes.

O usuário confirmou que a versão 0.1.9 ainda fecha na logo no Poco F7. O log
dessa tentativa ainda não foi fornecido; não há confirmação de que chegou à
seleção de perfil. **Carregamento completo e jogabilidade continuam sem
validação.** A [comparação com os outros ports](docs/reference-runtime-review.md)
confirma que o runtime já oferece um perfil local conectado e dispensa o
diálogo de login do Xbox; isso não resolve as falhas anteriores de despacho.

## Atualização anterior 0.1.8

Corrige o adaptador de despacho da VM de scripts `0x8270A5B0`, encontrado no
log real da versão 0.1.7 com LR `0x8271CEBC`. Ele lê o ponteiro de bytecode
armazenado em `frame+0x0C`, consome um byte de opcode, avança esse ponteiro e
transfere a execução para o destino em `GNativeFuncs`, preservando o LR.
A análise também comprovou a ausência do adaptador do banco anterior:

| Intervalo, com fim exclusivo | Tamanho | Despacho original |
| --- | --- | --- |
| `0x8270A588`–`0x8270A5B0` | 40 bytes | Banco 0: destino em `0x83417858 + opcode × 4`. |
| `0x8270A5B0`–`0x8270A5DC` | 44 bytes | Banco 1: destino em `0x83417858 + 0x400 + opcode × 4`. |

Os dois adaptadores pertencem ao conjunto de 16 ponteiros em
`0x83391A14`–`0x83391A54`; os outros 14 já estavam registrados. Seus
inicializadores estáticos instalam os handlers dos opcodes `0x60` e `0x61`
pelo registrador `0x8270A880`. A auditoria decodificou as 754 chamadas estáticas
a esse registrador: 261 com índice ativo e 493 com índice `-1`, ignorado pelo
helper. Somente estes dois handlers estavam sem registro; todos os demais
destinos dessas chamadas já estavam registrados.

A correção acrescenta os dois intervalos para gerar seus corpos originais e
registros, elevando o total de limites manuais de 26 para 28. O preenchimento
zero em `0x8270A5DC` fica fora do corpo. O suporte a ZIP e ISO e os diagnósticos
do runtime são preservados.

Geração completa com validação concluída em 582,2 segundos: 6 arquivos escritos,
533 preservados e nenhum removido. Os 28 intervalos estão gerados e registrados,
com 98.587 registros no total e os 16 adaptadores de banco registrados. Todos os
98.585 registros anteriores foram preservados. A compilação ARM64, os 29 testes
Python e as verificações de assinatura e conteúdo do APK passaram. O pacote
contém 4 bibliotecas ARM64 e 47 avisos de licença e fontes correspondentes.

O novo log real da versão 0.1.8 registrou a entrada ausente `0x8243FB30`,
tratada pela correção 0.1.9 acima. **Carregamento completo e jogabilidade
continuam sem validação.**

## Atualização anterior 0.1.7

Corrige a entrada virtual omitida `0x83054B60`, encontrada no log real da versão
0.1.6 com LR `0x8301AEFC`, `R3=0x44C22FD0` e `R4=0x44B64788`. Esse método lê
o ponteiro armazenado em `this+0x174` e grava o argumento `R4` no campo `+0x90`
do objeto apontado. O wrapper `0x83072630` transfere a execução pelo slot
`+0x210` da tabela virtual, preservando o LR do chamador.

A auditoria dos 151 slots da tabela `0x8226F878`, instalada pelo método
`0x8305B3A0` e reinstalada por `0x8305E198`, comprovou cinco entradas
reais ausentes. A correção acrescenta seus intervalos para gerar os corpos
originais e os registros:

| Intervalo, com fim exclusivo | Tamanho | Comportamento original |
| --- | --- | --- |
| `0x83054B60`–`0x83054B6C` | 12 bytes | Grava `R4` em `+0x90` do objeto apontado por `this+0x174`. |
| `0x83053BF8`–`0x83053C1C` | 36 bytes | Copia três valores de ponto flutuante e define uma flag como 1. |
| `0x83054598`–`0x830545A0` | 8 bytes | Ajusta o receptor em `+0x304` e transfere a execução para `0x83092298`, já registrado. |
| `0x830545A0`–`0x830545BC` | 28 bytes | Retorna se o receptor for nulo; caso contrário, transfere a execução pelo slot virtual `+0x258`. |
| `0x8309D5C8`–`0x8309D5D0` | 8 bytes | Retorna o ponteiro armazenado em `this+0x184`. |

Os cinco intervalos elevam o total de funções com limites manuais de 21 para
26. O `blr` isolado em `0x830545BC`, fora do fluxo da função anterior, não foi
incluído. O suporte a ZIP e ISO e os diagnósticos do runtime são preservados.

Geração completa com validação concluída em 608,8 segundos: 10 arquivos escritos,
529 preservados e nenhum removido. Os 26 intervalos estão gerados e registrados;
os 151 slots auditados possuem destinos registrados. A compilação ARM64 e as
verificações de assinatura e conteúdo do APK passaram, com 4 bibliotecas e 47
avisos de licença e fontes correspondentes. Os 29 testes Python passaram.

O novo log real da versão 0.1.7 registrou a entrada ausente `0x8270A5B0`,
tratada pela correção 0.1.8 acima. **Carregamento completo e jogabilidade
continuam sem validação.**

## Atualização anterior 0.1.6

Adiciona a importação direta de ISO Xbox 360 com uma partição XDVDFS em setores
de 2048 bytes. O botão **ARQUIVOS DO JOGO** extrai os arquivos da partição do
jogo para a mesma pasta privada usada pelo importador de ZIP. Não é preciso
extrair a ISO em outro programa antes de selecionar o arquivo no aplicativo.

Arquivos que permitem leitura por posição são lidos diretamente. Se o local
selecionado oferecer apenas leitura sequencial, o aplicativo prepara uma cópia
temporária da ISO, limitada a 24 GiB, com limpeza após a tentativa e ao abrir o
aplicativo novamente. O XEX extraído passa pelas mesmas verificações e o botão
de inicialização continua exigindo o SHA-256 da build. Os 21 intervalos de
funções do runtime permanecem os mesmos da versão 0.1.5.

Passaram 97 testes Java de importação e 29 testes Python do projeto. A
compilação ARM64, a assinatura e a verificação do conteúdo do APK concluíram.

Os testes de importação usam imagens sintéticas. **Nenhuma ISO real do MK foi
fornecida para validar este recurso; a importação dessa ISO no Poco F7 e a
jogabilidade continuam pendentes.** O novo log de inicialização da versão
0.1.6 registrou a entrada ausente `0x83054B60`, tratada pela correção 0.1.7
acima. Os formatos aceitos e detalhes de validação estão em
[Importação de ISO](docs/iso-import.md).

## Atualização anterior 0.1.5

Corrige o método virtual omitido `0x83128D48`, encontrado no log da versão
0.1.4. Ele retorna o ponteiro armazenado no campo `+0x34` do objeto. A auditoria
dos 42 slots da tabela de métodos do PhysX identificou outras duas entradas
omitidas, `0x83128D38` e `0x83128D40`, que leem o ponteiro do campo `+0x38`
e transferem a execução para métodos já registrados.

Os três intervalos de 8 bytes foram acrescentados para geração dos corpos
originais e registros, preservando as sete correções anteriores e os
diagnósticos do SDK. A geração validada, a compilação ARM64 e as verificações
de assinatura e conteúdo do APK concluíram. **A versão 0.1.5 ainda não foi
testada no Poco F7. Carregamento completo e jogabilidade permanecem não
verificados.**

## Atualização anterior 0.1.4

Corrige dois callbacks de memória do PhysX omitidos na recompilação:
`0x82FE62C8`, observado no log da versão 0.1.3, e `0x82FE6298`, encontrado
no mesmo descritor de callbacks instalado pelo jogo.

O callback de liberação já prevê retorno imediato quando recebe um ponteiro
nulo. O `R3=0` desse log é um argumento válido; a falha ocorreu antes de executar
essa verificação, porque a função não estava registrada. A correção gera os
corpos originais e os registros dos dois callbacks, preservando essa lógica.
O log da versão 0.1.4 superou esses callbacks e encontrou a entrada virtual
`0x83128D48`, tratada pela correção 0.1.5 acima.

## Atualização anterior 0.1.3

Corrige a entrada virtual omitida `0x832BB848`, encontrada no teste da versão
0.1.2. É uma função real de 8 bytes, terminando em `0x832BB850`, que ajusta o
receptor em 36 bytes e transfere a execução para `0x832BE380`. A chamada usa o
slot `+0x08` da tabela de métodos, com LR `0x832E0198`. A auditoria dos 25 slots
das três tabelas referenciadas pelo método `0x832BB850` identificou outras duas
entradas reais omitidas, `0x832BB358` e `0x832BB360`, incluídas nesta correção.

A correção acrescenta os três intervalos originais e seus registros no
runtime. Mantém as correções de `0x829AEB48` e `0x827EF090`, os diagnósticos de
chamadas indiretas e os avisos limitados de estado de ponto flutuante.
O log dessa versão superou a chamada anteriormente ausente e parou no callback
`0x82FE62C8`, tratado pela correção 0.1.4 acima.

## Atualização anterior 0.1.2

Corrige o método virtual omitido `0x827EF090`, chamado em `0x827C7BCC`, que
interrompeu o segundo teste depois da logo. A versão anterior já corrigiu
`0x829AEB48`. Os métodos são gerados a partir do XEX original, com limites
validados, sem substituição por funções fictícias.

O log agora mostra a pasta montada e a presença de `Config/Xe-MK9Engine.ini`
pelas rotas `game:` e `D:`. Os dois avisos de estado de ponto flutuante continuam
explicitamente `UNIMPLEMENTED`, mas aparecem na primeira chamada e a cada 4096,
preservando o comportamento e evitando dezenas de avisos por segundo.

O teste dessa versão superou a falha anterior e encontrou a entrada ausente
`0x832BB848`, tratada pela correção 0.1.3 descrita acima.

## Instalação e teste

1. Instale `MK-Experimental-0.1.11.apk` em Android 9 ou mais recente, ARM64 e Vulkan.
2. Toque em **ARQUIVOS DO JOGO**. Selecione a ISO Xbox 360 da sua cópia do
   jogo ou um ZIP contendo exatamente um `default.xex` e todas as demais pastas
   do jogo. No ZIP, uma pasta externa envolvendo esses arquivos é aceita.
   Somente o XEX não fornece os dados necessários ao jogo.
3. Mantenha o aplicativo aberto até terminar a importação. Se aparecer
   **Preparando ISO para importação…**, aguarde a cópia temporária e a extração.
   **Cancelar importação** interrompe a operação e mantém a cópia anterior.
4. Toque em **JOGAR**. Esta versão usa a entrada SDL e
   requer um controle físico compatível; ainda não possui controles de toque.
5. Depois de uma tentativa, **OPÇÕES → Log de inicialização → Abrir log** permite ler e copiar o
   log salvo. Se ocorrer uma falha nativa, abra novamente o aplicativo para
   consultar o log que tenha sido gravado.

O botão de inicialização só é habilitado para o mesmo XEX usado na geração:

| Campo | Versão desta build |
| --- | --- |
| Title ID | `575207FD` |
| Media ID | `6579DAFA` |
| Executável original | `MK9Game-Retail.exe` |
| Versão | `0.0.0.8` |
| SHA-256 do XEX | `e51751328751287b2a6113c815b99f470d1ea0c88f5d9d9f9b1942a28b3e7f6d` |

Os arquivos são extraídos no armazenamento privado do aplicativo. Não há envio
pela rede nem permissão de acesso amplo ao armazenamento. Desinstalar remove a
cópia importada; preserve o ZIP ou a ISO original. Uma substituição é validada
em uma pasta temporária e falhas ou cancelamento preservam a cópia anterior.

Reserve espaço para o arquivo original e os arquivos extraídos. Alguns locais
de armazenamento também exigem uma cópia temporária da ISO; selecionar um
arquivo local que permita leitura por posição evita essa cópia. Ao substituir
um jogo importado, a cópia anterior também ocupa espaço até a nova importação
ser concluída. A extração da ISO mantém uma reserva de 64 MiB de espaço livre.

São aceitas imagens XDVDFS da partição do jogo e imagens XGD2 ou XGD3 com setores
de 2048 bytes. Imagens raw de 2352 bytes por setor, ISO 9660 sem XDVDFS, imagens
comprimidas, pacotes GOD e ISOs divididas em partes não são aceitos.

## APK pelo GitHub Actions

Abra [Actions](https://github.com/AmzHook/MK-Komplet-Edition-Andrid-Recomp-X360/actions).
O workflow **Package MK Experimental APK** compila o aplicativo Java, verifica
as bibliotecas ARM64 previamente compiladas e empacota o APK experimental.
Baixe o artefato `MK-Experimental-0.1.11` de uma execução concluída com sucesso.
O workflow **Build MK Importador APK** produz a variante de importação.
O workflow experimental está habilitado para testes no aparelho. A publicação
da variante de importação é opcional e exige a variável de repositório
`MK_PUBLISH_IMPORTER_ONLY=true`. Um build bem-sucedido não comprova jogabilidade.

O job experimental **empacota bibliotecas pré-compiladas**; ele não recompila um
XEX no runner. XEX, ISO e fontes C++ geradas do jogo ficam fora do Git. Os
artefatos incluem APK e checksum, ficam disponíveis por 30 dias e seu download
pode exigir login no GitHub. Após extrair, confira:

```bash
sha256sum -c MK-Experimental-0.1.11.apk.sha256
```

A assinatura é de desenvolvimento. Cada runner gera uma chave nova; uma build
com assinatura diferente pode exigir desinstalar a anterior, removendo os dados
importados. A chave local fica fora do repositório e é reutilizada nos builds
locais.

## Empacotamento local

Requer Linux, Python 3.11+ e Git. Downloads de ferramentas usam HTTPS e os
checksums publicados nas fontes oficiais. Não é necessário Gradle ou Android
Studio para empacotar o aplicativo.

```bash
python3 tools/bootstrap_android.py
python3 -m unittest discover -s tests -p 'test_*.py' -v
bash tools/test_importer.sh
bash tools/test_native_diagnostics.sh
bash tools/test_driver_importer.sh
python3 tools/prepare_prebuilt_runtime.py
bash tools/build_apk.sh --native
python3 tools/verify_native_apk.py
```

Saída: `out/apk/MK-Experimental-0.1.11.apk`. Para compilar apenas o importador,
execute `bash tools/build_apk.sh`.

## Recompilação nativa a partir da sua cópia

O pipeline local usa ReXGlue 0.10.0 para geração no host, o SDK Android indicado
em `tools/bootstrap_native.py`, NDK 28.2.13676358 e CMake 3.31.6. As ferramentas
ficam em `/workspace/.tools` ou nos diretórios definidos por `MK_ANDROID_TOOLS`,
`MK_REXGLUE_TOOLS` e `MK_NATIVE_TOOLS`.

```bash
python3 tools/inspect_xex.py /local/path/default.xex --expected-title-id 575207FD
bash tools/prepare_mk_codegen.sh /local/path/default.xex
bash tools/build_native.sh /local/path/default.xex
bash tools/build_apk.sh --native
```

A geração pode levar vários minutos e a compilação exige memória e espaço para
centenas de unidades C++. `MK_BUILD_JOBS` controla o paralelismo, com padrão 3.
Os intervalos de funções em `native/mk9_functions.toml` são aplicados somente
ao SHA-256 acima. Outro executável exige análise e geração próprias; o Title ID
sozinho não estabelece compatibilidade.

O SDK recebe o patch publicado em `native/patches/android-fiber.patch`, que
substitui o uso de campos privados do `jmp_buf` do Android por uma troca de
contexto ARM64 explícita. Essa correção do runtime não resolve automaticamente
as particularidades das fibers do jogo. Nenhum conjunto parcial de hooks de
fibers foi aplicado ao MK.

Depois de preparar os dois SDKs, Linux x86_64 com `g++` compatível com C++23
também pode executar `bash tools/test_idle_waits.sh --compare-baseline`.
Esse teste compila o arquivo POSIX real e confere eventos, semáforos, timeouts
e callbacks alertáveis. Relata CPU da espera ociosa no host, sem impor um limite
de desempenho dependente de carga nem executar o jogo. Ele não mede temperatura
ou FPS no telefone.

## Validação e limites

Os testes sintéticos verificam extração de ZIP e ISO, CRC de ZIP, substituição,
recuperação, cancelamento, limites e rejeição de caminhos inseguros. Os testes
de ISO também cobrem os três offsets de partição, tabelas de diretórios,
imagens truncadas e acessos além de 4 GiB; os testes Python
verificam a análise de metadados e a associação dos intervalos ao XEX correto.
Não usam arquivos reais do jogo e não substituem teste em aparelho.

O importador rejeita caminhos fora do destino, colisões de nomes e importações com
mais de 100.000 entradas, 24 GiB extraídos ou 12 GiB em um arquivo. No ZIP, os
limites usam o tamanho realmente extraído; na ISO, os tamanhos são conferidos
com os limites da imagem antes da extração. Os limites adicionais do parser e
o estado dos testes estão em [Importação de ISO](docs/iso-import.md).

O [relatório de análise](docs/xex-analysis.md) registra os resultados e os
pontos ainda não verificados. Compilação, assinatura e inspeção de bibliotecas
não comprovam jogabilidade. A próxima validação depende da cópia completa do
jogo, do aparelho e dos logs de inicialização.

## Fontes e licenças

Runtime baseado no [SDK Android de Asura's Wrath](https://github.com/PauloUbirajara/asura-wrath-rexglue-sdk),
fixado no commit `38b40e7424e5d3ce1afa38dca10e9a84b1aa2317`, e geração pelo
[ReXGlue](https://github.com/rexglue/rexglue-sdk/releases/tag/v0.10.0).
SDL Java acompanha a mesma revisão usada pelo runtime. Os avisos das
dependências acompanham o APK; os pins, o patch e os comandos para reconstruir
o runtime estão disponíveis neste repositório.

FFmpeg e libmspack são incorporados ao runtime sob LGPL 2.1 ou posterior. A
árvore pública de fontes correspondente, incluindo as dependências e a alteração
local, está no artefato separado `MK-Experimental-Runtime-Sources`, como
`runtime-sources.tar.xz`, e está em
`native/prebuilt/`. Para reconstruir o runtime **sem XEX**, execute:

```bash
python3 tools/bootstrap_native.py
bash tools/build_runtime.sh
```

Para usar uma biblioteca compatível modificada, prepare uma vez os arquivos
com `prepare_prebuilt_runtime.py`, substitua `librexruntime.so` e
`librexgpu-xenos.so` em `out/native/jniLibs/arm64-v8a/` pelas bibliotecas da sua
build, e execute `bash tools/build_apk.sh --native`. Esse empacotamento aceita
as bibliotecas alteradas; não execute novamente a preparação dos prebuilt após
substituí-las. Não é necessário recompilar o código do jogo para substituir o
runtime mantendo sua ABI.
