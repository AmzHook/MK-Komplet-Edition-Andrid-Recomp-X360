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

## Atualização experimental 0.1.7

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

**A versão 0.1.7 ainda precisa de teste no Poco F7; carregamento completo e
jogabilidade continuam sem validação.** Registrar o método não comprova a validade do
ponteiro interno armazenado em `this+0x174`, que precisa ser observado no
próximo teste.

## Atualização anterior 0.1.6

Adiciona a importação direta de ISO Xbox 360 com uma partição XDVDFS em setores
de 2048 bytes. O botão **Importar ZIP ou ISO** extrai os arquivos da partição do
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

1. Instale `MK-Experimental-0.1.7.apk` em Android 9 ou mais recente, ARM64 e Vulkan.
2. Toque em **Importar ZIP ou ISO**. Selecione a ISO Xbox 360 da sua cópia do
   jogo ou um ZIP contendo exatamente um `default.xex` e todas as demais pastas
   do jogo. No ZIP, uma pasta externa envolvendo esses arquivos é aceita.
   Somente o XEX não fornece os dados necessários ao jogo.
3. Mantenha o aplicativo aberto até terminar a importação. Se aparecer
   **Preparando ISO para importação…**, aguarde a cópia temporária e a extração.
   **Cancelar importação** interrompe a operação e mantém a cópia anterior.
4. Toque em **Testar inicialização do jogo**. Esta versão usa a entrada SDL e
   requer um controle físico compatível; ainda não possui controles de toque.
5. Depois de uma tentativa, **Ver log de inicialização** permite ler e copiar o
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
Baixe o artefato `MK-Experimental-0.1.7` de uma execução concluída com sucesso.
O workflow **Build MK Importador APK** produz a variante de importação.
O workflow experimental está habilitado para testes no aparelho. A publicação
da variante de importação é opcional e exige a variável de repositório
`MK_PUBLISH_IMPORTER_ONLY=true`. Um build bem-sucedido não comprova jogabilidade.

O job experimental **empacota bibliotecas pré-compiladas**; ele não recompila um
XEX no runner. XEX, ISO e fontes C++ geradas do jogo ficam fora do Git. Os
artefatos incluem APK e checksum, ficam disponíveis por 30 dias e seu download
pode exigir login no GitHub. Após extrair, confira:

```bash
sha256sum -c MK-Experimental-0.1.7.apk.sha256
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
python3 tools/prepare_prebuilt_runtime.py
bash tools/build_apk.sh --native
```

Saída: `out/apk/MK-Experimental-0.1.7.apk`. Para compilar apenas o importador,
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
