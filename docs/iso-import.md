# Importação de ISO Xbox 360 · versão 0.1.6

O APK 0.1.6 aceita uma ISO Xbox 360 diretamente pelo botão **Importar ZIP ou
ISO**. Ele extrai os arquivos da partição do jogo para o armazenamento privado
do aplicativo e usa a mesma instalação, validação do XEX e substituição de
cópia do importador de ZIP.

O recurso foi validado com imagens sintéticas. Nenhuma ISO real de Mortal
Kombat Komplete Edition foi fornecida para este teste. A importação dessa cópia
e o comportamento dos provedores de arquivos no Poco F7 ainda precisam ser
verificados. Carregamento completo, partidas, áudio e desempenho do runtime
continuam sem validação. A versão 0.1.6 mantém os 21 intervalos de funções
nativas da versão 0.1.5.

## Como importar

1. Instale `MK-Experimental-0.1.6.apk` e abra o aplicativo.
2. Toque em **Importar ZIP ou ISO** e selecione a ISO da sua cópia Xbox 360.
3. Mantenha o aplicativo aberto durante a preparação e a extração. O botão
   **Cancelar importação** interrompe a operação; uma tentativa que falha ou é
   cancelada mantém a cópia anterior.
4. Após a importação, confira os detalhes do executável. **Testar inicialização
   do jogo** só fica disponível se o SHA-256 do XEX corresponder ao usado na
   build nativa. Os dados dessa versão estão no [README](../README.md).
5. Use um controle físico compatível para o teste; o aplicativo ainda não
   oferece controles de toque. Após uma tentativa, consulte **Ver log de
   inicialização**, reabrindo o aplicativo se o processo nativo fechar.

A ISO deve conter exatamente um `default.xex` e os arquivos do jogo. A extração
não transforma outro executável em uma versão compatível. Importar só um XEX
também não fornece as texturas, vídeos, configurações e demais dados necessários
ao jogo. ZIPs com a cópia já extraída continuam aceitos pelo mesmo botão.

## Formatos aceitos

O leitor reconhece XDVDFS em imagens com setores de 2048 bytes. Procura o
descritor do volume no setor 32 em relação a cada uma destas bases:

| Tipo de imagem | Base da partição do jogo |
| --- | --- |
| Apenas a partição XDVDFS do jogo, também chamada XISO | `0x00000000` |
| Imagem Xbox 360 XGD2 | `0x0FD90000` |
| Imagem Xbox 360 XGD3 | `0x02080000` |

Esses offsets são candidatos de detecção: o descritor precisa conter a
assinatura `MICROSOFT*XBOX*MEDIA` no início e no fim do setor, e as tabelas e
arquivos precisam estar dentro dos limites da imagem. Ter a extensão `.iso`
não basta para ser reconhecido.

Imagens raw de 2352 bytes por setor, ISO 9660 sem XDVDFS, imagens comprimidas,
pacotes Games on Demand (GOD) e imagens divididas em partes não são aceitos.
O leitor extrai os arquivos da partição do jogo; não copia o preenchimento nem
as outras áreas do disco para a pasta instalada.

## Leitura e espaço necessário

O seletor de arquivos do Android concede acesso apenas ao documento escolhido.
Quando o documento permite leitura por posição, o aplicativo usa um
`FileChannel` e lê a ISO diretamente, sem copiar a imagem inteira para o cache.

Alguns provedores oferecem somente um fluxo sequencial. Nesse caso, aparece
**Preparando ISO para importação…** e o aplicativo cria uma cópia temporária
para permitir a leitura por posição. Essa cópia tem limite de 24 GiB e pode ser
cancelada. O aplicativo tenta removê-la ao concluir, falhar ou cancelar a
operação; uma nova abertura também tenta limpar temporários restantes.

Reserve espaço para o arquivo original e os arquivos extraídos. Se o provedor
exigir a preparação temporária, reserve também o tamanho da ISO para essa
cópia. Ao substituir uma instalação, a cópia anterior permanece no aparelho
até a nova ser validada. A preparação e a extração mantêm uma reserva de
64 MiB de espaço livre. O original é preservado; desinstalar o aplicativo
remove a cópia extraída em seu armazenamento privado.

Salvar a ISO localmente em um provedor que permita leitura por posição pode
evitar a cópia temporária. A disponibilidade dessa leitura depende do provedor,
e não apenas do nome ou da extensão do arquivo.

## Validação e limites do leitor

O [leitor XDVDFS](../android/src/org/amzhook/mkimporter/IsoImporter.java)
percorre as árvores de diretórios antes de extrair. Confere os endereços e
tamanhos com o comprimento da imagem, os índices e a sobreposição dos registros,
os ciclos, os caminhos e o espaço disponível. Os nomes usam Windows-1252;
diretórios vazios e tabelas preenchidas com zeros ou `FF` são tratados como
vazios. Arquivos e diretórios irmãos podem compartilhar os mesmos dados na
imagem, mas uma pasta não pode referenciar uma tabela de seus ancestrais.

