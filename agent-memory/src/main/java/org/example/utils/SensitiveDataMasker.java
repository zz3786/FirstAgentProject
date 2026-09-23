package org.example.utils;

import java.util.regex.Pattern;

public class SensitiveDataMasker {

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}");

    /** 对文本做全量脱敏 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = PHONE.matcher(result).replaceAll(m -> maskPhone(m.group()));
        result = ID_CARD.matcher(result).replaceAll(m -> maskIdCard(m.group()));
        result = EMAIL.matcher(result).replaceAll(m -> maskEmail(m.group()));
        result = BANK_CARD.matcher(result).replaceAll(m -> maskBankCard(m.group()));
        return result;
    }

    private static String maskPhone(String p) {
        return p.substring(0, 3) + "****" + p.substring(7);
    }

    private static String maskIdCard(String id) {
        return id.substring(0, 6) + "********" + id.substring(14);
    }

    private static String maskEmail(String e) {
        int at = e.indexOf('@');
        if (at <= 1) {
            return e;
        }
        return e.charAt(0) + "***" + e.substring(at);
    }

    private static String maskBankCard(String c) {
        if (c.length() < 8) {
            return c;
        }
        return c.substring(0, 4) + " **** **** " + c.substring(c.length() - 4);
    }
}