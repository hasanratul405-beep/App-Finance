package com.financedashboard.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores detected SMS transactions in SharedPreferences
 * so they survive app restarts and can be reviewed later.
 */
public class TransactionStore {

    private static final String PREFS_NAME = "fd_transactions";
    private static final String KEY_PENDING = "pending_transactions";

    public static class PendingTransaction {
        public String id;
        public String rawSms;
        public double amount;
        public String acctDigits;
        public String type;        // credit / debit / transfer
        public String bank;
        public String date;
        public String matchedAcctId;
        public String matchedAcctName;
        public long timestamp;

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("rawSms", rawSms);
                o.put("amount", amount);
                o.put("acctDigits", acctDigits != null ? acctDigits : "");
                o.put("type", type != null ? type : "");
                o.put("bank", bank != null ? bank : "");
                o.put("date", date != null ? date : "");
                o.put("matchedAcctId", matchedAcctId != null ? matchedAcctId : "");
                o.put("matchedAcctName", matchedAcctName != null ? matchedAcctName : "");
                o.put("timestamp", timestamp);
                return o;
            } catch (Exception e) { return new JSONObject(); }
        }

        public static PendingTransaction fromJson(JSONObject o) {
            PendingTransaction t = new PendingTransaction();
            try {
                t.id             = o.optString("id", "");
                t.rawSms         = o.optString("rawSms", "");
                t.amount         = o.optDouble("amount", 0);
                t.acctDigits     = o.optString("acctDigits", null);
                t.type           = o.optString("type", "");
                t.bank           = o.optString("bank", "");
                t.date           = o.optString("date", "");
                t.matchedAcctId  = o.optString("matchedAcctId", null);
                t.matchedAcctName= o.optString("matchedAcctName", null);
                t.timestamp      = o.optLong("timestamp", 0);
            } catch (Exception ignored) {}
            return t;
        }
    }

    public static void addTransaction(Context ctx, PendingTransaction tx) {
        List<PendingTransaction> existing = getPending(ctx);
        // Avoid duplicates by checking raw SMS match
        for (PendingTransaction e : existing) {
            if (e.rawSms.equals(tx.rawSms)) return;
        }
        existing.add(0, tx); // newest first
        // Keep max 50
        if (existing.size() > 50) existing = existing.subList(0, 50);
        savePending(ctx, existing);
    }

    public static List<PendingTransaction> getPending(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_PENDING, "[]");
        List<PendingTransaction> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(PendingTransaction.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static void removePending(Context ctx, String id) {
        List<PendingTransaction> list = getPending(ctx);
        List<PendingTransaction> filtered = new ArrayList<>();
        for (PendingTransaction t : list) {
            if (!t.id.equals(id)) filtered.add(t);
        }
        savePending(ctx, filtered);
    }

    public static int getPendingCount(Context ctx) {
        return getPending(ctx).size();
    }

    private static void savePending(Context ctx, List<PendingTransaction> list) {
        JSONArray arr = new JSONArray();
        for (PendingTransaction t : list) arr.put(t.toJson());
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING, arr.toString()).apply();
    }
}
