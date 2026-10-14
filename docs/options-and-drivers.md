# Menu e drivers gráficos · 0.1.11

Abra **OPÇÕES** no menu principal. As configurações são persistentes e aplicadas
ao iniciar o jogo; volte ao launcher para alterar o driver entre tentativas.

| Opção | Efeito |
| --- | --- |
| Preencher a tela | Desativa as barras de proporção do presenter. |
| Resolução interna | 1× usa a resolução original; 2× aumenta detalhe e carga da GPU. |
| Sincronização vertical | Ligada usa apresentação FIFO; desligada permite outros modos disponíveis. |
| Registro de arquivos | Ativa o diagnóstico limitado de arquivos/vídeos da inicialização. |
| Restaurar opções | Volta aos padrões, incluindo o driver do aparelho. |

Para escolher um driver:

1. Use **Drivers personalizados → Importar driver ZIP**.
2. Selecione um pacote AdrenoTools/Turnip ARM64 compatível com sua GPU/Android.
3. A biblioteca e os metadados são validados e copiados para uma pasta privada.
4. Em **Driver gráfico**, escolha o pacote ou **Padrão do aparelho**.
5. Inicie o jogo. Consulte **Log de inicialização** para o pedido do loader e
   os campos Vulkan `driverName`/`driverInfo` do dispositivo criado.

O ZIP precisa conter `meta.json` e a biblioteca indicada por `libName` ou
`libraryName`. Metadados e bibliotecas em subpastas são aceitos se o caminho
declarado puder ser resolvido sem ambiguidade. `minAPI`/`minApi`, quando presente,
deve ser compatível com o Android instalado. A importação não executa o driver.

São recusados caminhos externos, links simbólicos, entradas especiais, CRCs
inválidos, arquivos duplicados, JSON inválido e bibliotecas de outra arquitetura.
Limites padrão: ZIP de até 64 MiB, até 128 MiB extraídos e 100 entradas.
Falha ou cancelamento preservam os outros drivers. Remover um driver não remove
arquivos de Mortal Kombat; desinstalar o APK remove os dados privados.

O loader utiliza a API documentada do
[AdrenoTools](https://github.com/bylaws/libadrenotools/tree/8fae8ce254dfc1344527e05301e43f37dea2df80),
incluindo hooks extraídos do APK, diretórios privados e nomes de biblioteca
derivados do catálogo validado. A escolha padrão também é enviada explicitamente,
para não reutilizar uma seleção customizada salva em um TOML antigo do runtime.

Os hooks podem retornar um loader válido e usar internamente o driver do sistema.
Por isso, o log de preparação do loader e a presença da biblioteca no mapa de
memória não são apresentados como prova de driver ativo. Confira as propriedades
Vulkan. Falhas na preparação ou na criação da instância tentam o sistema uma vez;
rejeição de recursos durante a criação posterior do dispositivo ainda pode
impedir a inicialização.

O carregamento real de um driver no Adreno 825, a aparência no F7 e a jogabilidade
permanecem pendentes de teste físico. Não é necessário usar driver customizado
para testar: **Padrão do aparelho** conserva o caminho original.
