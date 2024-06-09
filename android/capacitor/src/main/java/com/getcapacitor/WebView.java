package com.getcapacitor;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;

import java.io.*;
import java.util.Arrays;
import java.util.Map;

import androidx.annotation.NonNull;

public interface WebView {
    static String getChromeVersion(String userAgent) {
        String chromeVer = userAgent.replaceFirst(".*Chrome/([.0-9]+).*", "$1");

        return userAgent.equals(chromeVer) ? "0.0.0.0" : chromeVer;
    }

    public static WebView create(View view) {
        if (view instanceof android.webkit.WebView) {
            return new WebViewSystemImpl((android.webkit.WebView) view);
        } else if (view instanceof org.xwalk.core.XWalkView) {
            return new WebViewXWalkImpl((org.xwalk.core.XWalkView) view);
        } else if (view instanceof org.mozilla.geckoview.GeckoView) {
            return new WebViewGeckoImpl((org.mozilla.geckoview.GeckoView) view);
        } else {
            throw new UnsupportedOperationException("Unsupported WebView type: " + view.getClass().getName());
        }
    }

    void destroy();

    default void removeAllViews() {
        getView().removeAllViews();
    }

    ViewGroup getView();

    void setWebContentsDebuggingEnabled(boolean enabled);

    default void post(Runnable runnable) {
        getView().post(runnable);
    }

    String getUrl();

    default Context getContext() {
        return getView().getContext();
    }

    void setWebChromeClient(final WebChromeClient client);

    void setWebViewClient(final WebViewClient client);

    void onPause();

    void onResume();

    void pauseTimers();

    void resumeTimers();

    void loadUrl(String url);

    boolean canGoBack();

    void goBack();

    void evaluateJavascript(String script, ValueCallback<String> resultCallback);

    void addJavascriptInterface(Object object, String name);

    // Some old Android devices crashes when a method is annotated with
    // @android.webkit.JavascriptInterface, so we use a special method to add the MessageHandler.
    void addMessageHandler(final MessageHandler messageHandler, String name);

    CookieManager getCookieManager();

    WebSettings getSettings();

    interface CookieManager {
        Object getCookieManager();

        void setAcceptCookie(boolean accept);

        void setAcceptFileSchemeCookies(boolean accept);

        void setAcceptThirdPartyCookies(WebView webView, boolean accept);

        void setCookie(String url, String value);

        String getCookie(String url);

        void removeAllCookie();

        void flush();
    }

    interface WebSettings {
        int MIXED_CONTENT_ALWAYS_ALLOW = 0;

        Object getWebSettings();

        void setJavaScriptEnabled(boolean enabled);

        void setDomStorageEnabled(boolean enabled);

        void setGeolocationEnabled(boolean enabled);

        void setDatabaseEnabled(boolean enabled);

        void setMediaPlaybackRequiresUserGesture(boolean enabled);

        void setJavaScriptCanOpenWindowsAutomatically(boolean enabled);

        void setMixedContentMode(int mode);

        void setUserAgentString(String ua);

        String getUserAgentString();
    }

    interface WebResourceRequest {
        Uri getUrl();

        boolean isForMainFrame();

        boolean isRedirect();

        boolean hasGesture();

        String getMethod();

        Map<String, String> getRequestHeaders();
    }

    class WebResourceResponse extends android.webkit.WebResourceResponse {
        private String reasonPhrase;
        private Map<String, String> responseHeaders;
        private int statusCode;

        public WebResourceResponse(String mimeType, String encoding, InputStream data) {
            super(mimeType, encoding, data);
        }

        public WebResourceResponse(String mimeType, String encoding, int statusCode,
                                   String reasonPhrase, Map<String, String> responseHeaders, InputStream data) {
            super(mimeType, encoding, data);
            setStatusCodeAndReasonPhrase(statusCode, reasonPhrase);
            setResponseHeaders(responseHeaders);
        }

