package com.financedashboard.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class TransactionCardActivity extends AppCompatActivity {

    private FrameLayout cardStack;
    private TextView pendingCount;
    private LinearLayout doneLayout;
    private View swipeHint;

    private List<TransactionStore.PendingTransaction> transactions;
    private int currentIndex = 0;

    private static final float SWIPE_THRESHOLD = 120f;
    private static final float ROTATION_FACTOR = 0.08f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_card);

        cardStack    = findViewById(R.id.cardStack);
        pendingCount = findViewById(R.id.pendingCount);
        doneLayout   = findViewById(R.id.doneLayout);
        swipeHint    = findViewById(R.id.swipeHint);

        TextView closeBtn = findViewById(R.id.closeBtn);
        closeBtn.setOnClickListener(v -> finish());

        loadTransactions();
    }

    private void loadTransactions() {
        transactions = TransactionStore.getPending(this);
        currentIndex = 0;
        updateHeader();
        renderCards();
    }

    private void updateHeader() {
        int remaining = transactions.size() - currentIndex;
        if (remaining <= 0) {
            pendingCount.setText("All reviewed");
        } else {
            pendingCount.setText(remaining + " pending");
        }
    }

    private void renderCards() {
        cardStack.removeAllViews();

        if (currentIndex >= transactions.size()) {
            showDone();
            return;
        }

        // Show up to 3 cards stacked (back cards are scaled/offset for depth)
        int cardsToShow = Math.min(3, transactions.size() - currentIndex);

        for (int i = cardsToShow - 1; i >= 0; i--) {
            int txIndex = currentIndex + i;
            if (txIndex >= transactions.size()) continue;

            View cardView = buildCard(transactions.get(txIndex), i == 0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );

            // Stack effect: back cards offset down and slightly smaller
            float scale = 1f - (i * 0.04f);
            float offsetY = i * 14f;
            cardView.setScaleX(scale);
            cardView.setScaleY(scale);
            cardView.setTranslationY(offsetY);
            cardView.setAlpha(i == 0 ? 1f : 0.7f - (i * 0.15f));

            cardStack.addView(cardView, lp);

            // Only front card is interactive
            if (i == 0) {
                attachSwipe(cardView, transactions.get(txIndex));
            }
        }

        updateHeader();
    }

    private View buildCard(TransactionStore.PendingTransaction tx, boolean isFront) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View card = inflater.inflate(R.layout.card_transaction, cardStack, false);

        // Type pill
        TextView typePill = card.findViewById(R.id.typePill);
        typePill.setText(SmsParser.getTypeLabel(
            buildParsed(tx)));

        // Bank initial + name
        TextView bankInitial = card.findViewById(R.id.bankInitial);
        TextView bankName    = card.findViewById(R.id.bankName);
        String displayBank   = tx.bank != null && !tx.bank.isEmpty() ? tx.bank : "Unknown Bank";
        bankInitial.setText(displayBank.substring(0, 1).toUpperCase());
        bankName.setText(displayBank);

        // Matched account
        if (tx.matchedAcctName != null && !tx.matchedAcctName.isEmpty()) {
            TextView matched = card.findViewById(R.id.matchedAccount);
            matched.setText("Matched: " + tx.matchedAcctName);
            matched.setVisibility(View.VISIBLE);
        }

        // Amount
        TextView amountView = card.findViewById(R.id.amount);
        amountView.setText(formatAmount(tx.amount));

        // Transaction type label
        TextView txTypeLabel = card.findViewById(R.id.txTypeLabel);
        txTypeLabel.setText("debit".equals(tx.type) ? "Amount Sent" : "Amount Received");

        // Date
        TextView dateView = card.findViewById(R.id.txDate);
        dateView.setText(tx.date != null ? tx.date : "");

        // View details expands SMS
        TextView viewDetails = card.findViewById(R.id.viewDetails);
        viewDetails.setOnClickListener(v -> showSmsDialog(tx.rawSms));

        // Accept / Reject buttons (only on front card)
        View actionButtons = card.findViewById(R.id.actionButtons);
        if (isFront) {
            View acceptBtn = card.findViewById(R.id.acceptBtn);
            View rejectBtn = card.findViewById(R.id.rejectBtn);
            TextView skipBtn   = card.findViewById(R.id.skipBtn);

            acceptBtn.setOnClickListener(v -> acceptTransaction(card, tx));
            rejectBtn.setOnClickListener(v -> rejectTransaction(card, tx));
            skipBtn.setOnClickListener(v   -> skipTransaction(tx));
        } else {
            actionButtons.setVisibility(View.INVISIBLE);
        }

        return card;
    }

    private SmsParser.ParsedTransaction buildParsed(TransactionStore.PendingTransaction tx) {
        SmsParser.ParsedTransaction p = new SmsParser.ParsedTransaction();
        p.type = tx.type;
        p.bank = tx.bank;
        return p;
    }

    // ── Swipe gesture ─────────────────────────────────────────────
    private void attachSwipe(final View card,
                             final TransactionStore.PendingTransaction tx) {
        final float[] startX = {0};
        final float[] startY = {0};
        final View acceptOverlay = card.findViewById(R.id.acceptOverlay);
        final View rejectOverlay  = card.findViewById(R.id.rejectOverlay);

        card.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = event.getRawX() - card.getTranslationX();
                    startY[0] = event.getRawY() - card.getTranslationY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - startX[0];
                    float dy = event.getRawY() - startY[0];
                    card.setTranslationX(dx);
                    card.setTranslationY(dy * 0.3f);
                    card.setRotation(dx * ROTATION_FACTOR);

                    // Show swipe overlays
                    float progress = Math.min(1f, Math.abs(dx) / SWIPE_THRESHOLD);
                    if (dx > 20) {
                        acceptOverlay.setVisibility(View.VISIBLE);
                        acceptOverlay.setAlpha(progress);
                        rejectOverlay.setVisibility(View.INVISIBLE);
                    } else if (dx < -20) {
                        rejectOverlay.setVisibility(View.VISIBLE);
                        rejectOverlay.setAlpha(progress);
                        acceptOverlay.setVisibility(View.INVISIBLE);
                    } else {
                        acceptOverlay.setVisibility(View.INVISIBLE);
                        rejectOverlay.setVisibility(View.INVISIBLE);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    float finalDx = card.getTranslationX();
                    acceptOverlay.setVisibility(View.INVISIBLE);
                    rejectOverlay.setVisibility(View.INVISIBLE);

                    if (finalDx > SWIPE_THRESHOLD) {
                        acceptTransaction(card, tx);
                    } else if (finalDx < -SWIPE_THRESHOLD) {
                        rejectTransaction(card, tx);
                    } else {
                        // Snap back
                        card.animate()
                            .translationX(0).translationY(0).rotation(0)
                            .setDuration(200).start();
                    }
                    return true;
            }
            return false;
        });
    }

    // ── Accept: mark in dashboard, remove from pending ────────────
    private void acceptTransaction(View card, TransactionStore.PendingTransaction tx) {
        animateOut(card, true, () -> {
            TransactionStore.removePending(this, tx.id);
            // If there's a matched account, notify the WebView via broadcast
            if (tx.matchedAcctId != null && !tx.matchedAcctId.isEmpty()) {
                notifyDashboard("ACCEPT", tx);
            }
            currentIndex++;
            renderCards();
        });
    }

    // ── Reject: just remove from pending ─────────────────────────
    private void rejectTransaction(View card, TransactionStore.PendingTransaction tx) {
        animateOut(card, false, () -> {
            TransactionStore.removePending(this, tx.id);
            currentIndex++;
            renderCards();
        });
    }

    // ── Skip: move to back of queue ───────────────────────────────
    private void skipTransaction(TransactionStore.PendingTransaction tx) {
        TransactionStore.removePending(this, tx.id);
        TransactionStore.addTransaction(this, tx); // re-add to back
        currentIndex = 0;
        loadTransactions();
    }

    private void animateOut(View card, boolean toRight, Runnable onDone) {
        float targetX = toRight ? getScreenWidth() * 1.5f : -getScreenWidth() * 1.5f;
        float targetRot = toRight ? 20f : -20f;

        card.animate()
            .translationX(targetX)
            .translationY(-80f)
            .rotation(targetRot)
            .alpha(0f)
            .setDuration(280)
            .setInterpolator(new AccelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (onDone != null) onDone.run();
                }
            })
            .start();
    }

    private void showDone() {
        cardStack.setVisibility(View.INVISIBLE);
        swipeHint.setVisibility(View.INVISIBLE);
        doneLayout.setVisibility(View.VISIBLE);
        pendingCount.setText("All caught up");
    }

    /** Stores the accept action so WebView can pick it up on next open */
    private void notifyDashboard(String action,
                                  TransactionStore.PendingTransaction tx) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("action", action);
            obj.put("acctId", tx.matchedAcctId);
            obj.put("acctName", tx.matchedAcctName);
            obj.put("amount", tx.amount);
            obj.put("type", tx.type);
            obj.put("bank", tx.bank);
            obj.put("date", tx.date);

            getSharedPreferences("fd_pending_actions", Context.MODE_PRIVATE)
                .edit()
                .putString("last_action", obj.toString())
                .apply();
        } catch (Exception ignored) {}
    }

    private void showSmsDialog(String rawSms) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Original SMS")
            .setMessage(rawSms)
            .setPositiveButton("OK", null)
            .show();
    }

    private String formatAmount(double amount) {
        String sym = getSharedPreferences("fd_prefs", Context.MODE_PRIVATE)
            .getString("currency_symbol", "৳");
        return sym + " " + String.format("%,.0f", amount);
    }

    private float getScreenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }
}
