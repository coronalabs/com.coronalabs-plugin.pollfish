//
//  PollfishPlugin.mm
//  Pollfish Plugin
//
//  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
//

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

#import "CoronaRuntime.h"
#import "CoronaAssert.h"
#import "CoronaEvent.h"
#import "CoronaLua.h"
#import "CoronaLibrary.h"
#import "CoronaLuaIOS.h"

#import <sys/utsname.h>

#import "PollfishPlugin.h"
#import <Pollfish/Pollfish-Swift.h>

// some macros to make life easier, and code more readable
#define UTF8StringWithFormat(format, ...) [[NSString stringWithFormat:format, ##__VA_ARGS__] UTF8String]
#define UTF8IsEqual(utf8str1, utf8str2) (strcmp(utf8str1, utf8str2) == 0)
#define UTF8Concat(utf8str1, utf8str2) [[NSString stringWithFormat:@"%s%s", utf8str1, utf8str2] UTF8String]
#define MsgFormat(format, ...) [NSString stringWithFormat:format, ##__VA_ARGS__]

// ----------------------------------------------------------------------------
// Plugin Constants
// ----------------------------------------------------------------------------

#define PLUGIN_NAME        "plugin.pollfish"
#define PLUGIN_VERSION     "1.2.0"
#define PLUGIN_SDK_VERSION "6.4.2" // no API function to get SDK version (yet)

static const char EVENT_NAME[]    = "adsRequest";
static const char PROVIDER_NAME[] = "pollfish";

// positions
static const char POS_TOP[]    = "top";
static const char POS_BOTTOM[] = "bottom";
static const char POS_LEFT[]   = "left";
static const char POS_RIGHT[]  = "right";
static const char POS_CENTER[] = "center";

// valid button positions
static const NSArray *validYAlignPos = @[
  @(POS_TOP),
  @(POS_BOTTOM),
  @(POS_CENTER)
];

// valid alignment positions
static const NSArray *validXAlignPos = @[
  @(POS_LEFT),
  @(POS_RIGHT)
];

static const NSArray *validGender = @[
  @"male",
  @"female",
  @"other"
];

// event phases
static NSString * const PHASE_INIT      = @"init";
static NSString * const PHASE_LOADED    = @"loaded";
static NSString * const PHASE_DISPLAYED = @"displayed";
static NSString * const PHASE_CLOSED    = @"closed";
static NSString * const PHASE_COMPLETED = @"completed";
static NSString * const PHASE_FAILED    = @"failed";

// response codes
static NSString * const RESPONSE_NOT_ELIGIBLE  = @"notEligible";
static NSString * const RESPONSE_NOT_AVAILABLE = @"notAvailable";

// event types
static NSString * const TYPE_SURVEY = @"survey";

// add missing Corona keys
static NSString * const CORONA_EVENT_DATA_KEY = @"data";

// saved objects (apiKey, ad state, etc)
static NSMutableDictionary *pollfishObjects;
static PollfishParams *pollfishParams;

// message constants
static NSString * const ERROR_MSG   = @"ERROR: ";
static NSString * const WARNING_MSG = @"WARNING: ";

// Pollfish Object Dictionary Keys
static NSString * const SURVEY_READY_KEY    = @"surveyReady";
static NSString * const APIKEY_KEY          = @"apiKey";
static NSString * const CUSTOM_MODE_KEY     = @"customMode";

// ----------------------------------------------------------------------------
// plugin class and delegate definitions
// ----------------------------------------------------------------------------

@interface PollfishDelegate: NSObject

@property (nonatomic, assign) CoronaLuaRef coronaListener;          // Lua listener
@property (nonatomic, assign) id<CoronaRuntime> coronaRuntime;      // Corona runtime
@property (nonatomic, assign) bool isSurveyOpen;                    // flag if survey has been opened (needed to fix improper 'closed' events)

- (void)dispatchLuaEvent:(NSDictionary *)dict;
- (void)processPollfishRequest;
- (void)activateApplicationDidBecomeActiveListener;
- (void)applicationDidBecomeActive:(UIApplication *)application;


