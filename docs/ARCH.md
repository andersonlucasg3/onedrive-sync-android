# OneDrive Sync — Especificação Técnica

App Android nativo, sideload, foco HyperOS. Sync bidirecional OneDrive **Pessoal** ↔ pastas
locais arbitrárias do armazenamento compartilhado.

## Decisões fechadas (não reabrir sem perguntar ao usuário)

| Tema | Decisão |
|---|---|
| Conta | Somente **OneDrive Pessoal** (MSA, tenant `consumers`) |
| Acesso local | **MANAGE_EXTERNAL_STORAGE** (All files access) + `java.io.File`. Sem SAF. |
| Pastas | Pares (remota ↔ local) escolhidos livremente: `/storage/emulated/0/DCIM`, `.../Pictures/AlbumX`, etc. |
| Conflito | **Perguntar ao usuário** — operação fica pendente, notificação + tela de conflitos |
| Exclusão | Propaga nos dois sentidos **com confirmação** (fila de exclusões pendentes) |
| Move/rename | Detectado nos dois sentidos; move remoto = mesmo `id` com `parentReference`/`name` diferente → move local sem re-download |
| Auth | OAuth2 **device code flow** manual (OkHttp), Client ID embutido no APK e tenant configurável em runtime |
| Entrega | App completo: background, notificações, histórico/log, onboarding de permissões |

## Stack e versões (fixas)

- Gradle **9.8.0** (wrapper), AGP **9.4.1**, Kotlin **2.4.20**
- compileSdk **37** (obrigatório: AARs do BOM 2026.09.00 e okhttp 5.5.1 exigem minCompileSdk 37),
  targetSdk **36**, minSdk **30**, build-tools 37.0.0
- **AGP 9 tem Kotlin built-in**: NÃO aplicar `org.jetbrains.kotlin.android` (é rejeitado pelo AGP).
  Kotlin efetivo 2.4.20 vem do classpath dos plugins `kotlin.plugin.compose`/`kotlin.plugin.serialization`.
- Compose BOM **2026.09.00**, Material3
- **Sem** Room/KSP/Hilt: SQLite manual (`SQLiteOpenHelper`) + container de DI manual
- OkHttp 5.5.1 + kotlinx-serialization-json 1.12.0
- WorkManager 2.12.0
- JDK: `C:\Program Files\Android\Android Studio\jbr` (21) — NUNCA o JDK 25 do sistema
- package `com.anderson.onedrivesync`

Build:
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug --no-daemon
```

## Estrutura de pacotes

```
com.anderson.onedrivesync
  App.kt                  Application + AppContainer (DI manual, lazy singletons)
  core/     Result.kt, Logs.kt (ring buffer persistido), Prefs.kt (tenant, settings)
  auth/     DeviceCodeFlow.kt, TokenStore.kt (Keystore AES-GCM), AuthManager.kt
  graph/    GraphClient.kt, GraphDtos.kt, GraphError.kt
  db/       Db.kt (schema+open), PairsDao.kt, EntriesDao.kt, PendingDao.kt, LogDao.kt
  sync/     LocalScanner.kt, QuickXorHash.kt, Planner.kt, Executor.kt, SyncEngine.kt, SyncState.kt
  work/     SyncWorker.kt, SyncService.kt (foreground dataSync), Scheduler.kt
  hyperos/  HyperOsOnboarding.kt (autostart MIUI, bateria, all-files, notificações)
  ui/       MainActivity.kt, theme/, nav/, screens/ (Pairs, Wizard, Conflicts, Deletions, Log, Settings, Onboarding)
  notify/   Notifier.kt (canais: sync, conflito, exclusão)
```

## Modelo de dados (SQLite, `onedrive.db` v1)

```sql
pairs(id INTEGER PK, remote_id TEXT, remote_path TEXT, local_path TEXT,
      display_name TEXT, enabled INT, created_at INT, last_sync_at INT)

state(key TEXT PK, value TEXT)   -- 'delta_link' global do drive, 'last_full_scan', etc.

entries(id INTEGER PK, pair_id INT, rel_path TEXT UNIQUE(pair_id,rel_path),
        parent_rel TEXT, name TEXT, is_dir INT, size INT, mtime INT,
        local_hash TEXT, remote_id TEXT, remote_etag TEXT, remote_hash TEXT,
        synced_at INT, state TEXT)   -- state: SYNCED | LOCAL_NEW | REMOTE_NEW | DIRTY_BOTH

