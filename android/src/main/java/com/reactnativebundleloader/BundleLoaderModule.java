package com.reactnativebundleloader;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;

public class BundleLoaderModule extends ReactContextBaseJavaModule {

  private static final String TAG = "BundleLoader";
  private static final String BUNDLE_FILENAME = "verified-bundle.jsbundle";
  private static final int CONNECT_TIMEOUT_MS = 30_000;
  private static final int READ_TIMEOUT_MS = 30_000;
  private static final int MAX_BUNDLE_BYTES = 64 * 1024 * 1024;

  private static volatile boolean remoteLoaded = false;

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
    if (url == null || !url.startsWith("https://")) {
      Log.e(TAG, "Bundle URL must use the https scheme");
      return;
    }
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          File bundleFile = downloadToCache(url);
          swapBundleLoaderAndReload(bundleFile);
        } catch (Exception e) {
          Log.e(TAG, "load(" + url + ") failed", e);
        }
      }
    }, "BundleLoader-load").start();
  }

  @ReactMethod
  public void loadFromBase64(String base64, Promise promise) {
    try {
      byte[] data = Base64.decode(base64, Base64.DEFAULT);
      if (data == null || data.length == 0) {
        promise.reject("E_INVALID_BASE64", "Invalid base64 input");
        return;
      }
      File bundleFile = new File(
          getReactApplicationContext().getCacheDir(),
          BUNDLE_FILENAME
      );
      try (FileOutputStream out = new FileOutputStream(bundleFile)) {
        out.write(data);
      }
      swapBundleLoaderAndReload(bundleFile);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("E_LOAD_FAILED", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void runningMode(Promise promise) {
    promise.resolve(remoteLoaded ? "REMOTE" : "LOCAL");
  }

  private File downloadToCache(String urlString) throws IOException {
    URL url = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    // Disallow follow-redirects so an HTTPS URL cannot transparently downgrade to HTTP.
    conn.setInstanceFollowRedirects(false);
    try {
      int code = conn.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK) {
        throw new IOException("Bundle fetch failed: HTTP " + code);
      }
      File file = new File(
          getReactApplicationContext().getCacheDir(),
          BUNDLE_FILENAME
      );
      long total = 0;
      try (InputStream in = conn.getInputStream();
           FileOutputStream out = new FileOutputStream(file)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          total += n;
          if (total > MAX_BUNDLE_BYTES) {
            throw new IOException(
                "Bundle exceeds " + MAX_BUNDLE_BYTES + " bytes"
            );
          }
          out.write(buf, 0, n);
        }
      }
      return file;
    } finally {
      conn.disconnect();
    }
  }

  private void swapBundleLoaderAndReload(File bundleFile) throws Exception {
    ReactApplication app = (ReactApplication)
        getReactApplicationContext().getApplicationContext();
    final ReactInstanceManager instanceManager =
        app.getReactNativeHost().getReactInstanceManager();

    JSBundleLoader bundleLoader =
        JSBundleLoader.createFileLoader(bundleFile.getAbsolutePath());

    // mBundleLoader is private on ReactInstanceManager and there is no public
    // setter. The host app's ReactNativeHost wires the initial loader at
    // construction; we swap it in-place so the next reload picks up our file.
    Field field = ReactInstanceManager.class.getDeclaredField("mBundleLoader");
    field.setAccessible(true);
    field.set(instanceManager, bundleLoader);

    remoteLoaded = true;

    getReactApplicationContext().runOnUiQueueThread(new Runnable() {
      @Override
      public void run() {
        instanceManager.recreateReactContextInBackground();
      }
    });
  }
}
