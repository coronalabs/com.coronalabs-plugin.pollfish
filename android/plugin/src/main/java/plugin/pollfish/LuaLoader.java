//
// LuaLoader.java
// Pollfish Plugin
//
// Copyright (c) 2016 CoronaLabs inc. All rights reserved.
//

// @formatter:off

package plugin.pollfish;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;

import com.naef.jnlua.LuaState;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaType;
import com.naef.jnlua.NamedJavaFunction;

import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeListener;
import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaLuaEvent;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.pollfish.Pollfish;
import com.pollfish.builder.Params;
import com.pollfish.builder.Position;
import com.pollfish.builder.UserProperties;
import com.pollfish.callback.PollfishClosedListener;
import com.pollfish.callback.PollfishOpenedListener;
import com.pollfish.callback.PollfishSurveyCompletedListener;
import com.pollfish.callback.PollfishSurveyNotAvailableListener;
import com.pollfish.callback.PollfishSurveyReceivedListener;
import com.pollfish.callback.PollfishUserNotEligibleListener;
import com.pollfish.callback.SurveyInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

// Pollfish SDK imports


/**
 * Implements the Lua interface for the Pollfish Plugin.
 * <p/>
 * Only one instance of this class will be created by Corona for the lifetime of the application.
 * This instance will be re-used for every new Corona activity that gets created.
 */
public class LuaLoader implements JavaFunction, CoronaRuntimeListener
{
  private static final String PLUGIN_NAME               = "plugin.pollfish";
  private static final String PLUGIN_VERSION            = "1.2.0";
  private static final String PLUGIN_GOOGLE_SDK_VERSION = "6.4.0 for Google Play"; // no API function to get SDK version (yet)
  private static final String PLUGIN_AMAZON_SDK_VERSION = "6.4.0 Universal";

  private static final String EVENT_NAME    = "adsRequest";
  private static final String PROVIDER_NAME = "pollfish";

  // positions
  private static final String POS_TOP    = "top";
  private static final String POS_BOTTOM = "bottom";
  private static final String POS_LEFT   = "left";
  private static final String POS_RIGHT  = "right";
  private static final String POS_CENTER = "center";

  // validation arrays
  private static final List<String> validButtonPos = new ArrayList<>();
  private static final List<String> validAlignPos = new ArrayList<>();
  private static final List<String> validGender = new ArrayList<>();

  // event phases
  private static final String PHASE_INIT      = "init";
  private static final String PHASE_LOADED    = "loaded";
  private static final String PHASE_DISPLAYED = "displayed";
  private static final String PHASE_CLOSED    = "closed";
  private static final String PHASE_COMPLETED = "completed";
  private static final String PHASE_FAILED    = "failed";

  // response codes
  private static final String RESPONSE_NOT_ELIGIBLE  = "notEligible";
  private static final String RESPONSE_NOT_AVAILABLE = "notAvailable";

  // add missing keys
  private static final String EVENT_PHASE_KEY = "phase";
  private static final String EVENT_TYPE_KEY  = "type";
  private static final String EVENT_DATA_KEY  = "data";

  // event types
  private static final String TYPE_SURVEY = "survey";

  // Pollfish Object Dictionary Keys
  private static final String SURVEY_READY_KEY    = "surveyReady";
  private static final String APIKEY_KEY          = "apiKey";
  private static final String CUSTOM_MODE_KEY     = "customMode";
  private static final String OFFERWALL_MODE_KEY  = "offerwallMode";
  private static final String REWARD_MODE_KEY     = "rewardMode";
  private static final String DEVELOPER_MODE_KEY  = "developerMode";
  private static final String POSITION_KEY        = "position";
  private static final String PADDING_KEY         = "padding";
  private static final String REQUEST_UUID_KEY    = "requestUUID";
  private static final String USER_ATTRIBUTES_KEY = "userAttributes";

  // message constants
  private static final String CORONA_TAG  = "Corona";
  private static final String ERROR_MSG   = "ERROR: ";
  private static final String WARNING_MSG = "WARNING: ";
  private static final Map<String, Object> pollfishObjects = new HashMap<>();    // keep track of loaded objects
  private static int coronaListener = CoronaLua.REFNIL;
  private static CoronaRuntime coronaRuntime;
  private static CoronaRuntimeTaskDispatcher coronaRuntimeTaskDispatcher = null;
  private static String functionSignature = "";                                  // used in error reporting functions
  private static boolean isSurveyOpened = false;                                 // key track of if a survey is onscreen
  private static boolean appIsRegistered = false;                                // true when app is successfully registered with Pollfish
  private static boolean hasLoadedOnce = false;                                  // flag used in isLoaded() to make sure load() has been called

