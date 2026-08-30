package com.tenderbase.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * TenderBase launcher.
 *
 * A deliberately minimal app: it opens the live TenderBase API browser
 * (Swagger UI) inside a WebView so you can browse tenders and try endpoints
 * from your phone. This is the foundation the fuller native app will grow from.
 */
public class MainActivity extends Activity {

    /** The live TenderBase service. Change this if you rename the Render app. */
    private static final String TENDERBASE_URL = "https://tenderbase-api.onrender.com/docs";

    private WebView webView;
    private ProgressBar progressBar;
    private View errorView;
    private TextView errorText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        errorView = findViewById(R.id.errorView);
        errorText = findViewById(R.id.errorText);

        findViewById(R.id.retryButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                errorView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.loadUrl(TENDERBASE_URL);
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);       // Swagger UI needs JS.
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keep navigation inside the WebView.
                view.loadUrl(request.getUrl().toString());
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Only surface the full-screen error for the main frame.
                if (request.isForMainFrame()) {
                    showError();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(TENDERBASE_URL);
        }
    }

    private void showError() {
        webView.setVisibility(View.GONE);
        errorText.setText(getString(R.string.load_error, TENDERBASE_URL));
        errorView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    /** Let the back button navigate WebView history before leaving the app. */
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
