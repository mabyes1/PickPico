package com.mcpocket.poc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Crash-safe replacement for workspace.write (API 26+). */
final class WorkspaceFileWriter {
    // Bounded stripes serialize MCP writers without retaining every pathname.
    // Shell/Node processes do not participate in these in-process locks.
    private static final Object[] LOCKS = new Object[64];
    static {
        for (int index = 0; index < LOCKS.length; index++) LOCKS[index] = new Object();
    }

    interface ContentWriter {
        void write(OutputStream output) throws IOException;
    }

    private WorkspaceFileWriter() {
    }

    static long write(File target, byte[] bytes, boolean append) throws IOException {
        File canonical = target.getCanonicalFile();
        synchronized (lockFor(canonical)) {
            if (!append) return replace(canonical, output -> output.write(bytes));
            // Preserve streaming append semantics and cost. Appends are serialized
            // with replacements, but are not a crash-atomic whole-file transaction.
            try (FileOutputStream output = new FileOutputStream(canonical, true)) {
                output.write(bytes);
                output.getFD().sync();
            }
            return canonical.length();
        }
    }

    static long replace(File target, ContentWriter writer) throws IOException {
        File canonical = target.getCanonicalFile();
        synchronized (lockFor(canonical)) {
            if (canonical.exists() && !canonical.isFile()) {
                throw new IOException("Workspace target is not a regular file");
            }
            Path destination = canonical.toPath();
            Path staged = Files.createTempFile(destination.getParent(), ".pickpico-write-", ".tmp");
            try {
                // Keep existing executable/read permissions where POSIX is supported.
                Set<PosixFilePermission> permissions = null;
                if (canonical.isFile()
                        && Files.getFileAttributeView(destination, PosixFileAttributeView.class) != null) {
                    permissions = Files.getPosixFilePermissions(destination);
                }
                long size;
                try (FileOutputStream output = new FileOutputStream(staged.toFile())) {
                    writer.write(output);
                    output.flush();
                    if (permissions != null) Files.setPosixFilePermissions(staged, permissions);
                    output.getFD().sync();
                    size = output.getChannel().size();
                }
                // Never fall back to delete-then-copy: unsupported atomic moves
                // must fail while leaving the original destination untouched.
                Files.move(staged, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return size;
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }

    private static Object lockFor(File canonical) {
        return LOCKS[(canonical.getPath().hashCode() & Integer.MAX_VALUE) % LOCKS.length];
    }
}
