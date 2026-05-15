/** @format */
import React, { useCallback, useState } from 'react';
import {
  Alert,
  Modal,
  NativeModules,
  NativeSyntheticEvent,
  StyleSheet,
  Text,
  TextInput,
  TextInputChangeEventData,
  TouchableOpacity,
  View,
} from 'react-native';

export type RunningMode = 'LOCAL' | 'REMOTE';

type NativeBundleLoader = {
  load(url: string): void;
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
