package com.mcpocket.poc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public final class WorkspaceFileWriterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void overwriteReplacesWholeFileAndReportsUtf8ByteCount() throws Exception {
        File file = temp.newFile("text.txt");
        Files.write(file.toPath(), bytes("old longer content"));
        byte[] content = bytes("new \u4e2d\u6587");
        assertEquals(content.length, WorkspaceFileWriter.write(file, content, false));
        assertArrayEquals(content, Files.readAllBytes(file.toPath()));
        assertEquals(1, temp.getRoot().list().length);
    }

    @Test public void partialWriteFailurePreservesOriginalAndRemovesStagingFile() throws Exception {
        File file = temp.newFile("config.json");
        Files.write(file.toPath(), bytes("original"));
        IOException failure = assertThrows(IOException.class, () -> WorkspaceFileWriter.replace(file, output -> {
            output.write(bytes("partial replacement"));
            throw new IOException("injected disk-full failure");
        }));
        assertEquals("injected disk-full failure", failure.getMessage());
        assertEquals("original", read(file));
        assertEquals(1, temp.getRoot().list().length);
    }

    @Test public void failedNewFileDoesNotLeaveAnEmptyDestination() {
        File file = new File(temp.getRoot(), "new.txt");
        assertThrows(IOException.class, () -> WorkspaceFileWriter.replace(file, output -> {
            output.write(bytes("partial"));
            throw new IOException("injected failure");
        }));
        assertFalse(file.exists());
        assertEquals(0, temp.getRoot().list().length);
    }

    @Test public void readersSeeOriginalUntilReplacementIsComplete() throws Exception {
        File file = temp.newFile("visible.txt");
        Files.write(file.toPath(), bytes("old"));
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> write = executor.submit(() -> WorkspaceFileWriter.replace(file, output -> {
                output.write(bytes("new-"));
                staged.countDown();
                await(finish);
                output.write(bytes("complete"));
            }));
            assertTrue(staged.await(3, TimeUnit.SECONDS));
            assertEquals("old", read(file));
            finish.countDown();
            assertEquals(12L, write.get(3, TimeUnit.SECONDS).longValue());
            assertEquals("new-complete", read(file));
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void appendAndReplacementOnSamePathAreSerialized() throws Exception {
        File file = temp.newFile("shared.txt");
        Files.write(file.toPath(), bytes("old"));
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch appending = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> replacement = executor.submit(() -> WorkspaceFileWriter.replace(file, output -> {
                output.write(bytes("base"));
                staged.countDown();
                await(finish);
            }));
            assertTrue(staged.await(3, TimeUnit.SECONDS));
            Future<Long> append = executor.submit(() -> {
                appending.countDown();
                return WorkspaceFileWriter.write(file, bytes("+tail"), true);
            });
            assertTrue(appending.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> append.get(100, TimeUnit.MILLISECONDS));
            finish.countDown();
            assertEquals(4L, replacement.get(3, TimeUnit.SECONDS).longValue());
            assertEquals(9L, append.get(3, TimeUnit.SECONDS).longValue());
            assertEquals("base+tail", read(file));
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void emptyReplacementAndAppendKeepTheirExistingSemantics() throws Exception {
        File file = temp.newFile("empty.txt");
        Files.write(file.toPath(), bytes("old"));
        assertEquals(0L, WorkspaceFileWriter.write(file, new byte[0], false));
        assertEquals(1L, WorkspaceFileWriter.write(file, bytes("a"), true));
        assertEquals(2L, WorkspaceFileWriter.write(file, bytes("b"), true));
        assertEquals("ab", read(file));
    }

    @Test public void directoryIsNeverReplacedWithAFile() throws Exception {
        File directory = temp.newFolder("folder");
        assertThrows(IOException.class, () -> WorkspaceFileWriter.write(directory, bytes("data"), false));
        assertTrue(directory.isDirectory());
        assertEquals(1, temp.getRoot().list().length);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test latch timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }
}
