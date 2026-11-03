# Registro do app no Entra ID (Client ID)

O APK distribuído **já vem com um Client ID embutido** (registro de propriedade do autor do
projeto): para usar o app basta conectar a conta. Registrar um app próprio é **opcional e
avançado** — útil para não depender do registro do autor ou para usar uma conta de outro diretório.

O app usa **OAuth2 device code flow** contra o tenant configurado em Ajustes → Conta → **Diretório
(tenant)**, com `consumers` como padrão (OneDrive pessoal).
Não existe redirect URI, não existe PKCE manual, não precisa de admin consent.

## Passo a passo

Só é necessário para quem for usar um registro próprio (opcional):

1. Abra https://entra.microsoft.com (ou `portal.azure.com` → Microsoft Entra ID → App registrations;
   é o mesmo backend, útil se um dos portais estiver instável).
2. **App registrations** → **New registration**
3. Preencha:
   - **Name**: `OneDrive Sync`
   - **Supported account types**: *Personal Microsoft accounts only*
     (se quiser cobrir contas corporativas depois, use *Accounts in any organizational directory and
     personal Microsoft accounts* — mas o app foi desenhado e testado para pessoal)
   - **Redirect URI**: **deixe em branco**
4. **Register** → copie o **Application (client) ID** (formato `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).
5. Aba **Authentication**:
   - Em *Advanced settings* → **Allow public client flows** = **Yes**
   - Save
6. Aba **API permissions** → **Add a permission** → **Microsoft Graph** → **Delegated permissions** →
   marque `Files.ReadWrite.All` → **Add permissions**.
   Não precisa de "Grant admin consent" (conta pessoal não tem admin).
7. Aba **Token configuration**: nada a fazer.

## Onde fica o Client ID

O Client ID é embutido no APK (`BuildConfig.DEFAULT_CLIENT_ID`); o app não tem mais campo para
digitá-lo. Para gerar um APK com outro registro, passe a propriedade Gradle
`-PappClientId=<id>` (o `scripts/build.ps1` não repassa propriedades extras, então use
`gradlew.bat :app:assembleDebug -PappClientId=<id>`).

O único ajuste de login no app é o **Diretório (tenant)**, em Ajustes → Conta.

## Qual tenant usar

O campo **Diretório (tenant)** define o segmento da autoridade
(`https://login.microsoftonline.com/<tenant>/oauth2/v2.0`) e precisa combinar com o
*Supported account types* do registro:

| Registro do app | Valor do campo |
| --- | --- |
| *Personal Microsoft accounts only* | `consumers` (padrão) |
| *Accounts in any organizational directory and personal Microsoft accounts* | `common` |
| *Accounts in this organizational directory only* (single-tenant) | ID do diretório (GUID) ou domínio, ex. `contoso.onmicrosoft.com` |
| App multi-tenant usado só com um diretório | GUID ou domínio desse diretório |

Quando o tenant não combina com o registro, o login falha no `devicecode`/`token` com
`AADSTS700016` ("not a valid application identifier") ou `unauthorized_client`. Corrija o campo em
Ajustes → Conta e entre de novo; não é preciso reinstalar nem recompilar. O valor fica em
`SharedPreferences` e o padrão é `consumers`. O campo nunca fica vazio: valor em branco volta para
`consumers`.

## Escopos usados

`Files.ReadWrite.All offline_access`

`offline_access` é o que garante o `refresh_token` (sync em background sem relogar).
Sem `Files.ReadWrite.All` o app não consegue mover/renomear arquivos fora da pasta do app.

## Se o registro falhar

- `AADSTS700016` / "not a valid application identifier": Client ID errado ou app de outro tenant.
- `AADSTS7000218`: "Allow public client flows" está **No** → corrija na aba Authentication.
- `AADSTS65001` / consent required: faltou o `Files.ReadWrite.All` delegado na aba API permissions.
- `unauthorized_client` no devicecode: conta corporativa num app "personal accounts only".
