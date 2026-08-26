package com.gyrobridge.dragtest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public final class MainActivity extends Activity {
    static final String ACTION_TELEMETRY = "com.gyrobridge.action.TEST_TELEMETRY";
    private static final String TELEMETRY_PERMISSION = "com.gyrobridge.permission.TEST_TELEMETRY";
    private static final String TAG = "GyroDragTest";
    private WebView webView;
    private TelemetryReceiver telemetryReceiver;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        enterImmersiveMode();
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(2, 6, 23));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, message.message() + " @" + message.lineNumber());
                return true;
            }
        });
        webView.addJavascriptInterface(new MetricsBridge(), "GyroTestBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
        telemetryReceiver = new TelemetryReceiver(this::forwardTelemetry);
    }

    @Override protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        IntentFilter filter = new IntentFilter(ACTION_TELEMETRY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(telemetryReceiver, filter, TELEMETRY_PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(telemetryReceiver, filter, TELEMETRY_PERMISSION, null);
        }
        webView.onResume();
    }

    @Override protected void onPause() {
        unregisterReceiver(telemetryReceiver);
        webView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        webView.removeJavascriptInterface("GyroTestBridge");
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.destroy();
        super.onDestroy();
    }

    private void forwardTelemetry(String json) {
        String quoted = JSONObjectQuote.quote(json);
        webView.post(() -> webView.evaluateJavascript("window.onGyroBridgeTelemetry(JSON.parse(" + quoted + "));", null));
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private static final class MetricsBridge {
        @JavascriptInterface public void report(String json) {
            Log.d(TAG, "pointerMetrics=" + json);
        }
    }

    private static final class JSONObjectQuote {
        static String quote(String value) {
            return JSONObject.quote(value);
        }
    }
}