        @Override public String getReasonPhrase() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return super.getReasonPhrase();
            } else {
                return reasonPhrase;
            }
        }

        @Override public Map<String, String> getResponseHeaders() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return super.getResponseHeaders();
            } else {
                return responseHeaders;
            }
        }

        @Override public int getStatusCode() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return super.getStatusCode();
            } else {
                return statusCode;
            }
        }

        @Override public void setResponseHeaders(Map<String, String> headers) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                super.setResponseHeaders(headers);
            } else {
                responseHeaders = headers;
            }
        }

        @Override public void setStatusCodeAndReasonPhrase(int statusCode, @NonNull String reasonPhrase) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                super.setStatusCodeAndReasonPhrase(statusCode, reasonPhrase);
            } else {
                this.statusCode = statusCode;
                this.reasonPhrase = reasonPhrase;
            }
        }
    }

    class WebViewClient {
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return shouldOverrideUrlLoading(view, request.getUrl().toString());
        }

        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return shouldInterceptRequest(view, request.getUrl().toString());
        }

        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return null;
        }
    }

    class WebChromeClient {
        public void onShowCustomView(View view, CustomViewCallback callback) {
        }

        public void onHideCustomView() {
        }

        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        }

        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            return false;
        }

        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            return false;
        }

        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            return false;
        }

        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            return false;
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP) public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            return false;
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP) public static class FileChooserParams {
            private android.webkit.WebChromeClient.FileChooserParams fileChooserParams;

            private String title;
            private int mode;
            private String[] acceptTypes;
            private boolean capture;
            private boolean resolveUris;

            protected FileChooserParams(android.webkit.WebChromeClient.FileChooserParams params) {
                fileChooserParams = params;
            }

            protected FileChooserParams(String acceptType, String capture) {
                this(null, MODE_OPEN, new String[] { acceptType }, capture != null && !capture.isEmpty());
            }

            protected FileChooserParams(String title, int mode, String[] acceptTypes, boolean capture) {
                this.title       = title;
                this.mode        = mode;
                this.acceptTypes = acceptTypes;
                this.capture     = capture;
            }

            public static final int MODE_OPEN = 0;
            public static final int MODE_OPEN_MULTIPLE = 1;
            public static final int MODE_OPEN_FOLDER = 2;
            public static final int MODE_SAVE = 3;

            protected FileChooserParams resolveUris() {
                this.resolveUris = true;
                return this;
            }

            private Uri[] resolveUris(Context ctx, Uri[] uris) {
                for (int i = 0; i < uris.length; ++i) {
                    uris[i] = resolveUris ? CompatUtils.resolveUri(ctx, uris[i]) : uris[i];
                }

                return uris;
            }

            public Uri[] parseResult(Context ctx, int resultCode, Intent data) {
                if (fileChooserParams != null) {
                    return android.webkit.WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                } else {
                    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                        return null;
                    } else {
                        return resolveUris(ctx, new Uri[] { data.getData() });
                    }
                }
            }

            public int getMode() {
                if (fileChooserParams != null) {
                    return fileChooserParams.getMode();
                } else {
                    return mode;
                }
            }

            public String[] getAcceptTypes() {
                if (fileChooserParams != null) {
                    return fileChooserParams.getAcceptTypes();
                } else {
                    return acceptTypes;
                }
            }

            public boolean isCaptureEnabled() {
                if (fileChooserParams != null) {
                    return fileChooserParams.isCaptureEnabled();
                } else {
                    return capture;
                }
            }

            public CharSequence getTitle() {
                if (fileChooserParams != null) {
                    return fileChooserParams.getTitle();
                } else {
                    return title;
                }
            }

            public String getFilenameHint() {
                if (fileChooserParams != null) {
                    return fileChooserParams.getFilenameHint();
                } else {
                    return null;
                }
            }

            public Intent createIntent() {
                if (fileChooserParams != null) {
                    return fileChooserParams.createIntent();
                } else {
                    String type = acceptTypes.length == 1 ? acceptTypes[0] : "*/*";

                    return Intent.createChooser(new Intent(Intent.ACTION_GET_CONTENT)
                                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                                        .setTypeAndNormalize(type), getTitle())
                    ;
                }
            }
        }
    }

    interface CustomViewCallback {
        void onCustomViewHidden();
    }

    interface JsResult {
        void cancel();

        void confirm();
    }

    interface JsPromptResult extends JsResult {
        void confirm(String result);
    }
}
