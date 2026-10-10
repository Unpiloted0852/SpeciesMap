package io.github.unpiloted0852.speciesmap;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

/**
 * Hosts the app's single page (assets/index.html) in a WebView. The page does the work; this
 * class keeps it clear of the system bars, sends links to the browser, routes the back button
 * and exposes the self-updater to the page as {@code window.Android}.
 */
public class MainActivity extends Activity {
    private static final String PAGE = "file:///android_asset/index.html";

    private WebView webView;
    private AppUpdater updater;
    private AppUpdater.Release pendingUpdate;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        updater = new AppUpdater(this);

        // From Android 15 the app draws behind the system bars and keyboard; pad the page clear.
        findViewById(R.id.root).setOnApplyWindowInsetsListener(MainActivity::padForInsets);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            // Only the bundled page runs inside the app (and can reach the bridge below).
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (PAGE.equals(request.getUrl().toString())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (ActivityNotFoundException ignored) {
                    // no browser installed
                }
                return true;
            }
        });
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl(PAGE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    @SuppressWarnings("deprecation")
    private static WindowInsets padForInsets(View v, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
        } else {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
        return insets;
    }

    // Android 12 and earlier; newer versions use the callback registered in onCreate.
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /** Back first closes whatever is open in the page (search, About); otherwise it leaves. */
    private void handleBack() {
        webView.evaluateJavascript("!!(window.SpeciesMap && SpeciesMap.onBack())",
                handled -> { if (!"true".equals(handled)) finish(); });
    }

    private void callPage(String function, Object argument) {
        if (isDestroyed()) return;
        String arg = argument instanceof String ? JSONObject.quote((String) argument) : String.valueOf(argument);
        webView.evaluateJavascript("window.SpeciesMap && SpeciesMap." + function + "(" + arg + ")", null);
    }

    @Override
    protected void onDestroy() {
        updater.close();
        webView.destroy();
        super.onDestroy();
    }

    /** Methods the page calls as window.Android.*; they arrive on a WebView thread. */
    private final class Bridge {
        @JavascriptInterface
        public String getVersion() {
            return updater.installedVersion();
        }

        /** Reports a newer release to the page; says "none" or "failed" only when asked by hand. */
        @JavascriptInterface
        public void checkForUpdate(boolean manual) {
            updater.check((newer, failed) -> {
                if (newer != null) {
                    pendingUpdate = newer;
                    callPage("onUpdateAvailable", newer.versionName);
                } else if (manual) {
                    callPage("onNoUpdate", failed);
                }
            });
        }

        @JavascriptInterface
        public void installUpdate() {
            runOnUiThread(() -> {
                if (pendingUpdate == null) return;
                updater.install(pendingUpdate, new AppUpdater.InstallCallback() {
                    @Override
                    public void onProgress(int percent) {
                        callPage("onUpdateProgress", percent);
                    }

                    @Override
                    public void onFinished(String problem) {
                        if (problem != null) callPage("onUpdateFailed", problem);
                    }
                });
            });
        }
    }
}
