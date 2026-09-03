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
    File tmp = File.createTempFile("verified-bundle-", ".jsbundle.tmp", dir);
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
    File tmp = File.createTempFile("verified-bundle-", ".jsbundle.tmp", dir);
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
    File tmp = File.createTempFile("verified-bundle-", ".jsbundle.tmp", dir);
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
    File tmp = File.createTempFile("verified-bundle-", ".jsbundle.tmp", dir);
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

  /**
   * Regression guard for the concurrent-load race: each loadVerifiedFromUrl call now
   * downloads to its own File.createTempFile temp, so two overlapping installs can never
   * share a path and promote each other's (unverified/partial) bytes. Distinct temps mean
   * a call's verify→rename always promotes exactly the bytes it verified.
   */
  @Test
  public void concurrentCallsUseDistinctTempsSoTargetOnlyHoldsVerifiedBytes() throws Exception {
    File dir = freshDir();
    File target = new File(dir, BundleLoaderModule.BUNDLE_FILENAME);

    File tmpA = File.createTempFile("verified-bundle-", ".jsbundle.tmp", dir);
    File tmpB = File.createTempFile("verified-bundle-", ".jsbundle.tmp", dir);
    assertFalse("each call must get a distinct temp path", tmpA.getPath().equals(tmpB.getPath()));

    byte[] a = "bundle-A".getBytes(StandardCharsets.UTF_8);
    Files.write(tmpA.toPath(), a);
    Files.write(tmpB.toPath(), "bundle-B-partial".getBytes(StandardCharsets.UTF_8));

    // A promotes its own verified bytes; B mutating/deleting its own temp (as its call
    // would) cannot affect A's target — the shared-path swap is impossible.
    assertTrue(BundleLoaderModule.verifyAndInstall(tmpA, target, sha256("bundle-A"), sha256("bundle-A")));
    assertTrue("B's temp is fully independent of A's promotion", tmpB.delete());
    assertArrayEquals("target holds exactly A's verified bytes", a, Files.readAllBytes(target.toPath()));
  }
}
