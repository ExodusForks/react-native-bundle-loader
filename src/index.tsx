/** @format */
import React, { useCallback, useState } from 'react';
import {
  Alert,
  Modal,
  NativeModules,
  NativeSyntheticEvent,
  Platform,
  StyleSheet,
  Text,
  TextInput,
  TextInputChangeEventData,
  TouchableOpacity,
  View,
} from 'react-native';
import { hash } from '@exodus/crypto/hash';
import { fromHex } from '@exodus/bytes/hex.js';
import { toBase64 } from '@exodus/bytes/base64.js';

export type RunningMode = 'LOCAL' | 'REMOTE';

type NativeBundleLoader = {
  load(url: string): void;
  loadFromBase64?(base64: string): Promise<void>;
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

function timingSafeEqualBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  /* eslint-disable no-bitwise */
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  /* eslint-enable no-bitwise */
  return diff === 0;
}

export async function loadVerified(
  url: string,
  expectedSha256Hex: string
): Promise<void> {
  if (Platform.OS !== 'ios') {
    throw new Error(
      `loadVerified is only supported on iOS (Platform.OS=${Platform.OS})`
    );
  }
  assertSafeUrl(url);
  if (
    typeof expectedSha256Hex !== 'string' ||
    expectedSha256Hex.length !== 64
  ) {
    throw new Error('Expected sha256 must be a 64-character hex string');
  }
  const expected = fromHex(expectedSha256Hex);

  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Bundle fetch failed: HTTP ${response.status}`);
  }
  const buffer = await response.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  const actual = await hash('sha256', bytes, 'uint8');

  if (!timingSafeEqualBytes(actual, expected)) {
    throw new Error('Bundle hash mismatch — refusing to load');
  }

  const native = getNative();
  if (typeof native.loadFromBase64 !== 'function') {
    throw new Error(
      'Native loadFromBase64 not available. Rebuild the host app after upgrading.'
    );
  }
  await native.loadFromBase64(toBase64(bytes));
}

function loadUnverified(url: string): void {
  assertSafeUrl(url);
  getNative().load(url);
}

async function runningMode(): Promise<RunningMode> {
  return getNative().runningMode();
}

const BundleLoader = {
  load: loadUnverified,
  loadVerified,
  runningMode,
};

export default BundleLoader;

const styles = StyleSheet.create({
  container: { marginTop: 48, padding: 16, flex: 1 },
  input: {
    height: 48,
    marginTop: 8,
    paddingHorizontal: 8,
    borderColor: 'gray',
    borderRadius: 4,
    borderWidth: 1,
  },
  button: {
    backgroundColor: '#007AFF',
    marginTop: 16,
    height: 48,
    justifyContent: 'center',
  },
  buttonText: {
    color: 'white',
    alignSelf: 'center',
    fontSize: 18,
    alignContent: 'center',
  },
});

export function BundlePrompt() {
  const [url, setUrl] = useState<string>('');

  const reload = useCallback(() => {
    if (!url) {
      Alert.alert('Oops…', 'You need to provide a URL');
      return;
    }
    try {
      loadUnverified(url);
    } catch (e) {
      Alert.alert('Invalid URL', (e as Error).message);
    }
  }, [url]);

  return (
    <Modal>
      <View style={styles.container}>
        <TextInput
          keyboardType="url"
          onChange={(e: NativeSyntheticEvent<TextInputChangeEventData>) =>
            setUrl(e.nativeEvent.text.trim())
          }
          style={styles.input}
          clearButtonMode="always"
          autoFocus
          placeholder="https://…"
        />
        <TouchableOpacity
          style={styles.button}
          onPress={reload}
          accessibilityLabel="Reload entire app"
        >
          <Text style={styles.buttonText}>Reload</Text>
        </TouchableOpacity>
      </View>
    </Modal>
  );
}
