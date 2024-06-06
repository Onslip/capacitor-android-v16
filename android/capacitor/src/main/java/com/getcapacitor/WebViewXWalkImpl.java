package com.getcapacitor;

import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;

import com.getcapacitor.android.R;

import org.xwalk.core.*;

import java.util.Map;
import java.util.function.IntConsumer;

public class WebViewXWalkImpl implements WebView {
    private final XWalkView xwalkView;

    public static String getXWalkVersion(XWalkView xwalkView) {
        return WebView.getChromeVersion(xwalkView.getSettings().getUserAgentString());
    }

    public static class Initializer implements BridgeActivity.InitializationFactory {
        @Override public BridgeActivity.InitializationHandler create(BridgeActivity bridgeActivity) {
            return new InitializationHandler(bridgeActivity);
        }
    }

    private static class InitializationHandler extends BridgeActivity.InitializationHandler implements XWalkInitializer.XWalkInitListener, XWalkUpdater.XWalkBackgroundUpdateListener {
        private final XWalkInitializer xwalkInitializer;
        private OnInitialized onInitialized;
        private XWalkUpdater xwalkUpdater;

        private InitializationHandler(BridgeActivity bridgeActivity) {
            super(bridgeActivity);

            xwalkInitializer = new XWalkInitializer(this, bridgeActivity);
        }

        @Override public void initialize(OnInitialized onInitialized) {
            this.onInitialized = onInitialized;

            Logger.info("Initializing XWalk");
            xwalkInitializer.initAsync();
        }

        @Override public synchronized void cancel() {
            if (xwalkUpdater != null) {
                xwalkUpdater.cancelBackgroundDownload();
            }
        }

        @Override public void onXWalkInitStarted() {
            Logger.info("XWalk initialization started");
            getListener().onInitStarted();
        }

        @Override public void onXWalkInitCompleted() {
            Logger.info("XWalk initialization completed");
            getListener().onCompleted();

            onInitialized.contentView(R.layout.bridge_layout_xwalk);
        }

        @Override public void onXWalkInitCancelled() {
            Logger.warn("XWalk initialization cancelled");
            getListener().onCancelled();
        }

        @Override public synchronized void onXWalkInitFailed() {
            Logger.warn("XWalk initialization failed");

            if (xwalkUpdater == null) {
                xwalkUpdater = new XWalkUpdater(this, getBridgeActivity());

                if (getResourceUrl() != null) {
                    xwalkUpdater.setXWalkApkUrl(getResourceUrl());
                }

                Logger.info("Updating XWalk");
                xwalkUpdater.updateXWalkRuntime();
            } else {
                getListener().onFailed();
            }
        }

        @Override public void onXWalkUpdateStarted() {
            Logger.info("XWalk update started");
            getListener().onUpdateStarted();
        }

        @Override public void onXWalkUpdateProgress(int i) {
            Logger.debug("XWalk update progress " + i);
            getListener().onUpdateProgress(i);
        }

        @Override public void onXWalkUpdateCompleted() {
            Logger.info("XWalk update completed");
            getListener().onUpdateProgress(100);
            initialize(onInitialized); // Init again
        }

        @Override public void onXWalkUpdateCancelled() {
            Logger.warn("XWalk update cancelled");
            getListener().onCancelled();
        }

        @Override public void onXWalkUpdateFailed() {
            Logger.error("XWalk update failed");
            getListener().onFailed();
        }
    }

    public WebViewXWalkImpl(XWalkView xwalkView) {
        Logger.info("Using XWalk WebView " + getXWalkVersion(xwalkView));

        this.xwalkView = xwalkView;
    }

    @Override public void destroy() {
        xwalkView.onDestroy();
    }

    @Override public ViewGroup getView() {
        return xwalkView;
    }

