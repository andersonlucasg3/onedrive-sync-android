# OneDrive Sync

[![Release](https://github.com/andersonlucasg3/onedrive-sync-android/actions/workflows/release.yml/badge.svg)](https://github.com/andersonlucasg3/onedrive-sync-android/actions/workflows/release.yml)

Sincronização **bidirecional** entre o OneDrive **pessoal** e pastas quaisquer do celular.
App Android nativo (Kotlin + Jetpack Compose), distribuído como APK por sideload, com foco em
HyperOS/MIUI — onde o gerenciamento de bateria e o autostart costumam quebrar sincronização em
segundo plano.

## O que faz

- **Download**: itens novos ou alterados no OneDrive chegam às pastas locais do par.
- **Upload**: arquivos novos ou alterados no celular sobem para o OneDrive.
- **Mover/renomear nos dois sentidos**: um `PATCH` remoto que muda `name`/`parentReference` vira um
  move local sem re-download; um move local (mesmo conteúdo em outro caminho) vira `PATCH` remoto.
- **Conflitos**: quando os dois lados mudaram, nada é sobrescrito — a operação fica pendente e o
  usuário decide na tela de conflitos (manter local, manter remoto ou os dois).
- **Exclusões com confirmação**: remoções dos dois lados entram numa fila de pendências; nada é
  apagado sem aprovação explícita.
- **Sync em background**: WorkManager periódico (a cada 15 min, com rede) + sync manual em
  foreground service com progresso na notificação.
- **Auto-update pelo GitHub**: consulta a última release, baixa o APK e abre o instalador do
  Android (a instalação sempre depende de confirmação do usuário).

## Requisitos

- Android **11+** (API 30; `targetSdk` 36, `compileSdk` 37).
- Conta **OneDrive pessoal** (Microsoft account). Contas corporativas não são o alvo.
- Permissão **"acesso a todos os arquivos"** (`MANAGE_EXTERNAL_STORAGE`), necessária para ler e
  escrever em pastas arbitrárias como `DCIM` ou `Pictures/AlbumX`.
- Um registro de app próprio no Entra ID é **opcional** (veja abaixo) — o APK já vem com um Client
  ID embutido, de propriedade do autor do projeto.

## Instalação

1. Baixe o APK mais recente na aba [Releases](https://github.com/andersonlucasg3/onedrive-sync-android/releases).
   O asset se chama `OneDriveSync-<versão>.apk`.
2. Abra o arquivo baixado e permita **instalar apps de fontes desconhecidas** para o app de
   arquivos/navegador que fez o download.
3. Ao abrir, o onboarding lista as permissões necessárias (arquivos, notificações, bateria,
   autostart no MIUI/HyperOS) com o status real de cada uma e um botão para abrir a tela
   correspondente.

Atualizações usam a mesma assinatura de todos os releases — sem isso o Android recusa o update.
O app também checa atualizações sozinho (1x/dia) e no botão "Buscar atualização" em Configurações.

## Configuração

1. (Opcional) Registre um app próprio no Entra ID seguindo [`docs/APP-REGISTRATION.md`](docs/APP-REGISTRATION.md)
   (login Authorization Code + PKCE no WebView, device code como alternativa; permissão delegada
   `Files.ReadWrite.All`).
2. Toque em **Entrar com Microsoft** e conclua o login na tela do app. Se a Microsoft bloquear o
   navegador embutido, use **Entrar com código**: o app mostra um código de 8 caracteres para
   digitar em <https://microsoft.com/devicelogin>.
3. Crie um par em **Pares → Novo par**: escolha a pasta remota (navegando pelo OneDrive) e a pasta
   local, confirme e sincronize.

## Como funciona (resumo técnico)

- **Delta da raiz do drive**: cada ciclo percorre `GET /me/drive/root/delta` até o `@odata.deltaLink`
  e guarda o link em `state`. O caminho de cada item é reconstruído pela cadeia de
  `parentReference.id`, porque `parentReference.path` pode vir ausente e renomear pasta não retorna
  descendentes.
- **QuickXorHash**: hash nativo do OneDrive, calculado localmente (implementação própria, validada
  contra vetores conhecidos) para comparar conteúdo sem baixar nada.
- **Índice local SQLite**: `onedrive.db` guarda pares, entries (com hashes/etags remotos), fila de
  `pending` e conflitos. É o que permite detectar mudança real em vez de só tamanho/mtime.
- **Escrita atômica**: download sempre em `<arquivo>.part` + `renameTo`; upload simples
  (`PUT .../content`) até 4 MiB e, acima disso, upload session em chunks de 10 MiB.
- **Sem ADB, sem root, sem device owner**: não há instalador silencioso nem acesso privilegiado;
  o app usa `java.io.File` com "acesso a todos os arquivos" e `FileProvider` para o instalador.

Detalhes de arquitetura, schema e chamadas Graph: [`docs/ARCH.md`](docs/ARCH.md).

## Limites conhecidos

- Esta versão não foi validada em aparelho físico (sem ADB/device no ambiente de desenvolvimento):
  o que se verifica é build, testes JVM e assinatura do APK.
- Exclusão **sempre** exige confirmação; não existe modo "espelhar e apagar sozinho".
- Conflitos **não** são resolvidos automaticamente — ficam pendentes até o usuário decidir.
- Pasta movida/renomeada localmente aparece como exclusão + upload (o índice local não identifica
  move de diretório com a mesma confiança que o delta remoto).
- O cursor de delta é único por drive: ao adicionar um par novo, o ciclo seguinte reenumera o drive
  inteiro (necessário para o par nascer com conteúdo). Numa janela rara — exclusão remota de outro
  par ocorrida exatamente entre os dois ciclos — o tombstone pode ser pulado e o arquivo voltar a
  subir em vez de pedir confirmação de exclusão.
- Enumeração limitada a 1000 páginas de delta; acima disso o ciclo **não aplica nenhuma ação** e
  registra `ERROR` no log (evita planejar sobre visão parcial do drive).
- Android sempre pede confirmação na tela do instalador ao aplicar um update; não há instalação
  silenciosa.
- R8 (`minify` + `shrinkResources`) está ligado no release: o APK cai de ~48 MB para ~3,5 MB (a
  `material-icons-extended` sozinha eram ~11 MB, e nenhum ícone dela é usado). Sem device para
  executar o APK minificado, a garantia local é a suíte JVM + `scripts\verify-release-apk.ps1`
  (classes críticas presentes no dex) — não é smoke test de execução.
- Aviso de update ignora pré-releases: a release `vX.Y.Z-beta.N` é publicada como pré-release e o
  endpoint `/releases/latest` não a retorna — de propósito.

## Diagnóstico (sem ADB)

O app foi feito para ser depurado sem cabo:

- **Ajustes → Diagnóstico**: versão, conta, pares, último sync, tamanho do log, **Testar conexão**
  (mostra o erro cru do Graph), **Compartilhar logs** e **Forçar sincronização completa**.
- **Atividade → ícone de compartilhar**: exporta o log completo (o filtro da tela não se aplica)
  com cabeçalho de versão, aparelho, Android, permissões e estado de autenticação — **sem tokens**
  (o Client ID aparece mascarado).
- **Crash**: o stacktrace é gravado em `filesDir/crash/`, entra no log na próxima abertura e, se o
  banco local estiver corrompido, a tela de erro de inicialização oferece "Compartilhar logs".

## Build local

Pré-requisitos: JDK 21 (o JBR do Android Studio serve), Android SDK com `platforms;android-37` e
`build-tools;37.0.0`, e `local.properties` apontando para o SDK.

```powershell
# APK debug + suíte de testes JVM (o script ajusta JAVA_HOME/ANDROID_HOME)
powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Task all
```

Tasks aceitas: `apk` (`assembleDebug`), `test` (`testDebugUnitTest`), `all` (padrão) e `clean`.

Release assinado (exige keystore e as variáveis de ambiente lidas por `app/build.gradle.kts`):

```powershell
$env:KEYSTORE_PATH = (Resolve-Path .secrets\release.jks).Path
$env:KEYSTORE_PASSWORD = "<storePassword>"
$env:KEY_ALIAS = "<keyAlias>"
$env:KEY_PASSWORD = "<keyPassword>"
.\gradlew.bat :app:assembleRelease --no-daemon "-PappVersionName=1.0.0-beta.1" "-PappVersionCode=10000"
```

Sem `KEYSTORE_PATH` o release é gerado **não assinado** (`app-release-unsigned.apk`).

Verificação offline do release (sem device) — roda depois do `assembleRelease`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-release-apk.ps1
```

O script inspeciona o APK com `apkanalyzer dex packages --defined-only` e falha (exit 1) se alguma
classe crítica do R8 tiver sumido (`App`, `MainActivity`, `SyncEngine`, `GraphClient`, `SyncWorker`,
`UpdateChecker` e os `$$serializer` dos DTOs); com `aapt2 dump resources` falha também se um recurso
necessário tiver sido removido pelo `shrinkResources` (ex.: `drawable/ic_notification`, que o
shrinker não enxerga por vir de uma `const val` — ver `app/src/main/res/raw/keep.xml`). Também
imprime tamanho do APK, breakdown do dex (`classes*.dex` / `resources.arsc`) e o certificado via
`apksigner verify --print-certs`. Aceita `-Apk <caminho>` e `-Sdk <caminho do Android SDK>`.

## Versionamento

`versionName` e `versionCode` vêm de propriedades do Gradle (com defaults no `app/build.gradle.kts`):

- `-PappVersionName` → `versionName` (default `1.0.0-beta.1`)
- `-PappVersionCode` → `versionCode` (default `10000`)

Convenção do `versionCode`: `major*10000 + minor*100 + patch` — `1.0.0` → `10000`,
`1.2.3` → `10203`. Releases são publicadas por tag `vX.Y.Z`; sufixo de pré-release (`-beta.1`) não
entra no `versionCode`.

## Testes e CI

- **Testes JVM**: suíte JVM (`testDebugUnitTest`) cobrindo hash, planner, executor, Graph e update;
  roda com `scripts\build.ps1 -Task test`.
- **CI** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)): em push/PR para `main`, instala
  JDK 21 e o SDK Android necessário, roda `assembleDebug` + `testDebugUnitTest` e publica o
  relatório de testes como artefato quando falha.
- **Release** ([`.github/workflows/release.yml`](.github/workflows/release.yml)): em tag `v*`,
  decodifica o keystore do secret `KEYSTORE_BASE64`, calcula `versionCode` a partir da tag, gera o
  APK assinado, verifica a assinatura com `apksigner` e publica a release com
  `OneDriveSync-<versão>.apk`. Tags com `-` viram pré-release.

Secrets necessários: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
O keystore **nunca** é commitado (`.secrets/`, `*.jks` e `keystore.properties` estão no
`.gitignore`).

## Licença

[MIT](LICENSE) © 2026 Anderson Lucas C. Ramos
