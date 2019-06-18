package com.getcapacitor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import com.getcapacitor.android.R;
import com.getcapacitor.cordova.MockCordovaInterfaceImpl;
import com.getcapacitor.cordova.MockCordovaWebViewImpl;
import com.getcapacitor.plugin.App;

import org.apache.cordova.ConfigXmlParser;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.PluginEntry;
import org.apache.cordova.PluginManager;
import org.xwalk.core.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BridgeActivity extends AppCompatActivity {
  protected Bridge bridge;
  private WebView webView;
  protected MockCordovaInterfaceImpl cordovaInterface;
  protected boolean keepRunning = true;
  private ArrayList<PluginEntry> pluginEntries;
  private PluginManager pluginManager;
  private CordovaPreferences preferences;
  private MockCordovaWebViewImpl mockWebView;

  private boolean useXWalk;
  private XWalkInitializer xwalkInitializer;
  private XWalkUpdater xwalkUpdater;
  private XWalkUpdateAdapter xwalkUpdateAdapter;
  private String xwalkApkUrl;
  private ArrayList<Runnable> xwalkReadyQueue = new ArrayList<Runnable>();

  private int activityDepth = 0;

  private String lastActivityPlugin;

  private List<Class<? extends Plugin>> initialPlugins = new ArrayList<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  protected void init(final Bundle savedInstanceState, List<Class<? extends Plugin>> plugins) {
    this.initialPlugins = plugins;

    loadConfig(this.getApplicationContext(),this);

    getApplication().setTheme(getResources().getIdentifier("AppTheme_NoActionBar", "style", getPackageName()));
    setTheme(getResources().getIdentifier("AppTheme_NoActionBar", "style", getPackageName()));
    setTheme(R.style.AppTheme_NoActionBar);

    boolean defaultDebuggable = false;
    if (0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)) {
      defaultDebuggable = true;
    }

    if (useXWalk) {
      xwalkInitializer = new XWalkInitializer(new XWalkInitializer.XWalkInitListener() {
        @Override public void onXWalkInitStarted() {
          Log.i(LogUtils.getCoreTag(), "XWalk initialization started");
        }

        @Override public void onXWalkInitCancelled() {
          Log.w(LogUtils.getCoreTag(), "XWalk initialization cancelled");
          finish();
        }

        @Override public void onXWalkInitFailed() {
          Log.w(LogUtils.getCoreTag(), "XWalk initialization failed");

          if (xwalkUpdater == null) {
            if (xwalkUpdateAdapter != null) {
              xwalkUpdater = new XWalkUpdater(new XWalkUpdater.XWalkBackgroundUpdateListener() {
                @Override public void onXWalkUpdateStarted() {
                  Log.i(LogUtils.getCoreTag(), "XWalk update started");
                  xwalkUpdateAdapter.onXWalkUpdateStarted();
                }

                @Override public void onXWalkUpdateProgress(int i) {
                  Log.d(LogUtils.getCoreTag(), "XWalk update progress " + i);
                  xwalkUpdateAdapter.onXWalkUpdateProgress(i);
                }

                @Override public void onXWalkUpdateCancelled() {
                  Log.w(LogUtils.getCoreTag(), "XWalk update cancelled");
                  xwalkUpdateAdapter.onXWalkUpdateCancelled();
                }

                @Override public void onXWalkUpdateFailed() {
                  Log.e(LogUtils.getCoreTag(), "XWalk update failed");
                  xwalkUpdateAdapter.onXWalkUpdateFailed();
                }

                @Override public void onXWalkUpdateCompleted() {
                  Log.i(LogUtils.getCoreTag(), "XWalk update completed");
                  xwalkUpdateAdapter.onXWalkUpdateCompleted();
                }
              }, BridgeActivity.this);
            } else {
              xwalkUpdater = new XWalkUpdater(new XWalkUpdater.XWalkUpdateListener() {
                @Override public void onXWalkUpdateCancelled() {
                  Log.w(LogUtils.getCoreTag(), "XWalk update cancelled");
                  finish();
                }
              }, BridgeActivity.this);
            }

            if (xwalkApkUrl != null) {
              xwalkUpdater.setXWalkApkUrl(xwalkApkUrl);
            }
          }

          xwalkUpdater.updateXWalkRuntime();
        }

        @Override public void onXWalkInitCompleted() {
          Log.i(LogUtils.getCoreTag(), "XWalk initialization completed");

          BridgeActivity.this.load(savedInstanceState);

          synchronized (BridgeActivity.class) {
            for (Runnable runnable: xwalkReadyQueue) {
              runnable.run();
            }

            xwalkReadyQueue = null;
          }
        }
      }, this);

      initXWalk();

      WebView.setXWalkWebContentsDebuggingEnabled(Config.getBoolean("android.webContentsDebuggingEnabled", defaultDebuggable));

      setContentView(R.layout.bridge_layout_xwalk);
    } else {
      WebView.setWebContentsDebuggingEnabled(Config.getBoolean("android.webContentsDebuggingEnabled", defaultDebuggable));

      setContentView(R.layout.bridge_layout_main);

      this.load(savedInstanceState);
    }
  }

  /**
   * Load the WebView and create the Bridge
   */
  protected void load(Bundle savedInstanceState) {
    Log.d(LogUtils.getCoreTag(), "Starting BridgeActivity");

    View view = findViewById(R.id.webview);

    if (view instanceof android.webkit.WebView) {
      webView = new WebView((android.webkit.WebView) view);
    }
    else {
      webView = new WebView((XWalkView) view);
    }

    cordovaInterface = new MockCordovaInterfaceImpl(this);
    if (savedInstanceState != null) {
      cordovaInterface.restoreInstanceState(savedInstanceState);
    }

    mockWebView = new MockCordovaWebViewImpl(this.getApplicationContext());
    mockWebView.init(cordovaInterface, pluginEntries, preferences, webView);

    pluginManager = mockWebView.getPluginManager();
    cordovaInterface.onCordovaInit(pluginManager);
    bridge = new Bridge(this, webView, initialPlugins, cordovaInterface, pluginManager);

    Splash.showOnLaunch(this);

    if (savedInstanceState != null) {
      bridge.restoreInstanceState(savedInstanceState);
    }
    this.keepRunning = preferences.getBoolean("KeepRunning", true);
    this.onNewIntent(getIntent());
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
  public void onSaveInstanceState(final Bundle outState) {
    super.onSaveInstanceState(outState);

    if (this.bridge != null) {
      bridge.saveInstanceState(outState);
    }
  }

  @Override
  public void onStart() {
    super.onStart();

    activityDepth++;

    whenXWalkReady(new Runnable() {
      @Override public void run() {
        bridge.onStart();
        mockWebView.handleStart();

        Log.d(LogUtils.getCoreTag(), "App started");
      }
    });
  }

  @Override
  public void onRestart() {
    super.onRestart();

    if (this.bridge != null) {
      this.bridge.onRestart();
    }

    Log.d(LogUtils.getCoreTag(), "App restarted");
  }

  @Override
  public void onResume() {
    super.onResume();

    initXWalk();

    whenXWalkReady(new Runnable() {
      @Override public void run() {
        fireAppStateChanged(true);

        bridge.onResume();

        mockWebView.handleResume(keepRunning);

        Log.d(LogUtils.getCoreTag(), "App resumed");
      }
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

    Log.d(LogUtils.getCoreTag(), "App paused");
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

    Log.d(LogUtils.getCoreTag(), "App stopped");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (this.mockWebView != null) {
      mockWebView.handleDestroy();
    }
    Log.d(LogUtils.getCoreTag(), "App destroyed");
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

  protected class XWalkUpdateAdapter {
    public void onXWalkUpdateStarted() {
    }

    public void onXWalkUpdateProgress(int i) {
    }

    public void onXWalkUpdateCancelled() {
      finish();
    }

    public void onXWalkUpdateFailed() {
      finish();
    }

    public void onXWalkUpdateCompleted() {
      resumeInitialization();
    }

    protected void cancelDownload() {
      xwalkUpdater.cancelBackgroundDownload();
    }

    protected void resumeInitialization() {
      initXWalk();
    }
  }

  protected void useXWalk(boolean yes) {
    useXWalk = yes;
  }

  protected void setXWalkApkUrl(String url) {
    xwalkApkUrl = url;
  }

  protected void setXWalkUpdateListener(XWalkUpdateAdapter listener) {
    xwalkUpdateAdapter = listener;
  }

  private boolean initXWalk() {
    if (xwalkInitializer != null && xwalkInitializer.initAsync()) {
      synchronized (BridgeActivity.class) {
        xwalkReadyQueue = xwalkReadyQueue != null ? xwalkReadyQueue : new ArrayList<Runnable>();
        return true;
      }
    }

    return false;
  }

  protected void whenXWalkReady(Runnable runnable) {
    synchronized (BridgeActivity.class) {
      if (xwalkReadyQueue == null) {
        runnable.run();
      } else {
        xwalkReadyQueue.add(runnable);
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
        webView = WebView.getWebViewVersion(new android.webkit.WebView(this));
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
