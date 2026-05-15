package com.reactnativebundleloader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DownloadAndHashToCacheTest {

  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final int READ_TIMEOUT_MS = 5_000;
  private static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;

  private MockWebServer server;
  private Path tmpDir;
  private File targetFile;

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    tmpDir = Files.createTempDirectory("bundle-loader-hash-test");
    targetFile = new File(tmpDir.toFile(), "verified-bundle.jsbundle");
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
    if (targetFile.exists()) targetFile.delete();
    if (tmpDir != null) tmpDir.toFile().delete();
  }

  private static byte[] sha256(byte[] data) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(data);
  }

  @Test
  public void returnsCorrectSha256AndWritesFile() throws Exception {
    byte[] body = "console.log('hello verified bundle');".getBytes("UTF-8");
    server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(body)));

    byte[] digest = BundleLoaderModule.downloadAndHashToCache(
        server.url("/bundle.js").toString(),
        targetFile,
        CONNECT_TIMEOUT_MS,
        READ_TIMEOUT_MS,
        DEFAULT_MAX_BYTES
    );

    assertArrayEquals(sha256(body), digest);
    assertTrue(targetFile.exists());
    assertArrayEquals(body, Files.readAllBytes(targetFile.toPath()));
  }

  @Test
  public void digestMatchesIndependentSha256OfWrittenFile() throws Exception {
    byte[] body = new byte[32 * 1024]; // 32 KB across multiple read() chunks
    for (int i = 0; i < body.length; i++) body[i] = (byte) (i & 0xFF);
    server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(body)));

    byte[] digest = BundleLoaderModule.downloadAndHashToCache(
        server.url("/bundle.js").toString(),
        targetFile,
        CONNECT_TIMEOUT_MS,
        READ_TIMEOUT_MS,
        DEFAULT_MAX_BYTES
    );

    byte[] fileBytes = Files.readAllBytes(targetFile.toPath());
    assertArrayEquals(sha256(fileBytes), digest);
  }

  @Test
  public void throwsOnNon200AndDoesNotWriteFile() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));

    try {
      BundleLoaderModule.downloadAndHashToCache(
          server.url("/missing.js").toString(),
          targetFile,
          CONNECT_TIMEOUT_MS,
          READ_TIMEOUT_MS,
          DEFAULT_MAX_BYTES
      );
      fail("expected IOException for 404");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("404"));
    }

    assertFalse(targetFile.exists());
  }

  @Test
  public void throwsWhenBodyExceedsMaxBytes() throws Exception {
    long maxBytes = 1024L;
    byte[] body = new byte[(int) maxBytes * 2];
    server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(body)));

    try {
      BundleLoaderModule.downloadAndHashToCache(
          server.url("/big.js").toString(),
          targetFile,
          CONNECT_TIMEOUT_MS,
          READ_TIMEOUT_MS,
          maxBytes
      );
      fail("expected IOException when body exceeds maxBytes");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("exceeds") || e.getMessage().contains(String.valueOf(maxBytes)));
    }
  }

  @Test
  public void doesNotFollowRedirects() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location", server.url("/elsewhere").toString()));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("REDIRECT_BODY"));

    try {
      BundleLoaderModule.downloadAndHashToCache(
          server.url("/redirected.js").toString(),
          targetFile,
          CONNECT_TIMEOUT_MS,
          READ_TIMEOUT_MS,
          DEFAULT_MAX_BYTES
      );
      fail("expected IOException because redirects are disabled");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("302"));
    }

    assertFalse(targetFile.exists());
  }
}
