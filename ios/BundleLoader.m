#import "BundleLoader.h"

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

RCT_EXPORT_METHOD(loadFromBase64:(NSString *)base64
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  NSData *data = [[NSData alloc] initWithBase64EncodedString:base64
                                                     options:0];
  if (data == nil) {
    reject(@"E_INVALID_BASE64", @"Invalid base64 input", nil);
    return;
  }

  NSString *path = [NSTemporaryDirectory()
      stringByAppendingPathComponent:@"verified-bundle.jsbundle"];
  NSError *err = nil;
  if (![data writeToFile:path
                options:NSDataWritingAtomic | NSDataWritingFileProtectionComplete
                  error:&err]) {
    reject(@"E_WRITE_FAILED", err.localizedDescription, err);
    return;
  }

  NSURL *fileURL = [NSURL fileURLWithPath:path];
  dispatch_async(dispatch_get_main_queue(), ^{
    [self setBundleURLAndReload:fileURL];
    resolve(nil);
  });
}

@end
