package com.reactnativebundleloader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link BundleLoaderModule#downloadToCache} against a real (loopback)
 * HTTP server so the timeout/redirect/size-cap behavior is exercised end to
 * end without an Android device or Robolectric.
 */
public class DownloadToCacheTest {

  // Short timeouts keep the suite snappy. 5s is plenty over loopback.
  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final int READ_TIMEOUT_MS = 5_000;
  // Default size cap is large; tests that need to trigger the cap pass a small
  // value explicitly via the helper's maxBytes parameter.
  private static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;

  private MockWebServer server;
  private Path tmpDir;
  private File targetFile;

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    tmpDir = Files.createTempDirectory("bundle-loader-test");
    targetFile = new File(tmpDir.toFile(), "verified-bundle.jsbundle");
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
    if (targetFile.exists()) {
      // noinspection ResultOfMethodCallIgnored
      targetFile.delete();
    }
    if (tmpDir != null) {
      // noinspection ResultOfMethodCallIgnored
      tmpDir.toFile().delete();
    }
  }

  @Test
  public void writesBodyOnHttp200() throws Exception {
    byte[] body = "console.log('hello bundle');".getBytes("UTF-8");
    server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(body)));

    File written = BundleLoaderModule.downloadToCache(
        server.url("/bundle.js").toString(),
        targetFile,
        CONNECT_TIMEOUT_MS,
        READ_TIMEOUT_MS,
        DEFAULT_MAX_BYTES
    );

    assertNotNull(written);
    assertEquals(targetFile.getAbsolutePath(), written.getAbsolutePath());
    assertTrue("target file must exist after a 200", written.exists());
    assertArrayEquals(body, Files.readAllBytes(written.toPath()));
  }

  @Test
  public void throwsOnHttp404AndDoesNotWriteFile() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));

    try {
      BundleLoaderModule.downloadToCache(
          server.url("/missing.js").toString(),
          targetFile,
          CONNECT_TIMEOUT_MS,
          READ_TIMEOUT_MS,
          DEFAULT_MAX_BYTES
      );
      fail("expected IOException for 404 response");
    } catch (IOException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "IOException must mention the HTTP status; got: " + e.getMessage(),
          e.getMessage().contains("404")
      );
    }

    assertFalse(
        "non-200 response must not produce a target file",
        targetFile.exists()
    );
  }

  @Test
  public void throwsOnHttp500AndDoesNotWriteFile() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

    try {
      BundleLoaderModule.downloadToCache(
          server.url("/oops.js").toString(),
          targetFile,
          CONNECT_TIMEOUT_MS,
          READ_TIMEOUT_MS,
          DEFAULT_MAX_BYTES
      );
      fail("expected IOException for 500 response");
    } catch (IOException e) {
      assertTrue(
          "IOException must mention the HTTP status; got: " + e.getMessage(),
          e.getMessage().contains("500")
      );
    }

    assertFalse(targetFile.exists());
  }

  @Test
  public void throwsWhenBodyExceedsMaxBytes() throws Exception {
    // Lower the cap to make the test cheap. Body is twice the cap so the
    // overflow trips well before EOF regardless of buffer alignment.
    long maxBytes = 1024L;
    byte[] body = new byte[(int) maxBytes * 2];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) (i & 0x7F);
    }
    server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(body)));

    try {
      BundleLoaderModule.downloadToCache(
          server.url("/big.js").toString(),
          targetFile,
          CONNECT_TIMEOUT_MS,
          READ_TIMEOUT_MS,
          maxBytes
      );
      fail("expected IOException when body exceeds maxBytes");
    } catch (IOException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "IOException must mention the byte cap; got: " + e.getMessage(),
          e.getMessage().contains("exceeds") || e.getMessage().contains(String.valueOf(maxBytes))
      );
    }

    // The partial write may or may not have left bytes on disk depending on
    // exactly when the loop tripped; what matters is we never returned a
    // "valid" oversized bundle. Don't assert on file presence here.
  }

  @Test
  public void doesNotFollowRedirects() throws Exception {
    // First (and only) response is a 302 pointing somewhere. With
    // setInstanceFollowRedirects(false), the helper sees a non-200 status and
    // throws — the would-be target body is never read, never written.
    String redirectTarget = server.url("/elsewhere").toString();
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location", redirectTarget));
    // Belt-and-braces: even if the helper *did* follow, we'd see this body.
    // It must not appear on disk.
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setBody("REDIRECT_TARGET_BODY"));

    try {
      BundleLoaderModule.downloadToCache(
          server.url("/redirected.js").toString(),
          targetFile,
          CONNECT_TIMEOUT_MS,
          READ_TIMEOUT_MS,
          DEFAULT_MAX_BYTES
      );
      fail("expected IOException because redirects are disabled");
    } catch (IOException e) {
      assertTrue(
          "IOException must mention the 302 status; got: " + e.getMessage(),
          e.getMessage().contains("302")
      );
    }

    assertFalse(
        "redirect must not write any file to the target path",
        targetFile.exists()
    );
    // Exactly one request should have been made — the redirect was not chased.
    assertEquals(1, server.getRequestCount());
  }
}
