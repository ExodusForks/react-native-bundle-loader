#import "BundleLoader.h"
#import <CommonCrypto/CommonDigest.h>

@implementation BundleLoader

@synthesize bridge = _bridge;

RCT_EXPORT_MODULE()

- (void)setBundleURLAndReload:(NSURL *)url
{
  [_bridge setValue:url forKey:@"bundleURL"];
  [_bridge reload];
}

RCT_EXPORT_METHOD(runningMode:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  NSString *scheme = [[_bridge bundleURL] scheme];
  BOOL isRemote = [scheme isEqualToString:@"https"];
  resolve(isRemote ? @"REMOTE" : @"LOCAL");
}

RCT_EXPORT_METHOD(load:(NSURL *)url)
{
  if (![[url scheme] isEqualToString:@"https"]) {
    return;
  }
  dispatch_async(dispatch_get_main_queue(), ^{
    [self setBundleURLAndReload:url];
  });
}

// Downloads the bundle at `urlString`, verifies its SHA-256 digest against
// `expectedHex` using a constant-time byte comparison, then writes it to the
// sandbox temp directory and reloads the bridge — all in native code to avoid
// the Hermes RangeError that JS-side response.arrayBuffer() causes on large
// bundles (~70 MB+).
RCT_EXPORT_METHOD(loadVerifiedFromUrl:(NSString *)urlString
                  expectedSha256:(NSString *)expectedHex
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  NSURL *url = [NSURL URLWithString:urlString];
  if (![[url scheme] isEqualToString:@"https"]) {
    reject(@"E_INVALID_URL", @"Bundle URL must use the https scheme", nil);
    return;
  }

  if (expectedHex.length != 64) {
    reject(@"E_INVALID_HASH", @"Expected SHA-256 must be a 64-character hex string", nil);
    return;
  }

  // Parse hex string to raw bytes up-front so we can reject early on bad input.
  const char *cStr = [expectedHex UTF8String];
  uint8_t expectedDigestBuf[CC_SHA256_DIGEST_LENGTH];
  for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; i++) {
    char buf[3] = { cStr[i * 2], cStr[i * 2 + 1], '\0' };
    char *end;
    unsigned long val = strtoul(buf, &end, 16);
    if (end != buf + 2) {
      reject(@"E_INVALID_HASH", @"Expected SHA-256 contains invalid hex characters", nil);
      return;
    }
    expectedDigestBuf[i] = (uint8_t)val;
  }
  // Wrap in NSData so the block can capture it as an object pointer.
  NSData *expectedData = [NSData dataWithBytes:expectedDigestBuf length:CC_SHA256_DIGEST_LENGTH];

  NSURLSessionDataTask *task = [[NSURLSession sharedSession]
      dataTaskWithURL:url
    completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
    if (error) {
      reject(@"E_FETCH_FAILED", error.localizedDescription, error);
      return;
    }

    NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
    if (http.statusCode < 200 || http.statusCode >= 300) {
      reject(@"E_FETCH_FAILED",
             [NSString stringWithFormat:@"Bundle fetch failed: HTTP %ld", (long)http.statusCode],
             nil);
      return;
    }

    // Compute SHA-256 of the downloaded bytes.
    uint8_t actualDigest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(data.bytes, (CC_LONG)data.length, actualDigest);

    // Constant-time comparison: XOR all byte pairs and check the accumulator.
    const uint8_t *expected = (const uint8_t *)expectedData.bytes;
    uint8_t diff = 0;
    for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; i++) {
      diff |= actualDigest[i] ^ expected[i];
    }
    if (diff != 0) {
      reject(@"E_HASH_MISMATCH", @"Bundle hash mismatch — refusing to load", nil);
      return;
    }

    NSString *path = [NSTemporaryDirectory()
        stringByAppendingPathComponent:@"verified-bundle.jsbundle"];
    NSError *writeError = nil;
    if (![data writeToFile:path
                  options:NSDataWritingAtomic | NSDataWritingFileProtectionComplete
                    error:&writeError]) {
      reject(@"E_WRITE_FAILED", writeError.localizedDescription, writeError);
      return;
    }

    NSURL *fileURL = [NSURL fileURLWithPath:path];
    dispatch_async(dispatch_get_main_queue(), ^{
      [self setBundleURLAndReload:fileURL];
      resolve(nil);
    });
  }];

  [task resume];
}

@end
