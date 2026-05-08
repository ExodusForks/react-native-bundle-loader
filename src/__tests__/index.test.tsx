/* eslint-env node, jest */

// Jest 26 doesn't follow the package `exports` field that @exodus/crypto and
// @exodus/bytes use, so the loader can't resolve the subpaths under test. Stub
// them with Node-native equivalents — the SHA-256 implementation is the same
// one @exodus/crypto/hash uses on Node anyway.
jest.mock(
  '@exodus/crypto/hash',
  () => ({
    hash: async (algo: string, bytes: Uint8Array): Promise<Uint8Array> => {
      const { createHash } = require('crypto');
      return new Uint8Array(
        createHash(algo).update(Buffer.from(bytes)).digest()
      );
    },
  }),
  { virtual: true }
);

jest.mock(
  '@exodus/bytes/hex.js',
  () => ({
    fromHex: (hex: string): Uint8Array => {
      const out = new Uint8Array(hex.length / 2);
      for (let i = 0; i < out.length; i++) {
        out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
      }
      return out;
    },
  }),
  { virtual: true }
);

jest.mock(
  '@exodus/bytes/base64.js',
  () => ({
    toBase64: (bytes: Uint8Array): string =>
      Buffer.from(bytes).toString('base64'),
  }),
  { virtual: true }
);

import { NativeModules } from 'react-native';
import { createHash } from 'crypto';
import { loadVerified } from '../index';

type NativeMock = {
  load: jest.Mock;
  loadFromBase64: jest.Mock;
  runningMode: jest.Mock;
};

const native: NativeMock = {
  load: jest.fn(),
  loadFromBase64: jest.fn(),
  runningMode: jest.fn(),
};

(NativeModules as Record<string, unknown>).BundleLoader = native;

const stubFetchOk = (bytes: Uint8Array) => {
  const buffer = bytes.buffer.slice(
    bytes.byteOffset,
    bytes.byteOffset + bytes.byteLength
  );
  ((global as unknown) as Record<
    string,
    unknown
  >).fetch = jest.fn().mockResolvedValue({
    ok: true,
    status: 200,
    arrayBuffer: () => Promise.resolve(buffer),
  });
};

const stubFetchFail = (status: number) => {
  ((global as unknown) as Record<
    string,
    unknown
  >).fetch = jest.fn().mockResolvedValue({
    ok: false,
    status,
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
  });
};

const sha256Hex = (bytes: Uint8Array): string =>
  createHash('sha256').update(Buffer.from(bytes)).digest('hex');

const URL_OK = 'https://example.com/bundle.jsbundle';

beforeEach(() => {
  jest.clearAllMocks();
  native.loadFromBase64.mockResolvedValue(undefined);
});

describe('loadVerified — input validation', () => {
  it('rejects a non-https URL', async () => {
    await expect(
      loadVerified('http://example.com/bundle.jsbundle', '0'.repeat(64))
    ).rejects.toThrow(/https scheme/);
    expect(native.loadFromBase64).not.toHaveBeenCalled();
  });

  it('rejects an empty URL', async () => {
    await expect(loadVerified('', '0'.repeat(64))).rejects.toThrow(/non-empty/);
  });

  it('rejects a sha256 with the wrong length', async () => {
    await expect(loadVerified(URL_OK, '0'.repeat(63))).rejects.toThrow(
      /64-character/
    );
    await expect(loadVerified(URL_OK, '0'.repeat(65))).rejects.toThrow(
      /64-character/
    );
  });

  it('rejects when sha256 is not a string', async () => {
    await expect(
      loadVerified(URL_OK, (null as unknown) as string)
    ).rejects.toThrow(/64-character/);
  });
});

describe('loadVerified — network and integrity', () => {
  it('throws when fetch returns non-ok', async () => {
    stubFetchFail(404);
    await expect(loadVerified(URL_OK, '0'.repeat(64))).rejects.toThrow(
      /HTTP 404/
    );
    expect(native.loadFromBase64).not.toHaveBeenCalled();
  });

  it('throws on hash mismatch and does not invoke the native loader', async () => {
    const bytes = new Uint8Array([1, 2, 3, 4]);
    stubFetchOk(bytes);
    await expect(loadVerified(URL_OK, 'a'.repeat(64))).rejects.toThrow(
      /hash mismatch/
    );
    expect(native.loadFromBase64).not.toHaveBeenCalled();
  });

  it('rejects a near-miss hash (one hex digit flipped)', async () => {
    const bytes = new Uint8Array([0xde, 0xad, 0xbe, 0xef]);
    const real = sha256Hex(bytes);
    const last = real[real.length - 1];
    const flipped = last === '0' ? '1' : '0';
    const nearMiss = real.slice(0, -1) + flipped;
    stubFetchOk(bytes);

    await expect(loadVerified(URL_OK, nearMiss)).rejects.toThrow(
      /hash mismatch/
    );
    expect(native.loadFromBase64).not.toHaveBeenCalled();
  });

  it('passes the verified bytes to native.loadFromBase64 on match', async () => {
    const bytes = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]);
    stubFetchOk(bytes);

    await loadVerified(URL_OK, sha256Hex(bytes));

    expect(native.loadFromBase64).toHaveBeenCalledTimes(1);
    const base64Arg = native.loadFromBase64.mock.calls[0][0] as string;
    const decoded = Buffer.from(base64Arg, 'base64');
    expect(Array.from(decoded)).toEqual(Array.from(bytes));
  });
});

describe('loadVerified — native module wiring', () => {
  it('throws when the native module is not linked', async () => {
    const bytes = new Uint8Array([1, 2, 3]);
    stubFetchOk(bytes);
    const saved = (NativeModules as Record<string, unknown>).BundleLoader;
    (NativeModules as Record<string, unknown>).BundleLoader = undefined;
    try {
      await expect(loadVerified(URL_OK, sha256Hex(bytes))).rejects.toThrow(
        /not linked/
      );
    } finally {
      (NativeModules as Record<string, unknown>).BundleLoader = saved;
    }
  });

  it('throws when native.loadFromBase64 is missing', async () => {
    const bytes = new Uint8Array([1, 2, 3]);
    stubFetchOk(bytes);
    const saved = (NativeModules as Record<string, unknown>).BundleLoader;
    (NativeModules as Record<string, unknown>).BundleLoader = {
      load: jest.fn(),
      runningMode: jest.fn(),
    };
    try {
      await expect(loadVerified(URL_OK, sha256Hex(bytes))).rejects.toThrow(
        /loadFromBase64 not available/
      );
    } finally {
      (NativeModules as Record<string, unknown>).BundleLoader = saved;
    }
  });
});
