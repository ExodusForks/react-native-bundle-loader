#import <React/RCTBridgeModule.h>
#import <React/RCTRootView.h>

// NSUserDefaults key storing the pending remote bundle URL for loadSourceForBridge:.
extern NSString * const RNBundleLoaderPendingURLKey;

@interface BundleLoader : NSObject <RCTBridgeModule>

@end
