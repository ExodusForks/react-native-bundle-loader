# @exodus/react-native-bundle-loader

Loads a remote React Native JS bundle and reloads the bridge.

> Exodus fork of [`react-native-bundle-loader`](https://www.npmjs.com/package/react-native-bundle-loader)
> (originally by Jusbrasil; upstream GitHub repo deleted; full provenance below).

## Installation

```sh
yarn add @exodus/react-native-bundle-loader
cd ios && pod install
```

## Usage

```ts
import BundleLoader, { BundlePrompt } from '@exodus/react-native-bundle-loader';

BundleLoader.load('https://bundles.example.com/main.jsbundle');
```

Or use the developer-facing prompt component:

```tsx
import { BundlePrompt } from '@exodus/react-native-bundle-loader';

<BundlePrompt />;
```

## Threat model

Loading a remote JS bundle is, by construction, remote code execution inside the host app. **This library is intended for internal/development builds only — do not ship it in store builds without an out-of-band, statically-stripped feature flag.**

## Provenance

This is a fork of `react-native-bundle-loader@0.1.0` originally published by Jusbrasil (2020-10-21, npm publisher `helielson`, commit `ec3d4520`). The upstream GitHub repo at `github.com/jusbrasil/react-native-bundle-loader` was subsequently deleted. The complete original git history is preserved through the v0.1.0 release commit; the Android implementation was contributed by `milad.bagherii@digikala.com` in the `mldb/react-native-bundle-loader` mirror in 2021.

## License

MIT