@end

// ----------------------------------------------------------------------------

class PollfishPlugin
{
public:
  typedef PollfishPlugin Self;
  static const char kName[];
  
public:
  static int Open(lua_State *L);
  static int Finalizer(lua_State *L);
  static Self *ToLibrary(lua_State *L);
  
protected:
  PollfishPlugin();
  bool Initialize(void *platformContext);
  
public:
  static int init(lua_State *L);
  static int load(lua_State *L);
  static int show(lua_State *L);
  static int hide(lua_State *L);
  static int isLoaded(lua_State *L);
  static int setUserDetails(lua_State *L);
  
private: //internal helper functions
  static void logMsg(lua_State *L, NSString* msgType, NSString* errorMsg);
  static bool isSDKInitialized(lua_State *L);
  
private:
  NSString *functionSignature;                               // used in logMsg to identify function
  UIViewController *coronaViewController;                    // application's view controller
};

const char PollfishPlugin::kName[] = PLUGIN_NAME;
PollfishDelegate *pollfishDelegate = nil;

// true after app has been successfully registered with Pollfish
bool appIsRegistered = false;

// ----------------------------------------------------------------------------
// helper functions
// ----------------------------------------------------------------------------

// log message to console
void
PollfishPlugin::logMsg(lua_State *L, NSString* msgType, NSString* errorMsg)
{
  Self *context = ToLibrary(L);
  
  if (context) {
    Self& library = *context;
    
    NSString *functionID = [library.functionSignature copy];
    if (functionID.length > 0) {
      functionID = [functionID stringByAppendingString:@", "];
    }
    
    CoronaLuaLogPrefix(L, [msgType UTF8String], UTF8StringWithFormat(@"%@%@", functionID, errorMsg));
  }
}

// check if SDK calls can be made
bool
PollfishPlugin::isSDKInitialized(lua_State *L)
{
  if (pollfishDelegate.coronaListener == nil) {
    logMsg(L, ERROR_MSG, @"pollfish.init() must be called before calling other API functions");
    return false;
  }
  
  if (! appIsRegistered) {
    logMsg(L, ERROR_MSG, @"The Pollfish apiKey is not registered");
    return false;
  }
  
  return true;
}

// ----------------------------------------------------------------------------
// plugin implementation
// ----------------------------------------------------------------------------

int
PollfishPlugin::Open(lua_State *L)
{
  // Register __gc callback
  const char kMetatableName[] = __FILE__; // Globally unique string to prevent collision
  CoronaLuaInitializeGCMetatable(L, kMetatableName, Finalizer);
  
  void *platformContext = CoronaLuaGetContext(L);
  
  // Set library as upvalue for each library function
  Self *library = new Self;
  
  if (library->Initialize(platformContext)) {
    // Functions in library
    static const luaL_Reg kFunctions[] = {
      {"init", init},
      {"load", load},
      {"show", show},
      {"hide", hide},
      {"isLoaded", isLoaded},
      {"setUserDetails", setUserDetails},
      {nil, nil}
    };
    
    // Register functions as closures, giving each access to the
    // 'library' instance via ToLibrary()
    {
      CoronaLuaPushUserdata(L, library, kMetatableName);
      luaL_openlib(L, kName, kFunctions, 1); // leave "library" on top of stack
    }
  }
  
  return 1;
}

PollfishPlugin::PollfishPlugin()
: coronaViewController(nil)
{
}

bool
PollfishPlugin::Initialize(void *platformContext)
{
  bool shouldInit = (! coronaViewController);
  
  if (shouldInit) {
    id<CoronaRuntime> runtime = (__bridge id<CoronaRuntime>)platformContext;
    coronaViewController = runtime.appViewController;
    
    pollfishDelegate = [PollfishDelegate new];
    pollfishDelegate.coronaRuntime = runtime;
    
    pollfishObjects = [NSMutableDictionary new];
    pollfishObjects[SURVEY_READY_KEY] = @(false);
  }
  
  return shouldInit;
}

