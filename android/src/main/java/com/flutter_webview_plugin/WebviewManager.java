package com.flutter_webview_plugin;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import okhttp3.Request;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import static android.app.Activity.RESULT_OK;

/**
 * Created by lejard_h on 20/12/2017.
 */

class WebviewManager {

    private ValueCallback<Uri> mUploadMessage;
    private ValueCallback<Uri[]> mUploadMessageArray;
    private final static int FILECHOOSER_RESULTCODE=1;

    @TargetApi(7)
    class ResultHandler {
        public boolean handleResult(int requestCode, int resultCode, Intent intent){
            boolean handled = false;
            if(Build.VERSION.SDK_INT >= 21){
                if(requestCode == FILECHOOSER_RESULTCODE){
                    Uri[] results = null;
                    if(resultCode == Activity.RESULT_OK && intent != null){
                        String dataString = intent.getDataString();
                        if(dataString != null){
                            results = new Uri[]{ Uri.parse(dataString) };
                        }
                    }
                    if(mUploadMessageArray != null){
                        mUploadMessageArray.onReceiveValue(results);
                        mUploadMessageArray = null;
                    }
                    handled = true;
                }
            }else {
                if (requestCode == FILECHOOSER_RESULTCODE) {
                    Uri result = null;
                    if (resultCode == RESULT_OK && intent != null) {
                        result = intent.getData();
                    }
                    if (mUploadMessage != null) {
                        mUploadMessage.onReceiveValue(result);
                        mUploadMessage = null;
                    }
                    handled = true;
                }
            }
            return handled;
        }
    }

    boolean closed = false;
    WebView webView;
    Activity activity;
    ResultHandler resultHandler;

