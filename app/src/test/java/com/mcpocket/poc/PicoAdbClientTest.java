package com.mcpocket.poc;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class PicoAdbClientTest {
    @Test public void transportRequiresTheExactSerialAndDeviceState() {
        String rows = "List of devices attached\n127.0.0.1:40001\toffline\n127.0.0.1:400010\tdevice product:test\n";
        assertEquals("offline", PicoAdbClient.transportState(rows, "127.0.0.1:40001"));
        assertEquals("disconnected", PicoAdbClient.transportState(rows, "127.0.0.1:4000"));
        assertEquals("connected", PicoAdbClient.transportState(rows, "127.0.0.1:400010"));
        assertEquals("disconnected", PicoAdbClient.transportState("List of devices attached\n", "127.0.0.1:40001"));
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void tlsRejectionUsesOnlyNewLogBytesForTheRequestedEndpoint() throws Exception {
        File log = temporary.newFile("adb.log");
        String rejection = "Post-handshake SSL_peek failed [SSLV3_ALERT_CERTIFICATE_UNKNOWN]\n"
                + "kicking transport 192.168.0.15:41059\n";
        java.nio.file.Files.write(log.toPath(), rejection.getBytes(StandardCharsets.UTF_8));
        PicoAdbClient.Result failed = new PicoAdbClient.Result(0, false, false, "failed to connect");
        assertTrue(PicoAdbClient.diagnoseConnect(failed, log, 0, "192.168.0.15:41059")
                .output.startsWith("ADB_AUTHORIZATION_REQUIRED"));
        assertFalse(PicoAdbClient.diagnoseConnect(failed, log, 0, "192.168.0.15:41059").success());
        assertSame(failed, PicoAdbClient.diagnoseConnect(failed, log, log.length(), "192.168.0.15:41059"));
        assertSame(failed, PicoAdbClient.diagnoseConnect(failed, log, 0, "192.168.0.15:40000"));
        PicoAdbClient.Result connected = new PicoAdbClient.Result(0, false, false, "connected to 192.168.0.15:41059");
        assertSame(connected, PicoAdbClient.diagnoseConnect(connected, log, 0, "192.168.0.15:41059"));
        assertSame(failed, PicoAdbClient.diagnoseConnect(failed, new File(log, "missing"), 0, "192.168.0.15:41059"));
    }

    @Test public void failedLegacyDaemonStopNeverBlocksAndRunsOncePerProcess() throws Exception {
        java.util.concurrent.atomic.AtomicInteger stops = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean marked = new java.util.concurrent.atomic.AtomicBoolean();
        java.lang.reflect.Field attempted = PicoAdbClient.class.getDeclaredField("legacyDaemonAttempted");
        attempted.setAccessible(true);
        attempted.setBoolean(null, false);
        try {
            PicoAdbClient.replaceLegacyDaemon(false, () -> {
                stops.incrementAndGet();
                return PicoAdbClient.Result.failure(1, false, "cannot connect to daemon");
            }, () -> marked.set(true));
            PicoAdbClient.replaceLegacyDaemon(false, () -> {
                stops.incrementAndGet();
                throw new java.io.IOException("hung");
            }, () -> marked.set(true));
            assertEquals(1, stops.get());
            assertFalse(marked.get());

            attempted.setBoolean(null, false); // Next app process retries and persists success.
            PicoAdbClient.replaceLegacyDaemon(false, () -> {
                stops.incrementAndGet();
                return new PicoAdbClient.Result(0, false, false, "");
            }, () -> marked.set(true));
            assertEquals(2, stops.get());
            assertTrue(marked.get());

            attempted.setBoolean(null, false);
            PicoAdbClient.replaceLegacyDaemon(true, () -> {
                stops.incrementAndGet();
                return new PicoAdbClient.Result(0, false, false, "");
            }, () -> { });
            assertEquals(2, stops.get());
        } finally {
            attempted.setBoolean(null, false);
        }
    }

    @Test public void diagnosticFileRetainsOutputBeyondFourMiB() throws Exception {
        File target = temporary.newFile("diagnostic.txt");
        PicoAdbClient.Result result = PicoAdbClient.execute(child("large"), 15_000L, target);
        assertTrue(result.output, result.success());
        assertEquals("Full diagnostic output must stay out of the response", "", result.output);
        assertEquals(5 * 1024 * 1024 + 4, target.length());
        try (RandomAccessFile file = new RandomAccessFile(target, "r")) {
            file.seek(file.length() - 4);
            assertEquals("END!", file.readLine());
        }
    }

    @Test public void diagnosticFailureReturnsOnlyABoundedExcerpt() throws Exception {
        File target = temporary.newFile("failed.txt");
        PicoAdbClient.Result result = PicoAdbClient.execute(child("fail"), 15_000L, target);
        assertFalse(result.success());
        assertEquals(7, result.exitCode);
        assertEquals(2_000, result.output.length());
        assertEquals(5 * 1024 * 1024 + 4, target.length());
    }

    @Test public void diagnosticCannotSucceedWhenOutputCannotBeOpened() throws Exception {
        PicoAdbClient.Result result = PicoAdbClient.execute(child("large"), 15_000L,
                temporary.newFolder("directory"));
        assertFalse(result.success());
    }

    private static ProcessBuilder child(String mode) throws Exception {
        String executable = new File(System.getProperty("java.home"),
                "bin/" + (System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")).getPath();
        String classes = new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        return new ProcessBuilder(executable, "-cp", classes, Child.class.getName(), mode);
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            byte[] block = new byte[8192];
            java.util.Arrays.fill(block, (byte) 'x');
            for (int i = 0; i < 640; i++) System.out.write(block);
            System.out.write("END!".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            if ("fail".equals(args[0])) System.exit(7);
        }
    }

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