int
PollfishPlugin::Finalizer(lua_State *L)
{
  Self *library = (Self *)CoronaLuaToUserdata(L, 1);
  
  // free the Lua listener
  CoronaLuaDeleteRef(L, pollfishDelegate.coronaListener);
  pollfishDelegate.coronaListener = nil;
  
  pollfishDelegate = nil;
  
  [pollfishObjects removeAllObjects];
  
  // zap the Pollfish SDK
  [Pollfish dealloc];
  
  delete library;
  
  return 0;
}

PollfishPlugin *
PollfishPlugin::ToLibrary(lua_State *L)
{
  // library is pushed as part of the closure
  Self *library = (Self *)CoronaLuaToUserdata(L, lua_upvalueindex(1));
  return library;
}

// [Lua] init(listener, options)
int
PollfishPlugin::init(lua_State *L)
{
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  // set function signature to be used in error / warning messages
  library.functionSignature = @"pollfish.init(listener, options)";
  
  // prevent init from being called twice
  if (pollfishDelegate.coronaListener != nil) {
    logMsg(L, ERROR_MSG, @"init() should only be called once");
    return 0;
  }
  
  const char *apiKey = NULL;
  bool rewardMode = false;
  
  // check number of arguments passed
  int nargs = lua_gettop(L);
  if (nargs != 2) {
    logMsg(L, ERROR_MSG, MsgFormat(@"2 arguments expected. got %d", nargs));
    return 0;
  }
  
  // get listener (required)
  if (CoronaLuaIsListener(L, 1, PROVIDER_NAME)) {
    pollfishDelegate.coronaListener = CoronaLuaNewRef(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"listener function expected, got: %s", luaL_typename(L, 1)));
    return 0;
  }
  
  // check for options table
  if (lua_type(L, 2) == LUA_TTABLE) {
    for (lua_pushnil(L); lua_next(L, 2) != 0; lua_pop(L, 1)) {
      const char *key = lua_tostring(L, -2);
      
      if (UTF8IsEqual(key, "apiKey" )) {
        if (lua_type(L, -1) == LUA_TSTRING) {
          apiKey = lua_tostring(L, -1);
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.apiKey expected (string). Got %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else if (UTF8IsEqual(key, "developerMode")) {
        if (lua_type(L, -1) == LUA_TBOOLEAN) {
          [pollfishParams releaseMode:!lua_toboolean(L, -1)];
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.developerMode expected (boolean). Got %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else if (UTF8IsEqual(key, "requestUUID")) {
        if (lua_type(L, -1) == LUA_TSTRING ) {
            [pollfishParams requestUUID:[NSString stringWithUTF8String:lua_tostring(L, -1)]];
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.requestUUID expected (string). Got %s", luaL_typename(L, -1)));
          return 0;
        }
      }else if (UTF8IsEqual(key, "rewardMode")) {
          if (lua_type(L, -1) == LUA_TBOOLEAN ) {
              [pollfishParams rewardMode:lua_toboolean(L, -1)];
          }
          else {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.rewardMode expected (boolean). Got %s", luaL_typename(L, -1)));
            return 0;
          }
     } else {
        logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
        return 0;
      }
    }
    pollfishParams = [[PollfishParams alloc] init:[NSString stringWithUTF8String:apiKey]];
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"options table expected. Got %s", luaL_typename(L, 2)));
    return 0;
  }
  
  // validate apiKey
  if (apiKey == NULL) {
    logMsg(L, ERROR_MSG, MsgFormat(@"options.apiKey is required"));
    return 0;
  }
  
  appIsRegistered = true;
  pollfishObjects[APIKEY_KEY] = [NSString stringWithUTF8String:apiKey];
  
  NSDictionary *coronaEvent = @{
	@(CoronaEventPhaseKey()) : PHASE_INIT
  };
  [pollfishDelegate dispatchLuaEvent:coronaEvent];
  [pollfishParams rewardMode:rewardMode];
  // Log plugin version to device log
  NSLog(@"%s: %s (SDK: %s)", PLUGIN_NAME, PLUGIN_VERSION, PLUGIN_SDK_VERSION);
  
  return 0;
}

// [Lua] load(options)
int
PollfishPlugin::load(lua_State *L)
{
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  // check if SDK ready for method calls
  if (! isSDKInitialized(L)) {
    return 0;
  }
  
  // set function signature to be used in error / warning messages
  library.functionSignature = @"pollfish.load(options)";
  
  const char *yAlign = NULL;
  const char *xAlign = NULL;
  int padding = 0;
  bool customMode = false;
  bool offerwallMode = false;
  bool rewardMode = false;
  
  // check number of arguments passed
  int nargs = lua_gettop(L);
  if (nargs > 1) {
    logMsg(L, ERROR_MSG, MsgFormat(@"0 or 1 argument expected. got %d", nargs));
    return 0;
  }
  
  // check for options table
  if (! lua_isnoneornil(L, 1)) {
    if (lua_type(L, 1) == LUA_TTABLE) {
      for (lua_pushnil(L); lua_next(L, 1) != 0; lua_pop(L, 1)) {
        const char *key = lua_tostring(L, -2);
        
        if (UTF8IsEqual(key, "yAlign")) {
          if (lua_type(L, -1) == LUA_TSTRING) {
            yAlign = lua_tostring(L, -1);
          }
          else {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.position expected (string). Got %s", luaL_typename(L, -1)));
            return 0;
          }
        }
        else if (UTF8IsEqual(key, "xAlign")) {
          if (lua_type(L, -1) == LUA_TSTRING) {
            xAlign = lua_tostring(L, -1);
          }
          else {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.align expected (string). Got %s", luaL_typename(L, -1)));
            return 0;
          }
        }
        else if (UTF8IsEqual(key, "padding")) {
          if (lua_type(L, -1) == LUA_TNUMBER) {
            padding = lua_tonumber(L, -1);
          }
          else {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.padding expected (number). Got %s", luaL_typename(L, -1)));
            return 0;
          }
        }
        else if (UTF8IsEqual(key, "customMode")) {
          if ( lua_type(L, -1) == LUA_TBOOLEAN) {
            customMode = lua_toboolean(L, -1);
          }
          else {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.customMode expected (boolean). Got %s", luaL_typename(L, -1)));
            return 0;
          }
        }
        else if (UTF8IsEqual(key, "rewardMode")) {
          if ( lua_type(L, -1) == LUA_TBOOLEAN) {
            rewardMode = lua_toboolean(L, -1);
          }
          else {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.rewardMode expected (boolean). Got %s", luaL_typename(L, -1)));
            return 0;
          }
        }
        else if (UTF8IsEqual(key, "offerwallMode")) {
          if ( lua_type(L, -1) == LUA_TBOOLEAN) {
            offerwallMode = lua_toboolean(L, -1);
          }
          else {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.offerwallMode expected (boolean). Got %s", luaL_typename(L, -1)));
            return 0;
          }
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
          return 0;
        }
      }
    }
    else {
      logMsg(L, ERROR_MSG, MsgFormat(@"options table expected. Got %s", luaL_typename(L, 1)));
      return 0;
    }
  }
  
  // check for valid position
  if (yAlign == NULL) {
    yAlign = POS_BOTTOM;
  }
  else if (! [validYAlignPos containsObject:@(yAlign)]) {
    logMsg(L, ERROR_MSG, MsgFormat(@"options.position, invalid position '%s'", yAlign));
    return 0;
  }
  
  // check for valid align
  if (xAlign == NULL) {
    xAlign = POS_RIGHT;
  }
  else if (! [validXAlignPos containsObject:@(xAlign)]) {
    logMsg(L, ERROR_MSG, MsgFormat(@"options.align, invalid alignment '%s'", xAlign));
    return 0;
  }
  
  // set button position
  IndicatorPosition pollfishPosition;
  
  if (UTF8IsEqual(yAlign, POS_TOP)) {
    if (UTF8IsEqual(xAlign, POS_LEFT)) {
      pollfishPosition = IndicatorPositionTopLeft;
    }
    else { // default right
      pollfishPosition = IndicatorPositionTopRight;
    }
  }
  else if (UTF8IsEqual(yAlign, POS_CENTER))  {
    if (UTF8IsEqual(xAlign, POS_LEFT)) {
      pollfishPosition = IndicatorPositionMiddleLeft;
    }
    else { // default right
      pollfishPosition = IndicatorPositionMiddleRight;
    }
  }
  else { // default bottom
    if (UTF8IsEqual(xAlign, POS_LEFT)) {
      pollfishPosition = IndicatorPositionBottomLeft;
    }
    else { // default right
      pollfishPosition = IndicatorPositionBottomRight;
    }
  }
  
  // save values for pollfish request in delegate
  [pollfishParams indicatorPadding:padding];
  [pollfishParams indicatorPosition:pollfishPosition];
  [pollfishParams offerwallMode:offerwallMode];
  pollfishObjects[CUSTOM_MODE_KEY] = @(customMode);
  
  // use the delegate method to process the request
  [pollfishDelegate processPollfishRequest];
  
  // set the applicationDidBecomeActive listener
  [pollfishDelegate activateApplicationDidBecomeActiveListener];
  
  return 0;
}

// [Lua] show()
int
PollfishPlugin::show(lua_State *L)
{
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  library.functionSignature = @"pollfish.show()";
  
  // check if SDK ready for method calls
  if (! isSDKInitialized(L)) {
    return 0;
  }
  
  // check number of arguments passed
  int nargs = lua_gettop(L);
  if (nargs > 0) {
    logMsg(L, ERROR_MSG, MsgFormat(@"No arguments expected. got %d", nargs));
    return 0;
  }
  
  // check if a loaded survey is still valid
  pollfishObjects[SURVEY_READY_KEY] = @([Pollfish isPollfishPresent]);
  
  if (! [pollfishObjects[SURVEY_READY_KEY] boolValue]) {
    logMsg(L, WARNING_MSG, @"Survey not ready");
  }
  else {
    [Pollfish show];
  }
  
  return 0;
}

// [Lua] hide()
int
PollfishPlugin::hide(lua_State *L)
{
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  // set function signature to be used in error / warning messages
  library.functionSignature = @"pollfish.hide()";
  
  // check if SDK ready for method calls
  if (! isSDKInitialized(L)) {
    return 0;
  }
  
  // check number of arguments passed
  int nargs = lua_gettop(L);
  if (nargs > 0) {
    logMsg(L, ERROR_MSG, MsgFormat(@"No arguments expected. got %d", nargs));
    return 0;
  }
  
  // check if a survey is loaded
  if (! [pollfishObjects[SURVEY_READY_KEY] boolValue]) {
    logMsg(L, WARNING_MSG, @"Survey not ready");
  }
  else {
    [Pollfish hide];
  }
  
  return 0;
}

// [Lua] isLoaded()
int
PollfishPlugin::isLoaded(lua_State *L)
{
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  // set function signature to be used in error / warning messages
  library.functionSignature = @"pollfish.isLoaded()";
  
  // check if SDK ready for method calls
  if (! isSDKInitialized(L)) {
    return 0;
  }
  
  // check number of arguments passed
  int nargs = lua_gettop(L);
  if (nargs > 0) {
    logMsg(L, ERROR_MSG, MsgFormat(@"No arguments expected. got %d", nargs));
    return 0;
  }
  
  // check if a survey is loaded and valid
  bool isLoaded = [Pollfish isPollfishPresent];
  pollfishObjects[SURVEY_READY_KEY] = @(isLoaded);
  lua_pushboolean(L, isLoaded);
  
  return 1;
}

// [Lua] setUserDetails(options)
int
PollfishPlugin::setUserDetails(lua_State *L)
{
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  // set function signature to be used in error/warning messages
  library.functionSignature = @"pollfish.setUserDetails(options)";
  
  const char *gender = NULL;
  const char *facebookId = NULL;
  const char *twitterId = NULL;
  const char *requestUUID = NULL;
  lua_Number longitude = 0;
  lua_Number latitude = 0;
  lua_Number horizontalAccuracy = 0;
  
  // check if SDK ready for method calls
  if (! isSDKInitialized(L)) {
    return 0;
  }
  
  // check number of arguments passed
  int nargs = lua_gettop(L);
  if (nargs != 1) {
    logMsg(L, ERROR_MSG, MsgFormat(@"missing options table."));
    return 0;
  }
  
  // check for options table
  if (lua_type(L, 1) == LUA_TTABLE) {
    // get the options
    for (lua_pushnil(L); lua_next(L, 1) != 0; lua_pop(L, 1)) {
      const char *key = lua_tostring(L, -2);
      
      if (UTF8IsEqual(key, "age" )) {
        // NOP
        // remains here for backwards compatibility
        // TODO: remove in a later release
      }
      else if (UTF8IsEqual(key, "gender" )) {
        if (lua_type(L, -1) == LUA_TSTRING) {
          gender = lua_tostring(L, -1);
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.gender expected (string). Got %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else if (UTF8IsEqual(key, "ageGroup" )) {
        // NOP
        // remains here for backwards compatibility
        // TODO: remove in a later release
      }
      else if (UTF8IsEqual(key, "facebookId" )) {
        if (lua_type(L, -1) == LUA_TSTRING) {
          facebookId = lua_tostring(L, -1);
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.facebookId expected (string). Got %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else if (UTF8IsEqual(key, "twitterId" )) {
        if (lua_type(L, -1) == LUA_TSTRING) {
          twitterId = lua_tostring(L, -1);
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.twitterId expected (string). Got %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else if (UTF8IsEqual(key, "requestUUID")) {
		const char* uuid = lua_tostring(L, -1);
        if(uuid) {
            [pollfishParams requestUUID: [NSString stringWithUTF8String:uuid]];
		} else {
            //[pollfishParams requestUUID: NULL];
		}
      }
      else if (UTF8IsEqual(key, "customData" )) {
        // NOP
        // remains here for backwards compatibility
        // TODO: remove in a later release
      }
      else if (UTF8IsEqual(key, "location" )) {
        if (lua_type(L, -1) == LUA_TTABLE) {
          // get location
          for (lua_pushnil(L); lua_next(L, -2) != 0; lua_pop(L, 1)) {
            const char *locationKey = lua_tostring(L, -2);
            
            if (UTF8IsEqual(locationKey, "longitude" )) {
              if (lua_type(L, -1) == LUA_TNUMBER) {
                longitude = lua_tonumber(L, -1);
              }
              else {
                logMsg(L, ERROR_MSG, MsgFormat(@"options.location.longitude expected (number). Got %s", luaL_typename(L, -1)));
                return 0;
              }
            }
            else if (UTF8IsEqual(locationKey, "latitude" )) {
              if (lua_type(L, -1) == LUA_TNUMBER) {
                latitude = lua_tonumber(L, -1);
              }
              else {
                logMsg(L, ERROR_MSG, MsgFormat(@"options.location.latitude expected (number). Got %s", luaL_typename(L, -1)));
                return 0;
              }
            }
            else if (UTF8IsEqual(locationKey, "horizontalAccuracy" )) {
              if (lua_type(L, -1) == LUA_TNUMBER) {
                horizontalAccuracy = lua_tonumber(L, -1);
              }
              else {
                logMsg(L, ERROR_MSG, MsgFormat(@"options.location.horizontalAccuracy expected (number). Got %s", luaL_typename(L, -1)));
                return 0;
              }
            }
            else {
              logMsg(L, ERROR_MSG, MsgFormat(@"option.location invalid option '%s'", locationKey));
              return 0;
            }
          }
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.location table expected. Got %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else {
        logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
        return 0;
      }
    }
    
    // define user attributes dictionary
    UserProperties *userAttributes = [[UserProperties alloc] init];
    
    // validate gender
    if (gender != NULL) {
          if (! [validGender containsObject:@(gender)]) {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.gender invalid gender '%s'. Valid values: 'male', 'female', 'other'", gender));
            return 0;
          }
        if ([@(gender) isEqual:@"male"]) {
            [userAttributes gender:GenderMale];
        }
        if ([@(gender) isEqual:@"female"]) {
            [userAttributes gender:GenderFemale];
        }
        if ([@(gender) isEqual:@"other"]) {
            [userAttributes gender:GenderOther];
        }
      
      
    }
    
    // validate facebookId
    if (facebookId != NULL) {
      //[userAttributes :@(facebookId)];
    }
    
    // validate twitterId
    if (twitterId != nil) {
      //[userAttributes setTwitterId:@(twitterId)];
    }
    
    // validate location
    if ((longitude != 0) || (latitude != 0) || (horizontalAccuracy != 0)) {
      //[Pollfish updateLocationWithLatitude:latitude andLongitude:longitude andHorizontalAccuracy:horizontalAccuracy];
    }
    
    [pollfishParams userProperties:userAttributes];
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"options table expected. Got %s", luaL_typename(L, 1)));
    return 0;
  }
  
  return 0;
}

// ----------------------------------------------------------------------------
// Delegate implementation
// ----------------------------------------------------------------------------

@implementation PollfishDelegate

// initialize delegate
- (instancetype) init
{
  if (self = [super init]) {
    self.coronaListener = nil;
    self.coronaRuntime = nil;
    self.isSurveyOpen = false;
    
    
    // add observer "delegates"
    [[NSNotificationCenter defaultCenter]
     addObserver:self
     selector:@selector(surveyCompleted:)
     name:@"PollfishSurveyCompleted"
     object:nil
     ];
    
    [[NSNotificationCenter defaultCenter]
     addObserver:self
     selector:@selector(surveyOpened:)
     name:@"PollfishOpened"
     object:nil
     ];
    
    [[NSNotificationCenter defaultCenter]
     addObserver:self
     selector:@selector(surveyClosed:)
     name:@"PollfishClosed"
     object:nil
     ];
    
    [[NSNotificationCenter defaultCenter]
     addObserver:self
     selector:@selector(surveyReceived:)
     name:@"PollfishSurveyReceived"
     object:nil
     ];
    
    [[NSNotificationCenter defaultCenter]
     addObserver:self
     selector:@selector(surveyNotAvailable:)
     name:@"PollfishSurveyNotAvailable"
     object:nil
     ];
    
    [[NSNotificationCenter defaultCenter]
     addObserver:self
     selector:@selector(userNotEligible:)
     name:@"PollfishUserNotEligible"
     object:nil
     ];
  }
  
  return self;
}

- (void)activateApplicationDidBecomeActiveListener
{
  static bool didActivate = false;
  
  if (! didActivate) {
    [[NSNotificationCenter defaultCenter]
     addObserver:self
     selector:@selector(applicationDidBecomeActive:)
     name:UIApplicationDidBecomeActiveNotification
     object:nil
     ];
    
    didActivate = true;
  }
}

// called when delegate deallocated
- (void)dealloc
{
  // remove all listeners
  [[NSNotificationCenter defaultCenter] removeObserver:self];
}

// dispatch a new Lua event
- (void)dispatchLuaEvent:(NSDictionary *)dict
{
  [[NSOperationQueue mainQueue] addOperationWithBlock:^{
    lua_State *L = self.coronaRuntime.L;
    CoronaLuaRef coronaListener = self.coronaListener;
    bool hasErrorKey = false;
    
    // create new event
    CoronaLuaNewEvent(L, EVENT_NAME);
    
    for (NSString *key in dict) {
      CoronaLuaPushValue(L, [dict valueForKey:key]);
      lua_setfield(L, -2, key.UTF8String);
      
      if (! hasErrorKey) {
        hasErrorKey = [key isEqualToString:@(CoronaEventIsErrorKey())];
      }
    }
    
    // add error key if not in dict
    if (! hasErrorKey) {
      lua_pushboolean(L, false);
      lua_setfield(L, -2, CoronaEventIsErrorKey());
    }
    
    // add provider
    lua_pushstring(L, PROVIDER_NAME );
    lua_setfield(L, -2, CoronaEventProviderKey());
    
    CoronaLuaDispatchEvent(L, coronaListener, 0);
  }];
}

- (void)processPollfishRequest
{
  NSString *apiKey = pollfishObjects[APIKEY_KEY];
  bool customMode = [pollfishObjects[CUSTOM_MODE_KEY] boolValue];
  //[Pollfish initWithAPIKey:apiKey andParams:pollfishParams];
  if (customMode) {
    [Pollfish hide];
  }
}

- (void)applicationDidBecomeActive:(UIApplication *)application
{
  [self processPollfishRequest];
}

// get additional info about the survey (only available for 'loaded' and 'completed' events)
- (NSString *)getJSONDataFromNotification:(NSNotification *)notification
{
  NSDictionary *dataDictionary = @{
    @"playfulSurvey" : notification.userInfo[@"playfulSurvey"],
    @"surveyPrice" : notification.userInfo[@"surveyPrice"]
  };
  
  NSData *jsonData = [NSJSONSerialization dataWithJSONObject:dataDictionary options:0 error:nil];
  
  return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
}

- (void)surveyCompleted:(NSNotification *)notification
{
  // send Corona Lua Event
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_COMPLETED,
    @(CoronaEventTypeKey()) : TYPE_SURVEY,
    CORONA_EVENT_DATA_KEY : [self getJSONDataFromNotification:notification]
  };
  [self dispatchLuaEvent:coronaEvent];
  
  pollfishObjects[SURVEY_READY_KEY] = @(false);
}

- (void)surveyOpened:(NSNotification *)notification
{
  // send Corona Lua Event
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
    @(CoronaEventTypeKey()) : TYPE_SURVEY
  };
  [self dispatchLuaEvent:coronaEvent];
  
  // set the flag (used in closed event)
  self.isSurveyOpen = true;
}

- (void)surveyClosed:(NSNotification *)notification
{
  // This is needed as a 'closed' event is also sent by the SDK even if only the button is hidden
  // A 'closed' event should only be sent when a survey is closed.
  if (self.isSurveyOpen) {
    // send Corona Lua Event
    NSDictionary *coronaEvent = @{
      @(CoronaEventPhaseKey()) : PHASE_CLOSED,
      @(CoronaEventTypeKey()) : TYPE_SURVEY
    };
    [self dispatchLuaEvent:coronaEvent];
    
    self.isSurveyOpen = false;
  }
}

- (void)surveyReceived:(NSNotification *)notification
{
  // send Corona Lua Event
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_LOADED,
    @(CoronaEventTypeKey()) : TYPE_SURVEY,
    CORONA_EVENT_DATA_KEY : [self getJSONDataFromNotification:notification]
  };
  [self dispatchLuaEvent:coronaEvent];
  
  pollfishObjects[SURVEY_READY_KEY] = @(true);
}

- (void)userNotEligible:(NSNotification *)notification
{
  // send Corona Lua Event
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventTypeKey()) : TYPE_SURVEY,
    @(CoronaEventResponseKey()) : RESPONSE_NOT_ELIGIBLE,
    @(CoronaEventIsErrorKey()) : @(true)
  };
  [self dispatchLuaEvent:coronaEvent];
  
  pollfishObjects[SURVEY_READY_KEY] = @(false);
}

- (void)surveyNotAvailable:(NSNotification *)notification
{
  // send Corona Lua Event
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventTypeKey()) : TYPE_SURVEY,
    @(CoronaEventResponseKey()) : RESPONSE_NOT_AVAILABLE,
    @(CoronaEventIsErrorKey()) : @(true),
  };
  [self dispatchLuaEvent:coronaEvent];
  
  pollfishObjects[SURVEY_READY_KEY] = @(false);
}

@end

// ----------------------------------------------------------------------------

CORONA_EXPORT
int luaopen_plugin_pollfish(lua_State *L)
{
  return PollfishPlugin::Open(L);
}
