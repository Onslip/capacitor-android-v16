package com.getcapacitor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.getcapacitor.android.R;
import com.getcapacitor.cordova.MockCordovaInterfaceImpl;
import com.getcapacitor.cordova.MockCordovaWebViewImpl;
import com.getcapacitor.plugin.App;

import org.apache.cordova.ConfigXmlParser;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.PluginEntry;
import org.apache.cordova.PluginManager;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;

public class BridgeActivity extends AppCompatActivity {
  protected Bridge bridge;
  private WebView webView;
  protected MockCordovaInterfaceImpl cordovaInterface;
  protected boolean keepRunning = true;
  private ArrayList<PluginEntry> pluginEntries;
  private PluginManager pluginManager;
  private CordovaPreferences preferences;
  private MockCordovaWebViewImpl mockWebView;
  private JSONObject config;

  private String webViewResourceUrl;
  private BiFunction<BridgeActivity, IntConsumer, InitializationHandler> webViewInitializer = WebViewSystemImpl::initializer;
  private WebViewInitListener webViewInitListener = new WebViewInitListener();
  private InitializationHandler initializationHandler;
  private ArrayList<Runnable> initializationReadyQueue = new ArrayList<Runnable>();

  private int activityDepth = 0;

  private String lastActivityPlugin;

  private List<Class<? extends Plugin>> initialPlugins = new ArrayList<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  protected void init(Bundle savedInstanceState, List<Class<? extends Plugin>> plugins) {
    this.init(savedInstanceState, plugins, null);
  }
  protected void init(Bundle savedInstanceState, List<Class<? extends Plugin>> plugins, JSONObject config) {
    this.initialPlugins = plugins;
    this.config = config;
    loadConfig(this.getApplicationContext(),this);

    getApplication().setTheme(getResources().getIdentifier("AppTheme_NoActionBar", "style", getPackageName()));
    setTheme(getResources().getIdentifier("AppTheme_NoActionBar", "style", getPackageName()));
    setTheme(R.style.AppTheme_NoActionBar);

    initializationHandler = webViewInitializer.apply(this, (int viewID) -> {
      setContentView(viewID);
      this.load(savedInstanceState);
    });
  }

  /**
   * Load the WebView and create the Bridge
   */
  protected void load(Bundle savedInstanceState) {
    Logger.debug("Starting BridgeActivity");

    webView = WebView.create(findViewById(R.id.webview));

    cordovaInterface = new MockCordovaInterfaceImpl(this);
    if (savedInstanceState != null) {
      cordovaInterface.restoreInstanceState(savedInstanceState);
    }

    mockWebView = new MockCordovaWebViewImpl(this.getApplicationContext());
    mockWebView.init(cordovaInterface, pluginEntries, preferences, webView);

    pluginManager = mockWebView.getPluginManager();
    cordovaInterface.onCordovaInit(pluginManager);
    bridge = new Bridge(this, webView, initialPlugins, cordovaInterface, pluginManager, preferences, this.config);

    if (savedInstanceState != null) {
      bridge.restoreInstanceState(savedInstanceState);
    }
    this.keepRunning = preferences.getBoolean("KeepRunning", true);
    this.onNewIntent(getIntent());

    synchronized (BridgeActivity.class) {
      if (initializationReadyQueue != null) {
        for (Runnable runnable: initializationReadyQueue) {
            runnable.run();
        }

        initializationReadyQueue = null;
      }
    }
  }

  public Bridge getBridge() {
    return this.bridge;
  }

  /**
   * Notify the App plugin that the current state changed
   * @param isActive
   */
  private void fireAppStateChanged(boolean isActive) {
    PluginHandle handle = bridge.getPlugin("App");
    if (handle == null) {
      return;
    }

    App appState = (App) handle.getInstance();
    if (appState != null) {
      appState.fireChange(isActive);
    }
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    if (this.bridge != null) {
      bridge.saveInstanceState(outState);
    }
  }

  @Override
  public void onStart() {
    super.onStart();

    activityDepth++;

    whenInitialized(() -> {
      bridge.onStart();
      mockWebView.handleStart();

      Logger.debug("App started");
    });
  }

  @Override
  public void onRestart() {
    super.onRestart();

    if (this.bridge != null) {
      this.bridge.onRestart();
    }

    Logger.debug("App restarted");
  }

  @Override
  public void onResume() {
    super.onResume();

    initializationHandler.initialize();

    whenInitialized(() -> {
      fireAppStateChanged(true);

      bridge.onResume();

      mockWebView.handleResume(keepRunning);

      Logger.debug("App resumed");
    });
  }

