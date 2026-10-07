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
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
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

public class MainActivity extends AppCompatActivity {

    private static final String SITE_URL = "https://mzaai.ir/";
    private static final String[] PLAN_SKUS = {
            "pup_plan", "wolf_plan", "alpha_plan",
            "pup_12m", "wolf_12m", "alpha_12m"
    };
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WEB_PERMISSIONS = 2001;
    private static final int REQ_MIC_ON_START = 2002;
    private static final String[] BANK_HOSTS = {
            "zarinpal.com", "zibal.ir", "idpay.ir", "nextpay.org", "nextpay.ir",
            "shaparak.ir", "pay.ir", "payping.ir", "vandar.io", "aqayepardakht.ir",
            "behpardakht.com", "sadadpsp.ir", "sep.ir", "pec.ir", "gateway.zibal.ir"
    };

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPermissionRequest;
    private IabHelper billingHelper;
    private boolean billingReady;
    private boolean purchaseInFlight;
    private String pendingSku;
    private String billingError;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO);
        }

        webView.addJavascriptInterface(new BillingBridge(), "MzaAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(view, request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                view.evaluateJavascript(payHook(), null);
                CookieManager.getInstance().flush();
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
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popupView, WebResourceRequest request) {
                        handleUrl(webView, request.getUrl());
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
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
                if (request == pendingWebPermissionRequest) pendingWebPermissionRequest = null;
            }
        });

        setupBilling();
        if (hasMicPermission()) webView.loadUrl(SITE_URL);
        else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_ON_START);
    }

    private String payHook() {
        return "(function(){"
                + "window.MzaAndroidApp=true;"
                + "document.querySelectorAll('a[target]').forEach(function(a){a.removeAttribute('target');});"
                + "window.open=function(u){if(u)location.href=u;return null;};"
                + "function sku(plan,days){plan=String(plan||'');days=Number(days)||30;"
                + "if(plan!=='pup'&&plan!=='wolf'&&plan!=='alpha')return '';"
                + "return days>=180?plan+'_12m':plan+'_plan';}"
                + "if(typeof startPayment==='function'&&!startPayment.__mza){"
                + "var orig=startPayment;"
                + "startPayment=function(pid,days,btn){var s=sku(pid,days);"
                + "if(window.MzaAndroid&&s){window.MzaAndroid.purchase(s);return;}return orig(pid,days,btn);};"
                + "startPayment.__mza=true;}"
                + "})();";
    }

    private void setupBilling() {
        billingHelper = new IabHelper(this, getString(R.string.myket_public_key));
        billingHelper.startSetup(result -> runOnUiThread(() -> {
            billingReady = result.isSuccess();
            billingError = result.isSuccess() ? null : result.getMessage();
            if (billingReady && pendingSku != null) {
                String sku = pendingSku;
                pendingSku = null;
                launchPurchase(sku);
            }
        }));
    }

    private boolean handleUrl(WebView view, Uri uri) {
        if (uri == null) return false;
        if (isBankGateway(uri)) {
            String sku = skuFrom(uri);
            if (sku == null) sku = pendingSku;
            if (sku == null) Toast.makeText(this, R.string.payment_myket_only, Toast.LENGTH_LONG).show();
            else startMyketPurchase(sku);
            return true;
        }
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            if (view != null) view.loadUrl(uri.toString());
            return true;
        }
        return true;
    }

    private boolean isBankGateway(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        if (host.contains("shaparak") || host.contains("zibal")) return true;
        for (String blocked : BANK_HOSTS) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
        }
        return false;
    }

    private String skuFrom(Uri uri) {
        String raw = uri.toString();
        for (String sku : PLAN_SKUS) if (raw.contains(sku)) return sku;
        String[] params = {"sku", "productId", "plan", "plan_id"};
        for (String param : params) {
            String value = uri.getQueryParameter(param);
            if (isKnownSku(value)) return value.trim();
        }
        return null;
    }

    private boolean isKnownSku(String sku) {
        if (sku == null) return false;
        for (String known : PLAN_SKUS) if (known.equals(sku.trim())) return true;
        return false;
    }

    private void startMyketPurchase(String sku) {
        if (!isKnownSku(sku)) {
            Toast.makeText(this, R.string.payment_myket_only, Toast.LENGTH_LONG).show();
            return;
        }
        if (!isMyketInstalled()) {
            Toast.makeText(this, R.string.payment_myket_missing, Toast.LENGTH_LONG).show();
            return;
        }
        if (!billingReady || billingHelper == null) {
            pendingSku = sku;
            Toast.makeText(this, billingError == null ? getString(R.string.payment_myket_only) : billingError, Toast.LENGTH_LONG).show();
            return;
        }
        launchPurchase(sku);
    }

    private void launchPurchase(String sku) {
        if (purchaseInFlight || billingHelper == null) return;
        purchaseInFlight = true;
        try {
            billingHelper.launchPurchaseFlow(this, sku, (result, info) -> runOnUiThread(() -> {
                purchaseInFlight = false;
                if (result.isSuccess() && info != null) {
                    notifySite(true, sku, info.getToken(), info.getOrderId());
                    billingHelper.consumeAsync(info, (purchase, consumeResult) -> { });
                    return;
                }
                int code = result.getResponse();
                if (code == IabHelper.IABHELPER_USER_CANCELLED || code == 1) return;
                notifySite(false, sku, "", "");
                String message = result.getMessage();
                if (message != null && !message.toLowerCase().contains("cancel")) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            }), "");
        } catch (Exception e) {
            purchaseInFlight = false;
            Toast.makeText(this, R.string.payment_myket_only, Toast.LENGTH_LONG).show();
        }
    }

    private void notifySite(boolean success, String sku, String token, String orderId) {
        if (webView == null) return;
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('mza-myket-purchase',{detail:{success:"
                + success + ",sku:'" + jsQuote(sku) + "',token:'" + jsQuote(token) + "',orderId:'" + jsQuote(orderId) + "'}}));", null);
    }

    private String jsQuote(String value) {
        if (value == null) return "";
        return value.replace("\\", "").replace("'", "").replace("\n", "").replace("\r", "");
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
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) { wantsAudio = true; break; }
        }
        if (!wantsAudio) { request.deny(); return; }
        if (hasMicPermission()) { request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); return; }
        pendingWebPermissionRequest = request;
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_WEB_PERMISSIONS);
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC_ON_START) {
            if (!granted) Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show();
            webView.loadUrl(SITE_URL);
            return;
        }
        if (requestCode == REQ_WEB_PERMISSIONS && pendingWebPermissionRequest != null) {
            if (granted) pendingWebPermissionRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else { pendingWebPermissionRequest.deny(); Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show(); }
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
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        CookieManager.getInstance().flush();
        if (billingHelper != null) { billingHelper.dispose(); billingHelper = null; }
        super.onDestroy();
    }

    private class BillingBridge {
        @JavascriptInterface
        public void purchase(String sku) {
            if (!isKnownSku(sku)) return;
            final String product = sku.trim();
            runOnUiThread(() -> startMyketPurchase(product));
        }
    }
}
