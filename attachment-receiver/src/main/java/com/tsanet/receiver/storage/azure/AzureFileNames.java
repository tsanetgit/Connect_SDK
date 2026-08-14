package com.tsanet.receiver.storage.azure;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic, canonical, invertible encoding of one opaque token into one Azure
 * Files path component. Azure Files has real directory semantics and Windows naming
 * rules, so a remote-controlled name ({@code fileName} per the SPI, and defensively
 * {@code caseNumber} too) must never reach the share as a raw path component.
 *
 * <p>Rules: bytes in {@code [A-Za-z0-9_-]} pass through, as does an <em>interior</em>
 * {@code .}; everything else is {@code %XX} (uppercase hex, UTF-8 bytes, {@code %}
 * always encoded). A leading dot is always encoded, which is what makes collision with
 * the adapter's {@code .incoming-}/{@code .verify-} temp namespace impossible. A
 * trailing dot is encoded because Azure Files silently STRIPS trailing dots by default,
 * which would break store/exists symmetry; a trailing space is encoded per Windows
 * naming. Reserved MS-DOS device names (which the service rejects outright as file
 * names, per the Azure Files naming rules: LPT1-9, COM1-9, PRN, AUX, NUL, CON, CLOCK$)
 * get their first character encoded, before or without an extension, case-insensitively.
 * Encoded components are capped at Azure's 255-character component limit, failing fast
 * with the offending length rather than letting the service throw something opaque.
 */
final class AzureFileNames {

    private static final int MAX_COMPONENT = 255;

    private static final java.util.Set<String> RESERVED_DEVICE_NAMES = java.util.Set.of(
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "PRN", "AUX", "NUL", "CON", "CLOCK$");

    private AzureFileNames() {
    }

    static String encode(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        boolean reserved = isReservedDeviceName(token);
        StringBuilder out = new StringBuilder(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            boolean interior = i > 0 && i < bytes.length - 1;
            if (i == 0 && reserved) {
                out.append('%').append(String.format("%02X", b));
                continue;
            }
            if (isPassThrough(b) || (b == '.' && interior)) {
                out.append((char) b);
            } else {
                out.append('%').append(String.format("%02X", b));
            }
        }
        if (out.length() > MAX_COMPONENT) {
            throw new IllegalArgumentException("name encodes to " + out.length()
                    + " characters; Azure Files caps path components at " + MAX_COMPONENT
                    + ": " + token);
        }
        return out.toString();
    }

    static String decode(String encoded) {
        byte[] out = new byte[encoded.length()];
        int n = 0;
        for (int i = 0; i < encoded.length(); ) {
            char c = encoded.charAt(i);
            if (c == '%') {
                out[n++] = (byte) Integer.parseInt(encoded.substring(i + 1, i + 3), 16);
                i += 3;
            } else {
                out[n++] = (byte) c;
                i++;
            }
        }
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }

    private static boolean isPassThrough(int b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9') || b == '-' || b == '_';
    }

    /**
     * Case-insensitive, and matched with or without an extension: classic Windows
     * reserves {@code con.txt} as thoroughly as {@code CON}, and the doc does not say
     * which reading Azure implements, so both are neutralized.
     */
    private static boolean isReservedDeviceName(String token) {
        int dot = token.indexOf('.');
        String stem = (dot < 0 ? token : token.substring(0, dot)).toUpperCase(java.util.Locale.ROOT);
        return RESERVED_DEVICE_NAMES.contains(stem);
    }
}
