package com.reactnativebundleloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.JSBundleLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.Test;

/**
 * Canary tests that fail loudly if a React Native upgrade renames or removes
 * the private symbols that {@code BundleLoaderModule#swapBundleLoaderAndReload}
 * reflects on. Without these, a silent rename would surface only at runtime
 * on a device, after the bundle swap had already failed.
 */
public class ReactInternalsCanaryTest {

  @Test
  public void reactInstanceManager_hasPrivateBundleLoaderField() throws Exception {
    Field field = ReactInstanceManager.class.getDeclaredField("mBundleLoader");

    assertNotNull("mBundleLoader field must exist on ReactInstanceManager", field);
    assertEquals(
        "mBundleLoader field must be typed JSBundleLoader",
        JSBundleLoader.class,
        field.getType()
    );
  }

  @Test
  public void reactInstanceManager_hasRecreateReactContextInBackgroundMethod()
      throws Exception {
    Method method =
        ReactInstanceManager.class.getDeclaredMethod("recreateReactContextInBackground");

    assertNotNull(
        "recreateReactContextInBackground() must exist on ReactInstanceManager",
        method
    );
  }

  @Test
  public void jsBundleLoader_hasPublicStaticCreateFileLoader() throws Exception {
    Method method =
        JSBundleLoader.class.getDeclaredMethod("createFileLoader", String.class);

    assertNotNull("createFileLoader(String) must exist on JSBundleLoader", method);
    assertTrue(
        "createFileLoader(String) must be public",
        Modifier.isPublic(method.getModifiers())
    );
    assertTrue(
        "createFileLoader(String) must be static",
        Modifier.isStatic(method.getModifiers())
    );
    assertEquals(
        "createFileLoader(String) must return a JSBundleLoader",
        JSBundleLoader.class,
        method.getReturnType()
    );
  }
}
