package com.reactnativebundleloader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class LoadVerifiedHelpersTest {

  // --- parseHexSha256 ---

  @Test
  public void parseHexSha256_parsesValidLowercaseHex() {
    String hex = "a".repeat(64);
    byte[] result = BundleLoaderModule.parseHexSha256(hex);
    byte[] expected = new byte[32];
    for (int i = 0; i < 32; i++) expected[i] = (byte) 0xaa;
    assertArrayEquals(expected, result);
  }

  @Test
  public void parseHexSha256_parsesKnownDigest() {
    // SHA-256 of empty string
    String hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    byte[] result = BundleLoaderModule.parseHexSha256(hex);
    byte[] expected = new byte[]{
        (byte)0xe3,(byte)0xb0,(byte)0xc4,(byte)0x42,(byte)0x98,(byte)0xfc,(byte)0x1c,(byte)0x14,
        (byte)0x9a,(byte)0xfb,(byte)0xf4,(byte)0xc8,(byte)0x99,(byte)0x6f,(byte)0xb9,(byte)0x24,
        (byte)0x27,(byte)0xae,(byte)0x41,(byte)0xe4,(byte)0x64,(byte)0x9b,(byte)0x93,(byte)0x4c,
        (byte)0xa4,(byte)0x95,(byte)0x99,(byte)0x1b,(byte)0x78,(byte)0x52,(byte)0xb8,(byte)0x55
    };
    assertArrayEquals(expected, result);
  }

  @Test
  public void parseHexSha256_rejectsNull() {
    try {
      BundleLoaderModule.parseHexSha256(null);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("64-character"));
    }
  }

  @Test
  public void parseHexSha256_rejectsTooShort() {
    try {
      BundleLoaderModule.parseHexSha256("a".repeat(63));
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("64-character"));
    }
  }

  @Test
  public void parseHexSha256_rejectsTooLong() {
    try {
      BundleLoaderModule.parseHexSha256("a".repeat(65));
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("64-character"));
    }
  }

  @Test
  public void parseHexSha256_rejectsInvalidHexChars() {
    String bad = "g".repeat(64); // 'g' is not a valid hex character
    try {
      BundleLoaderModule.parseHexSha256(bad);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("invalid hex"));
    }
  }

  // --- timingSafeEquals ---

  @Test
  public void timingSafeEquals_returnsTrueForIdenticalArrays() {
    byte[] a = {1, 2, 3, 4};
    byte[] b = {1, 2, 3, 4};
    assertTrue(BundleLoaderModule.timingSafeEquals(a, b));
  }

  @Test
  public void timingSafeEquals_returnsFalseForDifferentArrays() {
    byte[] a = {1, 2, 3, 4};
    byte[] b = {1, 2, 3, 5};
    assertFalse(BundleLoaderModule.timingSafeEquals(a, b));
  }

  @Test
  public void timingSafeEquals_returnsFalseForOneBitFlip() {
    byte[] a = new byte[32];
    byte[] b = new byte[32];
    b[31] = 1;
    assertFalse(BundleLoaderModule.timingSafeEquals(a, b));
  }

  @Test
  public void timingSafeEquals_returnsFalseForDifferentLengths() {
    byte[] a = {1, 2, 3};
    byte[] b = {1, 2, 3, 4};
    assertFalse(BundleLoaderModule.timingSafeEquals(a, b));
  }

  @Test
  public void timingSafeEquals_returnsTrueForEmptyArrays() {
    assertTrue(BundleLoaderModule.timingSafeEquals(new byte[0], new byte[0]));
  }
}
