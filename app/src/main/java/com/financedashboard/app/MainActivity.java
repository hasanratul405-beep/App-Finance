package com.financedashboard.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private View pendingBar;
    private TextView pendingText;
    private TextView reviewBtn;
    private static final int SMS_PERMISSION_REQUEST = 101;

    private final BroadcastReceiver txReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            updatePendingBar();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView    = findViewById(R.id.webView);
        pendingBar = findViewById(R.id.pendingBar);
        pendingText = findViewById(R.id.pendingText);
        reviewBtn  = findViewById(R.id.reviewBtn);

        setupWebView();
        requestSmsPermission();
        updatePendingBar();

        reviewBtn.setOnClickListener(v -> openTransactionReview());

        // Listen for new SMS transactions detected in background
        IntentFilter filter = new IntentFilter("com.financedashboard.NEW_TRANSACTION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(txReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(txReceiver, filter);
        }
    }

    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // JS bridge — HTML tool calls NativeBridge.method()
        webView.addJavascriptInterface(new JsBridge(), "NativeBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                // Inject any pending dashboard actions after page loads
                injectPendingActions();
            }
        });

        webView.loadUrl("file:///android_asset/finance_dashboard_whitelabel.html");
    }

    /** After WebView loads, check if there are accepted transactions to apply */
    private void injectPendingActions() {
        String action = getSharedPreferences("fd_pending_actions", Context.MODE_PRIVATE)
                .getString("last_action", null);
        if (action == null) return;

        // Pass to JS
        final String js = "if(window.applyNativeAction){window.applyNativeAction(" +
                action.replace("'", "\\'") + ");}";
        webView.post(() -> webView.evaluateJavascript(js, null));

        // Clear it
        getSharedPreferences("fd_pending_actions", Context.MODE_PRIVATE)
                .edit().remove("last_action").apply();
    }

    private void updatePendingBar() {
        runOnUiThread(() -> {
            int count = TransactionStore.getPendingCount(this);
            if (count > 0) {
                pendingBar.setVisibility(View.VISIBLE);
                pendingText.setText(count + " new transaction" + (count > 1 ? "s" : "") + " detected");
            } else {
                pendingBar.setVisibility(View.GONE);
            }
        });
    }

    private void openTransactionReview() {
        startActivity(new Intent(this, TransactionCardActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void requestSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED) return;

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.sms_permission_title))
            .setMessage(getString(R.string.sms_permission_msg))
            .setPositiveButton(getString(R.string.grant), (d, w) ->
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS},
                    SMS_PERMISSION_REQUEST))
            .setNegativeButton(getString(R.string.skip), null)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePendingBar();
        injectPendingActions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(txReceiver); } catch (Exception ignored) {}
    }

    // ── JavaScript Bridge ─────────────────────────────────────────
    private class JsBridge {

        /** HTML tool calls this when user saves account last-digits in Settings */
        @JavascriptInterface
        public void syncAccountDigits(String accountsJson) {
            try {
                new JSONArray(accountsJson); // validate
                getSharedPreferences("fd_accounts", Context.MODE_PRIVATE)
                    .edit().putString("account_digits", accountsJson).apply();
            } catch (Exception ignored) {}
        }

        /** HTML tool calls this to save currency symbol */
        @JavascriptInterface
        public void saveCurrencySymbol(String symbol) {
            getSharedPreferences("fd_prefs", Context.MODE_PRIVATE)
                .edit().putString("currency_symbol", symbol).apply();
        }

        /** HTML tool calls this to get pending count */
        @JavascriptInterface
        public int getPendingCount() {
            return TransactionStore.getPendingCount(MainActivity.this);
        }

        /** HTML tool calls this to get all pending transactions */
        @JavascriptInterface
        public String getPendingTransactions() {
            List<TransactionStore.PendingTransaction> list =
                TransactionStore.getPending(MainActivity.this);
            JSONArray arr = new JSONArray();
            for (TransactionStore.PendingTransaction tx : list) arr.put(tx.toJson());
            return arr.toString();
        }

        /** HTML tool calls this to dismiss a transaction after handling */
        @JavascriptInterface
        public void dismissTransaction(String id) {
            TransactionStore.removePending(MainActivity.this, id);
            updatePendingBar();
        }

        /** HTML tool calls this to open the native card review screen */
        @JavascriptInterface
        public void openReview() {
            runOnUiThread(() -> openTransactionReview());
        }
    }
}
