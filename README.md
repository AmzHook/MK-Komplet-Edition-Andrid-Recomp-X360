# MK · Importador Android

APK para importar um ZIP com a pasta extraída de uma cópia de Mortal Kombat
Komplete Edition para Xbox 360. **Esta versão não executa o jogo.** Não contém
runtime ReXGlue, código recompilado de MK ou arquivos do jogo.

## Instalação e uso

1. Instale `out/apk/MK-Importador-0.1.0.apk` no Android 8.0 ou mais recente.
2. Toque em **Importar ZIP** e selecione um ZIP com exatamente um `default.xex`
   e as demais pastas do jogo. Uma pasta externa envolvendo esses arquivos é aceita.
3. Mantenha o aplicativo aberto durante a importação. Os arquivos são extraídos
   no armazenamento privado do aplicativo; o ZIP original é preservado.
4. **Detalhes da importação** mostra o caminho do XEX e seu SHA-256.

O APK verifica apenas o cabeçalho `XEX2`, não a identidade do jogo nem a
compatibilidade do executável. Um ZIP só com o XEX não contém todos os dados
necessários para uma futura integração do runtime. Não há botão de execução
nesta versão. Nenhum arquivo é enviado pela rede e não é necessária permissão
de acesso amplo ao armazenamento. Desinstalar o app apaga a cópia importada.

Uma substituição é feita em uma pasta temporária e só passa a ser a cópia atual
após a validação. Falhas e cancelamento preservam uma importação anterior.
O importador rejeita caminhos fora da pasta de destino, caminhos duplicados,
cabeçalho inválido e ZIPs com mais de 100.000 entradas, 24 GiB extraídos ou
12 GiB em um único arquivo. Esses limites usam o tamanho realmente extraído.

## Compilação no ambiente Linux

As ferramentas são instaladas fora do repositório, em
`/workspace/.tools/mk-android` (ou `MK_ANDROID_TOOLS`). Downloads usam HTTPS e
os checksums publicados nas fontes oficiais. Dependências: Python 3, Temurin
JDK 21.0.8+9, Android SDK Platform 35 e Build Tools 35.0.0. Não é necessário
Gradle ou Android Studio para esta compilação Java.

```bash
cd /workspace/MK-Komplet-Edition-Andrid-Recomp-X360
python3 tools/bootstrap_android.py
bash tools/test_importer.sh
bash tools/build_apk.sh
```

Saída: `out/apk/MK-Importador-0.1.0.apk`, com checksum ao lado. Assinatura local
de desenvolvimento: a chave é gerada fora do repositório e reutilizada nos
rebuilds. Essa assinatura não é apropriada para publicar na Play Store.

Os testes usam ZIPs sintéticos, sem dados do jogo: extração, integridade,
substituição, recuperação, cancelamento, limites e rejeição de caminhos
maliciosos. Não substituem o teste de instalação e importação em aparelho.

## APK pelo GitHub Actions

O workflow **Build MK Importador APK** compila e testa a versão atual a cada
push em `main`, em pull requests ou por execução manual. Abra
[Actions](https://github.com/AmzHook/MK-Komplet-Edition-Andrid-Recomp-X360/actions/workflows/build-apk.yml),
selecione uma execução concluída com sucesso e baixe o artefato
`MK-Importador-0.1.0`. Ele contém o APK e seu checksum SHA-256; o download
de artefatos pode exigir login no GitHub. O artefato fica disponível por 30 dias.

Essa compilação ainda é **somente do importador, não de um MK jogável**.
Nenhuma ISO, XEX ou chave de assinatura é armazenada no repositório.
A assinatura de desenvolvimento é gerada novamente em cada runner: instalar
um APK sobre outro com assinatura diferente pode exigir desinstalação.
Desinstalar remove os arquivos importados; preserve seu ZIP original.

Após extrair o artefato, o checksum pode ser conferido no mesmo diretório:

```bash
sha256sum -c MK-Importador-0.1.0.apk.sha256
```

## Próxima etapa para executar MK

Integrar um runtime e código gerado especificamente a partir da versão do XEX
do MK escolhida para o projeto. O importador sozinho não traduz instruções
PowerPC nem emula um Xbox 360. Os projetos Asura's Wrath e Hell's Gate são
referências de arquitetura; nenhum código deles foi incorporado neste APK.
