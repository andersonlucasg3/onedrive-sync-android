# Registro do app no Entra ID (Client ID)

O app usa **OAuth2 device code flow** contra o tenant `consumers` (OneDrive pessoal).
Não existe redirect URI, não existe PKCE manual, não precisa de admin consent.

## Passo a passo

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

## Onde colar no app

Settings → **Client ID do Azure** → colar → Salvar → **Entrar com Microsoft**.
O código abre https://microsoft.com/devicelogin, mostra um código de 8 caracteres; você digita no
navegador do celular (ou de outro dispositivo) e autoriza.

O Client ID fica em `SharedPreferences` (não no APK), então **trocar de registro não exige rebuild**.

## Escopos usados

`Files.ReadWrite.All offline_access`

`offline_access` é o que garante o `refresh_token` (sync em background sem relogar).
Sem `Files.ReadWrite.All` o app não consegue mover/renomear arquivos fora da pasta do app.

## Se o registro falhar

- `AADSTS700016` / "not a valid application identifier": Client ID errado ou app de outro tenant.
- `AADSTS7000218`: "Allow public client flows" está **No** → corrija na aba Authentication.
- `AADSTS65001` / consent required: faltou o `Files.ReadWrite.All` delegado na aba API permissions.
- `unauthorized_client` no devicecode: conta corporativa num app "personal accounts only".
