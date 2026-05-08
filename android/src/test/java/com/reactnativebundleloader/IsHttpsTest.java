package com.reactnativebundleloader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Direct unit tests for {@link BundleLoaderModule#isHttps(String)} so we can
 * cover URL scheme rejection without dragging in the threading + native-module
 * machinery that wraps {@code load()}.
 */
public class IsHttpsTest {

  @Test
  public void acceptsHttpsUrl() {
    assertTrue(BundleLoaderModule.isHttps("https://example.com/bundle.js"));
  }

  @Test
  public void rejectsHttpUrl() {
    assertFalse(BundleLoaderModule.isHttps("http://example.com/bundle.js"));
  }

  @Test
  public void rejectsNullUrl() {
    assertFalse(BundleLoaderModule.isHttps(null));
  }

  @Test
  public void rejectsEmptyString() {
    assertFalse(BundleLoaderModule.isHttps(""));
  }

  @Test
  public void rejectsFileUrl() {
    assertFalse(BundleLoaderModule.isHttps("file:///tmp/bundle.js"));
  }

  @Test
  public void rejectsFtpUrl() {
    assertFalse(BundleLoaderModule.isHttps("ftp://example.com/bundle.js"));
  }

  @Test
  public void rejectsProtocolRelativeUrl() {
    assertFalse(BundleLoaderModule.isHttps("//example.com/bundle.js"));
  }

  @Test
  public void rejectsUppercaseHttpsScheme() {
    // startsWith is case-sensitive; documented behavior of the helper.
    assertFalse(BundleLoaderModule.isHttps("HTTPS://example.com/bundle.js"));
  }

  @Test
  public void rejectsUrlWithEmbeddedHttps() {
    assertFalse(BundleLoaderModule.isHttps("http://evil.example/?u=https://"));
  }

  @Test
  public void rejectsLeadingWhitespace() {
    assertFalse(BundleLoaderModule.isHttps("  https://example.com/bundle.js"));
  }
}
