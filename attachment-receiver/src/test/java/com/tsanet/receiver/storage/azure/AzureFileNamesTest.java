package com.tsanet.receiver.storage.azure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AzureFileNamesTest {

    @Test
    void safeNamesPassThroughUnchanged() {
        assertEquals("diag.log", AzureFileNames.encode("diag.log"));
        assertEquals("01234567", AzureFileNames.encode("01234567"));
    }

    @Test
    void everyTraversalShapeBecomesASingleInertComponent() {
        for (String hostile : new String[]{"..", "../../etc/passwd", "a/b", "a\\b",
                ". ", "..\\..\\x"}) {
            String encoded = AzureFileNames.encode(hostile);
            assertFalse(encoded.contains("/"), encoded);
            assertFalse(encoded.contains("\\"), encoded);
            assertFalse(encoded.equals(".") || encoded.equals(".."), encoded);
            assertEquals(hostile, AzureFileNames.decode(encoded), "must round-trip");
        }
    }

    @Test
    void reservedDeviceNamesAreNeutralized() {
        // Azure Files rejects these outright as file names (verified against the
        // Naming and Referencing doc, 2026-08-12): the encoder must make them legal.
        for (String reserved : new String[]{"CON", "con", "NUL", "nul", "prn", "AUX",
                "com1", "LPT9", "CLOCK$", "con.txt", "Nul.log"}) {
            String encoded = AzureFileNames.encode(reserved);
            assertFalse(encoded.equalsIgnoreCase(reserved)
                            && encoded.chars().allMatch(c -> c != '%'),
                    "must not pass through raw: " + reserved + " -> " + encoded);
            assertTrue(encoded.startsWith("%"),
                    "first character must be encoded: " + reserved + " -> " + encoded);
            assertEquals(reserved, AzureFileNames.decode(encoded), "must round-trip");
        }
    }

    @Test
    void leadingDotIsAlwaysEncoded() {
        // This is what makes collision with the .incoming-/.verify- namespace impossible.
        assertTrue(AzureFileNames.encode(".incoming-fake").startsWith("%2E"));
        assertTrue(AzureFileNames.encode(".hidden").startsWith("%2E"));
    }

    @Test
    void trailingDotAndSpaceAreEncoded() {
        // Windows naming mangles or rejects both.
        assertTrue(AzureFileNames.encode("report.").endsWith("%2E"));
        assertTrue(AzureFileNames.encode("report ").endsWith("%20"));
    }

    @Test
    void interiorDotsArePreserved() {
        assertEquals("a.b.c", AzureFileNames.encode("a.b.c"));
    }

    @Test
    void percentIsAlwaysEncodedSoDecodingIsUnambiguous() {
        String encoded = AzureFileNames.encode("100%.log");
        assertEquals("100%25.log", encoded);
        assertEquals("100%.log", AzureFileNames.decode(encoded));
    }

    @Test
    void unicodeRoundTrips() {
        String name = "díag-übersicht-日誌.log";
        assertEquals(name, AzureFileNames.decode(AzureFileNames.encode(name)));
    }

    @Test
    void oversizedComponentsFailFastWithTheLength() {
        String big = "€".repeat(60); // 3 UTF-8 bytes -> 9 encoded chars each
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzureFileNames.encode(big));
        assertTrue(e.getMessage().contains("255"), e.getMessage());
    }
}