Os limites abaixo são políticas deste aplicativo, não limites gerais do
formato XDVDFS:

| Recurso | Limite |
| --- | --- |
| Entradas de arquivos e diretórios | 100.000 |
| Tamanho total extraído | 24 GiB |
| Tamanho por arquivo no importador compartilhado | 12 GiB |
| Tabela de um diretório | 32 MiB |
| Profundidade de diretórios | 128 níveis |
| Comprimento de um caminho | 4096 caracteres |
| Soma dos comprimentos dos caminhos | 16.777.216 caracteres |
| Leitura de metadados | 64 MiB e 1.000.000 leituras |
| Cópia temporária de uma ISO sequencial | 24 GiB |

Nomes inválidos, colisões de caminhos sem distinguir maiúsculas de minúsculas,
índices repetidos, dados fora da imagem e caminhos que sairiam do destino são
rejeitados. O limite próprio de tamanho dos campos XDVDFS também se aplica.

Os arquivos são preparados em `game.staging`. A
[validação compartilhada](../android/src/org/amzhook/mkimporter/ZipImporter.java)
exige um único executável com cabeçalho `XEX2`, calcula seu SHA-256 e grava os
metadados. Somente depois substitui a pasta `game`; a recuperação usa
`game.backup` se uma troca for interrompida. O botão de inicialização mantém a
verificação do hash esperado do runtime.

A ISO não oferece o CRC por arquivo usado no ZIP. As verificações do leitor
detectam problemas estruturais e conferem o executável, mas não comprovam a
integridade de cada textura, vídeo ou outro arquivo do jogo.

## Testes realizados

A suíte Java sintética passou com **97 testes: 56 de ISO, 23 de ZIP e 18 de
roteamento da fonte**. Os testes cobrem os três offsets de partição, árvores de
diretórios, nomes Windows-1252, diretórios vazios, aliases, ciclos, imagens
truncadas, limites, cancelamento e preservação da instalação anterior.

Os testes do
[adaptador de fontes](../tests/GameImporterTest.java) incluem leitura direta por
`FileChannel`, arquivo esparso com dados além de 4 GiB, preparação a partir de
fluxo sequencial, limpeza do cache, falhas de leitura, manutenção da propriedade
do fluxo pelo chamador e equivalência de conteúdo e metadados entre ZIP e ISO.
Também cobrem recuperação e remoção durante uma importação pausada, incluindo
cancelamento: essas operações aguardam o término da importação e de sua limpeza
antes de modificar as pastas compartilhadas.
Podem ser executados com `bash tools/test_importer.sh` após instalar as
ferramentas Android do projeto.

Esses testes não usam os arquivos comerciais do jogo e não executam o runtime.
Também não validam o comportamento real do seletor de documentos, dos
provedores de armazenamento ou do ciclo de vida da Activity em Android.

## Referências do formato

As referências abaixo são implementações públicas do formato, não uma
especificação oficial da Microsoft:

- [XboxDev/extract-xiso](https://github.com/XboxDev/extract-xiso/blob/master/extract-xiso.c)
  registra os offsets XGD2 e XGD3, setores de 2048 bytes e leitura das árvores
  de diretórios.
- [Xenia: disc_image_device.cc](https://github.com/xenia-project/xenia/blob/master/src/xenia/vfs/devices/disc_image_device.cc)
  usa os mesmos offsets conhecidos, o descritor no setor 32 e os índices
  relativos de diretório multiplicados por quatro.
- [xdvdfs: volume.rs](https://github.com/antangelo/xdvdfs/blob/master/xdvdfs-core/src/layout/volume.rs)
  descreve o setor de volume, as duas assinaturas e os campos da tabela raiz.
- [xdvdfs: dirent_node.rs](https://github.com/antangelo/xdvdfs/blob/master/xdvdfs-core/src/layout/dirent_node.rs)
  e [name.rs](https://github.com/antangelo/xdvdfs/blob/master/xdvdfs-core/src/layout/name.rs)
  documentam o cabeçalho de 14 bytes, entradas vazias e nomes Windows-1252.
- [xdvdfs: dirent_table.rs](https://github.com/antangelo/xdvdfs/blob/master/xdvdfs-core/src/read/dirent_table.rs)
  e [dirtab.rs](https://github.com/antangelo/xdvdfs/blob/master/xdvdfs-core/src/write/dirtab.rs)
  mostram os índices da árvore, o alinhamento e os registros que não cruzam
  setores.
