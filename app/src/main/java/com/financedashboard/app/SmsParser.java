package com.financedashboard.app;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses bank SMS messages to extract transaction data.
 * Works with all Bangladeshi banks + mobile banking.
 */
public class SmsParser {

    public static class ParsedTransaction {
        public double amount = 0;
        public String acctDigits = null;   // last digits of account
        public String type = null;          // "credit", "debit", "transfer"
        public String bank = null;
        public String date = null;
        public String rawSms = "";
        public int confidence = 0;          // 0-100
        public boolean isValid() { return amount > 0 && confidence >= 30; }
    }

    private static final Map<String, String> BANK_MAP = new HashMap<String, String>() {{
        put("dutch-bangla", "Dutch-Bangla Bank");
        put("dbbl", "Dutch-Bangla Bank");
        put("dutch bangla", "Dutch-Bangla Bank");
        put("rocket", "Rocket (DBBL)");
        put("bkash", "bKash");
        put("b-kash", "bKash");
        put("nagad", "Nagad");
        put("islami bank", "Islami Bank");
        put("ibbl", "Islami Bank");
        put("brac bank", "BRAC Bank");
        put("brac", "BRAC Bank");
        put("city bank", "City Bank");
        put("citybank", "City Bank");
        put("trust bank", "Trust Bank");
        put("southeast bank", "Southeast Bank");
        put("national bank", "National Bank");
        put("ucb", "UCB");
        put("united commercial", "UCB");
        put("eastern bank", "EBL");
        put("ebl", "EBL");
        put("standard chartered", "Standard Chartered");
        put("hsbc", "HSBC");
        put("ab bank", "AB Bank");
        put("prime bank", "Prime Bank");
        put("mercantile", "Mercantile Bank");
        put("mutual trust", "MTB");
        put("mtb", "MTB");
        put("jamuna bank", "Jamuna Bank");
        put("one bank", "One Bank");
        put("premier bank", "Premier Bank");
        put("shahjalal", "SJIBL");
        put("exim", "EXIM Bank");
        put("ific", "IFIC Bank");
        put("social islami", "SIBL");
        put("sibl", "SIBL");
        put("midland", "Midland Bank");
        put("nrb", "NRB Bank");
        put("meghna bank", "Meghna Bank");
        put("modhumoti", "Modhumoti Bank");
        put("payoneer", "Payoneer");
        put("wise", "Wise");
        put("paypal", "PayPal");
        put("upwork", "Upwork");
        put("fiverr", "Fiverr");
    }};

