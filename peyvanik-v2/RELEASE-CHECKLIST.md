# Peyvanik 2.5 RC1 — release checklist
## Scope
- Source branch: peyvanik-v2. Base verified phase 5 commit: 9412a320990a4507b2faf67e419c1292913bff03.
- Connected clients: Android system WebView (API 26+, target 35) and Windows x64 WebView2 (.NET 8, Windows 10 build 19041+).
- Android application ID com.peyvanik.messenger; Windows AppId unchanged. Both load https://peyvanik-web.ahbe0911.workers.dev.
- No backend/database migration, account changes, new secrets or public signup changes in this RC.

## Automated gate
- Require the RC workflow verify, android and windows jobs to succeed for the same commit.
- Download artifacts from the workflow run; check BUILD-INFO.txt, SHA256SUMS.txt and Android SIGNING.txt. A hash confirms integrity, not publisher identity.
- Android is debug-signed; compare signing certificate against installed APK BEFORE upgrading. Ephemeral runner debug keys may differ. Do not uninstall a working client merely to force an update: local session and preferences may be lost. Establish a securely managed stable signing key separately.
- Windows setup is unsigned; verify checksum and expect a possible SmartScreen warning. Do not disable system security. Official code-signing is a separate release requirement.
- Artifacts retained 14 days; archive approved release files separately.

## Deployment gate (manual; not done by CI)
- Confirm authorization and access to existing peyvanik-web Worker; record current deployment/version and export existing source/config via authorized Cloudflare controls. Do not expose secrets or change API binding (peyvanik-v2-api).
- Confirm the intended web-worker.js imports the matching web-assets.js, pdf-assets.js and all PDF vendor modules. Do not deploy a preview to the production domain accidentally.
- Verify current /health endpoint, API service binding and existing registration state. Deploy ONLY the web Worker from the approved RC commit; do not deploy worker.js or run SQL migrations for this phase.
- Verify /health returns healthy and connected, HTTP response security headers, app.js/style.css ETags, login and theme rendering. CI uses mock API and SQLite/in-memory storage, not production data.
- With authorized test accounts, verify login, logout/relogin, session revocation, two-party text, delete/rollback, pagination, drafts, offline/reconnect, cloud upload/cancel/download, note/folder/search/favorites and private previews. Test both clients on actual devices including small screen, keyboard, RTL, dark/light/system and Windows dock/tray. Do not use real user keys in logs.
- If regression occurs, restore the previously recorded web Worker deployment/version in Cloudflare. Preserve API, database, keys and binding. Recheck health and login. Reverting Git alone does not restore a live deployment.

## Release status
- Automated build result: check GitHub Actions for the RC commit.
- Live deployment: not performed by this workflow.
- Real-device acceptance: pending.
- Stable release signing and operational security review: pending.
- No end-to-end encryption, native push, group messaging or direct media attachments are implied by RC1.
