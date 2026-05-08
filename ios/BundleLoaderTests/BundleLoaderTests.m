#import <XCTest/XCTest.h>
#import "BundleLoader.h"

#pragma mark - Testing surface

@interface BundleLoader (Testing)
- (void)setBundleURLAndReload:(NSURL *)url;
- (void)load:(NSURL *)url;
- (void)loadFromBase64:(NSString *)base64
              resolver:(void (^)(id))resolve
              rejecter:(void (^)(NSString *, NSString *, NSError *))reject;
@end

#pragma mark - Mock bridge

/**
 * The module talks to its bridge through `[_bridge setValue:forKey:]` and
 * `[_bridge reload]` only. The mock therefore doesn't need to inherit from
 * `RCTBridge` -- it just has to respond to those messages and keep a record.
 */
@interface BLMockBridge : NSObject
@property (nonatomic, strong) NSMutableArray<NSDictionary *> *kvcSets;
@property (nonatomic, strong) NSMutableArray<NSDate *> *reloads;
/// Combined ordered log of operations: each entry is either
/// `@{ @"op": @"set", @"key": ..., @"value": ... }` or `@{ @"op": @"reload" }`.
@property (nonatomic, strong) NSMutableArray<NSDictionary *> *log;
/// Optional URL we pretend to host for `runningMode` style queries.
@property (nonatomic, strong) NSURL *bundleURL;
- (void)reload;
@end

@implementation BLMockBridge

- (instancetype)init
{
  if ((self = [super init])) {
    _kvcSets = [NSMutableArray array];
    _reloads = [NSMutableArray array];
    _log = [NSMutableArray array];
  }
  return self;
}

- (void)setValue:(id)value forKey:(NSString *)key
{
  // Record without dispatching to NSObject's KVC machinery to avoid trying
  // to set any real properties on this stub class.
  [_kvcSets addObject:@{ @"key": key ?: @"<nil>", @"value": value ?: [NSNull null] }];
  [_log addObject:@{ @"op": @"set", @"key": key ?: @"<nil>", @"value": value ?: [NSNull null] }];
  if ([key isEqualToString:@"bundleURL"] && [value isKindOfClass:[NSURL class]]) {
    _bundleURL = value;
  }
}

- (void)reload
{
  [_reloads addObject:[NSDate date]];
  [_log addObject:@{ @"op": @"reload" }];
}

@end

#pragma mark - Tests

@interface BundleLoaderTests : XCTestCase
@property (nonatomic, strong) BundleLoader *loader;
@property (nonatomic, strong) BLMockBridge *mockBridge;
@end

@implementation BundleLoaderTests

- (void)setUp
{
  [super setUp];
  self.loader = [BundleLoader new];
  self.mockBridge = [BLMockBridge new];
  // The module declares `bridge` as a weak property synthesized from
  // `_bridge`. `setValue:forKey:` writes through the synthesized setter,
  // and the strong reference held by `self.mockBridge` keeps it alive.
  [self.loader setValue:self.mockBridge forKey:@"bridge"];
}