  @Override
  public void onPause() {
    super.onPause();

    if (this.bridge != null) {
      this.bridge.onPause();
    }

    if (this.mockWebView != null) {
      boolean keepRunning = this.keepRunning || this.cordovaInterface.getActivityResultCallback() != null;
      this.mockWebView.handlePause(keepRunning);
    }

    Logger.debug("App paused");
  }

  @Override
  public void onStop() {
    super.onStop();

    activityDepth = Math.max(0, activityDepth - 1);

    if (this.bridge != null) {
      if (activityDepth == 0) {
        fireAppStateChanged(false);
      }

      this.bridge.onStop();
    }

    if (mockWebView != null) {
      mockWebView.handleStop();
    }

    Logger.debug("App stopped");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    this.bridge.onDestroy();
    if (this.mockWebView != null) {
      mockWebView.handleDestroy();
    }
    Logger.debug("App destroyed");
  }

  @Override
  public void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    if (webView != null) {
      webView.removeAllViews();
      webView.destroy();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
    if (this.bridge == null) {
      return;
    }

    this.bridge.onRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (this.bridge == null) {
      return;
    }
    this.bridge.onActivityResult(requestCode, resultCode, data);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    if (this.bridge == null || intent == null) {
      return;
    }

    this.bridge.onNewIntent(intent);
    mockWebView.onNewIntent(intent);
  }

  @Override
  public void onBackPressed() {
    if (this.bridge == null) {
      return;
    }

    this.bridge.onBackPressed();
  }

  public void loadConfig(Context context, Activity activity) {
    ConfigXmlParser parser = new ConfigXmlParser();
    parser.parse(context);
    preferences = parser.getPreferences();
    preferences.setPreferencesBundle(activity.getIntent().getExtras());
    pluginEntries = parser.getPluginEntries();
  }

  public static abstract class InitializationHandler {
    private final BridgeActivity bridgeActivity;

    protected InitializationHandler(BridgeActivity bridgeActivity) {
      this.bridgeActivity = bridgeActivity;
    }

    protected BridgeActivity getBridgeActivity() {
      return bridgeActivity;
    }

    protected WebViewInitListener getListener() {
      return bridgeActivity.webViewInitListener;
    }

    protected String getResourceUrl() {
      return bridgeActivity.webViewResourceUrl;
    }

    public abstract void initialize();
    public abstract void cancel();
  }

  protected class WebViewInitListener {
    public void onInitStarted() {
    }

    public void onUpdateStarted() {
    }

    public void onUpdateProgress(int i) {
    }

    public void onCancelled() {
      finish();
    }

    public void onFailed() {
      finish();
    }

    public void onCompleted() {
    }

    protected void cancel() {
      initializationHandler.cancel();
    }
  }

  protected void setWebViewInitializer(BiFunction<BridgeActivity, IntConsumer, InitializationHandler> initializer) {
    webViewInitializer = initializer;
  }

  protected void setWebViewInitListener(WebViewInitListener listener) {
    webViewInitListener = listener;
  }

  protected void setWebViewResourceUrl(String url) {
    webViewResourceUrl = url;
  }

  protected void whenInitialized(Runnable runnable) {
    synchronized (BridgeActivity.class) {
      if (initializationReadyQueue == null) {
        runnable.run();
      } else {
        initializationReadyQueue.add(runnable);
      }
    }
  }

  protected boolean isSystemWebViewOlderThan(String version) {
    try {
      String xv = "", cv = "", webView;

      if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.M) {
        // On Android 6.0 (only, apparently), creating a system WebView before Crosswalk has been
        // initialized will cause the Crosswalk-provided WebView to crash with the following error
        // when certain (all?) localization methods are invoked, like "new Date().toLocaleString()":
        //
        // E/v8: Failed to create ICU date format, are ICU data files missing?
        // A/libc: Fatal signal 4 (SIGILL), code 2, fault addr 0x9e5b2019 in tid 4248 (Chrome_InProcRe)
        //
        // So we just assume the system WebView is ancient on Android 6.0 (the factory version is
        // 44.0.2403.119 and the last Crosswalk version was 53.0.2785.14) without actually checking.
        webView = "0.0.0.0";
      }
      else {
        webView = WebViewSystemImpl.getWebViewVersion(new android.webkit.WebView(this));
      }

      for (String num : version.split("\\.")) xv += String.format(Locale.ROOT, "%010d", Integer.parseInt(num));
      for (String num : webView.split("\\.")) cv += String.format(Locale.ROOT, "%010d", Integer.parseInt(num));

      return cv.compareTo(xv) < 0;
    }
    catch (NumberFormatException ignored) {
      return true;
    }
  }
}