  // Device info
  private static String advertisingId = "unknown";
  private static String androidId 	= "unknown";
  private static String appLabel 		= "unknown";
  private static String appVersion 	= "unknown";
  private static boolean didGetInfo = false;  // true after first call to beacon

  // delegates
  private static CoronaSurveyReceivedDelegate surveyReceivedDelegate = null;
  private static CoronaSurveyNotAvailableDelegate surveyNotAvailableDelegate = null;
  private static CoronaSurveyCompletedDelegate surveyCompletedDelegate = null;
  private static CoronaUserNotEligibleDelegate userNotEligibleDelegate = null;
  private static CoronaSurveyOpenedDelegate surveyOpenedDelegate = null;
  private static CoronaSurveyClosedDelegate surveyClosedDelegate = null;

  /**
   * <p/>
   * Note that a new LuaLoader instance will not be created for every CoronaActivity instance.
   * That is, only one instance of this class will be created for the lifetime of the
   * application process.
   * This gives a plugin the option to do operations in the background while the CoronaActivity
   * is destroyed.
   */
  @SuppressWarnings("unused")
  public LuaLoader()
  {
    // Set up this plugin to listen for Corona runtime events to be received by methods
    // onLoaded(), onStarted(), onSuspended(), onResumed(), and onExiting().
    CoronaEnvironment.addRuntimeListener(this);
  }