    @Override public void setWebContentsDebuggingEnabled(boolean enabled) {
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, enabled);
    }

    @Override public String getUrl() {
        return xwalkView.getUrl();
    }

    @Override public void setWebChromeClient(final WebChromeClient client) {
        xwalkView.setUIClient(new XWalkUIClient(xwalkView) {
            @Override public void onShowCustomView(View view, org.xwalk.core.CustomViewCallback callback) {
                client.onShowCustomView(view, new CustomViewCallbackXWalkImpl(callback));
            }

            @Override public void onHideCustomView() {
                client.onHideCustomView();
            }

            @Override public boolean onJsAlert(XWalkView view, String url, String message, XWalkJavascriptResult result) {
                return client.onJsAlert(WebViewXWalkImpl.this, url, message, new JsResultXWalkImpl(result));
            }

            @Override public boolean onJsConfirm(XWalkView view, String url, String message, XWalkJavascriptResult result) {
                return client.onJsConfirm(WebViewXWalkImpl.this, url, message, new JsResultXWalkImpl(result));
            }

            @Override public boolean onJsPrompt(XWalkView view, String url, String message, String defaultValue, XWalkJavascriptResult result) {
                return client.onJsPrompt(WebViewXWalkImpl.this, url, message, defaultValue, new JsPromptResultXWalkImpl(result));
            }

            @Override public boolean onConsoleMessage(XWalkView view, String message, int lineNumber, String sourceId, ConsoleMessageType messageType) {
                ConsoleMessage.MessageLevel level = ConsoleMessage.MessageLevel.TIP;

                switch (messageType) {
                    case ERROR:   level = ConsoleMessage.MessageLevel.ERROR;   break;
                    case LOG:     level = ConsoleMessage.MessageLevel.LOG;     break;
                    case INFO:    level = ConsoleMessage.MessageLevel.TIP;     break;
                    case WARNING: level = ConsoleMessage.MessageLevel.WARNING; break;
                }

                if (client.onConsoleMessage(new ConsoleMessage(message, sourceId, lineNumber, level))) {
                    return true;
                }
                else {
                    return super.onConsoleMessage(view, message, lineNumber, sourceId, messageType);
                }
            }

            @Override public void openFileChooser(XWalkView view, final ValueCallback<Uri> uploadFile, final String acceptType, final String capture) {
                if (!client.onShowFileChooser(WebViewXWalkImpl.this, new ValueCallback<Uri[]>() {
                    @Override public void onReceiveValue(Uri[] value) {
                        if (value == null || value.length == 0) {
                            uploadFile.onReceiveValue(null);
                        }
                        else if (value.length == 1) {
                            uploadFile.onReceiveValue(value[0]);
                        } else {
                            Logger.error("Expected a single file from FileChooser, got " + value.length);
                            uploadFile.onReceiveValue(value[0]);
                        }
                    }
                }, new WebChromeClient.FileChooserParams(acceptType, capture))) {
                    super.openFileChooser(view, uploadFile, acceptType, capture);
                }
            }
        });
    }

    @Override public void setWebViewClient(final WebViewClient client) {
        xwalkView.setResourceClient(new XWalkResourceClient(xwalkView) {
            @Override public android.webkit.WebResourceResponse shouldInterceptLoadRequest(XWalkView view, String url) {
                return client.shouldInterceptRequest(WebViewXWalkImpl.this, url);
            }

            @Override public XWalkWebResourceResponse shouldInterceptLoadRequest(XWalkView view, final XWalkWebResourceRequest request) {
                WebResourceResponse response = client.shouldInterceptRequest(WebViewXWalkImpl.this, new WebResourceRequestXWalkImpl(request));

                if (response != null) {
                    return createXWalkWebResourceResponse(response.getMimeType(), response.getEncoding(), response.getData(),
                                                          response.getStatusCode(), response.getReasonPhrase(), response.getResponseHeaders());
                } else {
                    return null;
                }
            }

            @Override public boolean shouldOverrideUrlLoading(XWalkView view, String url) {
                return client.shouldOverrideUrlLoading(WebViewXWalkImpl.this, url);
            }
        });
    }

    @Override public void onPause() {
        // No Crosswalk API for this
    }

    @Override public void onResume() {
        // No Crosswalk API for this
    }

    @Override public void pauseTimers() {
        xwalkView.pauseTimers();
    }

    @Override public void resumeTimers() {
        xwalkView.resumeTimers();
    }

    @Override public void loadUrl(String url) {
        xwalkView.loadUrl(url);
    }

    @Override public boolean canGoBack() {
        return xwalkView.getNavigationHistory().canGoBack();
    }

    @Override public void goBack() {
        xwalkView.getNavigationHistory().navigate(XWalkNavigationHistory.Direction.BACKWARD, 1);
    }

    @Override public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        xwalkView.evaluateJavascript(script, resultCallback);
    }

    @Override public void addJavascriptInterface(Object object, String name) {
        xwalkView.addJavascriptInterface(object, name);
    }

    // Some old Android devices crashes when a method is annotated with
    // @android.webkit.JavascriptInterface, so we use a special method to add the MessageHandler.
    @Override public void addMessageHandler(final MessageHandler messageHandler, String name) {
        xwalkView.addJavascriptInterface(new Object() {
            @org.xwalk.core.JavascriptInterface public void postMessage(String jsonStr) {
                messageHandler.postMessage(jsonStr);
            }
        }, name);
    }

    @Override public CookieManager getCookieManager() {
        return new CookieManagerXWalkImpl();
    }

    @Override public WebSettings getSettings() {
        return new WebSettingsXWalkImpl();
    }

    private static class CookieManagerXWalkImpl implements CookieManager {
        private final XWalkCookieManager xwalkCookieManager;

        private CookieManagerXWalkImpl() {
            xwalkCookieManager = new XWalkCookieManager();
        }

        @Override public Object getCookieManager() {
            return xwalkCookieManager;
        }

        @Override public void setAcceptCookie(boolean accept) {
            xwalkCookieManager.setAcceptCookie(accept);
        }

        @Override public void setAcceptFileSchemeCookies(boolean accept) {
            xwalkCookieManager.setAcceptFileSchemeCookies(accept);
        }

        @Override public void setAcceptThirdPartyCookies(WebView webView, boolean accept) {
            // No Crosswalk API for this
        }

        @Override public void setCookie(String url, String value) {
            xwalkCookieManager.setCookie(url, value);
        }

        @Override public String getCookie(String url) {
            return xwalkCookieManager.getCookie(url);
        }

        @Override public void removeAllCookie() {
            xwalkCookieManager.removeAllCookie();
        }

        @Override public void flush() {
            xwalkCookieManager.flushCookieStore();
        }
    }

    private class WebSettingsXWalkImpl implements WebSettings {
        private final XWalkSettings xwalkSettings;

        private WebSettingsXWalkImpl() {
            xwalkSettings = xwalkView.getSettings();
        }

        @Override public Object getWebSettings() {
            return xwalkSettings;
        }

        @Override public void setJavaScriptEnabled(boolean enabled) {
            xwalkSettings.setJavaScriptEnabled(enabled);
       }

        @Override public void setDomStorageEnabled(boolean enabled) {
            xwalkSettings.setDomStorageEnabled(enabled);
        }

        @Override public void setGeolocationEnabled(boolean enabled) {
            // No Crosswalk API for this
        }

        @Override public void setDatabaseEnabled(boolean enabled) {
            xwalkSettings.setDatabaseEnabled(enabled);
        }

        @Override public void setMediaPlaybackRequiresUserGesture(boolean enabled) {
            xwalkSettings.setMediaPlaybackRequiresUserGesture(enabled);
        }

        @Override public void setJavaScriptCanOpenWindowsAutomatically(boolean enabled) {
            xwalkSettings.setJavaScriptCanOpenWindowsAutomatically(enabled);
        }

        @Override public void setMixedContentMode(int mode) {
            // No Crosswalk API for this
        }

        @Override public void setUserAgentString(String ua) {
            xwalkSettings.setUserAgentString(ua);
        }

        @Override public String getUserAgentString() {
            return xwalkSettings.getUserAgentString();
        }
    }

    private static class WebResourceRequestXWalkImpl implements WebResourceRequest {
        private final XWalkWebResourceRequest request;

        private WebResourceRequestXWalkImpl(XWalkWebResourceRequest request) {
            this.request = request;
        }

        @Override public Uri getUrl() {
            return request.getUrl();
        }

        @Override public boolean isForMainFrame() {
            return request.isForMainFrame();
        }

        @Override public boolean isRedirect() {
            return false; // No Crosswalk API for this
        }

        @Override public boolean hasGesture() {
            return request.hasGesture();
        }

        @Override public String getMethod() {
            return request.getMethod();
        }

        @Override public Map<String, String> getRequestHeaders() {
            return request.getRequestHeaders();
        }
    }

    private static class CustomViewCallbackXWalkImpl implements CustomViewCallback {
        private final org.xwalk.core.CustomViewCallback xwalkCallback;

        private CustomViewCallbackXWalkImpl(org.xwalk.core.CustomViewCallback callback) {
            xwalkCallback = callback;
        }

        @Override public void onCustomViewHidden() {
            xwalkCallback.onCustomViewHidden();
        }
    }

    private static class JsResultXWalkImpl implements JsResult {
        protected final XWalkJavascriptResult xwalkResult;

        private JsResultXWalkImpl(XWalkJavascriptResult result) {
            xwalkResult = result;
        }

        @Override public void cancel() {
            xwalkResult.cancel();
        }

        @Override public void confirm() {
            xwalkResult.confirm();
        }
    }

    private static class JsPromptResultXWalkImpl extends JsResultXWalkImpl implements JsPromptResult {
        private JsPromptResultXWalkImpl(XWalkJavascriptResult result) {
            super(result);
        }

        @Override public void confirm(String result) {
            xwalkResult.confirmWithResult(result);
        }
    }
}
