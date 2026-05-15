package com.reactnativebundleloader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class BundleLoaderModule extends ReactContextBaseJavaModule {

  private static final String TAG = "BundleLoader";
  // These values are referenced by string literals in the host app's MainApplication.
  // Do not rename without updating the host app integration accordingly.
  static final String BUNDLE_FILENAME = "verified-bundle.jsbundle";
  static final String PREFS_NAME = "BundleLoader";
  static final String PREFS_PENDING_KEY = "pending_remote_bundle";
  static final String PREFS_ACTIVE_KEY = "active_remote_bundle";

  private static final int CONNECT_TIMEOUT_MS = 30_000;
  private static final int READ_TIMEOUT_MS = 30_000;
  static final long MAX_BUNDLE_BYTES = 64L * 1024L * 1024L;

  BundleLoaderModule(ReactApplicationContext context) {
    super(context);
  }

  @NonNull
  @Override
  public String getName() {
    return "BundleLoader";
  }

  @ReactMethod
  public void load(final String url) {
    if (!isHttps(url)) {
      Log.e(TAG, "Bundle URL must use the https scheme");
      return;
    }
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          File targetFile = new File(
              getReactApplicationContext().getCacheDir(),
              BUNDLE_FILENAME
          );
          downloadToCache(
              url,
              targetFile,
              CONNECT_TIMEOUT_MS,
              READ_TIMEOUT_MS,
              MAX_BUNDLE_BYTES
          );
          setPendingFlag();
          restartApp();
        } catch (Exception e) {
          Log.e(TAG, "load(" + url + ") failed", e);
        }
      }
    }, "BundleLoader-load").start();
  }

  @ReactMethod
  public void loadVerifiedFromUrl(final String url, final String expectedSha256, final Promise promise) {
    if (!isHttps(url)) {
      promise.reject("E_INVALID_URL", "Bundle URL must use the https scheme");
      return;
    }

    final byte[] expectedDigest;
    try {
      expectedDigest = parseHexSha256(expectedSha256);
    } catch (IllegalArgumentException e) {
      promise.reject("E_INVALID_HASH", e.getMessage());
      return;
    }

    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          File targetFile = new File(
              getReactApplicationContext().getCacheDir(),
              BUNDLE_FILENAME
          );
          byte[] actualDigest = downloadAndHashToCache(
              url,
              targetFile,
              CONNECT_TIMEOUT_MS,
              READ_TIMEOUT_MS,
              MAX_BUNDLE_BYTES
          );
          if (!timingSafeEquals(actualDigest, expectedDigest)) {
            promise.reject("E_HASH_MISMATCH", "Bundle hash mismatch — refusing to load");
            return;
          }
          // Resolve before killing the process so the JS side receives the result.
          promise.resolve(null);
          setPendingFlag();
          restartApp();
        } catch (Exception e) {
          promise.reject("E_LOAD_FAILED", e.getMessage(), e);
        }
      }
    }, "BundleLoader-loadVerifiedFromUrl").start();
  }

  @ReactMethod
  public void runningMode(Promise promise) {
    SharedPreferences prefs = getReactApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    promise.resolve(prefs.getBoolean(PREFS_ACTIVE_KEY, false) ? "REMOTE" : "LOCAL");
  }

  private void setPendingFlag() {
    // commit() not apply() — apply() is async and the write may not reach disk
    // before killProcess() terminates the process.
    getReactApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREFS_PENDING_KEY, true)
        .commit();
  }

  /**
   * Restarts the app process. On next launch, MainApplication reads the pending
   * flag from SharedPreferences and loads the cached bundle instead of Metro.
   * A process restart avoids running both the old and new Hermes runtimes
   * simultaneously, which would exceed the device heap limit.
   */
  private void restartApp() {
    Context context = getReactApplicationContext();
    Intent intent = context.getPackageManager()
        .getLaunchIntentForPackage(context.getPackageName());
    if (intent != null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      context.startActivity(intent);
    }
    android.os.Process.killProcess(android.os.Process.myPid());
  }

  /**
   * Returns true iff the URL is non-null and uses the https scheme.
   * Extracted as a static helper so it can be unit-tested without the threading
   * machinery in {@link #load(String)}.
   */
  static boolean isHttps(String url) {
    return url != null && url.startsWith("https://");
  }

  /**
   * Parses a 64-character lowercase hex string into a 32-byte SHA-256 digest.
   * Throws {@link IllegalArgumentException} on invalid input so callers can
   * reject the promise before touching the network.
   */
  static byte[] parseHexSha256(String hex) {
    if (hex == null || hex.length() != 64) {
      throw new IllegalArgumentException("Expected SHA-256 must be a 64-character hex string");
    }
    byte[] out = new byte[32];
    for (int i = 0; i < 32; i++) {
      int hi = Character.digit(hex.charAt(i * 2), 16);
      int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
      if (hi < 0 || lo < 0) {
        throw new IllegalArgumentException("Expected SHA-256 contains invalid hex characters");
      }
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  /**
   * Constant-time byte array comparison: XORs all pairs into an accumulator
   * and returns true iff the accumulator is zero. Both arrays must be the
   * same length; returns false immediately if they differ.
   */
  static boolean timingSafeEquals(byte[] a, byte[] b) {
    if (a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff == 0;
  }

  /**
   * Downloads {@code urlString} into {@code targetFile}, computing SHA-256 of
   * the body in the same streaming pass. Returns the 32-byte digest.
   * <p>
   * Mirrors the security properties of {@link #downloadToCache}: redirects are
   * disabled, non-200 responses throw, and the body is capped at {@code maxBytes}.
   * <p>
   * Package-private and static so the JVM unit tests can drive it against a
   * MockWebServer without spinning up a ReactApplicationContext.
   */
  static byte[] downloadAndHashToCache(
      String urlString,
      File targetFile,
      int connectTimeoutMs,
      int readTimeoutMs,
      long maxBytes
  ) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 not available: " + e.getMessage(), e);
    }

    URL url = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(connectTimeoutMs);
    conn.setReadTimeout(readTimeoutMs);
    conn.setInstanceFollowRedirects(false);
    try {
      int code = conn.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK) {
        throw new IOException("Bundle fetch failed: HTTP " + code);
      }
      long total = 0;
      try (InputStream in = conn.getInputStream();
           FileOutputStream out = new FileOutputStream(targetFile)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          total += n;
          if (total > maxBytes) {
            throw new IOException("Bundle exceeds " + maxBytes + " bytes");
          }
          out.write(buf, 0, n);
          digest.update(buf, 0, n);
        }
      }
      return digest.digest();
    } finally {
      conn.disconnect();
    }
  }

  /**
   * Downloads {@code urlString} into {@code targetFile} using HttpURLConnection.
   * Redirects are not followed and non-200 responses throw IOException with the
   * status code in the message. The download is hard-capped at {@code maxBytes};
   * once the cap is exceeded the read loop aborts with IOException.
   * <p>
   * Package-private and static so the JVM unit tests can drive it against a
   * MockWebServer without spinning up a ReactApplicationContext.
   */
  static File downloadToCache(
      String urlString,
      File targetFile,
      int connectTimeoutMs,
      int readTimeoutMs,
      long maxBytes
  ) throws IOException {
    URL url = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(connectTimeoutMs);
    conn.setReadTimeout(readTimeoutMs);
    // Disallow follow-redirects so an HTTPS URL cannot transparently downgrade to HTTP.
    conn.setInstanceFollowRedirects(false);
    try {
      int code = conn.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK) {
        throw new IOException("Bundle fetch failed: HTTP " + code);
      }
      long total = 0;
      try (InputStream in = conn.getInputStream();
           FileOutputStream out = new FileOutputStream(targetFile)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          total += n;
          if (total > maxBytes) {
            throw new IOException(
                "Bundle exceeds " + maxBytes + " bytes"
            );
          }
          out.write(buf, 0, n);
        }
      }
      return targetFile;
    } finally {
      conn.disconnect();
    }
  }
}
