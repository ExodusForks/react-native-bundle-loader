#import <XCTest/XCTest.h>
#import "BundleLoader.h"

#pragma mark - Testing surface

/// Defined in RCTShim.m; called by its `RCTTriggerReloadCommandListeners` stub.
extern void (^BLReloadCommandHandler)(NSString *reason);

@interface BundleLoader (Testing)
- (void)setBundleURLAndReload:(NSURL *)url;
- (void)loadVerifiedFromUrl:(NSString *)urlString
             expectedSha256:(NSString *)expectedHex
                   resolver:(void (^)(id))resolve
                   rejecter:(void (^)(NSString *, NSString *, NSError *))reject;
@end

#pragma mark - Mock bridge

/**
 * The production module writes the pending bundle URL to `NSUserDefaults` and
 * triggers `RCTTriggerReloadCommandListeners` -- it never sends anything to the
 * bridge. The mock still records any `setValue:forKey:` or `reload` so a test can
 * assert that none happen; it therefore doesn't need to inherit from `RCTBridge`,
 * it just has to respond to those messages and keep a record.
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
  // Best-effort cleanup of the bundle file any test may have written to disk.
  NSString *path = [NSTemporaryDirectory()
      stringByAppendingPathComponent:@"verified-bundle.jsbundle"];
  [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
  [[NSUserDefaults standardUserDefaults] removeObjectForKey:RNBundleLoaderPendingURLKey];
  BLReloadCommandHandler = nil;
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

#pragma mark - Test 1: setBundleURLAndReload order

- (void)testSetBundleURLAndReloadOrder
{
  NSURL *url = [NSURL URLWithString:@"https://example.com/bundle.js"];
  NSMutableArray *storedAtReload = [NSMutableArray array];
  BLReloadCommandHandler = ^(NSString *reason) {
    NSURL *stored = [[NSUserDefaults standardUserDefaults] URLForKey:RNBundleLoaderPendingURLKey];
    [storedAtReload addObject:stored ?: [NSNull null]];
  };
  [self.loader setBundleURLAndReload:url];

  // URL is written to NSUserDefaults (not KVC) before the reload command fires.
  XCTAssertEqual(storedAtReload.count, 1u, @"exactly one reload command expected");
  XCTAssertEqualObjects(storedAtReload.firstObject, url, @"URL must be stored before the reload command");
  XCTAssertEqual(self.mockBridge.kvcSets.count, 0u, @"no KVC sets expected");
  XCTAssertEqual(self.mockBridge.reloads.count, 0u, @"bridge reload must not be called");
}

#pragma mark - Test 4: loadVerifiedFromUrl rejects non-https

- (void)testLoadVerifiedFromUrlRejectsNonHttps
{
  XCTestExpectation *exp = [self expectationWithDescription:@"rejecter fires for non-https URL"];
  __block NSString *seenCode = nil;
  __block BOOL resolved = NO;

  NSString *validHex = [@"" stringByPaddingToLength:64 withString:@"a" startingAtIndex:0];
  [self.loader loadVerifiedFromUrl:@"http://example.com/bundle.js"
                    expectedSha256:validHex
                          resolver:^(id r) { resolved = YES; [exp fulfill]; }
                          rejecter:^(NSString *code, NSString *msg, NSError *err) {
    seenCode = code;
    [exp fulfill];
  }];

  [self waitForExpectations:@[exp] timeout:2.0];
  XCTAssertFalse(resolved, @"resolver must not fire");
  XCTAssertEqualObjects(seenCode, @"E_INVALID_URL");
}

#pragma mark - Test 5: loadVerifiedFromUrl rejects wrong-length hex

- (void)testLoadVerifiedFromUrlRejectsWrongLengthHex
{
  XCTestExpectation *exp = [self expectationWithDescription:@"rejecter fires for short hex"];
  __block NSString *seenCode = nil;
  __block BOOL resolved = NO;

  [self.loader loadVerifiedFromUrl:@"https://example.com/bundle.js"
                    expectedSha256:@"abc"
                          resolver:^(id r) { resolved = YES; [exp fulfill]; }
                          rejecter:^(NSString *code, NSString *msg, NSError *err) {
    seenCode = code;
    [exp fulfill];
  }];

  [self waitForExpectations:@[exp] timeout:2.0];
  XCTAssertFalse(resolved, @"resolver must not fire");
  XCTAssertEqualObjects(seenCode, @"E_INVALID_HASH");
}

#pragma mark - Test 6: loadVerifiedFromUrl rejects invalid hex chars

- (void)testLoadVerifiedFromUrlRejectsInvalidHexChars
{
  XCTestExpectation *exp = [self expectationWithDescription:@"rejecter fires for invalid hex chars"];
  __block NSString *seenCode = nil;
  __block BOOL resolved = NO;

  // 'g' is not a valid hex character; 64 of them pass the length check but fail strtoul.
  NSString *badHex = [@"" stringByPaddingToLength:64 withString:@"g" startingAtIndex:0];
  [self.loader loadVerifiedFromUrl:@"https://example.com/bundle.js"
                    expectedSha256:badHex
                          resolver:^(id r) { resolved = YES; [exp fulfill]; }
                          rejecter:^(NSString *code, NSString *msg, NSError *err) {
    seenCode = code;
    [exp fulfill];
  }];

  [self waitForExpectations:@[exp] timeout:2.0];
  XCTAssertFalse(resolved, @"resolver must not fire");
  XCTAssertEqualObjects(seenCode, @"E_INVALID_HASH");
}

@end