pending(id INTEGER PK, pair_id INT, kind TEXT, rel_path TEXT, remote_id TEXT,
        detail TEXT, created_at INT)  -- kind: DOWNLOAD|UPLOAD|MOVE_LOCAL|MOVE_REMOTE|DELETE_LOCAL|DELETE_REMOTE|CONFLICT

conflicts(id INTEGER PK, pair_id INT, rel_path TEXT, remote_id TEXT,
          local_mtime INT, remote_mtime INT, local_size INT, remote_size INT,
          decision TEXT, created_at INT)  -- decision: NULL|KEEP_LOCAL|KEEP_REMOTE|KEEP_BOTH

log(id INTEGER PK, ts INT, level TEXT, tag TEXT, msg TEXT)
```

`rel_path` é relativo à raiz do par, sempre com `/`. Pastas também viram entries (is_dir=1).

## Graph API usada

Base `https://graph.microsoft.com/v1.0`. Token via `Authorization: Bearer`.
`/me/drive` para a raiz pessoal.

| Operação | Request |
|---|---|
| Delta do drive (raiz) | `GET /me/drive/root/delta` ou a URL absoluta de `@odata.deltaLink`/`@odata.nextLink` |
| Metadados do item | `GET /me/drive/items/{itemId}` |
| Children (browser de pastas) | `GET /me/drive/items/{itemId}/children` |
| Baixar | `GET /me/drive/items/{id}/content` → follow redirect, stream p/ `.part` |
| Subir pequeno (<4 MiB) | `PUT /me/drive/items/{id}/content` ou `PUT /me/drive/items/{parentId}:/{name}:/content` |
| Subir grande | `POST /me/drive/items/{parentId}:/{name}:/createUploadSession` + `PUT` chunks 10 MiB (`Content-Range`) |
| Mover/renomear | `PATCH /me/drive/items/{id}` body `{parentReference:{id:...}, name:"..."}` |
| Criar pasta | `POST /me/drive/items/{parentId}/children` `{name, folder:{}, @microsoft.graph.conflictBehavior:"fail"}` |
| Excluir | `DELETE /me/drive/items/{id}` |

Campos que importam no delta: `id, name, size, parentReference{id,path}, folder,
file{hashes{quickXorHash}, mimeType}, fileSystemInfo{lastModifiedDateTime,createdDateTime},
deleted{state}`, `@removed{reason}`.

**Regras do delta (confirmadas na doc oficial v1.0):**
- O delta suportado é o da **raiz do drive**; delta por pasta não é documentado. Filtramos por par
  reconstruindo o caminho pela cadeia de `parentReference.id`.
- `parentReference.path` **pode vir ausente** → nunca depender de path remoto; sempre `id`.
- Renomear/mover uma pasta **não** retorna os descendentes: o caminho dos filhos é recalculado
  localmente a partir do ancestral renomeado (é assim que move de pasta propaga).
- O mesmo item pode aparecer várias vezes na página → usar a última ocorrência.
- `410 Gone` = token velho: reiniciar enumeração completa e reconciliar; nada é apagado por
  ausência nesse ciclo sem confirmação.
- Em exclusão, OneDrive pessoal omite `name` e `size` → só `id` + `deleted`.
- `quickXorHash` é o hash nativo do OneDrive — detecta conteúdo igual sem baixar. Arquivos
  > 4 GiB podem não ter hash → comparar `size`+mtime.

## Algoritmo de sync

1. `remoteScan` (uma vez por ciclo, para o drive inteiro): percorre `delta` (raiz) até
   `@odata.deltaLink`; acumula por `id` (última ocorrência vence) e grava o `delta_link` global
   em `state`. Depois, para cada par, seleciona os itens cujo ancestral alcance a raiz do par,
   reconstruindo `rel_path` pela cadeia de `parentReference.id` — em passes repetidos até
   estabilizar, porque a ordem das páginas não é a da árvore e a raiz do par pode ser uma pasta
   renomeada. Itens sem ancestral conhecido (pasta pai fora do ciclo) ficam pendentes até o
   próximo ciclo. `deleted`/`@removed` = exclusão remota, identificada **só pelo `id`**
   (o nome pode vir ausente).
2. `localScan`: walk recursivo de `local_path` (ignora `.part`), produz
   Mapa<relPath, LocalItem{size,mtime,hash?}>; hash (`quickXorHash`) calculado só quando
   size/mtime divergem do índice (lazy).