    public static ParsedTransaction parse(String smsText) {
        ParsedTransaction result = new ParsedTransaction();
        if (smsText == null || smsText.trim().isEmpty()) return result;
        result.rawSms = smsText;
        String lower = smsText.toLowerCase();

        // ── 1. Amount extraction ──────────────────────────────────────
        String[][] amtPatterns = {
            {"(?:BDT|Tk\\.?|TK)\\s*([\\d,]+(?:\\.\\d+)?)", "group1"},
            {"([\\d,]+(?:\\.\\d+)?)\\s*(?:BDT|Tk\\.?|TK)\\b", "group1"},
            {"(?:amount|amt)[:\\s]+(?:BDT|Tk\\.?|TK)?\\s*([\\d,]+(?:\\.\\d+)?)", "group1"},
            {"(?:credited|debited|received|deposited|paid)[:\\s]+(?:BDT|Tk\\.?|TK)?\\s*([\\d,]+(?:\\.\\d+)?)", "group1"},
            {"(?:Rs\\.?|INR)\\s*([\\d,]+(?:\\.\\d+)?)", "group1"},
            // USD amounts
            {"(?:USD|\\$)\\s*([\\d,]+(?:\\.\\d+)?)", "group1"},
            {"([\\d,]+(?:\\.\\d+)?)\\s*(?:USD)", "group1"},
        };

        for (String[] patArr : amtPatterns) {
            try {
                Pattern p = Pattern.compile(patArr[0], Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(smsText);
                if (m.find()) {
                    String raw = m.group(1).replace(",", "");
                    result.amount = Double.parseDouble(raw);
                    result.confidence += 35;
                    break;
                }
            } catch (Exception ignored) {}
        }

        // ── 2. Account digits (last 3-6 digits) ──────────────────────
        String[] digitPatterns = {
            "[Xx*]{2,}(\\d{3,6})",
            "a\\/c\\s*[#:\\-]*\\s*[Xx*]*(\\d{3,6})",
            "account\\s*[#:\\-]*\\s*[Xx*]*(\\d{3,6})",
            "acct?\\s*[#:\\-]*\\s*[Xx*]*(\\d{3,6})",
            "no\\.\\s*[Xx*]*(\\d{3,6})",
            "ending\\s*(\\d{3,6})",
            "last\\s*\\d*\\s*digits?[:\\s]+(\\d{3,6})",
        };

        for (String dp : digitPatterns) {
            try {
                Pattern p = Pattern.compile(dp, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(smsText);
                if (m.find()) {
                    result.acctDigits = m.group(1);
                    result.confidence += 25;
                    break;
                }
            } catch (Exception ignored) {}
        }

        // ── 3. Transaction type ───────────────────────────────────────
        String[] creditWords = {"credited", "credit", "received", "deposited", "added", "deposit", "incoming", " cr ", "cr."};
        String[] debitWords  = {"debited", "debit", "withdrawn", "sent to", "transferred to", "paid to", "pos ", "purchase", "deducted", " dr ", "dr."};

        boolean isCredit = false, isDebit = false;
        for (String w : creditWords) { if (lower.contains(w)) { isCredit = true; break; } }
        for (String w : debitWords)  { if (lower.contains(w)) { isDebit  = true; break; } }

        if (isCredit && !isDebit)       { result.type = "credit";   result.confidence += 20; }
        else if (isDebit && !isCredit)  { result.type = "debit";    result.confidence += 20; }
        else if (isCredit)              { result.type = "transfer";  result.confidence += 10; }
        else                            { result.type = "unknown"; }

        // ── 4. Bank detection ─────────────────────────────────────────
        for (Map.Entry<String, String> entry : BANK_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) {
                result.bank = entry.getValue();
                result.confidence += 15;
                break;
            }
        }

        // ── 5. Date extraction ────────────────────────────────────────
        String[] datePatterns = {
            "\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}",
            "\\d{4}[/\\-]\\d{2}[/\\-]\\d{2}",
            "\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+\\d{2,4}",
        };
        for (String dp : datePatterns) {
            try {
                Pattern p = Pattern.compile(dp, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(smsText);
                if (m.find()) { result.date = m.group(); break; }
            } catch (Exception ignored) {}
        }

        // If no date found use today
        if (result.date == null) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            result.date = c.get(java.util.Calendar.DAY_OF_MONTH) + " " +
                new String[]{"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"}
                    [c.get(java.util.Calendar.MONTH)] + " " +
                c.get(java.util.Calendar.YEAR);
        }

        return result;
    }

    /** Returns display bank name or "Unknown Bank" */
    public static String getBankDisplay(ParsedTransaction tx) {
        if (tx.bank != null) return tx.bank;
        return "Unknown Bank";
    }

    /** First letter of bank for icon */
    public static String getBankInitial(ParsedTransaction tx) {
        String name = getBankDisplay(tx);
        return name.substring(0, 1).toUpperCase();
    }

    /** Type label for pill */
    public static String getTypeLabel(ParsedTransaction tx) {
        if ("credit".equals(tx.type))   return "CREDIT RECEIVED";
        if ("debit".equals(tx.type))    return "DEBIT SENT";
        if ("transfer".equals(tx.type)) return "TRANSFER";
        return "TRANSACTION";
    }

    /** Format amount with currency symbol */
    public static String formatAmount(double amount, String symbol) {
        if (symbol == null) symbol = "৳";
        long rounded = Math.round(amount);
        String formatted = String.format("%,d", rounded);
        return symbol + " " + formatted;
    }
}
