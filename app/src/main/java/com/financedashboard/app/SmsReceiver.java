package com.financedashboard.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

import java.util.UUID;

/**
 * Listens for incoming SMS in the background.
 * Parses every SMS for bank transaction patterns.
 * Stores valid transactions and notifies MainActivity.
 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        String format = bundle.getString("format");

        for (Object pdu : pdus) {
            try {
                SmsMessage smsMessage = (format != null)
                    ? SmsMessage.createFromPdu((byte[]) pdu, format)
                    : SmsMessage.createFromPdu((byte[]) pdu);

                if (smsMessage == null) continue;

                String body   = smsMessage.getMessageBody();
                String sender = smsMessage.getOriginatingAddress();

                if (body == null || body.isEmpty()) continue;

                // Parse it
                SmsParser.ParsedTransaction parsed = SmsParser.parse(body);

                // Only store if it looks like a real financial transaction
                if (!parsed.isValid()) continue;

                // Build pending transaction
                TransactionStore.PendingTransaction tx = new TransactionStore.PendingTransaction();
                tx.id          = UUID.randomUUID().toString();
                tx.rawSms      = body;
                tx.amount      = parsed.amount;
                tx.acctDigits  = parsed.acctDigits;
                tx.type        = parsed.type;
                tx.bank        = parsed.bank != null ? parsed.bank : (sender != null ? sender : "Unknown Bank");
                tx.date        = parsed.date;
                tx.timestamp   = System.currentTimeMillis();

                // Try to match to a registered account using stored digits
                tryMatchAccount(context, tx);

                // Save
                TransactionStore.addTransaction(context, tx);

                // Notify the app UI if it is running
                Intent update = new Intent("com.financedashboard.NEW_TRANSACTION");
                update.setPackage(context.getPackageName());
                context.sendBroadcast(update);

            } catch (Exception ignored) {}
        }
    }

    /**
     * Checks if acctDigits match any account stored in SharedPreferences
     * (the HTML tool saves account digits via the JSBridge)
     */
    private void tryMatchAccount(Context context, TransactionStore.PendingTransaction tx) {
        if (tx.acctDigits == null || tx.acctDigits.isEmpty()) return;

        try {
            String accountsJson = context
                .getSharedPreferences("fd_accounts", Context.MODE_PRIVATE)
                .getString("account_digits", "[]");

            org.json.JSONArray arr = new org.json.JSONArray(accountsJson);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject acc = arr.getJSONObject(i);
                String storedDigits = acc.optString("digits", "");
                if (!storedDigits.isEmpty() &&
                    storedDigits.endsWith(tx.acctDigits) || tx.acctDigits.endsWith(storedDigits)) {
                    tx.matchedAcctId   = acc.optString("id", "");
                    tx.matchedAcctName = acc.optString("name", "");
                    break;
                }
            }
        } catch (Exception ignored) {}
    }
}
