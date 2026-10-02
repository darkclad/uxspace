# Self-contained privileged access

UxSpace needs ADB-shell privilege to launch apps onto its virtual displays, inject input,
and create *trusted* virtual displays. Today it borrows that privilege from the separate
**Shizuku** app. This is the plan to drop that dependency: UxSpace activates and hosts its
own shell-uid helper, the same way Shizuku does internally — via Android 11's **Wireless
Debugging**.

## Why

- One app to install, not two.
- A first-run setup flow inside UxSpace instead of "go install and configure Shizuku".
- The helper gains a real capability the Shizuku-app helper could not give cleanly: it
  creates **trusted** virtual displays, so a launched app's splash-screen / new-task
  launches stay on the workspace instead of escaping to the phone (see the Amazon bug).

## What stays

The privileged work itself does not change. `ShizukuUserService` — the `IShizukuService`
AIDL helper that shells out `am` / `input` and creates virtual displays — is reused almost
verbatim. What changes is only how that helper process is *started* and *reached*.

The package `com.uxspace.shizuku` is renamed `com.uxspace.privileged`; `IShizukuService` →
`IPrivilegedService`; `ShizukuUserService` → `PrivilegedServer`; `ShizukuManager` →
`PrivilegedService`. The AIDL method set is unchanged.

---

## How Shizuku-less activation works

On Android 11+ (API 30) a device can be debugged over the network with no PC: Settings →
Developer options → **Wireless debugging**. An app on the device can itself act as the ADB
client, connecting to the loopback. The sequence:

1. **Pair, once.** The user opens "Pair device with a pairing code"; the dialog shows a
   port and a 6-digit code. UxSpace connects to that port over TLS and runs the ADB pairing
   exchange with the code — after which the device trusts UxSpace's ADB key permanently.
2. **Connect.** Wireless debugging advertises a second port; UxSpace opens a TLS ADB
   connection there using the now-trusted key.
3. **Start the helper.** Over an ADB `shell:` stream, UxSpace runs `app_process` to start
   `PrivilegedServer` — a process running as **shell uid (2000)**, loaded from UxSpace's own
   APK.
4. **Reach the helper.** The server hands its Binder back to the app and UxSpace talks to it
   exactly as it talks to the Shizuku helper today.

Both ports are announced over mDNS, so UxSpace discovers them with `NsdManager` and the user
only ever types the 6-digit code.

### Honest limitations (same as Shizuku)

- **API 30+** for the wireless path. `minSdk` stays 26; the setup flow is gated at runtime.
- The user must enable **Wireless debugging** — UxSpace cannot toggle it (no permission).
- After a **reboot** the helper process is gone and wireless debugging is usually off
  again. Pairing is *not* lost (the key persists). On next launch UxSpace re-discovers the
  connect port and restarts the helper; if wireless debugging is off, the setup card asks
  the user to switch it back on. This is exactly Shizuku's post-reboot behaviour.

---

## Dependencies

Added to `gradle/libs.versions.toml` and `app/build.gradle.kts`; JitPack must be added to
`settings.gradle.kts` `dependencyResolutionManagement.repositories`. **One online build is
required** to fetch them; `--offline` works again afterwards.

| Dependency | Purpose |
|---|---|
| `com.github.MuntashirAkon:libadb-android:3.1.1` | ADB key handling, pairing, TLS connection, shell streams |
| `org.conscrypt:conscrypt-android:2.5.3` | TLS provider libadb-android requires |
| `com.github.MuntashirAkon:sun-security-android:1.1` | X509 self-signed cert generation for the ADB key |

The `dev.rikka.shizuku:api` / `:provider` dependencies are removed.

---

## Components (`com.uxspace.privileged`)

### `AdbConnectionManager` — `: AbsAdbConnectionManager`

libadb-android's connection manager, subclassed to persist the ADB identity:

- On first use, generate an RSA-2048 key pair and a self-signed X509 certificate
  (`sun-security-android`); store under `filesDir/adb/` (`key.pk8`, `cert.pem`).
- `getPrivateKey()` / `getCertificate()` load them back.
- `setApi()` / device name = `"UxSpace"`.
- Exposes `pair(host, port, code)`, `connect(host, port)`, and `openStream("shell:…")`.

### `AdbDiscovery` — mDNS via `NsdManager`

- `discoverPairing()` → resolves `_adb-tls-pairing._tcp` to `host:port` (advertised only
  while the pairing dialog is open).
- `discoverConnect()` → resolves `_adb-tls-connect._tcp` to `host:port` (advertised while
  wireless debugging is on).
