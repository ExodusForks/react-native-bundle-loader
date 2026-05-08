# @exodus/react-native-bundle-loader

Loads a remote React Native JS bundle, with optional **hash-pinned integrity verification** before the bridge reloads.

> This is the Exodus security-hardened fork of [`react-native-bundle-loader`](https://www.npmjs.com/package/react-native-bundle-loader)
> (originally by Jusbrasil; upstream GitHub repo deleted; see [provenance](#provenance) for details).

## Threat model

Loading a remote JS bundle is, by construction, remote code execution inside the host app. **This library is intended for internal/development builds only — do not ship it in store builds without an out-of-band, statically-stripped feature flag.** See `SECURITY.md`.

The `loadVerified()` API closes the dominant runtime risk: it fetches the bundle bytes itself, hashes them with `@exodus/crypto`, compares the hash to a caller-supplied digest in constant time, and only then asks the bridge to reload from the verified bytes. Anything that mutates the response between fetch and reload is rejected.

## Installation

```sh
yarn add @exodus/react-native-bundle-loader
```

iOS:

```sh
cd ios && pod install
```

Android: `BundleLoaderPackage` is autolinked.

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
- The bytes are fetched, hashed in JS using `@exodus/crypto/hash`, and compared to the expected hash with a constant-time comparison.
- On match, the bytes are written to the iOS sandbox temp directory with `NSDataWritingFileProtectionComplete` and the bridge is reloaded from that local `file://` URL.
- On mismatch, an error is thrown and the bridge is left untouched.

iOS only. On Android the call rejects with `E_NOT_SUPPORTED`.

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
- `platform`: `ios` (Android is not supported for actual bundle loading)

Example: `https://example.ngrok.io/index.bundle?dev=false&platform=ios&excludeSource=true`

## Platform support

| Capability                  | iOS   | Android                                    |
| --------------------------- | ----- | ------------------------------------------ |
| `load(url)`                 | ✅    | ⚠️ Sets Metro debug-server host only (dev) |
| `loadVerified(url, sha256)` | ✅    | ❌ Rejects with `E_NOT_SUPPORTED`          |
| `runningMode()`             | ✅    | ❌ Rejects with `E_NOT_SUPPORTED`          |

The Android `load(host)` calls into `DevInternalSettings`, which is an internal React Native API. It is intentionally **not** symmetric with iOS; do not rely on it for production loads. Track the open question in `SECURITY.md`.

## Provenance

This is a fork of `react-native-bundle-loader@0.1.0` originally published by Jusbrasil (2020-10-21, npm publisher `helielson`, commit `ec3d4520`). The upstream GitHub repo at `github.com/jusbrasil/react-native-bundle-loader` was subsequently deleted. The complete original git history is preserved through the v0.1.0 release commit; the Android implementation was contributed by `milad.bagherii@digikala.com` in the `mldb/react-native-bundle-loader` mirror in 2021.

## Security

See `SECURITY.md` for the threat model, accepted residual risks, and disclosure procedure.

## License

MIT
