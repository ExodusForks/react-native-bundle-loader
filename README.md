# @exodus/react-native-bundle-loader

Loads a remote React Native JS bundle, with optional **hash-pinned integrity verification** before the bridge reloads.

> This is the Exodus security-hardened fork of [`react-native-bundle-loader`](https://www.npmjs.com/package/react-native-bundle-loader)
> (originally by Jusbrasil; upstream GitHub repo deleted; see [provenance](#provenance) for details).

## Threat model

Loading a remote JS bundle is, by construction, remote code execution inside the host app. **This library is intended for internal/development builds only — do not ship it in store builds without an out-of-band, statically-stripped feature flag.** See `SECURITY.md`.

The `loadVerified()` API closes the dominant runtime risk: it downloads the bundle natively, hashes the bytes with platform crypto (iOS: `CommonCrypto CC_SHA256`, Android: `MessageDigest SHA-256`), compares the hash to a caller-supplied digest in constant time, and only then loads the verified bytes from app-private storage. Anything that mutates the response between fetch and reload is rejected.

## Installation

```sh
yarn add @exodus/react-native-bundle-loader
```

iOS:

```sh
cd ios && pod install
```

Android: requires both Gradle wiring and host app changes — see [Android integration](#android-integration) below.

## Usage

### Verified loading (recommended)

```ts
import BundleLoader from '@exodus/react-native-bundle-loader';

await BundleLoader.loadVerified(
  'https://bundles.example.com/main.jsbundle',
  // Lower-case hex sha256, exactly 64 chars
  '4f1b9c…ec'
);
```

Behavior:

- The URL must use the `https:` scheme.
- The expected sha256 must be a 64-character hex string.
- Download, SHA-256 hashing, and constant-time comparison all happen in native code. This avoids the Hermes `RangeError` that JS-side `response.arrayBuffer()` causes on large bundles (≥ ~70 MB).
- On match, the bytes are written to app-private storage and the bundle is loaded (see platform notes below).
- On mismatch, an error is thrown and the current bundle is left untouched.
- The remote bundle is active for **one session only**. The next cold start returns to the local bundle — matching the behaviour consumers expect from a developer preview tool.

Works on iOS and Android.

> The library exposes **only** the verified path. There is no unverified `load()`
> API: loading a remote bundle without native SHA-256 verification is an
> unauthenticated remote-code-execution primitive, so it was removed.

## Platform support

| Capability                  | iOS | Android |
| --------------------------- | --- | ------- |
| `loadVerified(url, sha256)` | ✅  | ✅      |
| `runningMode()`             | ✅  | ✅      |

### How bundle loading works

**iOS** downloads and verifies the bundle natively via `NSURLSession` + `CommonCrypto CC_SHA256`, then writes the verified bytes to `NSTemporaryDirectory()` with `NSDataWritingAtomic | NSDataWritingFileProtectionComplete` (nothing is written before verification). It stores that file URL in `NSUserDefaults` under `RNBundleLoaderPendingURLKey` and calls `[bridge reload]`; the host app's `loadSourceForBridge:` reads the pending URL and loads from it, so the bridge's own `bundleURL` — and therefore `SourceCode.scriptURL` — is never mutated, keeping asset resolution correct. This is an in-process reload; under ARC the old bridge (and its Hermes runtime) is freed before the new one allocates, so there is no double-memory peak.

**Android** uses a process restart instead of an in-process bridge swap. The reason: Android's ART garbage collector is non-deterministic. When a new React context is created alongside an existing one, ART does not guarantee the old Hermes runtime's native heap is freed before the new runtime allocates. On real-world bundle sizes (~50 MB of Hermes bytecode) this causes OOM. The process restart avoids the problem entirely by ensuring only one runtime is ever live.

After download and hash verification, the module:

1. Downloads to a **per-call unique temp file** (`File.createTempFile` in `Context.getCacheDir()`), verifies the SHA-256, then **atomically promotes** it to `Context.getCacheDir()/verified-bundle.jsbundle` — the canonical path never holds unverified or partial bytes. A unique temp per call (rather than a shared fixed path) means two overlapping loads can never swap each other's file between verify and rename. On a hash mismatch or download error the temp file is deleted and the current bundle is left untouched.
2. Sets a one-shot flag in `SharedPreferences` (`"BundleLoader"` / `"pending_remote_bundle"`), using a synchronous `commit()` so the flag survives the imminent process kill.
3. Restarts the process via `startActivity` + `Process.killProcess`.

On the next launch, the host app reads the flag, disables Metro (so `ReactInstanceManager` does not query the packager and ignore the file — confirmed necessary by bytecode analysis of RN 0.78), and serves `verified-bundle.jsbundle` as the JS bundle for this session. The flag is consumed on first use so subsequent restarts return to Metro.

## Android integration

Because Android requires host app changes that cannot be encapsulated in the module itself, the following manual steps are required.

### 1. Gradle wiring

`settings.gradle` — include the subproject conditionally (the module is a `devDependency`; prod CI runs `yarn install --production` and the directory won't exist):

```groovy
def bundleLoaderDir = new File(rootProject.projectDir, '../node_modules/@exodus/react-native-bundle-loader/android')
if (bundleLoaderDir.exists()) {
    include ':@exodus_react-native-bundle-loader'
    project(':@exodus_react-native-bundle-loader').projectDir = bundleLoaderDir
}
```

`app/build.gradle` — depend only in debug builds:

```groovy
if (new File("$rootDir/../node_modules/@exodus/react-native-bundle-loader/android").exists()) {
    debugImplementation project(':@exodus_react-native-bundle-loader')
}
```

### 2. Register the package

In `MainApplication.java`, inside `getPackages()`, add the package via reflection so a missing module (absent in prod CI) doesn't cause a compile-time error:

```java
if (BuildConfig.DEBUG) {
    // devDependency absent in prod CI (yarn install --production); reflection avoids a compile-time import
    try {
        packages.add((ReactPackage) Class.forName("com.reactnativebundleloader.BundleLoaderPackage")
                .getDeclaredConstructor().newInstance());
    } catch (ReflectiveOperationException e) {
        throw new RuntimeException(e);
    }
}
```

### 3. Hook bundle loading into ReactNativeHost

Add these three methods to your `ReactNativeHost` anonymous subclass in `MainApplication.java`:

```java
import java.io.File;

// ...

@Override
public boolean getUseDeveloperSupport() {
    if (BuildConfig.DEBUG && hasPendingRemoteBundle()) {
        // Must disable dev support: when enabled and Metro is reachable,
        // ReactInstanceManager queries the packager and ignores getJSBundleFile().
        return false;
    }
    // No pending bundle — clear the active flag so runningMode() returns LOCAL.
    getSharedPreferences("BundleLoader", MODE_PRIVATE)
            .edit().remove("active_remote_bundle").apply();
    return BuildConfig.DEBUG;
}

@Override
protected String getJSBundleFile() {
    if (BuildConfig.DEBUG && hasPendingRemoteBundle()) {
        File cachedBundle = new File(getCacheDir(), "verified-bundle.jsbundle");
        if (cachedBundle.exists()) {
            // Consume the one-shot latch: next restart goes back to Metro.
            getSharedPreferences("BundleLoader", MODE_PRIVATE).edit()
                    .remove("pending_remote_bundle")
                    .putBoolean("active_remote_bundle", true)
                    .apply();
            return cachedBundle.getAbsolutePath();
        }
    }
    return null;
}

private boolean hasPendingRemoteBundle() {
    return getSharedPreferences("BundleLoader", MODE_PRIVATE)
            .getBoolean("pending_remote_bundle", false);
}
```

The SharedPreferences keys (`"BundleLoader"`, `"pending_remote_bundle"`, `"active_remote_bundle"`) must match the constants defined in `BundleLoaderModule` (`PREFS_NAME`, `PREFS_PENDING_KEY`, `PREFS_ACTIVE_KEY`).

## Provenance

This is a fork of `react-native-bundle-loader@0.1.0` originally published by Jusbrasil (2020-10-21, npm publisher `helielson`, commit `ec3d4520`). The upstream GitHub repo at `github.com/jusbrasil/react-native-bundle-loader` was subsequently deleted. The complete original git history is preserved through the v0.1.0 release commit; the Android implementation was contributed by `milad.bagherii@digikala.com` in the `mldb/react-native-bundle-loader` mirror in 2021.

## Security

See `SECURITY.md` for the threat model, accepted residual risks, and disclosure procedure.

## License

MIT
