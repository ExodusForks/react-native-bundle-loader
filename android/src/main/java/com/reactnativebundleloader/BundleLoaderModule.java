package com.reactnativebundleloader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.ReactApplication;
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
  // Host app references these as string literals (library is debugImplementation only).
  static final String BUNDLE_FILENAME = "verified-bundle.jsbundle";
  static final String PREFS_NAME = "BundleLoader";
  static final String PREFS_PENDING_KEY = "pending_remote_bundle";
  static final String PREFS_ACTIVE_KEY = "active_remote_bundle";
  // Metro/APK source URL captured at load time for correct asset resolution after restart.
  static final String PREFS_METRO_SOURCE_URL_KEY = "metro_source_url";

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
    SharedPreferences.Editor editor = getReactApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREFS_PENDING_KEY, true);

    // Store scriptURL for asset resolution after restart: Metro URL if available,
    // asset:/// otherwise (genesis/release — assets served from APK).
    String sourceUrl = getMetroSourceUrl();
    editor.putString(PREFS_METRO_SOURCE_URL_KEY,
        sourceUrl != null ? sourceUrl : "asset:///index.android.bundle");

    // commit() not apply() — write must reach disk before killProcess().
    editor.commit();
  }

  private String getMetroSourceUrl() {
    try {
      ReactApplication app = (ReactApplication) getReactApplicationContext().getApplicationContext();
      String url = app.getReactNativeHost()
          .getReactInstanceManager()
          .getDevSupportManager()
          .getSourceUrl();
      return (url != null && !url.isEmpty()) ? url : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** Kills and relaunches the process. Avoids running two Hermes runtimes simultaneously. */
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

  /** Package-private for testing. */
  static boolean isHttps(String url) {
    return url != null && url.startsWith("https://");
  }

  /** Parses a 64-char hex string into a 32-byte digest. Package-private for testing. */
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

  /** Constant-time equality check to prevent timing attacks. Package-private for testing. */
  static boolean timingSafeEquals(byte[] a, byte[] b) {
    if (a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff == 0;
  }

  /**
   * Downloads into {@code targetFile} and returns its SHA-256 digest.
   * No redirects; non-200 throws; body capped at {@code maxBytes}. Package-private for testing.
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
   * Downloads into {@code targetFile}. No redirects; non-200 throws; body capped at
   * {@code maxBytes}. Package-private for testing.
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
