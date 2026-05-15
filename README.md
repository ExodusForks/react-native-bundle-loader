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

### Unverified loading

```ts
BundleLoader.load('https://bundles.example.com/main.jsbundle');
```

Functionally identical to the upstream `load()`: passes the URL straight through to the native bridge, which fetches and reloads. **This skips integrity verification — only use it for developer ergonomics, never in production paths.**

The URL is required to use `https:`.

### `BundlePrompt`

A `Modal`-wrapped text input + Reload button intended for developer UX. The default URL field is **empty** (the upstream's hardcoded jsdelivr default has been removed). The button calls the unverified `load()` path.

```tsx
import { BundlePrompt } from '@exodus/react-native-bundle-loader';
```

Do not render `BundlePrompt` in store builds.

## Accessing a running Metro packager

Same idea as upstream: expose your local Metro packager via a tunnel (e.g. `ngrok http 8081`) and call `BundleLoader.load(<https tunnel URL>)`. Required Metro query params:

- `dev`: `true` or `false` matching how the binary was built
- `excludeSource`: `true`
- `platform`: `ios` or `android` matching the host

Example: `https://example.ngrok.io/index.bundle?dev=false&platform=ios&excludeSource=true`

## Platform support

| Capability                  | iOS | Android |
| --------------------------- | --- | ------- |
| `load(url)`                 | ✅  | ✅      |
| `loadVerified(url, sha256)` | ✅  | ✅      |
| `runningMode()`             | ✅  | ✅      |

### How bundle loading works

**iOS** downloads and verifies the bundle natively via `NSURLSession` + `CommonCrypto CC_SHA256`, writes it to `NSTemporaryDirectory()` with `NSDataWritingFileProtectionComplete`, then sets the bridge's `bundleURL` via KVC (`[bridge setValue:url forKey:@"bundleURL"]`) and calls `[bridge reload]`. This is an in-process reload: the old bridge is torn down and a new one is created with the cached file. Because iOS uses ARC, the old bridge's memory (including the Hermes runtime) is freed immediately when the bridge reference is released, before the new runtime allocates — no double-memory peak.

**Android** uses a process restart instead of an in-process bridge swap. The reason: Android's ART garbage collector is non-deterministic. When a new React context is created alongside an existing one, ART does not guarantee the old Hermes runtime's native heap is freed before the new runtime allocates. On real-world bundle sizes (~50 MB of Hermes bytecode) this causes OOM. The process restart avoids the problem entirely by ensuring only one runtime is ever live.

After download and hash verification, the module:

1. Writes the bundle to `Context.getCacheDir()/verified-bundle.jsbundle`.
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
