# Security policy

## Reporting a vulnerability

Email `security@exodus.com`. We will acknowledge within 2 business days. Do not file a public GitHub issue for security reports.

## Threat model

This library exists to load and execute a remote JavaScript bundle inside the host React Native app. That is, by construction, **a remote code execution primitive** with the host app's full bridge surface. Two consequences:

1. The library has no business in a store-distributed binary unless protected by a build-flavor flag that is statically stripped (not just runtime-gated). A leaked release build with this library reachable is equivalent to a leaked signing key.
2. Any defense the library offers is layered on top of that fundamental risk. It does not eliminate it.

## What this fork hardens vs. upstream

| Surface                                     | Upstream `0.1.0`                                        | This fork                                                                                                                          |
| ------------------------------------------- | ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Bundle integrity                            | None — bridge fetches whatever the URL serves           | `loadVerified(url, sha256)` downloads bytes natively (iOS: `NSURLSession`, Android: `HttpURLConnection`), hashes with platform crypto (iOS: `CommonCrypto CC_SHA256`, Android: `MessageDigest SHA-256`), compares in constant-time, writes to app-private storage, and reloads the bridge from the local file — closing the TOCTOU window between fetch and load |
| `BundlePrompt` default URL                  | Hardcoded `cdn.jsdelivr.net/gh/jusbrasil/...` (deleted) | Empty — operator must type a URL                                                                                                   |
| Scheme enforcement                          | None — accepts `http://`, `file://`, etc.               | `https://` required at the JS boundary; both native `load` implementations re-check before touching the network                     |
| Verified bundle on-disk protection (iOS)    | n/a                                                     | Written with `NSDataWritingFileProtectionComplete`                                                                                 |
| Lockfile                                    | Not shipped                                             | `yarn.lock` committed; `.yarnrc` enforces `--frozen-lockfile`                                                                      |
| Dependency version pinning                  | Carets (`^`)                                            | All direct deps pinned to exact versions; `.npmrc` `save-exact=true`                                                               |
| Maven `+` on `react-native`                 | Yes                                                     | Configurable via `rootProject.ext.reactNativeVersion`; default `+` is the only fallback                                            |
| Maven `jcenter()`                           | In repo list                                            | Removed (sunset 2021)                                                                                                              |
| Husky 4 auto-postinstall                    | Yes                                                     | Husky removed entirely                                                                                                             |
| `release-it` publishing                     | Yes                                                     | Removed; releases are manual `npm publish` after `yarn preflight` passes                                                           |
| Eclipse / Xcode user-state in repo          | Committed                                               | Removed and gitignored                                                                                                             |
| Source maps shipped to npm                  | Yes (`lib/**/*.map`)                                    | Excluded via `files` array; tarball denied by `verify-pack` if any reappear                                                        |
| Tarball contents                            | Anything matching `files` blanket globs                 | Strict `.npm-tarball-allowlist` exact-match enforced by `yarn verify-pack`                                                         |
| `example/public/ios.min.js` (700kB blob)    | Committed; served from jsdelivr to any `BundlePrompt`   | Removed along with the rest of `example/`                                                                                          |
| CircleCI / Node 10 build container          | `.circleci/config.yml` shipped                          | Removed                                                                                                                            |

## Accepted residual risks

- **The bridge `bundleURL` setter is a KVC write** on iOS (`[bridge setValue:url forKey:@"bundleURL"]`) to a non-public RN property. Behavior could change on an RN upgrade and silently no-op the loader.
- **The Android bundle swap reflects on a private field.** `ReactInstanceManager.mBundleLoader` has no public setter, so we use `Field.setAccessible(true)` to install a fresh `JSBundleLoader.createFileLoader(...)` before calling `recreateReactContextInBackground()`. The field name has been stable across RN 0.62–0.74 but is not part of the public API; an RN upgrade could rename or remove it, in which case `loadVerified`/`load` will throw `NoSuchFieldException` rather than silently no-op.
- **Hash verification runs in native code, not JS.** `loadVerifiedFromUrl` uses `CommonCrypto CC_SHA256` (iOS) and `MessageDigest SHA-256` (Android) with a constant-time XOR comparison loop in native code. This avoids a Hermes `RangeError: Maximum regex stack depth reached` that the previous JS-side `response.arrayBuffer()` path hit on bundles ≥ ~70 MB. The trade-off is that the integrity contract is no longer auditable as TypeScript.
- **`timingSafeEqual` is an inlined XOR loop in native code.** Both `ios/BundleLoader.m` and `android/src/main/java/com/reactnativebundleloader/BundleLoaderModule.java` XOR all byte pairs into an accumulator and reject the bundle if the accumulator is non-zero.

## Release process

This package has no CI/CD. Maintainers cut releases manually from a developer machine:

```sh
yarn preflight   # lint + typecheck + JS tests + Android JVM tests + iOS XCTests + verify-pack
npm publish --access public
```

`yarn verify-pack` runs `npm pack` and diffs the resulting tarball file list against `.npm-tarball-allowlist`. Any deviation (extra files, missing files, renames) fails the script. Adding a new shippable file to the package requires updating `.npm-tarball-allowlist` in the same commit so the change is reviewable.

The npm publish credential lives on the publishing maintainer's machine. There is no shared CI token, and no GitHub Actions workflow holds publish rights. The trust boundary is whoever has `npm login` access to `@exodus`.

## Out of scope

- The host app's CI pipeline that mints the deep links carrying `(url, sha256)` pairs is **not** covered by this library. Threats #1, #5, #6, #7, #8 in the originating threat model are the consuming app's responsibility.
- Bundle code review and signing are out of scope. This library verifies *what was hashed by your CI* matches *what the device fetched*. It cannot tell you that the bundle does not contain malicious JS.
