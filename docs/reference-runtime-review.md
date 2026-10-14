# Comparação de perfis e inicialização dos ports Android

Revisão de código realizada em 14 de outubro de 2026, com o MK experimental
0.1.9 como referência. O usuário confirmou que essa versão ainda fecha na
logo. Sem o log dessa tentativa, não podemos identificar a nova causa nem
afirmar que a seleção de perfil foi alcançada.

## Código conferido

Os resultados se referem aos snapshots abaixo, não a todos os APKs publicados
por esses projetos. A revisão não executou os jogos de referência no aparelho.

| Projeto | Snapshot | Resultado relevante |
| --- | --- | --- |
| [SDK usado pelo MK](https://github.com/PauloUbirajara/asura-wrath-rexglue-sdk/tree/38b40e7424e5d3ce1afa38dca10e9a84b1aa2317) | `38b40e7` | Perfil offline e conclusão do diálogo de login já disponíveis. |
| [SDK do Asura](https://github.com/PauloUbirajara/asura-wrath-rexglue-sdk/tree/396c126c447313024412a77bcf86aef7d2f7dfbf) | `396c126` | `xam_user.cpp`, `xam_ui.cpp` e `user_profile.cpp` idênticos aos do SDK usado pelo MK. |
| [Shiftglue SDK](https://github.com/deivid22srk/shiftglue-sdk/tree/2db68133de6ec609c275fd8707316a240dd980e5) | `2db6813` | Mesma lógica de login offline; acrescenta um provedor de diálogos do aplicativo. |
| [Pinyon](https://github.com/deivid22srk/pinyon-shift-android/tree/dca4da9327bb695561b62011e7007830c44049cc) | `dca4da9` | Caixas de mensagem e teclado próprios, preservando notificações e conclusão assíncrona. |
| [Hell's Gate](https://github.com/deivid22srk/hells-gate-recomp-android/tree/0a79f938ab839c187d25bd7bf48e51e30a6150be) | `0a79f93` | Hooks de Dante's Inferno; o patch de busca de usuários depende de estruturas desse título. Esse repositório não é DMC 4. |
| [Forza Horizon 2](https://github.com/gerisson1996/ForzaHorizon2Recomp-Android-RexGlue/tree/ffab1cc36823a98686b3b83028f493f64376d834) | `ffab1cc` | Infraestrutura Android e carregamento de módulos; o README ainda declara execução completa em desenvolvimento. |
| [NFS III](https://github.com/ZloiKILLER/nfs3recompiled-android/tree/ddd49951e36393f5297b848d5e5f2a22bacf1a29) | `ddd4995` | Port da versão PC de 1998, baseado em recompilação x86; não é um runtime Xbox 360/ReXGlue. |

## O perfil offline já está conectado

No SDK atual, `KernelState` cria um `UserProfile` para o jogador 0.
`UserProfile::signin_state()` retorna `1`, que representa conexão local.
O nome padrão é `User`; não é necessário autenticar uma conta Xbox Live para
que essas APIs retornem um perfil local. Isso não fornece serviços online.

[`XamShowSigninUI_entry`](https://github.com/PauloUbirajara/asura-wrath-rexglue-sdk/blob/38b40e7424e5d3ce1afa38dca10e9a84b1aa2317/src/kernel/xam/xam_user.cpp#L478)
já retorna sucesso sem abrir o diálogo de login. Antes de retornar, publica
`XN_SYS_SIGNINCHANGED(10, 1)` e `XN_SYS_UI(9, 0)`.
`KernelState::RegisterNotifyListener` também entrega notificações iniciais de
perfil, interface e controles ao primeiro listener do sistema.

A análise do executável correspondente ao SHA-256 documentado em
[xex-analysis.md](xex-analysis.md) identificou o wrapper `0x82BDA438` e três
chamadores: `0x824F3AF0`, `0x8250EA70` e `0x82AA4538`. O último aguarda
`XN_SYS_UI(9, 0)` e depois consulta o estado do perfil. Um hook que simplesmente
retornasse sucesso sem publicar a notificação poderia deixar esse caminho
esperando indefinidamente. As importações de login e de consulta ao perfil já
possuem registros no código gerado.

## O que podemos reutilizar quando houver um diálogo bloqueando

O provedor de interface do Shiftglue e os diálogos do Pinyon são referências
para substituir mensagens e teclados do sistema por uma interface própria.
É necessário preservar as notificações de abertura/fechamento e a conclusão
das operações assíncronas. Essas mudanças não removem telas desenhadas pelo
próprio MK nem geram corpos de funções PowerPC ausentes.

O SDK atual também oferece `--headless=true`. Para `XamShowMessageBoxUI`,
seleciona automaticamente o botão focado e conclui a operação; para o teclado,
usa o texto padrão. O nome dessa opção não desliga a renderização Vulkan.
O botão focado pode ser "Cancelar", por isso a opção não foi ativada como
suposta correção para um fechamento na logo. `GameActivity.java` mantém os
argumentos atuais.

Há uma lacuna adicional: `XamShowMessageBoxUIEx_entry` retorna sucesso sem
preencher o resultado ou concluir o `overlapped`. O mesmo stub aparece nas
referências comparadas. MK contém uma chamada por `0x8236E220`, em
`0x8236E2E8`, mas nenhum log fornecido comprova que essa chamada foi atingida.
Uma implementação precisa confirmar a assinatura específica de `UIEx` antes
de adaptar o caminho normal; não podemos supor que as duas APIs recebem os
mesmos argumentos.

Os SDKs e o Pinyon usam BSD de três cláusulas; qualquer código incorporado
deve manter os avisos aplicáveis. O checkout do Hell's Gate não tem uma licença
própria de aplicativo explícita, portanto seus hooks não foram copiados.

## Pular os vídeos de abertura também exige um caminho do MK

O [hook de abertura do Pinyon](https://github.com/deivid22srk/pinyon-shift-android/blob/dca4da9327bb695561b62011e7007830c44049cc/src/pinyon_shift_runtime_hooks.cpp#L555)
identifica vídeos `Splash_Intros/*.wmv` e devolve o resultado normal de fim
de reprodução `0x16660026` em uma função específica desse jogo. Assim, o fluxo
normal de eventos de conclusão continua. O endereço desse hook não identifica
a mesma função no MK.

No executável do MK foram encontrados literais `MwyMoviePlayer`,
`MwyMoviePlayer_AsyncFIO_Thread`, `MOVIES/` e `xbmovie`. As buscas por literais
como `NoMovies`, `NoIntro`, `nostartupmovies` e `skipintro` não encontraram
correspondências. Isso não prova que toda opção de pular vídeo inexiste; não
oferece, porém, evidência para ativar uma opção genérica da Unreal como correção.
Seria necessário identificar o player efetivamente chamado e sua forma normal
de terminar a reprodução.

Uma ferramenta de diagnóstico que pode ser adaptada é
[`GuestFileOpenObserver`](https://github.com/deivid22srk/shiftglue-sdk/blob/2db68133de6ec609c275fd8707316a240dd980e5/include/rex/kernel/xboxkrnl/io.h#L7)
do Shiftglue: permite observar os nomes de arquivos solicitados pelo jogo,
inclusive vídeos. Esse observador não estava incorporado na 0.1.9; a 0.1.10
adapta sua API genérica e limita o log. Ele traça tentativas de abertura, sem
implementar os corpos de funções ausentes ou confirmar o sucesso da leitura.

O NFS III permite interromper seu próprio loop de reprodução de filmes, mas
seu código é da versão PC e a licença declara "ALL RIGHTS RESERVED — NO LICENSE
GRANTED". Nenhum código desse projeto foi incorporado.

## Próximo diagnóstico do fechamento na logo

Os logs até a 0.1.8 encerraram com chamadas a funções do jogo sem registro.
Isso difere de uma recusa de login. A 0.1.9 inclui os 70 intervalos manuais
verificados, mas o relato de fechamento não identifica a função ou o erro
seguinte. A última linha `[FATAL]` da 0.1.9, com endereço e LR, permite conferir
o destino no executável e implementar uma correção fundamentada.

Esta revisão inicial não alterou o runtime e não estabeleceu jogabilidade. As mudanças
de código de um título de referência precisam ser avaliadas contra os caminhos
reais do MK; copiar seus endereços ou substituir funções por retornos de
sucesso não demonstra que o jogo funcionará.

## Pesquisa e implementação da 0.1.10

O link [Reddit fornecido pelo usuário](https://www.reddit.com/r/AndroidNativePorts/s/waCXd9Mmk4)
foi bloqueado pela política de rede do ambiente. O usuário colou um trecho dos
comentários sobre SDL e áudio consumirem CPU enquanto esperavam sem trabalho.
A análise usa esse trecho; não atribui autoria nem afirma ter lido o restante
do post. A pesquisa no GitHub identificou implementações aplicáveis:

- [Shiftglue 26824c6](https://github.com/deivid22srk/shiftglue-sdk/commit/26824c612a829c6ef7dc3baa507a4e1ce4a52222):
  `SDL_EVENT_POLL_SENTINEL` acordava continuamente a própria fila no Android.
  O contexto do MK também usa `SDL_WaitEvent` e o mesmo SDL fixado em
  `8bf3b7215ad9fc3deb583c6a3a37c6c67f2e24e4`. Desativar somente esse evento
  interno preserva os eventos reais e os wakeups explícitos do aplicativo.
- [Shiftglue 0d85db4](https://github.com/deivid22srk/shiftglue-sdk/commit/0d85db454648df3838265d1b53b803df7a1af4b6):
  `WaitMultiple` arredondava o tempo restante para milissegundos inteiros,
  causando `sleep_for(0)` quando restava menos de 1 ms. O áudio do MK usa
  `WaitAny` alertável, cujas fatias de espera são justamente de 1 ms.
  A adaptação conserva o restante preciso e o limite de pausa de 1 ms.

Os dois commits são de 1º de outubro de 2026, por Brandon G. Neri (`arcanite24`).
Foram adaptados somente os trechos genéricos em
`native/patches/android-idle-waits.patch`, mantendo os avisos BSD. Esses ajustes
de trabalho ocioso de CPU não corrigem por si só chamadas PowerPC ausentes.
Não usamos limites de FPS ou hooks do renderer específico de reBlue.

Para essas chamadas, a [issue oficial ReXGlue #363](https://github.com/rexglue/rexglue-sdk/issues/363)
mostra a mesma sequência de compilação bem-sucedida seguida por erros em
pequenos thunks. A [documentação de chamadas não resolvidas](https://github.com/rexglue/rexglue-sdk/wiki/Generated-Code-Structure#unresolved-calls)
orienta informar endereço e tamanho exatos de funções reais. A
[issue #117](https://github.com/rexglue/rexglue-sdk/issues/117) confirma que essa
etapa manual é esperada. A [issue #359](https://github.com/rexglue/rexglue-sdk/issues/359)
também mostra por que o erro isolado não basta: destinos podem ser labels de
uma tabela de saltos, que não devem virar novas funções.

Os seis arquivos de descoberta/emissão examinados no HEAD oficial
`c94f5ebdcb3c9d1a460ca48e04f9758448f8d518` eram idênticos aos do SDK usado pelo
MK. Não havia nesses arquivos uma correção nova que justificasse atualizar
todo o SDK. A auditoria do próprio executável recuperou outros 20 corpos
originais em tabelas realmente instaladas, incluindo o proxy `0x82B4CBC8`
associado aos comandos de vídeo. Uma revisão independente conferiu seus
limites e a ordem dos argumentos. Os intervalos constam em
[mk9_functions.toml](../native/mk9_functions.toml); nenhum desses novos
endereços foi observado em um fatal do aparelho até esta revisão.

A 0.1.10 incorpora essas três mudanças: esperas corrigidas, corpos originais
recuperados e observação limitada de arquivos. A execução física, a temperatura
e a jogabilidade no Poco F7 permanecem sem validação.