- Short timeout + callback; loopback addresses preferred.

### `PrivilegedServer` — the helper `main()`

Runs under `app_process` as shell uid. It:

1. Reads a one-use 256-bit launch token from the ADB shell stream's stdin.
2. `Looper.prepareMainLooper()`.
3. Builds a system `Context` (`ActivityThread.systemMain().getSystemContext()`).
4. Creates the `IPrivilegedService.Stub` (the body that is `ShizukuUserService` today —
   `am` / `input` shell-outs and trusted `createVirtualDisplay`).
5. Hands the Binder and launch token to the app through `BinderReceiverProvider`.
6. `Looper.loop()` — stays alive until `destroy()`.

The ADB shell stream remains open as the helper's lifetime anchor.

### `ServerBootstrap`

Builds and runs the start command over an `AdbStream`:

```
CLASSPATH=<uxspace-apk-path> app_process /system/bin \
    --nice-name=uxspace_privileged com.uxspace.privileged.PrivilegedServer
```

The APK path comes from `context.applicationInfo.sourceDir`. The command remains attached
to the ADB stream, which the app retains as the helper's lifeline; closing it tears the
helper down.

Immediately after opening the stream, the app writes the launch token to its stdin. The
token is deliberately absent from the command line, environment, and logs. The stream is
then retained for the helper's lifetime as before.

### `BinderReceiverProvider` — `: ContentProvider`

A tiny exported provider, authority `com.uxspace.privileged`. Its `call("setBinder", …)`
pulls the Binder out of the Bundle and passes it to `PrivilegedService`. This is how the
shell-uid server reaches back into the app process (Binders travel in Bundles across the
process boundary; Shizuku uses the same trick). Publication is accepted only while the
orchestrator is `STARTING` and the one-use launch token matches.

### `PrivilegedService` — orchestrator (replaces `ShizukuManager`)

The process-wide object the rest of UxSpace calls. It owns:

- **State machine**: `NEEDS_WIRELESS_DEBUGGING → NEEDS_PAIRING → CONNECTING → STARTING →
  READY` (plus `UNSUPPORTED` below API 30), with listeners — the phone panel renders the
  current step, mirroring today's Shizuku banner.
- **`activate(pairingCode)`** — discover pairing port, `pair()`, persist, then connect.
- **`ensureRunning()`** — called on every app launch: if the Binder is alive, done; else
  discover the connect port, connect, `ServerBootstrap`, wait for the provider callback.
- The privileged call surface — `launchApp`, `tap`, `swipe`, `key`, `text`, `forceStop`,
  `sendBack`, `displayHasActivity`, `createVirtualDisplay`, `releaseVirtualDisplay` —
  unchanged signatures, so `UxSpaceApp`'s `WorkspaceController` wiring is untouched.

### Setup UI

The Shizuku banner in `MainActivity` becomes a small setup card driven by the state
machine:

- `UNSUPPORTED` — "needs Android 11 or newer".
- `NEEDS_WIRELESS_DEBUGGING` — explainer + a button to
  `Settings.ACTION_DEVELOPER_SETTINGS` (or the wireless-debugging screen).
- `NEEDS_PAIRING` — "open Pair with code, then enter the 6 digits here": a 6-digit field;
  submit calls `PrivilegedService.activate(code)`.
- `CONNECTING` / `STARTING` — progress.
- `READY` — card hidden, exactly as today.

---

## Build order

1. **Scaffolding** — JitPack repo, dependencies, rename `com.uxspace.shizuku` →
   `com.uxspace.privileged`, AIDL rename. Builds green (online build once).
2. **Server side** — `PrivilegedServer` (reuse `ShizukuUserService` body),
   `BinderReceiverProvider`, manifest entry. Dependency-free; compiles.
3. **ADB side** — `AdbConnectionManager`, `AdbDiscovery`, `ServerBootstrap`.
4. **`PrivilegedService`** — state machine, `activate`, `ensureRunning`; re-wire
   `UxSpaceApp`.
5. **Setup UI** in `MainActivity`; remove the old Shizuku banner.
6. Remove the `dev.rikka.shizuku` dependencies and `com.uxspace.shizuku`.

Stages 1–2 compile and are verifiable offline; 3–6 need the one online build.

---

## Sources

- [libadb-android — MuntashirAkon](https://github.com/MuntashirAkon/libadb-android)
- [Android Debug Bridge — Wireless debugging](https://developer.android.com/tools/adb#wireless-android11-command-line)
