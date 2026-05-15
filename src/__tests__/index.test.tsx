/* eslint-env node, jest */

import { NativeModules } from 'react-native';
import { loadVerified } from '../index';

type NativeMock = {
  load: jest.Mock;
  loadVerifiedFromUrl: jest.Mock;
  runningMode: jest.Mock;
};

const native: NativeMock = {
  load: jest.fn(),
  loadVerifiedFromUrl: jest.fn(),
  runningMode: jest.fn(),
};

(NativeModules as Record<string, unknown>).BundleLoader = native;

const URL_OK = 'https://example.com/bundle.jsbundle';
const SHA256_OK = 'a'.repeat(64);

beforeEach(() => {
  jest.clearAllMocks();
  native.loadVerifiedFromUrl.mockResolvedValue(undefined);
});

describe('loadVerified — input validation', () => {
  it('rejects a non-https URL', async () => {
    await expect(
      loadVerified('http://example.com/bundle.jsbundle', SHA256_OK)
    ).rejects.toThrow(/https scheme/);
    expect(native.loadVerifiedFromUrl).not.toHaveBeenCalled();
  });

  it('rejects an empty URL', async () => {
    await expect(loadVerified('', SHA256_OK)).rejects.toThrow(/non-empty/);
  });

  it('rejects a sha256 shorter than 64 chars', async () => {
    await expect(loadVerified(URL_OK, '0'.repeat(63))).rejects.toThrow(
      /64-character/
    );
    expect(native.loadVerifiedFromUrl).not.toHaveBeenCalled();
  });

  it('rejects a sha256 longer than 64 chars', async () => {
    await expect(loadVerified(URL_OK, '0'.repeat(65))).rejects.toThrow(
      /64-character/
    );
    expect(native.loadVerifiedFromUrl).not.toHaveBeenCalled();
  });

  it('rejects when sha256 is not a string', async () => {
    await expect(
      loadVerified(URL_OK, (null as unknown) as string)
    ).rejects.toThrow(/64-character/);
    expect(native.loadVerifiedFromUrl).not.toHaveBeenCalled();
  });
});

describe('loadVerified — native wiring', () => {
  it('throws when the native module is not linked', async () => {
    const saved = (NativeModules as Record<string, unknown>).BundleLoader;
    (NativeModules as Record<string, unknown>).BundleLoader = undefined;
    try {
      await expect(loadVerified(URL_OK, SHA256_OK)).rejects.toThrow(
        /not linked/
      );
    } finally {
      (NativeModules as Record<string, unknown>).BundleLoader = saved;
    }
  });

  it('throws when loadVerifiedFromUrl is not available on the native module', async () => {
    const saved = (NativeModules as Record<string, unknown>).BundleLoader;
    (NativeModules as Record<string, unknown>).BundleLoader = {
      load: jest.fn(),
      runningMode: jest.fn(),
    };
    try {
      await expect(loadVerified(URL_OK, SHA256_OK)).rejects.toThrow(
        /loadVerifiedFromUrl is not available/
      );
    } finally {
      (NativeModules as Record<string, unknown>).BundleLoader = saved;
    }
  });

  it('calls loadVerifiedFromUrl with the url and sha256', async () => {
    await loadVerified(URL_OK, SHA256_OK);
    expect(native.loadVerifiedFromUrl).toHaveBeenCalledTimes(1);
    expect(native.loadVerifiedFromUrl).toHaveBeenCalledWith(URL_OK, SHA256_OK);
  });

  it('resolves when loadVerifiedFromUrl resolves', async () => {
    native.loadVerifiedFromUrl.mockResolvedValue(undefined);
    await expect(loadVerified(URL_OK, SHA256_OK)).resolves.toBeUndefined();
  });

  it('rejects when loadVerifiedFromUrl rejects', async () => {
    native.loadVerifiedFromUrl.mockRejectedValue(
      new Error('E_HASH_MISMATCH: Bundle hash mismatch')
    );
    await expect(loadVerified(URL_OK, SHA256_OK)).rejects.toThrow(
      /hash mismatch/
    );
  });
});
