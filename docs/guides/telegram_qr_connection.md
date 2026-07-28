# Telegram QR Connection

Canonical procedure for authorizing Harbor's Telegram connector with a QR
code, in-app, on the same or a second device. Phone-number/code entry
remains available on the same screen and is unaffected.

## Procedure

1. Open the Telegram connector login screen (identifier entry step).
2. Tap **Connect with QR code**
   (`TELEGRAM_LOGIN_QR_TAG` in `TelegramLoginPlaceholderFragment.kt`).
3. Harbor requests TDLib `RequestQrCodeAuthentication` and renders the
   returned link as an in-app QR code. The link is held in memory only; it is
   never logged, persisted, or included in exceptions.
4. Scan the code from **Telegram Settings -> Devices -> Link Desktop Device**
   on a second device, or tap **Open Telegram** on the same device to hand the
   `org.telegram.messenger` app via an explicit `ACTION_VIEW` intent.
5. Confirm the login from within Telegram. Harbor polls TDLib for
   `AuthorizationStateWaitOtherDeviceConfirmation` updates and rotates the
   displayed QR automatically if Telegram issues a new link.
6. If the account has Telegram two-factor password enabled, Harbor shows the
   existing password step after confirmation, same as the phone/code path.
7. On success, Harbor returns to sign-in without inventing or persisting a
   phone-number identity — a QR-authorized account has none. If the QR
   confirmation window times out or fails, Harbor returns to the identifier
   entry step so phone/code entry can be used instead.

## Notes

- The QR route reuses the existing login/session/UI boundaries
  (`StartupViewModel`, `TelegramAuthSessionImpl`, `TelegramAuthSession`); it
  does not add a separate login surface.
- If the process dies while waiting, re-enter Telegram login. The persisted
  `AuthorizationStateWaitOtherDeviceConfirmation` TDLib state then maps back
  to `QR_WAITING`, and polling resumes without requesting a new challenge.

## Historical/recovery reference

Before this UI landed, QR authorization was validated with a standalone
debug-only diagnostic (`QrAuthDiagnosticActivity`, launched directly via ADB,
not reachable from app UI) in the `Harbor-qr-auth-temp` worktree. That
diagnostic is not part of the shipped app and is kept only as a reference for
manually re-proving the underlying TDLib QR flow if the canonical UI ever
needs to be bypassed for debugging.