    WebviewManager(final Activity activity) {
        this.webView = new ObservableWebView(activity);
        this.activity = activity;
        this.resultHandler = new ResultHandler();
        WebViewClient webViewClient = new BrowserClient() {
            // TANG CW - 10012019 - START
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                String jsScript = "window.flutter_inappbrowser.callHandler = function(handlerName, ...args) {" +
                        "window.flutter_inappbrowser._callHandler(handlerName, JSON.stringify(args));" +
                        "}";
                if (webView != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        webView.evaluateJavascript(jsScript, null);
                    } else {
                        webView.loadUrl("javascript:" + jsScript);
                    }
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest (WebView view, WebResourceRequest request) {
                if (!request.getMethod().equalsIgnoreCase("get") || request.getUrl().getPath().equals("/")
                        || !(request.getUrl().getPath().endsWith("css")
                        || request.getUrl().getPath().endsWith("js")
                        || request.getUrl().getPath().endsWith("woff")
                        || request.getUrl().getPath().endsWith("woff2")
                        || request.getUrl().getPath().endsWith("ttf")
                        || request.getUrl().getPath().endsWith("eot"))) {
                    return null;
                }

                String filepath = activity.getApplicationContext().getCacheDir() + request.getUrl().getPath();
                File file = new File(filepath);
                try {
                    if (file.exists()) {
                        InputStream inputStream = new FileInputStream(filepath);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            int statusCode = 200;
                            String reasonPhase = "OK";
                            Map<String, String> responseHeaders = new HashMap<String, String>();
                            responseHeaders.put("Access-Control-Allow-Origin", "*");

                            return new WebResourceResponse(getMimeType(filepath), "UTF-8", statusCode, reasonPhase, responseHeaders, inputStream);
                        }
                        return new WebResourceResponse(getMimeType(filepath), "UTF-8", inputStream);
                    }
                    else {
                        final String url = request.getUrl().toString();
                        Request mRequest = new Request.Builder().url(url).build();

                        long startResourceTime = System.currentTimeMillis();
                        OkHttpClient httpClient = new OkHttpClient();
                        Response response = httpClient.newCall(mRequest).execute();
                        long duration = System.currentTimeMillis() - startResourceTime;

                        if (response.cacheResponse() != null) {
                            duration = 0;
                        }

                        String reasonPhrase = response.message();
                        reasonPhrase = (reasonPhrase.equals("") || reasonPhrase == null) ? "OK" : reasonPhrase;

                        Map<String, String> headersResponse = new HashMap<String, String>();
                        for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
                            StringBuilder value = new StringBuilder();
                            for (String val : entry.getValue()) {
                                value.append((value.toString().isEmpty()) ? val : "; " + val);
                            }
                            headersResponse.put(entry.getKey().toLowerCase(), value.toString());
                        }

                        Map<String, String> headersRequest = new HashMap<String, String>();
                        for (Map.Entry<String, List<String>> entry : mRequest.headers().toMultimap().entrySet()) {
                            StringBuilder value = new StringBuilder();
                            for (String val : entry.getValue()) {
                                value.append((value.toString().isEmpty()) ? val : "; " + val);
                            }
                            headersRequest.put(entry.getKey().toLowerCase(), value.toString());
                        }

                        byte[] dataBytes = response.body().bytes();
                        InputStream dataStream = new ByteArrayInputStream(dataBytes);

                        if (!file.getParentFile().exists())
                            file.getParentFile().mkdirs();
                        FileOutputStream outputStream;

                        try {
                            outputStream = new FileOutputStream(file, false);
                            outputStream.write(dataBytes);
                            outputStream.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        return new WebResourceResponse(
                                response.header("content-type", "text/plain").split(";")[0].trim(),
                                response.header("content-encoding"),
                                response.code(),
                                reasonPhrase,
                                headersResponse,
                                dataStream
                        );
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }

            private String getMimeType(String filepath){
                String fileExtension = filepath.substring(filepath.lastIndexOf(".") + 1, filepath.length());
                String mimeType = "";
                switch (fileExtension){
                    case "css" :
                        mimeType = "text/css";
                        break;
                    case "js" :
                        mimeType = "text/javascript";
                        break;
                    case "png" :
                        mimeType = "image/png";
                        break;
                    case "jpg" :
                        mimeType = "image/jpeg";
                        break;
                    case "ico" :
                        mimeType = "image/x-icon";
                        break;
                    case "woff" :
                    case "woff2" :
                    case "ttf" :
                    case "eot" :
                        mimeType = "application/x-font-opentype";
                        break;
                }
                return mimeType;
            }

            // TANG CW - 10012019 - END
        };
        webView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_BACK:
                            if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                close();
                            }
                            return true;
                    }
                }

                return false;
            }
        });

        ((ObservableWebView) webView).setOnScrollChangedCallback(new ObservableWebView.OnScrollChangedCallback(){
            public void onScroll(int x, int y, int oldx, int oldy){
                Map<String, Object> yDirection = new HashMap<>();
                yDirection.put("yDirection", (double)y);
                FlutterWebviewPlugin.channel.invokeMethod("onScrollYChanged", yDirection);
                Map<String, Object> xDirection = new HashMap<>();
                xDirection.put("xDirection", (double)x);
                FlutterWebviewPlugin.channel.invokeMethod("onScrollXChanged", xDirection);
            }
        });

        webView.setWebViewClient(webViewClient);
        webView.setWebChromeClient(new WebChromeClient()
        {
            //The undocumented magic method override
            //Eclipse will swear at you if you try to put @Override here
            // For Android 3.0+
            public void openFileChooser(ValueCallback<Uri> uploadMsg) {

                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                activity.startActivityForResult(Intent.createChooser(i,"File Chooser"), FILECHOOSER_RESULTCODE);

            }

            // For Android 3.0+
            public void openFileChooser( ValueCallback uploadMsg, String acceptType ) {
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                activity.startActivityForResult(
                        Intent.createChooser(i, "File Browser"),
                        FILECHOOSER_RESULTCODE);
            }

            //For Android 4.1
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture){
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                activity.startActivityForResult( Intent.createChooser( i, "File Chooser" ), FILECHOOSER_RESULTCODE );

            }

            //For Android 5.0+
            public boolean onShowFileChooser(
                    WebView webView, ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams){
                if(mUploadMessageArray != null){
                    mUploadMessageArray.onReceiveValue(null);
                }
                mUploadMessageArray = filePathCallback;

                Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentSelectionIntent.setType("*/*");
                Intent[] intentArray;
                intentArray = new Intent[0];

                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Image Chooser");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);
                activity.startActivityForResult(chooserIntent, FILECHOOSER_RESULTCODE);
                return true;
            }
        });
        // TANG CW - 10012019 - START
        webView.addJavascriptInterface(this, "flutter_inappbrowser");
        // TANG CW - 10012019 - END
    }

    // TANG CW - 10012019 - START
    @JavascriptInterface
    public void _callHandler(String handlerName, String args) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("handlerName", handlerName);
        obj.put("args", args);
        FlutterWebviewPlugin.channel.invokeMethod("onCallJsHandler", obj);
    }
    // TANG CW - 10012019 - END

    private void clearCookies() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean aBoolean) {

                }
            });
        } else {
            CookieManager.getInstance().removeAllCookie();
        }
    }

    private void clearCache() {
        webView.clearCache(true);
        webView.clearFormData();
    }

    void openUrl(
            boolean withJavascript,
            boolean clearCache,
            boolean hidden,
            boolean clearCookies,
            String userAgent,
            String url,
            Map<String, String> headers,
            boolean withZoom,
            boolean withLocalStorage,
            boolean scrollBar,
            boolean supportMultipleWindows,
            boolean appCacheEnabled,
            boolean allowFileURLs
    ) {
        webView.getSettings().setJavaScriptEnabled(withJavascript);
        webView.getSettings().setBuiltInZoomControls(withZoom);
        webView.getSettings().setSupportZoom(withZoom);
        webView.getSettings().setDomStorageEnabled(withLocalStorage);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(supportMultipleWindows);

        webView.getSettings().setSupportMultipleWindows(supportMultipleWindows);

        webView.getSettings().setAppCacheEnabled(appCacheEnabled);

        webView.getSettings().setAllowFileAccessFromFileURLs(allowFileURLs);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(allowFileURLs);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        if (clearCache) {
            clearCache();
        }

        if (hidden) {
            webView.setVisibility(View.INVISIBLE);
        }

        if (clearCookies) {
            clearCookies();
        }

        if (userAgent != null) {
            webView.getSettings().setUserAgentString(userAgent);
        }

        if(!scrollBar){
            webView.setVerticalScrollBarEnabled(false);
        }

        if (headers != null) {
            webView.loadUrl(url, headers);
        } else {
            webView.loadUrl(url);
        }
    }

    void reloadUrl(String url) {
        webView.loadUrl(url);
    }

    void close(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            ViewGroup vg = (ViewGroup) (webView.getParent());
            vg.removeView(webView);
        }
        webView = null;
        if (result != null) {
            result.success(null);
        }

        closed = true;
        FlutterWebviewPlugin.channel.invokeMethod("onDestroy", null);
    }

    void close() {
        close(null, null);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    void eval(MethodCall call, final MethodChannel.Result result) {
        String code = call.argument("code");

        webView.evaluateJavascript(code, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                result.success(value);
            }
        });
    }
    /**
     * Reloads the Webview.
     */
    void reload(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            webView.reload();
        }
    }
    /**
     * Navigates back on the Webview.
     */
    void back(MethodCall call, MethodChannel.Result result) {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        }
    }
    /**
     * Navigates forward on the Webview.
     */
    void forward(MethodCall call, MethodChannel.Result result) {
        if (webView != null && webView.canGoForward()) {
            webView.goForward();
        }
    }

    void resize(FrameLayout.LayoutParams params) {
        webView.setLayoutParams(params);
    }
    /**
     * Checks if going back on the Webview is possible.
     */
    boolean canGoBack() {
        return webView.canGoBack();
    }
    /**
     * Checks if going forward on the Webview is possible.
     */
    boolean canGoForward() {
        return webView.canGoForward();
    }
    void hide(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            webView.setVisibility(View.INVISIBLE);
        }
    }
    void show(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
        }
    }

    void stopLoading(MethodCall call, MethodChannel.Result result){
        if (webView != null){
            webView.stopLoading();
        }
    }
}
