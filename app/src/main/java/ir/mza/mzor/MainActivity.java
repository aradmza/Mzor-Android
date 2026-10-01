package ir.mza.mzor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import ir.myket.billingclient.IabHelper;
import ir.myket.billingclient.util.IabResult;
import ir.myket.billingclient.util.Purchase;

/**
 * Fullscreen WebView for https://mzaai.ir/.
 * Bank gateways are blocked and purchases go through Myket in-app billing.
 * Only microphone capture is granted; camera is not used.
 */
public class MainActivity extends AppCompatActivity {

    private static final String SITE_URL = "https://mzaai.ir/";
    private static final String SITE_HOST = "mzaai.ir";

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WEB_PERMISSIONS = 2001;
    private static final int REQ_MIC_ON_START = 2002;

    private static final String[] BANK_HOSTS = {
            "zarinpal.com", "zibal.ir", "idpay.ir", "nextpay.org", "nextpay.ir",
            "shaparak.ir", "pay.ir", "payping.ir", "vandar.io", "aqayepardakht.ir",
            "behpardakht.com", "sadadpsp.ir", "sep.ir", "pec.ir"
    };

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPermissionRequest;
    private IabHelper billingHelper;
    private boolean billingReady;
    private String pendingSku;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO);
        }

        webView.addJavascriptInterface(new BillingBridge(), "MzaAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progressBar.setProgress(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                filePathCallback = callback;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (request == pendingWebPermissionRequest) {
                    pendingWebPermissionRequest = null;
                }
            }
        });

        setupBilling();
        if (hasMicPermission()) {
            webView.loadUrl(SITE_URL);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_ON_START);
        }
    }

    private void setupBilling() {
        String publicKey = getString(R.string.myket_public_key);
        billingHelper = new IabHelper(this, publicKey);
        billingHelper.startSetup(result -> {
            billingReady = result.isSuccess();
            if (billingReady && pendingSku != null) {
                String sku = pendingSku;
                pendingSku = null;
                launchPurchase(sku);
            }
        });
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        if (isBankGateway(uri)) {
            startMyketPurchase(skuFrom(uri));
            return true;
        }
        String host = uri.getHost();
        if (host != null && (SITE_HOST.equals(host) || host.endsWith("." + SITE_HOST))) {
            return false;
        }
        openExternally(uri);
        return true;
    }

    private boolean isBankGateway(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        if (host.contains("shaparak")) return true;
        for (String blocked : BANK_HOSTS) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
        }
        return false;
    }

    private String skuFrom(Uri uri) {
        String sku = uri.getQueryParameter("sku");
        if (sku == null || sku.trim().isEmpty()) sku = uri.getQueryParameter("productId");
        if (sku == null || sku.trim().isEmpty()) sku = getString(R.string.myket_sku_default);
        return sku.trim();
    }

    private void startMyketPurchase(String sku) {
        if (!isMyketInstalled()) {
            Toast.makeText(this, R.string.payment_myket_missing, Toast.LENGTH_LONG).show();
            return;
        }
        if (!billingReady || billingHelper == null) {
            pendingSku = sku;
            Toast.makeText(this, R.string.payment_myket_only, Toast.LENGTH_SHORT).show();
            return;
        }
        launchPurchase(sku);
    }

    private void launchPurchase(String sku) {
        try {
            billingHelper.launchPurchaseFlow(this, sku, new IabHelper.OnIabPurchaseFinishedListener() {
                @Override
                public void onIabPurchaseFinished(IabResult result, Purchase info) {
                    if (result.isSuccess() && info != null) {
                        billingHelper.consumeAsync(info, (purchase, consumeResult) -> notifySite(true, sku));
                    } else {
                        notifySite(false, sku);
                    }
                }
            }, "mza");
        } catch (Exception e) {
            Toast.makeText(this, R.string.payment_myket_only, Toast.LENGTH_LONG).show();
        }
    }

    private void notifySite(boolean success, String sku) {
        if (webView == null) return;
        String js = "window.dispatchEvent(new CustomEvent('mza-myket-purchase',{detail:{success:"
                + success + ",sku:'" + sku.replace("'", "") + "'}}));";
        webView.evaluateJavascript(js, null);
    }

    private boolean isMyketInstalled() {
        try {
            getPackageManager().getPackageInfo("ir.mservices.market", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void handleWebPermission(PermissionRequest request) {
        boolean wantsAudio = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                wantsAudio = true;
                break;
            }
        }
        if (!wantsAudio) {
            request.deny();
            return;
        }
        if (hasMicPermission()) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            return;
        }
        pendingWebPermissionRequest = request;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_WEB_PERMISSIONS);
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC_ON_START) {
            if (!granted) {
                Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show();
            }
            webView.loadUrl(SITE_URL);
            return;
        }
        if (requestCode == REQ_WEB_PERMISSIONS && pendingWebPermissionRequest != null) {
            if (granted) {
                pendingWebPermissionRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingWebPermissionRequest.deny();
                Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show();
            }
            pendingWebPermissionRequest = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (billingHelper != null) {
            billingHelper.dispose();
            billingHelper = null;
        }
        super.onDestroy();
    }

    private class BillingBridge {
        @JavascriptInterface
        public void purchase(String sku) {
            final String product = (sku == null || sku.trim().isEmpty())
                    ? getString(R.string.myket_sku_default) : sku.trim();
            runOnUiThread(() -> startMyketPurchase(product));
        }
    }
}