- (void)tearDown
{
  // Best-effort cleanup of the file we may have written in test #1.
  NSString *path = [NSTemporaryDirectory()
      stringByAppendingPathComponent:@"verified-bundle.jsbundle"];
  [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
  self.loader = nil;
  self.mockBridge = nil;
  [super tearDown];
}

#pragma mark - Helpers

/// Spin the main runloop briefly so any `dispatch_async(main)` blocks that
/// the module enqueues actually run before we assert. Tests run on the main
/// thread, so `dispatch_sync(main, ...)` would deadlock; spinning the loop
/// is the standard XCTest workaround.
- (void)pumpMainRunloop
{
  for (int i = 0; i < 5; i++) {
    [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
  }
}

#pragma mark - Test 1: loadFromBase64 happy path

- (void)testLoadFromBase64HappyPath
{
  NSString *plaintext = @"hello world";
  NSData *expected = [plaintext dataUsingEncoding:NSUTF8StringEncoding];
  NSString *base64 = [expected base64EncodedStringWithOptions:0];

  XCTestExpectation *resolved = [self expectationWithDescription:@"resolver fires"];

  __block id resolveValue = (id)@"<<unset>>";
  __block BOOL rejected = NO;

  [self.loader loadFromBase64:base64
                     resolver:^(id result) {
                       resolveValue = result;
                       [resolved fulfill];
                     }
                     rejecter:^(NSString *code, NSString *message, NSError *err) {
                       rejected = YES;
                       [resolved fulfill];
                     }];

  [self waitForExpectations:@[resolved] timeout:5.0];

  XCTAssertFalse(rejected, @"resolver should have fired, not the rejecter");
  XCTAssertTrue(resolveValue == nil || [resolveValue isKindOfClass:[NSNull class]],
                @"resolver should be called with nil");

  NSString *path = [NSTemporaryDirectory()
      stringByAppendingPathComponent:@"verified-bundle.jsbundle"];
  XCTAssertTrue([[NSFileManager defaultManager] fileExistsAtPath:path],
                @"bundle file should exist on disk");

  NSData *actual = [NSData dataWithContentsOfFile:path];
  XCTAssertEqualObjects(actual, expected, @"file bytes should round-trip exactly");

  NSError *attrErr = nil;
  NSDictionary *attrs = [[NSFileManager defaultManager]
      attributesOfItemAtPath:path
                       error:&attrErr];
  XCTAssertNil(attrErr);

  // Data Protection is only enforced on real iOS devices; the iOS Simulator
  // silently ignores `NSDataWritingFileProtectionComplete` and reports nil
  // for `NSFileProtectionKey`. Accept either: nil (simulator) or
  // `NSFileProtectionComplete` (device).
  id protection = attrs[NSFileProtectionKey];
#if TARGET_OS_SIMULATOR
  XCTAssertTrue(protection == nil
                    || [protection isEqualToString:NSFileProtectionComplete],
                @"on simulator, NSFileProtectionKey is typically nil; saw: %@",
                protection);
#else
  XCTAssertEqualObjects(protection, NSFileProtectionComplete,
                        @"file should be written with NSFileProtectionComplete");
#endif
}

#pragma mark - Test 2: loadFromBase64 invalid input

- (void)testLoadFromBase64InvalidInput
{
  XCTestExpectation *rejected = [self expectationWithDescription:@"rejecter fires"];
  __block NSString *seenCode = nil;
  __block BOOL resolved = NO;

  [self.loader loadFromBase64:@"!!!not-base64!!!"
                     resolver:^(id result) {
                       resolved = YES;
                       [rejected fulfill];
                     }
                     rejecter:^(NSString *code, NSString *message, NSError *err) {
                       seenCode = code;
                       [rejected fulfill];
                     }];

  [self waitForExpectations:@[rejected] timeout:5.0];

  XCTAssertFalse(resolved, @"resolver should not fire for invalid base64");
  XCTAssertEqualObjects(seenCode, @"E_INVALID_BASE64");
}

#pragma mark - Test 3: load: rejects non-https

- (void)testLoadRejectsNonHttps
{
  NSURL *url = [NSURL URLWithString:@"http://example.com/bundle.js"];
  [self.loader load:url];
  [self pumpMainRunloop];

  XCTAssertEqual(self.mockBridge.kvcSets.count, 0u,
                 @"non-https URL must not trigger any KVC set");
  XCTAssertEqual(self.mockBridge.reloads.count, 0u,
                 @"non-https URL must not trigger reload");
}

#pragma mark - Test 4: load: accepts https

- (void)testLoadAcceptsHttps
{
  NSURL *url = [NSURL URLWithString:@"https://example.com/bundle.js"];
  [self.loader load:url];
  [self pumpMainRunloop];

  XCTAssertEqual(self.mockBridge.kvcSets.count, 1u,
                 @"exactly one KVC set expected for https URL");
  XCTAssertEqualObjects(self.mockBridge.kvcSets[0][@"key"], @"bundleURL");
  XCTAssertEqualObjects(self.mockBridge.kvcSets[0][@"value"], url);
  XCTAssertEqual(self.mockBridge.reloads.count, 1u,
                 @"exactly one reload expected for https URL");
}

#pragma mark - Test 5: setBundleURLAndReload: directly

- (void)testSetBundleURLAndReloadOrder
{
  NSURL *url = [NSURL URLWithString:@"https://example.com/bundle.js"];
  [self.loader setBundleURLAndReload:url];

  XCTAssertEqual(self.mockBridge.log.count, 2u,
                 @"setBundleURLAndReload: should produce exactly two ops");

  // KVC set first, then reload -- ordering matters because the bridge can't
  // reload sensibly without the URL in place.
  XCTAssertEqualObjects(self.mockBridge.log[0][@"op"], @"set");
  XCTAssertEqualObjects(self.mockBridge.log[0][@"key"], @"bundleURL");
  XCTAssertEqualObjects(self.mockBridge.log[0][@"value"], url);
  XCTAssertEqualObjects(self.mockBridge.log[1][@"op"], @"reload");

  XCTAssertEqual(self.mockBridge.kvcSets.count, 1u);
  XCTAssertEqual(self.mockBridge.reloads.count, 1u);
}

@end