3. `plan(prev, remote, local)` → lista de `Action`:
   - remoto novo, local ausente → `Download`
   - remoto mudou (etag/hash ≠ índice) e local intacto (hash == índice) → `Download`
   - local novo, remoto ausente → `Upload`
   - local mudou e remoto intacto → `Upload`
   - ambos mudaram e conteúdo difere (hash remoto ≠ hash local) → `Conflict`
   - remoto excluído e local intacto → `PendingDeleteLocal`
   - local ausente e remoto intacto → `PendingDeleteRemote`
   - remoto excluído e local mudou → `Conflict` (decisão: manter local / excluir)
   - **move remoto**: `remoteId` conhecido com `rel_path` novo → `MoveLocal(old→new)`.
     Se já existir arquivo idêntico no destino, apenas remover o antigo. Pasta renomeada no
     remoto: todos os descendentes conhecidos têm `rel_path` recalculado pelo prefixo.
   - **move local**: entrada do índice cujo `rel_path` desapareceu mas o hash/size aparece em
     outro `rel_path` novo → `MoveRemote(old_id → novo parent/name)`; para pasta, move a pasta
     e mantém os ids dos filhos.
4. `execute(actions)` sequencial por par, com progresso; cada sucesso atualiza `entries`.
   Falha isolada não aborta o par (registra em log, retry na próxima rodada).
5. Pós-execução: pastas criadas/removidas conforme o plano e `last_sync_at` do par.

Primeira execução (sem `delta_link`): delta completo; itens remotos viram `Download`; local
existente com mesmo nome/tamanho → compara hash; se igual, marca SYNCED (evita re-download).

Concorrência: uma sincronização por vez (Mutex em `SyncEngine`); UI observa via `SyncState`
(StateFlow: Idle, Running(pairId, action, done, total), Error(msg)).

## Background

- `SyncWorker` (CoroutineWorker) — WorkManager periódico (15 min, constraint: rede) +
  `OneTimeWorkRequest` disparado manualmente e por broadcast de boot.
- `SyncService` — foreground service (`dataSync`) para sync manual longo; notificação com
  progresso e ação "Cancelar".
- Boot receiver: `BOOT_COMPLETED` → agenda trabalho. HyperOS pode bloquear; onboarding explica.

## HyperOS / MIUI

- `MANAGE_EXTERNAL_STORAGE`: abrir `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
- `POST_NOTIFICATIONS` (API 33+).
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + tela de bateria MIUI (intent
  `com.miui.powerkeeper`/`Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)`).
- Autostart MIUI: `Intent().setComponent(ComponentName("com.miui.securitycenter",
  "com.miui.permcenter.autostart.AutoStartManagementActivity"))` com try/catch → fallback
  para `ACTION_APPLICATION_DETAILS_SETTINGS`.
- Onboarding: checklist com status real de cada item, botão "Abrir" por item, detectar MIUI
  via `Build.MANUFACTURER == "Xiaomi"` / `ro.miui.ui.version.name`.

## UI (Compose, Material3, dark/light)

- Onboarding (permissões) → obrigatório antes do primeiro par.
- `PairsScreen`: lista de pares com status, último sync, contagem de pendências, FAB "Novo par".
- `WizardScreen`: 1) escolher pasta remota (navegação Graph com busca), 2) escolher pasta
  local (browser de diretórios via `File.listFiles`), 3) confirmar e sincronizar.
- `ConflictsScreen`: lista com lado local/remoto (nome, tamanho, data), 3 botões de decisão.
- `DeletionsScreen`: fila de exclusões pendentes com aprovação individual/em lote.
- `LogScreen`: log persistido, filtro por nível.
- `SettingsScreen`: Diretório (tenant), intervalo de sync, Wi-Fi only, diretório de downloads parciais.

## Distribuição e auto-update

Repo público: `github.com/andersonlucasg3/onedrive-sync-android`.

- Releases com tag `vX.Y.Z` e asset `OneDriveSync-X.Y.Z.apk` (o `v` da tag sai do nome do arquivo).
- `versionCode = major*10000 + minor*100 + patch` (1.0.0 → 10000, 1.2.3 → 10203); sufixo de
  pré-release (`-beta.1`) não entra no `versionCode`. `versionName`/`versionCode` vêm das
  propriedades `-PappVersionName`/`-PappVersionCode`, com defaults `1.0.0-beta.1`/`10000`.
- App consulta `https://api.github.com/repos/andersonlucasg3/onedrive-sync-android/releases/latest`
  (sem token, 60 req/h), compara `tag_name` com a versão instalada.
