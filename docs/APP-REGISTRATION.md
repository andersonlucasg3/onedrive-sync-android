# Registro do app no Entra ID (Client ID)

O APK distribuído **já vem com um Client ID embutido** (registro de propriedade do autor do
projeto): para usar o app basta conectar a conta. Registrar um app próprio é **opcional e
avançado** — útil para não depender do registro do autor.

O app usa **Authorization Code + PKCE dentro do app (WebView)** e, como alternativa, **device code
flow**. A autoridade é fixa em `consumers` (OneDrive pessoal): não existe campo de diretório/tenant
na interface. Não precisa de client secret nem de admin consent.

## Redirect URI

O login interativo usa a redirect URI que a plataforma **Mobile and desktop applications** do
portal oferece por padrão:

```
https://login.microsoftonline.com/common/oauth2/nativeclient
```

Com ela registrada, o login interativo funciona **sem alterar nada no portal**. Se ela não existir
(ou se a Microsoft recusar o WebView), o app continua funcionando pelo modo **Entrar com código**
(device code), que não usa redirect URI.

Opcional: adicionar a redirect URI customizada

```
msal<clientId>://auth      (ex.: msalb556261b-31a9-49a9-aa9b-2a4a611291ff://auth)
```

em **Authentication → Add a platform → Mobile and desktop applications**. Com ela registrada, o
`<intent-filter>` do app também conclui o login quando o redirect volta como intent (o parser aceita
as duas URIs). Sem ela, apenas o WebView e o device code funcionam.

## Passo a passo

Só é necessário para quem for usar um registro próprio (opcional):

1. Abra https://entra.microsoft.com (ou `portal.azure.com` → Microsoft Entra ID → App registrations).
2. **App registrations** → **New registration**
3. Preencha:
   - **Name**: `OneDrive Sync`
   - **Supported account types**: *Personal Microsoft accounts only* (o app é desenhado para
     contas pessoais; `consumers` é fixo no código)
   - **Redirect URI**: plataforma **Mobile and desktop applications** e marque
     `https://login.microsoftonline.com/common/oauth2/nativeclient` (não escolha a plataforma
     **Web**)
4. **Register** → copie o **Application (client) ID** (formato `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).
5. Aba **Authentication**:
   - Confirme a plataforma *Mobile and desktop applications* com a redirect URI do passo 3.
   - Em *Advanced settings* → **Allow public client flows** = **Yes** (o device code e o PKCE sem
     secret dependem disso)
   - Opcional: **Add URI** com `msal<clientId>://auth` (veja acima)
   - Save
6. Aba **API permissions** → **Add a permission** → **Microsoft Graph** → **Delegated permissions** →
   marque `Files.ReadWrite.All` → **Add permissions**.
   Não precisa de "Grant admin consent" (conta pessoal não tem admin).
7. Aba **Token configuration**: nada a fazer.

## Onde fica o Client ID

O Client ID é embutido no APK (`BuildConfig.DEFAULT_CLIENT_ID`); o app não tem campo para
digitá-lo. Para gerar um APK com outro registro, passe a propriedade Gradle
`-PappClientId=<id>` (o `scripts/build.ps1` não repassa propriedades extras, então use
`gradlew.bat :app:assembleDebug -PappClientId=<id>`). O scheme do `<intent-filter>`
(`msal<clientId>`) acompanha automaticamente esse valor via `manifestPlaceholders`.

## Escopos usados

`Files.ReadWrite.All offline_access`

`offline_access` é o que garante o `refresh_token` (sync em background sem relogar).
Sem `Files.ReadWrite.All` o app não consegue mover/renomear arquivos fora da pasta do app.

## Limitações do WebView (risco conhecido)

A Microsoft **desaconselha** WebView embutido para contas Microsoft e pode responder com uma página
de erro `disallowed_useragent`; `AADSTS50011` aparece quando a redirect URI não está registrada no
app. O app detecta a página de bloqueio (URL/título — sem ler o DOM), mostra o aviso
"A Microsoft bloqueou o login neste navegador — use Entrar com código" e mantém o device code a um
toque, na barra superior e na tela de conta. O user-agent **não** é disfarçado.

## Se o registro falhar

- `AADSTS50011` / redirect URI mismatch: a redirect URI usada (`.../common/oauth2/nativeclient`) não
  está na plataforma *Mobile and desktop applications* → adicione-a.
- `AADSTS700016` / "not a valid application identifier": Client ID errado ou app de outro tenant.
- `AADSTS7000218`: "Allow public client flows" está **No** → corrija na aba Authentication.
- `AADSTS65001` / consent required: faltou o `Files.ReadWrite.All` delegado na aba API permissions.
- `unauthorized_client` no devicecode: conta corporativa num app "personal accounts only".
- `disallowed_useragent`: o WebView foi bloqueado → use **Entrar com código**.
