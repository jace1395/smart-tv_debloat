package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * Fullscreen web stream player for Android TV.
 * Streams sports web URLs (e.g. westreamf1.st) directly on the TV using hardware-accelerated WebView.
 */
public class WebStreamActivity extends Activity {

    public static final String EXTRA_URL = "extra_stream_url";
    public static final String EXTRA_TITLE = "extra_stream_title";

    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customVideoView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ProgressBar progressBar;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (4 * getResources().getDisplayMetrics().density)));

        root.addView(webView);
        root.addView(fullscreenContainer);
        root.addView(progressBar);
        setContentView(root);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setLoadsImagesAutomatically(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                view.evaluateJavascript(
                    "(function() {" +
                    "  try {" +
                    "    if (window.yt && window.yt.player) { yt.player.setPlaybackQuality('hd1080'); }" +
                    "    function enhance() {" +
                    "      var v = document.querySelector('video');" +
                    "      if (v) {" +
                    "        v.controls = true;" +
                    "        v.setAttribute('playsinline', '');" +
                    "      }" +
                    "    }" +
                    "    enhance();" +
                    "    setInterval(enhance, 1500);" +
                    "  } catch(e) {}" +
                    "})()", null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 90) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customVideoView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customVideoView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customVideoView == null) return;
                fullscreenContainer.removeView(customVideoView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                customVideoView = null;
            }
        });

        String streamUrl = getIntent().getStringExtra(EXTRA_URL);
        if (streamUrl == null || streamUrl.trim().isEmpty()) {
            streamUrl = "https://westreamf1.st/stream";
        }
        webView.loadUrl(streamUrl);
        webView.requestFocus();

        android.widget.Toast.makeText(this, "Remote: [OK] Play/Pause \u00B7 [\u25B2] Fullscreen \u00B7 [\u25C0 \u25B6] \u00B110s", android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customVideoView != null) {
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                fullscreenContainer.removeView(customVideoView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                customVideoView = null;
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (webView != null) {
                webView.evaluateJavascript(
                    "(function() {" +
                    "  var v = document.querySelector('video');" +
                    "  if (v) { if (v.paused) v.play(); else v.pause(); }" +
                    "})()", null);
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_MENU) {
            if (webView != null) {
                webView.evaluateJavascript(
                    "(function() {" +
                    "  var v = document.querySelector('video');" +
                    "  if (v) {" +
                    "    if (document.fullscreenElement || document.webkitFullscreenElement) {" +
                    "      (document.exitFullscreen || document.webkitExitFullscreen).call(document);" +
                    "    } else {" +
                    "      (v.requestFullscreen || v.webkitRequestFullscreen || v.webkitEnterFullscreen).call(v);" +
                    "    }" +
                    "  }" +
                    "})()", null);
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (webView != null) {
                webView.evaluateJavascript(
                    "(function() {" +
                    "  var v = document.querySelector('video');" +
                    "  if (v) { v.currentTime = Math.max(0, v.currentTime - 10); }" +
                    "})()", null);
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (webView != null) {
                webView.evaluateJavascript(
                    "(function() {" +
                    "  var v = document.querySelector('video');" +
                    "  if (v) { v.currentTime = Math.min((v.duration || 999999), v.currentTime + 10); }" +
                    "})()", null);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