- **`/releases/latest` ignora pré-releases.** A beta é publicada com `prerelease: true` (tag com
  `-`), então instalação manual da beta não é avisada de novas betas — comportamento desejado:
  só releases estáveis notificam update.
- Se houver versão nova: baixa o asset para **`cacheDir/updates/update.apk`** (via
  `<arquivo>.part` + rename), confere `size` e o `digest` sha256 do asset quando presente, e
  dispara o instalador (`ACTION_VIEW` + FileProvider + `REQUEST_INSTALL_PACKAGES`,
  `FLAG_GRANT_READ_URI_PERMISSION`). Com vários assets, escolhe o maior `.apk`.
- Android não permite instalação silenciosa sem device owner/root: o usuário confirma na tela
  do instalador. O app pede "Instalar apps desconhecidos" no onboarding/ao atualizar.
- Checagem automática 1x/dia (WorkManager) + botão "Buscar atualização" em Settings.
- Assinatura: **a mesma chave em todos os releases** (senão o Android recusa o update). A
  `signingConfig` de release só existe quando `KEYSTORE_PATH` está no ambiente (CI/local); sem ela
  o build gera APK não assinado.
- **Minify desligado** nesta versão (`isMinifyEnabled = false`, `isShrinkResources = false`): não
  há device para smoke-testar o APK com R8.

## CI e release (GitHub Actions)

- `.github/workflows/ci.yml` — push/PR em `main`: JDK 21 (temurin), `gradle/actions/setup-gradle`,
  `sdkmanager` instala `platforms;android-37` + `build-tools;37.0.0` (o passo tolera layout
  diferente do SDK e segue se o `sdkmanager` não existir), depois
  `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Em falha, sobe
  `app/build/reports/tests/test` como artefato.
- `.github/workflows/release.yml` — push de tag `v*` (e `workflow_dispatch` sobre tag):
  decodifica `secrets.KEYSTORE_BASE64` para `$RUNNER_TEMP/release.jks`, deriva `VERSION` da tag e
  `VCODE` por aritmética bash (`major*10000+minor*100+patch`, ignorando o sufixo), roda
  `:app:assembleRelease` com as env `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`,
  copia para `OneDriveSync-${VERSION}.apk`, verifica com `apksigner --print-certs` e publica via
  `softprops/action-gh-release@v2` com `generate_release_notes` e
  `prerelease: contains(ref_name, '-')`.
- Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. O keystore nunca é
  commitado (`.secrets/`, `*.jks`, `keystore.properties` no `.gitignore`).

## Pareamento

- A **raiz do drive** é pareável: o wizard começa em `/me/drive/root` com `id = null` e, ao
  escolher a raiz, resolve o item id real (`GET /me/drive/root`) antes de gravar o par.
- O par remoto pode ser qualquer pasta; o delta continua sendo o da raiz do drive, filtrado por
  ancestral na reconstrução de `rel_path`.
- O par local não pode ser a própria raiz do armazenamento (`localPath != storageRoot`).

## Convenções

- Kotlin, `explicitApi()` no módulo app? não — desnecessário. Sem libs além das listadas.
- Estado imutável + StateFlow na ViewModel; sem `!!` em código de produção.
- Nomes/strings de UI em pt-BR; código/comentários em inglês (curtos, só o não-óbvio).
- Nada de retry infinito: backoff 2s/8s/30s e desiste no ciclo.
- Download sempre para `<arquivo>.part` e `renameTo` atômico; upload session cancelável.
- Nunca deletar nada sem passar pela fila `pending` e confirmação do usuário.

## Fases

- **F0** Scaffold + build verde (APK vazio, tema, navegação).
- **F1** Auth device-code + TokenStore + GraphClient (delta/download/upload/move/delete).
- **F2** DB + LocalScanner + QuickXorHash (+ testes unitários do hash).
- **F3** Planner + Executor + SyncEngine (+ testes unitários do planner/move).
- **F4** WorkManager + ForegroundService + Notifier + boot receiver.
- **F5** UI completa (onboarding, pares, wizard, conflitos, exclusões, log, settings).
- **F6** Build final, APK debug, README de instalação e app registration.
- **F7** Assinatura de release, CI/release no GitHub Actions, versionamento por tag, README e
  limpeza (PlaceholderScreen removido).
