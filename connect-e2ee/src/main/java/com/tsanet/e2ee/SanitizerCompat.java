package com.tsanet.e2ee;

/**
 * Undoes the Connect API's HTML sanitizing of an encrypted value, the one place this module does.
 *
 * <p>PROVISIONAL(tsanetgit/Connect-API-Code#214): the API passes {@code problemDescription} and a
 * note's {@code summary} and {@code description} through its HTML sanitizer on create, which stores
 * each {@code "} in a JWE as {@code &#34;}, so the stored value is no longer the value the sender
 * signed. Until #214 stores encrypted values as received, a protected value that starts with
 * {@code {&#34;} is HTML-decoded before its digest is checked and before it is parsed, as the Power
 * App does ({@code tsanetgit/MS_Power_App}, {@code PayloadFieldMap.cs}). That restores the
 * transmitted value exactly. Once #214 ships, this class and its two call sites go.
 */
final class SanitizerCompat {
    private static final String SANITIZED_JWE_PREFIX = "{&#34;";

    private SanitizerCompat() {
    }

    /** The value as the sender sent it: decoded when it is a sanitized JWE, otherwise unchanged. */
    static String restore(String value) {
        if (value == null || !value.startsWith(SANITIZED_JWE_PREFIX)) {
            return value;
        }
        return decodeEntities(value);
    }

    // Numeric character references and the five XML entities, the forms an HTML sanitizer emits.
    private static String decodeEntities(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            int end = c == '&' ? value.indexOf(';', i) : -1;
            if (end > i) {
                String entity = value.substring(i + 1, end);
                Integer decoded = decodeEntity(entity);
                if (decoded != null) {
                    out.appendCodePoint(decoded);
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static Integer decodeEntity(String entity) {
        try {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return Integer.parseInt(entity.substring(2), 16);
            }
            if (entity.startsWith("#")) {
                return Integer.parseInt(entity.substring(1));
            }
        } catch (NumberFormatException notANumber) {
            return null;
        }
        return switch (entity) {
            case "quot" -> (int) '"';
            case "amp" -> (int) '&';
            case "lt" -> (int) '<';
            case "gt" -> (int) '>';
            case "apos" -> (int) '\'';
            default -> null;
        };
    }
}
