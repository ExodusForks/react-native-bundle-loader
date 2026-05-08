/**
 * Test-only stubs for React Native bridge symbols referenced by
 * `BundleLoader.m` via the `RCT_EXPORT_MODULE` / `RCT_EXPORT_METHOD` macros.
 *
 * `RCT_EXPORT_MODULE` synthesizes `+ (void)load { RCTRegisterModule(self); }`,
 * so without an implementation of `RCTRegisterModule` the test bundle won't
 * link. We don't actually exercise module registration in these unit tests --
 * the bridge interaction we care about is `[_bridge setValue:forKey:]` and
 * `[_bridge reload]`, both of which the mock handles directly.
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
