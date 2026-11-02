/**
 * Test-only stubs for React Native symbols referenced by `BundleLoader.m`.
 *
 * `RCT_EXPORT_MODULE` synthesizes `+ (void)load { RCTRegisterModule(self); }`,
 * so without an implementation of `RCTRegisterModule` the test bundle won't
 * link. We don't actually exercise module registration in these unit tests.
 *
 * `setBundleURLAndReload:` reloads through `RCTTriggerReloadCommandListeners`;
 * the stub below forwards each call to `BLReloadCommandHandler` so a test can
 * observe it.
 *
 * This file is only compiled into the `BundleLoaderTests.xctest` bundle and
 * is not shipped to npm consumers (it lives outside the package.json `files`
 * allowlist) nor to CocoaPods consumers (we exclude `ios/BundleLoaderTests/**`
 * from `s.source_files` in the podspec).
 */

#import <Foundation/Foundation.h>

void RCTRegisterModule(Class moduleClass);
void RCTRegisterModule(Class moduleClass)
{
  // Intentional no-op for tests.
  (void)moduleClass;
}

extern void (^BLReloadCommandHandler)(NSString *reason);
void (^BLReloadCommandHandler)(NSString *reason) = nil;

void RCTTriggerReloadCommandListeners(NSString *reason);
void RCTTriggerReloadCommandListeners(NSString *reason)
{
  if (BLReloadCommandHandler) {
    BLReloadCommandHandler(reason);
  }
}
