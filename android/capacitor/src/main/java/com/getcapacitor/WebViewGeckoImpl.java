package com.getcapacitor;

import android.net.Uri;
import android.view.ViewGroup;
import android.webkit.*;

import com.getcapacitor.WebView.WebChromeClient.FileChooserParams;
import com.getcapacitor.android.R;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.*;
import org.mozilla.geckoview.*;
import org.mozilla.geckoview.GeckoSession.*;
import org.mozilla.geckoview.WebExtension.MessageDelegate;
import org.mozilla.geckoview.WebExtension.PortDelegate;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.getcapacitor.CompatUtils.US_ASCII;
import static com.getcapacitor.CompatUtils.wrapJSONObject;
import static java.lang.String.format;
import static java.util.Locale.ROOT;

// GeckoView 118 verkar ha stöd för Android 4.1
// CeckoView 119 och senare kräver 5.1. https://bugzilla.mozilla.org/show_bug.cgi?id=1820295
// Default interface methods + compileOnly: https://issuetracker.google.com/issues/225900051?pli=1

public class WebViewGeckoImpl implements WebView {
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD})
    public @interface JavascriptInterface {
    }

    private static GeckoRuntime geckoRuntime;

    private final GeckoView geckoView;
    private final GeckoSession geckoSession;
    private final CapacitorGVProxy gvProxy = new CapacitorGVProxy();
    private final CapacitorGVAPI gvAPI = new CapacitorGVAPI();

    private boolean currentCanGoBack = false;
    private @Nullable String currentURI = null;
    private String currentUserAgent = GeckoSession.getDefaultUserAgent();

    private @Nullable WebChromeClient webChromeClient;
    private @Nullable WebViewClient webViewClient;

    public static class Initializer implements BridgeActivity.InitializationFactory {
        @Override public BridgeActivity.InitializationHandler create(BridgeActivity bridgeActivity) {
            return new BridgeActivity.InitializationHandler(bridgeActivity) {
                @Override public void initialize(OnInitialized onInitialized) {
                    getListener().onInitStarted();
                    getListener().onCompleted();
                    onInitialized.contentView(R.layout.bridge_layout_gecko);
                }

                @Override public void cancel() {
                    // Nothing to do
                }
            };
        }
    }

    public WebViewGeckoImpl(GeckoView geckoView) {
        Logger.info("Using GeckoView WebView");

        this.geckoView = geckoView;
        geckoSession = new GeckoSession();

        geckoSession.setNavigationDelegate(new NavigationDelegate() {
            @Override public void onLocationChange(@NonNull GeckoSession session, @Nullable String url, @NonNull List<PermissionDelegate.ContentPermission> perms, @NonNull Boolean hasUserGesture) {
                Logger.info("NavigationDelegate.onLocationChange " + url);
                currentURI = url;
            }

            @Override public void onCanGoBack(@NonNull GeckoSession session, boolean canGoBack) {
                Logger.info("NavigationDelegate.onCanGoBack " + canGoBack);
                currentCanGoBack = canGoBack;
            }

            @Override public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession session, @NonNull LoadRequest request) {
                Logger.info("NavigationDelegate.onLoadRequest " + request);

                return webViewClient != null && webViewClient.shouldOverrideUrlLoading(WebViewGeckoImpl.this, request.uri)
                        ? GeckoResult.deny()
                        : GeckoResult.allow();
            }
        });

        geckoSession.setPromptDelegate(new PromptDelegate() {
            @Nullable @Override public GeckoResult<PromptResponse> onAlertPrompt(@NonNull GeckoSession session, @NonNull AlertPrompt prompt) {
                GeckoResult<PromptDelegate.PromptResponse> result = new GeckoResult<>();

                return webChromeClient != null && webChromeClient.onJsAlert(WebViewGeckoImpl.this, currentURI, prompt.message, new JsResult() {
                    @Override public void cancel()  { result.complete(prompt.dismiss()); }
                    @Override public void confirm() { result.complete(prompt.dismiss()); }
                }) ? result : null;
            }

            @Nullable @Override public GeckoResult<PromptResponse> onButtonPrompt(@NonNull GeckoSession session, @NonNull ButtonPrompt prompt) {
                GeckoResult<PromptDelegate.PromptResponse> result = new GeckoResult<>();

                return webChromeClient != null && webChromeClient.onJsConfirm(WebViewGeckoImpl.this, currentURI, prompt.message, new JsResult() {
                    @Override public void cancel()  { result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)); }
                    @Override public void confirm() { result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)); }
                }) ? result : null;
            }

            @Nullable @Override public GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession session, @NonNull TextPrompt prompt) {
                GeckoResult<PromptDelegate.PromptResponse> result = new GeckoResult<>();

                return webChromeClient != null && webChromeClient.onJsPrompt(WebViewGeckoImpl.this, currentURI, prompt.message, prompt.defaultValue, new JsPromptResult() {
                    @Override public void cancel()             { result.complete(prompt.dismiss());     }
                    @Override public void confirm()            { result.complete(prompt.confirm(""));   }
                    @Override public void confirm(String text) { result.complete(prompt.confirm(text)); }
                }) ? result : null;
            }

            @Nullable @Override public GeckoResult<PromptResponse> onFilePrompt(@NonNull GeckoSession session, @NonNull FilePrompt prompt) {
                GeckoResult<PromptDelegate.PromptResponse> result = new GeckoResult<>();
                FileChooserParams params = new FileChooserParams(
                        prompt.title,
                        prompt.type == FilePrompt.Type.SINGLE ? FileChooserParams.MODE_OPEN : FileChooserParams.MODE_OPEN_MULTIPLE,
                        prompt.mimeTypes,
                        prompt.capture != FilePrompt.Capture.NONE
                ).resolveUris();

                return webChromeClient != null && webChromeClient.onShowFileChooser(WebViewGeckoImpl.this, (uris) -> {
                    if (uris == null || prompt.type == FilePrompt.Type.SINGLE && uris.length == 0) {
                        result.complete(prompt.dismiss());
                    } else {
                        try {
                            result.complete(prompt.confirm(getView().getContext(), uris));
                        } catch (Exception ex) {
                            Logger.error("Failed to confirm " + Arrays.toString(uris), ex);
                            result.complete(prompt.dismiss());
                        }
                    }
                }, params) ? result : null;
            }
        });

        synchronized (WebViewGeckoImpl.class) {
            if (geckoRuntime == null) {
                geckoRuntime = GeckoRuntime.create(geckoView.getContext());
                geckoRuntime.getSettings().setConsoleOutputEnabled(true);
            }
        }

        geckoRuntime
            .getWebExtensionController()
            .ensureBuiltIn("resource://android/assets/capacitor-gv/", "capacitor-gv@onslip.com")
            .accept(extension -> org.mozilla.gecko.util.ThreadUtils.runOnUiThread(() -> {
                        gvAPI.attach(extension, geckoSession.getWebExtensionController());
                        gvProxy.attach(extension);
                    }),
                    ex -> Logger.error("MessageDelegate", "Error registering extension", ex));

        geckoSession.open(geckoRuntime);
        geckoView.setSession(geckoSession);
    }

    @Override public void destroy() {
        gvAPI.close();
        gvProxy.close();
        geckoSession.close();
    }

    @Override public ViewGroup getView() {
        return geckoView;
    }

    @Override public void setWebContentsDebuggingEnabled(boolean enabled) {
        Logger.info("GeckoView setWebContentsDebuggingEnabled " + enabled);
        geckoRuntime.getSettings().setRemoteDebuggingEnabled(enabled);
    }

    @Override public String getUrl() {
        Logger.info("GeckoView getUrl " + currentURI);
        return currentURI;
    }

    @Override public void setWebChromeClient(WebChromeClient client) {
        Logger.info("GeckoView setWebChromeClient " + client);
        webChromeClient = client;
    }

    @Override public void setWebViewClient(WebViewClient client) {
        Logger.info("GeckoView setWebViewClient " + client);
        webViewClient = client;
    }

    @Override public void onPause() {
        Logger.info("GeckoView onPause");
    }

    @Override public void onResume() {
        Logger.info("GeckoView onResume");
    }

    @Override public void pauseTimers() {
        Logger.info("GeckoView pauseTimers");
        geckoSession.setActive(false);
    }

    @Override public void resumeTimers() {
        Logger.info("GeckoView resumeTimers");
        geckoSession.setActive(true);
    }

    @Override public void loadUrl(String url) {
        Logger.info("GeckoView loadUrl " + url);
        geckoSession.loadUri(url);
    }

    @Override public boolean canGoBack() {
        Logger.info("GeckoView canGoBack " + currentCanGoBack);
        return currentCanGoBack;
    }

    @Override public void goBack() {
        Logger.info("GeckoView goBack");
        geckoSession.goBack();
    }

    @Override public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        Logger.info("GeckoView evaluateJavascript " + script);
        gvAPI.evaluateJavascript(script, resultCallback);
    }

    @Override public void addJavascriptInterface(Object object, String name) {
        Logger.info("GeckoView addJavascriptInterface " + object + " - " + name);
        ArrayList<String> names = new ArrayList<>();

        for (Method method : object.getClass().getMethods()) {
            if (method.isAnnotationPresent(JavascriptInterface.class)) {
                names.add(method.getName());
            }
        }

        gvAPI.addJavascriptInterface(object, name, names.toArray(new String[0]));
    }

    // Some old Android devices crashes when a method is annotated with
    // @android.webkit.JavascriptInterface, so we use a special method to add the MessageHandler.
    @Override public void addMessageHandler(MessageHandler messageHandler, String name) {
        Logger.info("GeckoView addMessageHandler " + messageHandler + " - " + name);

        if (!"androidBridge".equals(name)) {
            throw new IllegalArgumentException("MessageHandler must be registered as 'androidBridge'");
        }

        gvAPI.addMessageHandler(messageHandler);
    }

    @Override public CookieManager getCookieManager() {
        return new CookieManagerGeckoImpl();
    }

    @Override public WebSettings getSettings() {
        return new WebSettingsGeckoImpl();
    }

    private static class CookieManagerGeckoImpl implements CookieManager {
        private CookieManagerGeckoImpl() {
            Logger.error("TODO: GeckoView CookieManager");
        }

        @Override public Object getCookieManager() {
            Logger.error("TODO: GeckoView getCookieManager");
            return null;
        }

        @Override public void setAcceptCookie(boolean accept) {
            Logger.error("TODO: GeckoView setAcceptCookie " + accept);
        }

        @Override public void setAcceptFileSchemeCookies(boolean accept) {
            Logger.error("TODO: GeckoView setAcceptFileSchemeCookies " + accept);
        }

        @Override public void setAcceptThirdPartyCookies(WebView webView, boolean accept) {
            Logger.error("TODO: GeckoView setAcceptThirdPartyCookies " + webView + ": " + accept);
        }

        @Override public void setCookie(String url, String value) {
            Logger.error("TODO: GeckoView setCookie " + url + " = " + value);
        }

        @Override public String getCookie(String url) {
            Logger.error("TODO: GeckoView getCookie " + url);
            return null;
        }

        @Override public void removeAllCookie() {
            Logger.error("TODO: GeckoView removeAllCookie");
        }

        @Override public void flush() {
            Logger.error("TODO: GeckoView flush");
        }
    }

    private class WebSettingsGeckoImpl implements WebSettings {
        private WebSettingsGeckoImpl() {
            Logger.info("GeckoView WebSettings");
        }

        @Override public Object getWebSettings() {
            Logger.info("GeckoView getWebSettings");
            return geckoSession.getSettings();
        }

        @Override public void setJavaScriptEnabled(boolean enabled) {
            Logger.info("GeckoView setJavaScriptEnabled " + enabled);
            geckoSession.getSettings().setAllowJavascript(enabled);
       }

        @Override public void setDomStorageEnabled(boolean enabled) {
            Logger.error("TODO: GeckoView setDomStorageEnabled " + enabled);
        }

        @Override public void setGeolocationEnabled(boolean enabled) {
            Logger.error("TODO: GeckoView setGeolocationEnabled " + enabled);
        }

        @Override public void setDatabaseEnabled(boolean enabled) {
            Logger.error("TODO: GeckoView setDatabaseEnabled " + enabled);
        }

        @Override public void setMediaPlaybackRequiresUserGesture(boolean enabled) {
            Logger.error("TODO: GeckoView setMediaPlaybackRequiresUserGesture " + enabled);
        }

        @Override public void setJavaScriptCanOpenWindowsAutomatically(boolean enabled) {
            Logger.error("TODO: GeckoView setJavaScriptCanOpenWindowsAutomatically " + enabled);
        }

        @Override public void setMixedContentMode(int mode) {
            Logger.error("TODO: GeckoView setMixedContentMode " + mode);
        }

        @Override public void setUserAgentString(String ua) {
            Logger.info("GeckoView setUserAgentString " + ua);
            geckoSession.getSettings().setUserAgentOverride(ua);
            currentUserAgent = ua;
        }

        @Override public String getUserAgentString() {
            Logger.info("GeckoView getUserAgentString " + currentUserAgent);
            return currentUserAgent;
        }
    }

    private static class WebResourceRequestGeckoImpl implements WebResourceRequest {
        private final NavigationDelegate.LoadRequest request;

        private WebResourceRequestGeckoImpl(NavigationDelegate.LoadRequest request) {
            this.request = request;
        }

        @Override public Uri getUrl() {
            return Uri.parse(request.uri);
        }

        @Override public boolean isForMainFrame() {
            return true;
        }

        @Override public boolean isRedirect() {
            return request.isRedirect;
        }

        @Override public boolean hasGesture() {
            return request.hasUserGesture;
        }

        @Override public String getMethod() {
            return "GET";
        }

        @Override public Map<String, String> getRequestHeaders() {
            return Collections.emptyMap();
        }
    }

    private class CapacitorGVAPI implements Closeable {
        final private List<JSONObject> enquedRequests = new ArrayList<>();
        final private Map<String, Object> jsInterfaces = Collections.synchronizedMap(new HashMap<>());
        final private Map<Long, ValueCallback<String>> resultCallbacks = Collections.synchronizedMap(new HashMap<>());
        final private AtomicLong nextRequestID = new AtomicLong(0);

        private @Nullable WebExtension extension;
        private @Nullable WebExtension.SessionController controller;
        private @Nullable WebExtension.Port apiPort;

        public void attach(WebExtension extension, WebExtension.SessionController controller) {
            this.extension = extension;
            this.controller = controller;

            // Hook us up with the Gecko API extension
            MessageDelegate delegate = new MessageDelegate() {
                public void onConnect(@NonNull WebExtension.Port port) {
                    port.setDelegate(new PortDelegate() {
                        @Override public void onPortMessage(@NonNull Object portMessage, @NonNull WebExtension.Port port) {
                            Logger.info("GVAPI response " + portMessage);

                            try {
                                if (portMessage instanceof JSONObject) {
                                    JSONObject request = (JSONObject) portMessage;

                                    long requestID = request.getLong("id");
                                    Object message = request.get("message");

                                    ValueCallback<String> resultCallback = resultCallbacks.remove(requestID);

                                    if (resultCallback != null) {
                                        Logger.info("GVAPI: Invoking value callback for request " + requestID + ": " + message);
                                        resultCallback.onReceiveValue(Objects.toString(message));
                                    } else {
                                        Logger.info("GVAPI: No value callback for request " + requestID + ": " + message);
                                    }
                                } else {
                                    Logger.error("Unexpected GVAPI response type: " + portMessage);
                                }
                            } catch (Exception ex) {
                                Logger.error("Failed to handle GVAPI response", ex);
                            }
                        }

                        @Override public void onDisconnect(@NonNull WebExtension.Port port) {
                            Logger.info("GVAPI WebExtension disconnected: " + port);
                        }
                    });

                    synchronized (CapacitorGVAPI.this) {
                        apiPort = port;

                        for (JSONObject request : enquedRequests) {
                            apiPort.postMessage(request);
                        }

                        enquedRequests.clear();
                    }
                }

                @Override @Nullable public GeckoResult<Object> onMessage(@NonNull String nativeApp, @NonNull Object message, @NonNull WebExtension.MessageSender sender) {
                    Logger.info("GVAPI JS interface: " + nativeApp + " " + message + " " + sender);

                    try {
                        if (message instanceof JSONObject) {
                            JSONObject request = (JSONObject) message;

                            String    name = request.getString("name");
                            String  method = request.getString("method");
                            JSONArray args = request.getJSONArray("args");
                            Object hostObj = jsInterfaces.get(name);
                            Object[] hArgs = new Object[args.length()];

                            if (hostObj == null) {
                                throw new IllegalArgumentException("JS interface " + name + " not registered");
                            }

                            for (int i = 0; i < args.length(); ++i) {
                                hArgs[i] = args.get(i);
                            }

                            for (Method m : hostObj.getClass().getMethods()) {
                                if (m.getName().equals(method)) {
                                    m.invoke(hostObj, hArgs);
                                    return null;
                                }
                            }

                            throw new IllegalArgumentException("Method " + method + " not found in JS interface " + name);
                        } else {
                            throw new IllegalArgumentException("Expected JSONObject");
                        }
                    } catch (Exception ex) {
                        Logger.error("Failed to handle message " + message, ex);
                        return null;
                    }
                }
            };

            extension.setMessageDelegate(delegate, "capacitor.gv.api");
            controller.setMessageDelegate(extension, delegate, "capacitor.gv.api");
        }

        @Override public void close() {
            if (extension != null) {
                extension.setMessageDelegate(null, "capacitor.gv.api");

                if (controller != null) {
                    controller.setMessageDelegate(extension, null, "capacitor.gv.api");
                }
            }
        }

        public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
            try {
                gvAPI.sendMessage(new JSONObject()
                                          .put("action", "evaluate-js")
                                          .put("script", script),
                                  resultCallback);
            } catch (JSONException ex) {
                throw new UnsupportedOperationException(ex);
            }
        }

        public void addJavascriptInterface(Object object, String name, String... methods) {
            try {
                gvAPI.sendMessage(new JSONObject()
                                          .put("action",  "add-js-interface")
                                          .put("name",    name)
                                          .put("methods", wrapJSONObject(methods)),
                                  null);
                jsInterfaces.put(name, object);
            } catch (JSONException ex) {
                throw new UnsupportedOperationException(ex);
            }
        }

        public void addMessageHandler(MessageHandler messageHandler) {
            // The actual interface is added by the content script in order to make it available before the first page
            // script is executed.
            jsInterfaces.put("androidBridge", messageHandler);
        }

        private synchronized void sendMessage(JSONObject message, @Nullable ValueCallback<String> resultCallback) {
            try {
                long requestID = nextRequestID.incrementAndGet();
                JSONObject request = new JSONObject().put("id", requestID).put("message", message);
                resultCallbacks.put(requestID, resultCallback);

                if (apiPort != null) {
                    apiPort.postMessage(request);
                } else {
                    enquedRequests.add(request);
                }
            } catch (JSONException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private class CapacitorGVProxy implements Closeable {
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final Set<Socket> connections = Collections.synchronizedSet(new HashSet<>());
        private @Nullable WebExtension extension;
        private @Nullable volatile ServerSocket serverSocket;

        private CapacitorGVProxy() {
            executor.execute(() -> acceptProxyConnections());
        }

        public void attach(WebExtension extension) {
            this.extension = extension;

            // Hook us up with the Gecko Proxy extension
            extension.setMessageDelegate(new MessageDelegate() {
                public void onConnect(@NonNull WebExtension.Port port) {
                    port.setDelegate(new PortDelegate() {
                        @Override public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port port) {
                            try {
                                if (message instanceof JSONObject) {
                                    JSONObject request = (JSONObject) message;

                                    @Nullable JSONObject proxyInfo = shouldInterceptRequest(request.getJSONObject("message"));
                                    port.postMessage(new JSONObject().put("id", request.getLong("id")).put("message", proxyInfo));
                                }
                            } catch (Exception ex) {
                                Logger.error("Failed to send ProxyInfo response", ex);
                            }
                        }

                        @Override public void onDisconnect(@NonNull WebExtension.Port port) {
                            Logger.info("Proxy WebExtension disconnected: " + port);
                        }
                    });
                }

                @Override @Nullable public GeckoResult<Object> onMessage(@NonNull String nativeApp, @NonNull Object message, @NonNull WebExtension.MessageSender sender) {
                    Logger.info("Proxy WebExtension messageDelegate: " + nativeApp + " " + message + " " + sender);
                    return null;
                }
            }, "capacitor.gv.proxy");
        }

        @Override public void close() {
            if (extension != null) {
                extension.setMessageDelegate(null, "capacitor.gv.proxy");
            }

            executor.shutdownNow();
        }

        private @Nullable JSONObject shouldInterceptRequest(JSONObject requestDetails) throws JSONException {
            ServerSocket serverSocket = this.serverSocket;
            String method = requestDetails.getString("method");
            String source = requestDetails.getString("url");

            if (serverSocket != null && shouldInterceptRequest(method, source, Collections.emptyMap()) != null) {
                return new JSONObject()
                        .put("type",  "http")
                        .put("host",  serverSocket.getInetAddress().getHostAddress())
                        .put("port",  serverSocket.getLocalPort());
            } else {
                return null;
            }
        }

        private @Nullable WebResourceResponse shouldInterceptRequest(String method, String url, Map<String, String> requestHeaders) {
            return webViewClient == null ? null : webViewClient.shouldInterceptRequest(WebViewGeckoImpl.this, new WebResourceRequest() {
                @Override public String              getMethod()         { return method;         }
                @Override public Uri                 getUrl()            { return Uri.parse(url); }
                @Override public Map<String, String> getRequestHeaders() { return requestHeaders; }
                @Override public boolean             isRedirect()        { return false;          }
                @Override public boolean             isForMainFrame()    { return false;          }
                @Override public boolean             hasGesture()        { return false;          }
            });
        }

        private void acceptProxyConnections() {
            try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                this.serverSocket = serverSocket;

                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    connections.add(socket);

                    executor.execute(() -> handleProxyConnection(socket));
                }
            } catch (Throwable ex) {
                Logger.error("acceptProxyConnections failed", ex);
            } finally {
                serverSocket = null;

                for (Socket socket : connections) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }

        private void handleProxyConnection(Socket socket) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), CompatUtils.UTF_8))) {
                String[] request = reader.readLine().split("\\s+");
                Map<String, String> headers = new LinkedHashMap<>();

                for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                    String[] header = line.split(":\\s*", 2);
                    headers.put(header[0], header[1]);
                }

                WebResourceResponse response = shouldInterceptRequest(request[0], request[1], headers);

                if (response == null) {
                    response = new WebResourceResponse(null, null, 502, format(ROOT, "Proxy not handling %s request to %s", request[0], request[1]), null, null);
                }

                Logger.info("PROXY: " + request[0] + " " + request[1] + " " + headers + " => " + response);

                OutputStream out = socket.getOutputStream();
                InputStream data = response.getData();

                out.write(format(ROOT, "HTTP/1.0 %d %s\r\n", response.getStatusCode(), response.getReasonPhrase()).getBytes(US_ASCII));
                out.write("Proxy-Connection: close\r\n".getBytes());

                if (response.getMimeType() != null) {
                    out.write(format(ROOT, "Content-Type: %s%s\r\n", response.getMimeType(),
                        response.getEncoding() != null ? format(ROOT, "; charset=%s", response.getEncoding()) : "").getBytes(US_ASCII));
                }

                if (response.getResponseHeaders() != null) {
                    for (Map.Entry<String, String> header : response.getResponseHeaders().entrySet()) {
                        out.write(format(ROOT, "%s: %s\r\n", header.getKey(), header.getValue()).getBytes(US_ASCII));
                    }
                }

                out.write("\r\n".getBytes(US_ASCII));

                if (data != null) {
                    byte[] chunk = new byte[4096];

                    for (int length = data.read(chunk); length != -1; length = data.read(chunk)) {
                        out.write(chunk, 0, length);
                    }
                }

                out.flush();
            } catch (Throwable ex) {
                Logger.error("handleProxyConnection failed", ex);
            } finally {
                Logger.info("handleProxyConnection done");
                connections.remove(socket);
            }
        }
    }
}