/** @format */
import { NativeModules } from 'react-native';

export type RunningMode = 'LOCAL' | 'REMOTE';

type NativeBundleLoader = {
  loadVerifiedFromUrl?(url: string, sha256: string): Promise<void>;
  runningMode(): Promise<RunningMode>;
};

function getNative(): NativeBundleLoader {
  const m = (NativeModules as { BundleLoader?: NativeBundleLoader })
    .BundleLoader;
  if (!m) {
    throw new Error(
      "@exodus/react-native-bundle-loader: native module 'BundleLoader' is not linked. " +
        'On iOS, run `pod install` and rebuild the host app. ' +
        'On Android, ensure BundleLoaderPackage is registered.'
    );
  }
  return m;
}

function assertSafeUrl(url: string): void {
  if (typeof url !== 'string' || url.length === 0) {
    throw new Error('Bundle URL must be a non-empty string');
  }
  if (!/^https:\/\//.test(url)) {
    throw new Error('Bundle URL must use the https scheme');
  }
}

export async function loadVerified(
  url: string,
  expectedSha256Hex: string
): Promise<void> {
  assertSafeUrl(url);
  if (
    typeof expectedSha256Hex !== 'string' ||
    expectedSha256Hex.length !== 64
  ) {
    throw new Error('Expected sha256 must be a 64-character hex string');
  }

  const native = getNative();

  if (typeof native.loadVerifiedFromUrl !== 'function') {
    throw new Error(
      'loadVerifiedFromUrl is not available on this platform. Rebuild the host app with the latest native module.'
    );
  }

  await native.loadVerifiedFromUrl(url, expectedSha256Hex);
}

async function runningMode(): Promise<RunningMode> {
  return getNative().runningMode();
}

// Only the verified load path is exposed. The unverified `load()` path was
// removed (security): loading a remote bundle without native SHA-256
// verification is a remote-code-execution primitive with no integrity check.
const BundleLoader = {
  loadVerified,
  runningMode,
};

export default BundleLoader;
