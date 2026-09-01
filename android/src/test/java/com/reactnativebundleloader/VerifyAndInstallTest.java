package com.reactnativebundleloader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import org.junit.Test;

/**
 * Drives {@link BundleLoaderModule#verifyAndInstall}: the canonical bundle path must
 * only ever hold verified bytes, and the temp file must never be left behind.
 */
public class VerifyAndInstallTest {

  private static byte[] sha256(String s) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
  }

  private File freshDir() throws IOException {
    File d = Files.createTempDirectory("bl-verify").toFile();
    d.deleteOnExit();
    return d;
  }

  @Test
  public void promotesTempToTargetOnHashMatch() throws Exception {
    File dir = freshDir();
    File tmp = new File(dir, BundleLoaderModule.BUNDLE_TMP_FILENAME);
    File target = new File(dir, BundleLoaderModule.BUNDLE_FILENAME);
    byte[] body = "bundle-bytes".getBytes(StandardCharsets.UTF_8);
    Files.write(tmp.toPath(), body);
    byte[] digest = sha256("bundle-bytes");

    boolean installed = BundleLoaderModule.verifyAndInstall(tmp, target, digest, digest);

    assertTrue("verified bundle should install", installed);
    assertFalse("temp file must be gone after promotion", tmp.exists());
    assertTrue("target must exist", target.exists());
    assertArrayEquals(body, Files.readAllBytes(target.toPath()));
  }

  @Test
  public void deletesTempAndDoesNotCreateTargetOnMismatch() throws Exception {
    File dir = freshDir();
    File tmp = new File(dir, BundleLoaderModule.BUNDLE_TMP_FILENAME);
    File target = new File(dir, BundleLoaderModule.BUNDLE_FILENAME);
    Files.write(tmp.toPath(), "attacker-bytes".getBytes(StandardCharsets.UTF_8));

    boolean installed = BundleLoaderModule.verifyAndInstall(
        tmp, target, sha256("attacker-bytes"), sha256("expected-good"));

    assertFalse("hash mismatch must not install", installed);
    assertFalse("unverified temp must be deleted on mismatch", tmp.exists());
    assertFalse("canonical path must not be created on mismatch", target.exists());
  }

  @Test
  public void doesNotClobberExistingVerifiedBundleOnMismatch() throws Exception {
    File dir = freshDir();
    File tmp = new File(dir, BundleLoaderModule.BUNDLE_TMP_FILENAME);
    File target = new File(dir, BundleLoaderModule.BUNDLE_FILENAME);
    byte[] good = "previously-verified".getBytes(StandardCharsets.UTF_8);
    Files.write(target.toPath(), good);
    Files.write(tmp.toPath(), "bad".getBytes(StandardCharsets.UTF_8));

    boolean installed = BundleLoaderModule.verifyAndInstall(
        tmp, target, sha256("bad"), sha256("something-else"));

    assertFalse(installed);
    assertFalse("temp deleted", tmp.exists());
    assertArrayEquals("existing verified bundle must be untouched", good,
        Files.readAllBytes(target.toPath()));
  }

  @Test
  public void replacesExistingBundleOnHashMatch() throws Exception {
    File dir = freshDir();
    File tmp = new File(dir, BundleLoaderModule.BUNDLE_TMP_FILENAME);
    File target = new File(dir, BundleLoaderModule.BUNDLE_FILENAME);
    Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));
    byte[] body = "new-verified".getBytes(StandardCharsets.UTF_8);
    Files.write(tmp.toPath(), body);
    byte[] digest = sha256("new-verified");

    boolean installed = BundleLoaderModule.verifyAndInstall(tmp, target, digest, digest);

    assertTrue(installed);
    assertFalse(tmp.exists());
    assertArrayEquals(body, Files.readAllBytes(target.toPath()));
  }
}
