package com.mcpocket.poc;

import org.junit.Test;

import static org.junit.Assert.*;

public class PicoAdbClientTest {
    @Test public void pairingCodeMustBeExactlySixDigits() {
        assertTrue(PicoAdbClient.isValidPairingCode("123456"));
        assertFalse(PicoAdbClient.isValidPairingCode("12345"));
        assertFalse(PicoAdbClient.isValidPairingCode("1234567"));
        assertFalse(PicoAdbClient.isValidPairingCode("12A456"));
        assertFalse(PicoAdbClient.isValidPairingCode(null));
    }

    @Test public void hostValidationRejectsShellSyntax() {
        assertTrue(PicoAdbClient.isValidHost("127.0.0.1"));
        assertTrue(PicoAdbClient.isValidHost("fe80::1%wlan0"));
        assertFalse(PicoAdbClient.isValidHost("127.0.0.1;id"));
        assertFalse(PicoAdbClient.isValidHost("$(id)"));
        assertFalse(PicoAdbClient.isValidHost(""));
    }

    @Test public void endpointBracketsIpv6Only() {
        assertEquals("127.0.0.1:37123", PicoAdbClient.endpoint("127.0.0.1", 37123));
        assertEquals("[fe80::1%wlan0]:37123", PicoAdbClient.endpoint("fe80::1%wlan0", 37123));
    }

    @Test public void adbOutputMustExplicitlyConfirmPairOrConnect() {
        PicoAdbClient.Result paired = new PicoAdbClient.Result(
                0, false, false, "Successfully paired to localhost:37123");
        PicoAdbClient.Result connected = new PicoAdbClient.Result(
                0, false, false, "already connected to localhost:37124");
        PicoAdbClient.Result vague = new PicoAdbClient.Result(0, false, false, "done");

        assertTrue(paired.paired());
        assertTrue(connected.connected());
        assertFalse(vague.paired());
        assertFalse(vague.connected());
    }
}