  // Get device info for pollfish registration
  private static void getDeviceInfo()
  {
    final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

    if (coronaActivity != null) {
      Runnable runnableActivity = new Runnable() {
        public void run() {
          final Context coronaContext = CoronaEnvironment.getApplicationContext();

          try {
            // Get the app version
            appVersion = coronaContext.getPackageManager().getPackageInfo(coronaContext.getPackageName(), 0).versionName;

            // Get the application info
            final PackageManager packageManager = coronaContext.getApplicationContext().getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(coronaContext.getPackageName(), 0);

            if (applicationInfo != null) {
              // Get the app label
              CharSequence appLabelSeq = packageManager.getApplicationLabel(applicationInfo);
              if (appLabelSeq != null) {
                appLabel = appLabelSeq.toString();
              }
            }

            androidId = Settings.Secure.getString(coronaContext.getContentResolver(), Settings.Secure.ANDROID_ID);
            didGetInfo = true;
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      };

      coronaActivity.runOnUiThread(runnableActivity);
    }
  }


  /**
   * Called when this plugin is being loaded via the Lua require() function.
   * <p/>
   * Note that this method will be called every time a new CoronaActivity has been launched.
   * This means that you'll need to re-initialize this plugin here.
   * <p/>
   * Warning! This method is not called on the main UI thread.
   *
   * @param L Reference to the Lua state that the require() function was called from.
   * @return Returns the number of values that the require() function will return.
   * <p/>
   * Expected to return 1, the library that the require() function is loading.
   */
  @Override
  public int invoke(LuaState L)
  {
    // Register this plugin into Lua with the following functions.
    NamedJavaFunction[] luaFunctions = new NamedJavaFunction[] {
      new Init(),
      new Load(),
      new Show(),
      new Hide(),
      new IsLoaded(),
      new SetUserDetails()
    };
    String libName = L.toString(1);
    L.register(libName, luaFunctions);

    // Returning 1 indicates that the Lua require() function will return the above Lua
    return 1;
  }

  /**
   * Called after the Corona runtime has been created and just before executing the "main.lua"
   * file.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that has just been loaded/initialized.
   *                Provides a LuaState object that allows the application to extend the Lua API.
   */
  @Override
  public void onLoaded(CoronaRuntime runtime)
  {
    // Note that this method will not be called the first time a Corona activity has been
    // launched.
    // This is because this listener cannot be added to the CoronaEnvironment until after
    // this plugin has been required-in by Lua, which occurs after the onLoaded() event.
    // However, this method will be called when a 2nd Corona activity has been created.

    if (coronaRuntimeTaskDispatcher == null) {
      coronaRuntimeTaskDispatcher = new CoronaRuntimeTaskDispatcher(runtime);
      coronaRuntime = runtime;

      validButtonPos.add(POS_TOP);
      validButtonPos.add(POS_BOTTOM);
      validButtonPos.add(POS_CENTER);

      validAlignPos.add(POS_LEFT);
      validAlignPos.add(POS_RIGHT);

      validGender.add("male");
      validGender.add("female");
      validGender.add("other");

      pollfishObjects.put(SURVEY_READY_KEY, false);

      surveyReceivedDelegate = new CoronaSurveyReceivedDelegate();
      surveyCompletedDelegate = new CoronaSurveyCompletedDelegate();
      surveyNotAvailableDelegate = new CoronaSurveyNotAvailableDelegate();
      userNotEligibleDelegate = new CoronaUserNotEligibleDelegate();
      surveyOpenedDelegate = new CoronaSurveyOpenedDelegate();
      surveyClosedDelegate = new CoronaSurveyClosedDelegate();
    }
  }

  /**
   * Called just after the Corona runtime has executed the "main.lua" file.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that has just been started.
   */
  @Override
  public void onStarted(CoronaRuntime runtime)
  {
  }

  /**
   * Called just after the Corona runtime has been suspended which pauses all rendering, audio,
   * timers,
   * and other Corona related operations. This can happen when another Android activity (ie:
   * window) has
   * been displayed, when the screen has been powered off, or when the screen lock is shown.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that has just been suspended.
   */
  @Override
  public void onSuspended(CoronaRuntime runtime)
  {
  }

  /**
   * Called just after the Corona runtime has been resumed after a suspend.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that has just been resumed.
   */
  @Override
  public void onResumed(CoronaRuntime runtime)
  {
    processPollfishRequest();
  }

  /**
   * Called just before the Corona runtime terminates.
   * <p/>
   * This happens when the Corona activity is being destroyed which happens when the user
   * presses the Back button
   * on the activity, when the native.requestExit() method is called in Lua, or when the
   * activity's finish()
   * method is called. This does not mean that the application is exiting.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that is being terminated.
   */
  @Override
  public void onExiting(CoronaRuntime runtime)
  {
    CoronaLua.deleteRef(runtime.getLuaState(), coronaListener);
    coronaListener = CoronaLua.REFNIL;

    coronaRuntimeTaskDispatcher = null;

    // release all objects
    pollfishObjects.clear();
    validGender.clear();
    validAlignPos.clear();
    validButtonPos.clear();

    surveyReceivedDelegate = null;
    surveyNotAvailableDelegate = null;
    surveyCompletedDelegate = null;
    userNotEligibleDelegate = null;
    surveyOpenedDelegate = null;
    surveyClosedDelegate = null;

    appIsRegistered = false;
    hasLoadedOnce = false;
  }

  // -------------------------------------------------------------------
  // helper functions
  // -------------------------------------------------------------------

  public static String getMetadata(Context context, String name) {
    try {
      ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(
      context.getPackageName(), PackageManager.GET_META_DATA);
      if (appInfo.metaData != null) {
        return appInfo.metaData.getString(name);
      }
    }
    catch (PackageManager.NameNotFoundException e) {
      // if we can’t find it in the manifest, just return null
    }

    return null;
  }

  private void processPollfishRequest()
  {
    // make sure init has been called before proceeding
    if (coronaListener != CoronaLua.REFNIL) {
      final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
      final String fApiKey = (String)pollfishObjects.get(APIKEY_KEY);
      final String fRequestUUID = (String)pollfishObjects.get(REQUEST_UUID_KEY);
      final Position fPollfishPosition = (Position)pollfishObjects.get(POSITION_KEY);
      final int fPadding = (int)pollfishObjects.get(PADDING_KEY);
      final boolean fDebugMode = (boolean)pollfishObjects.get(DEVELOPER_MODE_KEY);
      final boolean fCustomMode = (boolean)pollfishObjects.get(CUSTOM_MODE_KEY);
      final UserProperties fUserAttributes = (UserProperties)pollfishObjects.get(USER_ATTRIBUTES_KEY);
      final boolean fOfferwallMode = (boolean)pollfishObjects.get(OFFERWALL_MODE_KEY);
      final boolean fRewardMode = (boolean)pollfishObjects.get(REWARD_MODE_KEY);


      // Run the activity on the uiThread
      if ((coronaActivity != null) && (hasLoadedOnce)) {
        coronaActivity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            Params.Builder paramsBuilder = new Params.Builder(fApiKey);

            paramsBuilder = paramsBuilder.indicatorPosition(fPollfishPosition)
              .indicatorPadding(fPadding)
              .releaseMode(!fDebugMode)
                    .offerwallMode(fOfferwallMode)
                    .rewardMode(fRewardMode)
              .pollfishSurveyReceivedListener(surveyReceivedDelegate)
              .pollfishSurveyCompletedListener(surveyCompletedDelegate)
              .pollfishSurveyNotAvailableListener(surveyNotAvailableDelegate)
              .pollfishUserNotEligibleListener(userNotEligibleDelegate)
              .pollfishOpenedListener(surveyOpenedDelegate)
              .pollfishClosedListener(surveyClosedDelegate);

            if (fRequestUUID != null) {
              paramsBuilder = paramsBuilder.requestUUID(fRequestUUID);
            }

            if (fUserAttributes != null) {
              paramsBuilder = paramsBuilder.userProperties(fUserAttributes);
            }

            // initialize and load
            Pollfish.initWith(coronaActivity, paramsBuilder.build());

            // make sure survey is not automatically displayed if using custom mode
            if (fCustomMode) {
              Pollfish.hide();
            }
          }
        });
      }
    }
  }

  // log message to console
  private void logMsg(String msgType, String errorMsg)
  {
    String functionID = functionSignature;
    if (!functionID.isEmpty()) {
      functionID += ", ";
    }

    Log.i(CORONA_TAG, msgType + functionID + errorMsg);
  }

  // return true if SDK is properly initialized
  private boolean isSDKInitialized()
  {
    if (coronaListener == CoronaLua.REFNIL) {
      logMsg(ERROR_MSG, "pollfish.init() must be called before calling other API functions");
      return false;
    }

    if (! appIsRegistered) {
      logMsg(ERROR_MSG, "The Pollfish apiKey is not registered");
      return false;
    }

    return true;
  }

  // dispatch a Lua event to our callback (dynamic handling of properties through map)
  private void dispatchLuaEvent(final Map<String, Object> event) {
    if (coronaRuntimeTaskDispatcher != null) {
      coronaRuntimeTaskDispatcher.send(new CoronaRuntimeTask() {
        @Override
        public void executeUsing(CoronaRuntime runtime) {
          try {
            LuaState L = runtime.getLuaState();
            CoronaLua.newEvent(L, EVENT_NAME);
            boolean hasErrorKey = false;

            // add event parameters from map
            for (String key: event.keySet()) {
              CoronaLua.pushValue(L, event.get(key));           // push value
              L.setField(-2, key);                              // push key

              if (! hasErrorKey) {
                hasErrorKey = key.equals(CoronaLuaEvent.ISERROR_KEY);
              }
            }

            // add error key if not in map
            if (! hasErrorKey) {
              L.pushBoolean(false);
              L.setField(-2, CoronaLuaEvent.ISERROR_KEY);
            }

            // add provider
            L.pushString(PROVIDER_NAME);
            L.setField(-2, CoronaLuaEvent.PROVIDER_KEY);

            CoronaLua.dispatchEvent(L, coronaListener, 0);
          }
          catch (Exception ex) {
            ex.printStackTrace();
          }
        }
      });
    }
  }

  String getJSONData(SurveyInfo info)
  {
    Map<String,Object> dataDictionary = new HashMap<>();
    dataDictionary.put("playfulSurvey", info.getSurveyClass().endsWith("Playful"));
    dataDictionary.put("surveyPrice", info.getSurveyCPA());

    dataDictionary.put("surveyCPA", info.getSurveyCPA());
    dataDictionary.put("surveyIR", info.getSurveyIR());
    dataDictionary.put("surveyLOI", info.getSurveyLOI());
    dataDictionary.put("surveyClass", info.getSurveyClass());
    dataDictionary.put("rewardName", info.getRewardName());
    dataDictionary.put("rewardValue", info.getRewardValue());

    return new JSONObject(dataDictionary).toString();
  }

  // [Lua] init(listener, options)
  @SuppressWarnings("unused")
  private class Init implements NamedJavaFunction
  {
    @Override
    public String getName()
    {
      return "init";
    }

    @Override
    public int invoke(LuaState luaState)
    {
      // set function signature for error / warning messages
      functionSignature = "pollfish.init(listener, options)";

      // prevent init from being called twice
      if (coronaListener != CoronaLua.REFNIL) {
        logMsg(ERROR_MSG, "init() can only be called once");
        return 0;
      }

      String apiKey = null;
      String requestUUID = null;
      boolean developerMode = false;
      boolean rewardMode = false;

      // check number of arguments passed
      int nargs = luaState.getTop();
      if (nargs != 2) {
        logMsg(ERROR_MSG, "2 arguments expected. got " + nargs);
        return 0;
      }

      // get listener (required)
      if (CoronaLua.isListener(luaState, 1, PROVIDER_NAME)) {
        coronaListener = CoronaLua.newRef(luaState, 1);
      }
      else {
        logMsg(ERROR_MSG, "listener function expected, got: " + luaState.typeName(1));
        return 0;
      }

      // check for options table
      if (luaState.type(2) == LuaType.TABLE) {
        for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
          String key = luaState.toString(-2);

          if (key.equals("apiKey")) {
            if (luaState.type(-1) == LuaType.STRING) {
              apiKey = luaState.toString(-1);
            }
            else {
              logMsg(ERROR_MSG, "options.apiKey expected (string). Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else if (key.equals("developerMode")) {
            if (luaState.type(-1) == LuaType.BOOLEAN) {
              developerMode = luaState.toBoolean(-1);
            }
            else {
              logMsg(ERROR_MSG, "options.developerMode expected (boolean). Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else if (key.equals("requestUUID")) {
            if (luaState.type(-1) == LuaType.STRING ) {
              requestUUID = luaState.toString(-1);
            }
            else {
              logMsg(ERROR_MSG, "options.requestUUID expected (string). Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else if (key.equals("rewardMode")) {
            if (luaState.type(-1) == LuaType.BOOLEAN ) {
              rewardMode = luaState.toBoolean(-1);
            }
            else {
              logMsg(ERROR_MSG, "options.rewardMode expected (boolean). Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else {
            logMsg(ERROR_MSG, "Invalid option '" + key + "'");
            return 0;
          }
        }
      }
      else {
        logMsg(ERROR_MSG, "options table expected. Got " + luaState.typeName(2));
        return 0;
      }

      // validate
      if (apiKey == null) {
        logMsg(ERROR_MSG, "options.apiKey is required");
        return 0;
      }

      final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

      // bail of no valid activity
      if (coronaActivity == null) {
        return 0;
      }

      // declare final values for inner class
      final String fApiKey = apiKey;
      final boolean fDeveloperMode = developerMode;
      final boolean fRewardMode = rewardMode;

      final String fRequestUUID = requestUUID;

      // Create a new runnable object to invoke our activity
      Runnable runnableActivity = new Runnable() {
        public void run() {
          // set up callback for onResume
          pollfishObjects.put(APIKEY_KEY, fApiKey);
          pollfishObjects.put(PADDING_KEY, 0);
          pollfishObjects.put(POSITION_KEY, Position.BOTTOM_RIGHT);
          pollfishObjects.put(DEVELOPER_MODE_KEY, fDeveloperMode);
          pollfishObjects.put(CUSTOM_MODE_KEY, false);
          pollfishObjects.put(OFFERWALL_MODE_KEY, false);
          pollfishObjects.put(REWARD_MODE_KEY, fRewardMode);
          pollfishObjects.put(REQUEST_UUID_KEY, fRequestUUID);

          // log plugin version to device log
          String targetStore = getMetadata(coronaActivity, "targetedAppStore");
          Log.i(CORONA_TAG, PLUGIN_NAME + ": " + PLUGIN_VERSION + " (SDK: " + (targetStore.startsWith("google") ? PLUGIN_GOOGLE_SDK_VERSION : PLUGIN_AMAZON_SDK_VERSION) + ")");

          Map<String, Object> coronaEvent = new HashMap<>();
          coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
          dispatchLuaEvent(coronaEvent);

          appIsRegistered = true;
        }
      };

      // Run the activity on the uiThread
      coronaActivity.runOnUiThread(runnableActivity);

      return 0;
    }
  }

  // [Lua] load( [options] )
  @SuppressWarnings("unused")
  private class Load implements NamedJavaFunction
  {
    @Override
    public String getName()
    {
      return "load";
    }

    @Override
    public int invoke(LuaState luaState)
    {
      // set function signature for error / warning messages
      functionSignature = "pollfish.load( [options] )";

      if (! isSDKInitialized()) {
        return 0;
      }

      String yAlign = null;
      String xAlign = null;
      int padding = 0;
      boolean customMode = false;
      boolean offerwallMode = false;
      boolean rewardMode = false;

      // check number of arguments passed
      int nargs = luaState.getTop();
      if (nargs > 1) {
        logMsg(ERROR_MSG, "0 or 1 argument expected. got " + nargs);
        return 0;
      }

      // check for options table
      if (! luaState.isNoneOrNil(1)) {
        if (luaState.type(1) == LuaType.TABLE) {
          for (luaState.pushNil(); luaState.next(1); luaState.pop(1)) {
            String key = luaState.toString(-2);

            if (key.equals("yAlign")) {
              if (luaState.type(-1) == LuaType.STRING) {
                yAlign = luaState.toString(-1);
              }
              else {
                logMsg(ERROR_MSG, "options.position expected (string). Got " + luaState.typeName(-1));
                return 0;
              }
            }
            else if (key.equals("xAlign")) {
              if (luaState.type(-1) == LuaType.STRING) {
                xAlign = luaState.toString(-1);
              }
              else {
                logMsg(ERROR_MSG, "options.align expected (string). Got " + luaState.typeName(-1));
                return 0;
              }
            }
            else if (key.equals("padding")) {
              if (luaState.type(-1) == LuaType.NUMBER) {
                padding = (int)luaState.toNumber(-1);
              }
              else {
                logMsg(ERROR_MSG, "options.padding expected (number). Got " + luaState.typeName(-1));
                return 0;
              }
            }
            else if (key.equals("customMode")) {
              if ( luaState.type(-1) == LuaType.BOOLEAN) {
                customMode = luaState.toBoolean(-1);
              }
              else {
                logMsg(ERROR_MSG, "options.customMode expected (boolean). Got " + luaState.typeName(-1));
                return 0;
              }
            }
            else if (key.equals("offerwallMode")) {
              if ( luaState.type(-1) == LuaType.BOOLEAN) {
                offerwallMode = luaState.toBoolean(-1);
              }
              else {
                logMsg(ERROR_MSG, "options.offerwallMode expected (boolean). Got " + luaState.typeName(-1));
                return 0;
              }
            }
            else if (key.equals("rewardMode")) {
              if ( luaState.type(-1) == LuaType.BOOLEAN) {
                rewardMode = luaState.toBoolean(-1);
              }
              else {
                logMsg(ERROR_MSG, "options.rewardMode expected (boolean). Got " + luaState.typeName(-1));
                return 0;
              }
            }
            else {
              logMsg(ERROR_MSG, "Invalid option '" + key + "'");
              return 0;
            }
          }
        }
        else {
          logMsg(ERROR_MSG, "options table expected. Got " + luaState.typeName(2));
          return 0;
        }
      }

      // check for valid position
      if (yAlign == null) {
        yAlign = POS_BOTTOM;
      }
      else if (! validButtonPos.contains(yAlign)) {
        logMsg(ERROR_MSG, "options.position, invalid position '" + yAlign + "'");
        return 0;
      }

      // check for valid align
      if (xAlign == null) {
        xAlign = POS_RIGHT;
      }
      else if (! validAlignPos.contains(xAlign)) {
        logMsg(ERROR_MSG, "options.align, invalid alignment '" + xAlign + "'");
        return 0;
      }

      // set button position
      Position pollfishPosition;

      if (yAlign.equals(POS_TOP)) {
        if (xAlign.equals(POS_LEFT)) {
          pollfishPosition = Position.TOP_LEFT;
        }
        else { // default right
          pollfishPosition = Position.TOP_RIGHT;
        }
      }
      else if (yAlign.equals(POS_CENTER))  {
        if (xAlign.equals(POS_LEFT)) {
          pollfishPosition = Position.MIDDLE_LEFT;
        }
        else { // default right
          pollfishPosition = Position.MIDDLE_RIGHT;
        }
      }
      else { // default bottom
        if (xAlign.equals(POS_LEFT)) {
          pollfishPosition = Position.BOTTOM_LEFT;
        }
        else { // default right
          pollfishPosition = Position.BOTTOM_RIGHT;
        }
      }

      // save values for request function
      pollfishObjects.put(PADDING_KEY, padding);
      pollfishObjects.put(POSITION_KEY, pollfishPosition);
      pollfishObjects.put(CUSTOM_MODE_KEY, customMode);
      pollfishObjects.put(OFFERWALL_MODE_KEY, offerwallMode);
      pollfishObjects.put(REWARD_MODE_KEY, rewardMode);

      // set loaded flag
      hasLoadedOnce = true;

      processPollfishRequest();

      return 0;
    }
  }

  // [Lua] show()
  @SuppressWarnings("unused")
  private class Show implements NamedJavaFunction
  {
    @Override
    public String getName()
    {
      return "show";
    }

    @Override
    public int invoke(LuaState luaState)
    {
      // set function signature for error / warning messages
      functionSignature = "pollfish.show()";

      if (! isSDKInitialized()) {
        return 0;
      }

      final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

      // Run the activity on the uiThread
      if (coronaActivity != null) {
        Runnable runnableActivity = new Runnable() {
          public void run() {
            pollfishObjects.put(SURVEY_READY_KEY, Pollfish.isPollfishPresent());

            if (! (boolean)pollfishObjects.get(SURVEY_READY_KEY)) {
              logMsg(WARNING_MSG, "Survey not ready");
            }
            else {
              Pollfish.show();
            }
          }
        };

        coronaActivity.runOnUiThread(runnableActivity);
      }

      return 0;
    }
  }

  // [Lua] hide()
  @SuppressWarnings("unused")
  private class Hide implements NamedJavaFunction
  {
    @Override
    public String getName()
    {
      return "hide";
    }

    @Override
    public int invoke(LuaState luaState)
    {
      // set function signature for error / warning messages
      functionSignature = "pollfish.hide()";

      if (! isSDKInitialized()) {
        return 0;
      }

      final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

      // Run the activity on the uiThread
      if (coronaActivity != null) {
        Runnable runnableActivity = new Runnable() {
          public void run() {
            if (! (boolean)pollfishObjects.get(SURVEY_READY_KEY)) {
              logMsg(WARNING_MSG, "Survey not ready");
            }
            else {
              Pollfish.hide();
            }
          }
        };

        coronaActivity.runOnUiThread(runnableActivity);
      }

      return 0;
    }
  }

  // [Lua] isLoaded()
  @SuppressWarnings("unused")
  private class IsLoaded implements NamedJavaFunction
  {
    @Override
    public String getName()
    {
      return "isLoaded";
    }

    @Override
    public int invoke(LuaState luaState)
    {
      // set function signature for error / warning messages
      functionSignature = "pollfish.isLoaded()";

      if (! isSDKInitialized()) {
        return 0;
      }

      // check if a survey is available
      // must also check hasLoadedOnce since an app restart will falsely report isPollfishPresent as true
      boolean isLoaded = Pollfish.isPollfishPresent() && hasLoadedOnce;
      pollfishObjects.put(SURVEY_READY_KEY, isLoaded);
      luaState.pushBoolean(isLoaded);
      return 1;
    }
  }

  // -------------------------------------------------------------------
  // Delegates
  // -------------------------------------------------------------------

  // [Lua] setUserDetails(options)
  @SuppressWarnings("unused")
  private class SetUserDetails implements NamedJavaFunction
  {
    @Override
    public String getName()
    {
      return "setUserDetails";
    }

    @Override
    public int invoke(LuaState luaState)
    {
      // set function signature for error / warning messages
      functionSignature = "pollfish.setUserDetails(options)";

      String gender = null;
      String facebookId = null;
      String twitterId = null;
      String requestUUID = null;
      double longitude = 0;
      double latitude = 0;
      double horizontalAccuracy = 0;

      // check if SDK ready for method calls
      if (! isSDKInitialized()) {
        return 0;
      }

      // check number of arguments passed
      int nargs = luaState.getTop();
      if (nargs != 1) {
        logMsg(ERROR_MSG, "missing options table.");
        return 0;
      }

      // check for options table
      if (luaState.type(1) == LuaType.TABLE) {
        // get the options
        for (luaState.pushNil(); luaState.next(1); luaState.pop(1)) {
          String key = luaState.toString(-2);

          if (key.equals("age" )) {
            // NOP
            // remains here for backwards compatibility
            // TODO: remove in a later release
          }
          else if (key.equals("gender" )) {
            if (luaState.type(-1) == LuaType.STRING) {
              gender = luaState.toString(-1);
            }
            else {
              logMsg(ERROR_MSG, "options.gender expected (string). Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else if (key.equals("ageGroup" )) {
            // NOP
            // remains here for backwards compatibility
            // TODO: remove in a later release
          }
          else if (key.equals("facebookId" )) {
            if (luaState.type(-1) == LuaType.STRING) {
              facebookId = luaState.toString(-1);
            }
            else {
              logMsg(ERROR_MSG, "options.facebookId expected (string). Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else if (key.equals("twitterId" )) {
            if (luaState.type(-1) == LuaType.STRING) {
              twitterId = luaState.toString(-1);
            }
            else {
              logMsg(ERROR_MSG, "options.twitterId expected (string). Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else if (key.equals("requestUUID")) {
            if (luaState.type(-1) == LuaType.STRING ) {
              requestUUID = luaState.toString(-1);
            }
            else {
              logMsg(ERROR_MSG, "options.requestUUID expected (string). Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else if (key.equals("customData" )) {
            // NOP
            // remains here for backwards compatibility
            // TODO: remove in a later release
          }
          else if (key.equals("location" )) {
            if (luaState.type(-1) == LuaType.TABLE) {
              // get location
              for (luaState.pushNil(); luaState.next(-2); luaState.pop(1)) {
                String locationKey = luaState.toString(-2);

                if (locationKey.equals("longitude")) {
                  if (luaState.type(-1) == LuaType.NUMBER) {
                    longitude = luaState.toNumber(-1);
                  }
                  else {
                    logMsg(ERROR_MSG, "options.location.longitude expected (number). Got " + luaState.typeName(-1));
                    return 0;
                  }
                }
                else if (locationKey.equals("latitude")) {
                  if (luaState.type(-1) == LuaType.NUMBER) {
                    latitude = luaState.toNumber(-1);
                  }
                  else {
                    logMsg(ERROR_MSG, "options.location.latitude expected (number). Got " + luaState.typeName(-1));
                    return 0;
                  }
                }
                else if (locationKey.equals("horizontalAccuracy")) {
                  if (luaState.type(-1) == LuaType.NUMBER) {
                    horizontalAccuracy = luaState.toNumber(-1);
                  }
                  else {
                    logMsg(ERROR_MSG, "options.location.horizontalAccuracy expected (number). Got " + luaState.typeName(-1));
                    return 0;
                  }
                }
                else {
                  logMsg(ERROR_MSG, "option.location invalid option '" + locationKey + "'");
                  return 0;
                }
              }
            }
            else {
              logMsg(ERROR_MSG, "options.location table expected. Got " + luaState.typeName(-1));
              return 0;
            }
          }
          else {
            logMsg(ERROR_MSG, "Invalid option '" + key + "'");
            return 0;
          }
        }

        // define user attributes dictionary
        UserProperties.Builder userAttributes = new UserProperties.Builder();

        // validate gender
        if (gender != null) {
          if (! validGender.contains(gender)) {
            logMsg(ERROR_MSG, "options.gender invalid gender '" + gender + "'. Valid values: 'male', 'female', 'other'");
            return 0;
          }
          if("male".equals(gender)) userAttributes.gender(UserProperties.Gender.MALE);
          if("female".equals(gender)) userAttributes.gender(UserProperties.Gender.FEMALE);
          if("other".equals(gender)) userAttributes.gender(UserProperties.Gender.OTHER);

        }

        // validate facebookId
        if (facebookId != null) {
          //userAttributes.setFacebookId(facebookId);
        }

        // validate twitterId
        if (twitterId != null) {
          //userAttributes.setTwitterId(twitterId);
        }

        // set request UUID
        if (requestUUID != null) {
          pollfishObjects.put(REQUEST_UUID_KEY, requestUUID);
        }

        // location automatically set on Android if developer adds location-permissions to the manifest


        pollfishObjects.put(USER_ATTRIBUTES_KEY, userAttributes.build());
      }
      else {
        logMsg(ERROR_MSG, "options table expected. Got " + luaState.typeName(1));
        return 0;
      }

      return 0;
    }
  }

  public class CoronaSurveyReceivedDelegate implements PollfishSurveyReceivedListener
  {
    @Override
    public void onPollfishSurveyReceived(@Nullable SurveyInfo surveyInfo) {
      // send Corona Lua event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
      coronaEvent.put(EVENT_TYPE_KEY, TYPE_SURVEY);
      coronaEvent.put(EVENT_DATA_KEY, getJSONData(surveyInfo));
      dispatchLuaEvent(coronaEvent);

      pollfishObjects.put(SURVEY_READY_KEY, true);
    }
  }

  public class CoronaSurveyCompletedDelegate implements PollfishSurveyCompletedListener
  {
    @Override
    public void onPollfishSurveyCompleted(SurveyInfo info)
    {
      // send Corona Lua event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_COMPLETED);
      coronaEvent.put(EVENT_TYPE_KEY, TYPE_SURVEY);
      coronaEvent.put(EVENT_DATA_KEY, getJSONData(info));
      dispatchLuaEvent(coronaEvent);

      pollfishObjects.put(SURVEY_READY_KEY, false);
    }
  }

  public class CoronaSurveyNotAvailableDelegate implements PollfishSurveyNotAvailableListener
  {
    @Override
    public void onPollfishSurveyNotAvailable()
    {
      // send Corona Lua event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
      coronaEvent.put(EVENT_TYPE_KEY, TYPE_SURVEY);
      coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_NOT_AVAILABLE);
      coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
      dispatchLuaEvent(coronaEvent);

      pollfishObjects.put(SURVEY_READY_KEY, false);
    }
  }

  public class CoronaUserNotEligibleDelegate implements PollfishUserNotEligibleListener
  {
    @Override
    public void onUserNotEligible()
    {
      // send Corona Lua event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
      coronaEvent.put(EVENT_TYPE_KEY, TYPE_SURVEY);
      coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_NOT_ELIGIBLE);
      coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
      dispatchLuaEvent(coronaEvent);

      pollfishObjects.put(SURVEY_READY_KEY, false);
    }
  }

  public class CoronaSurveyOpenedDelegate implements PollfishOpenedListener
  {
    @Override
    public void onPollfishOpened()
    {
      // send Corona Lua event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
      coronaEvent.put(EVENT_TYPE_KEY, TYPE_SURVEY);
      dispatchLuaEvent(coronaEvent);

      // set the flag (used in closed event)
      isSurveyOpened = true;
    }
  }

  public class CoronaSurveyClosedDelegate implements PollfishClosedListener
  {
    @Override
    public void onPollfishClosed()
    {
      // This is needed as a 'closed' event is also sent by the SDK even if only the button is hidden
      // A 'closed' event should only be sent when a survey is closed.
      if (isSurveyOpened) {
        // send Corona Lua event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
        coronaEvent.put(EVENT_TYPE_KEY, TYPE_SURVEY);
        dispatchLuaEvent(coronaEvent);

        isSurveyOpened = false;
      }
    }
  }
}
