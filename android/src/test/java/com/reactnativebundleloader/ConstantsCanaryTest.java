package com.reactnativebundleloader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Guards the string constants that are referenced by literal values in the host
 * app's MainApplication.java. The module and host app are in separate packages
 * so a rename here would silently break the Android integration at runtime.
 * These tests catch that at build time instead.
 */
public class ConstantsCanaryTest {

  @Test
  public void bundleFilename_hasExpectedValue() {
    assertEquals("verified-bundle.jsbundle", BundleLoaderModule.BUNDLE_FILENAME);
  }

  @Test
  public void prefsName_hasExpectedValue() {
    assertEquals("BundleLoader", BundleLoaderModule.PREFS_NAME);
  }

  @Test
  public void prefsPendingKey_hasExpectedValue() {
    assertEquals("pending_remote_bundle", BundleLoaderModule.PREFS_PENDING_KEY);
  }

  @Test
  public void prefsActiveKey_hasExpectedValue() {
    assertEquals("active_remote_bundle", BundleLoaderModule.PREFS_ACTIVE_KEY);
  }
}
