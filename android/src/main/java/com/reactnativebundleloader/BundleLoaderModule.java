package com.reactnativebundleloader;

import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.devsupport.DevInternalSettings;

public class BundleLoaderModule extends ReactContextBaseJavaModule {

  private static final String TAG = "BundleLoader";

  BundleLoaderModule(ReactApplicationContext context) {
    super(context);
  }

  @NonNull
  @Override
  public String getName() {
    return "BundleLoader";
  }

  @ReactMethod
  public void load(String host) {
    try {
      ReactApplication app = (ReactApplication)
          getReactApplicationContext().getApplicationContext();
      final ReactInstanceManager instanceManager =
          app.getReactNativeHost().getReactInstanceManager();
      DevInternalSettings devSettings = new DevInternalSettings(
          getReactApplicationContext(),
          new DevInternalSettings.Listener() {
            @Override
            public void onInternalSettingsChanged() {
              instanceManager.recreateReactContextInBackground();
            }
          });
      devSettings.getPackagerConnectionSettings().setDebugServerHost(host);
      instanceManager.recreateReactContextInBackground();
    } catch (Exception e) {
      Log.e(TAG, "load(" + host + ") failed", e);
    }
  }

  @ReactMethod
  public void runningMode(Promise promise) {
    promise.reject(
        "E_NOT_SUPPORTED",
        "runningMode is not supported on Android"
    );
  }

  @ReactMethod
  public void loadFromBase64(String base64, Promise promise) {
    promise.reject(
        "E_NOT_SUPPORTED",
        "loadFromBase64 is not supported on Android"
    );
  }
}
