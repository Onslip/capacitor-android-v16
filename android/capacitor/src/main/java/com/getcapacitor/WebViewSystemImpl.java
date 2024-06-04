package com.getcapacitor;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;

import com.getcapacitor.android.R;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.function.IntConsumer;

public class WebViewSystemImpl implements WebView {
    private final android.webkit.WebView webView;

    public static String getWebViewVersion(android.webkit.WebView webView) {
        return WebView.getChromeVersion(webView.getSettings().getUserAgentString());
    }

    public static BridgeActivity.InitializationHandler initializer(BridgeActivity bridgeActivity, IntConsumer onInitialized) {
        return new BridgeActivity.InitializationHandler(bridgeActivity) {
            @Override public void initialize() {
                getListener().onInitStarted();
                getListener().onCompleted();
                onInitialized.accept(R.layout.bridge_layout_main);
            }

            @Override public void cancel() {
                // Nothing to do
            }
        };
    }

    public WebViewSystemImpl(android.webkit.WebView webView) {
        Logger.info("Using System WebView " + getWebViewVersion(webView));

        this.webView = webView;
    }

    @Override public void destroy() {
        webView.destroy();
    }

    @Override public ViewGroup getView() {
        return webView;
    }

    @Override public void setWebContentsDebuggingEnabled(boolean enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(enabled);
        }
    }

    @Override public String getUrl() {
        return webView.getUrl();
    }

    @Override public void setWebChromeClient(final WebChromeClient client) {
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onShowCustomView(View view, android.webkit.WebChromeClient.CustomViewCallback callback) {
                client.onShowCustomView(view, new SystemCustomViewCallback(callback));
            }

            @Override public void onHideCustomView() {
                client.onHideCustomView();
            }

            @Override public boolean onJsAlert(android.webkit.WebView view, String url, String message, android.webkit.JsResult result) {
                return client.onJsAlert(WebViewSystemImpl.this, url, message, new SystemJsResult(result));
            }

            @Override public boolean onJsConfirm(android.webkit.WebView view, String url, String message, android.webkit.JsResult result) {
                return client.onJsConfirm(WebViewSystemImpl.this, url, message, new SystemJsResult(result));
            }

            @Override public boolean onJsPrompt(android.webkit.WebView view, String url, String message, String defaultValue, android.webkit.JsPromptResult result) {
                return client.onJsPrompt(WebViewSystemImpl.this, url, message, defaultValue, new SystemJsPromptResult(result));
            }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                client.onGeolocationPermissionsShowPrompt(origin, callback);
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                client.onPermissionRequest(request);
            }

            @Override public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return client.onConsoleMessage(consoleMessage);
            }

            @Override public boolean onShowFileChooser(android.webkit.WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                return client.onShowFileChooser(WebViewSystemImpl.this, filePathCallback, new WebChromeClient.FileChooserParams(fileChooserParams));
            }
        });
    }

    @Override public void setWebViewClient(final WebViewClient client) {
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String url) {
                return client.shouldOverrideUrlLoading(WebViewSystemImpl.this, url);
            }

            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
                return client.shouldOverrideUrlLoading(WebViewSystemImpl.this, new SystemWebResourceRequest(request));
            }

            @Nullable @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view, String url) {
                return client.shouldInterceptRequest(WebViewSystemImpl.this, url);
            }

            @Nullable @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
                return client.shouldInterceptRequest(WebViewSystemImpl.this, new SystemWebResourceRequest(request));
            }
        });
    }

    @Override public void onPause() {
        webView.onPause();
    }

    @Override public void onResume() {
        webView.onResume();
    }

    @Override public void pauseTimers() {
        webView.pauseTimers();
    }

    @Override public void resumeTimers() {
        webView.resumeTimers();
    }

    @Override public void loadUrl(String url) {
        webView.loadUrl(url);
    }

    @Override public boolean canGoBack() {
        return webView.canGoBack();
    }

    @Override public void goBack() {
        webView.goBack();
    }

    @Override @TargetApi(Build.VERSION_CODES.LOLLIPOP) public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        webView.evaluateJavascript(script, resultCallback);
    }

    @Override @SuppressLint({"AddJavascriptInterface", "JavascriptInterface"}) public void addJavascriptInterface(Object object, String name) {
        webView.addJavascriptInterface(object, name);
    }

    // Some old Android devices crashes when a method is annotated with
    // @android.webkit.JavascriptInterface, so we use a special method to add the MessageHandler.
    @Override @SuppressLint("AddJavascriptInterface") public void addMessageHandler(final MessageHandler messageHandler, String name) {
        webView.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface public void postMessage(String jsonStr) {
                    messageHandler.postMessage(jsonStr);
                }
        }, name);
    }

    @Override public CookieManager getCookieManager() {
        return new SystemCookieManager();
    }

    @Override public WebSettings getSettings() {
        return new SystemWebSettings();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP) private static class SystemCookieManager implements CookieManager {
        private final android.webkit.CookieManager cookieManager;

        private SystemCookieManager() {
            cookieManager = android.webkit.CookieManager.getInstance();
        }

        @Override public Object getCookieManager() {
            return cookieManager;
        }

        @Override public void setAcceptCookie(boolean accept) {
            cookieManager.setAcceptCookie(accept);
        }

        @Override public void setAcceptFileSchemeCookies(boolean accept) {
            cookieManager.setAcceptFileSchemeCookies(accept);
        }

        @Override public void setAcceptThirdPartyCookies(WebView webView, boolean accept) {
            cookieManager.setAcceptThirdPartyCookies(((WebViewSystemImpl) webView).webView, accept);
        }

        @Override public void setCookie(String url, String value) {
            cookieManager.setCookie(url, value);
        }

        @Override public String getCookie(String url) {
            return cookieManager.getCookie(url);
        }

        @Override public void removeAllCookie() {
            cookieManager.removeAllCookie();
        }

        @Override public void flush() {
            cookieManager.flush();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP) private class SystemWebSettings implements WebSettings {
        private final android.webkit.WebSettings webSettings;

        private SystemWebSettings() {
            webSettings = webView.getSettings();
        }

        @Override public Object getWebSettings() {
            return webSettings;
        }

        @Override public void setJavaScriptEnabled(boolean enabled) {
            webSettings.setJavaScriptEnabled(enabled);
       }

        @Override public void setDomStorageEnabled(boolean enabled) {
            webSettings.setDomStorageEnabled(enabled);
        }

        @Override public void setGeolocationEnabled(boolean enabled) {
            webSettings.setGeolocationEnabled(enabled);
        }

        @Override public void setDatabaseEnabled(boolean enabled) {
            webSettings.setDatabaseEnabled(enabled);
        }

        @Override public void setMediaPlaybackRequiresUserGesture(boolean enabled) {
            webSettings.setMediaPlaybackRequiresUserGesture(enabled);
        }

        @Override public void setJavaScriptCanOpenWindowsAutomatically(boolean enabled) {
            webSettings.setJavaScriptCanOpenWindowsAutomatically(enabled);
        }

        @Override public void setMixedContentMode(int mode) {
            webSettings.setMixedContentMode(mode);
        }

        @Override public void setUserAgentString(String ua) {
            webSettings.setUserAgentString(ua);
        }

        @Override public String getUserAgentString() {
            return webSettings.getUserAgentString();
        }
    }

    private static class SystemWebResourceRequest implements WebResourceRequest {
        private final android.webkit.WebResourceRequest request;

        private SystemWebResourceRequest(android.webkit.WebResourceRequest request) {
            this.request = request;
        }

        @Override public Uri getUrl() {
            return request.getUrl();
        }

        @Override public boolean isForMainFrame() {
            return request.isForMainFrame();
        }

        @Override @TargetApi(Build.VERSION_CODES.LOLLIPOP) public boolean isRedirect() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? request.isRedirect() : false;
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

    private static class SystemCustomViewCallback implements CustomViewCallback {
        private final android.webkit.WebChromeClient.CustomViewCallback customViewCallback;

        private SystemCustomViewCallback(android.webkit.WebChromeClient.CustomViewCallback callback) {
            customViewCallback = callback;
        }

        @Override public void onCustomViewHidden() {
            customViewCallback.onCustomViewHidden();
        }
    }

    private static class SystemJsResult implements JsResult {
        protected final android.webkit.JsResult jsResult;

        private SystemJsResult(android.webkit.JsResult result) {
            jsResult = result;
        }

        @Override public void cancel() {
            jsResult.cancel();
        }

        @Override public void confirm() {
            jsResult.confirm();
        }
    }

    private static class SystemJsPromptResult extends SystemJsResult implements JsPromptResult {
        private SystemJsPromptResult(android.webkit.JsPromptResult result) {
            super(result);
        }

        @Override public void confirm(String result) {
            ((android.webkit.JsPromptResult) jsResult).confirm(result);
        }
    }
}
