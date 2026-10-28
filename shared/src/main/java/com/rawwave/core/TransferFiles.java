package com.rawwave.core;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** G2a immutable diagnostic transport, not a health session protocol. */
public final class TransferFiles {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private TransferFiles() {}
    public static boolean sameAttempt(String window, String id, String hash, int bytes,
                                      String remoteWindow, String remoteId, String remoteHash, int remoteBytes) {
        return !window.isEmpty() && window.equals(remoteWindow) && id.equals(remoteId)
            && hash.equals(remoteHash) && bytes == remoteBytes;
    }
    public static void validate(String id, String hash, long bytes) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IOException("INVALID_ID");
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("INVALID_HASH");
        if (bytes <= 0 || bytes > MAX_BYTES) throw new IOException("INVALID_SIZE");
    }
    public static byte[] readExact(InputStream input, int size) throws IOException {
        if (size <= 0 || size > MAX_BYTES) throw new IOException("INVALID_SIZE");
        byte[] result = new byte[size];
        new DataInputStream(input).readFully(result);
        return result;
    }
    public static byte[] fixture() {
        byte[] data = new byte[10240];
        for (int i=0; i<data.length; i++) data[i] = (byte)(i % 251);
        return data;
    }
    public static File commit(File folder, String id, String hash, byte[] data) throws IOException {
        validate(id, hash, data.length);
        if (!DiagnosticArchive.hash(data).equals(hash)) throw new IOException("HASH_MISMATCH");
        Files.createDirectories(folder.toPath());
        File target = new File(folder, id + ".zip");
        if (target.exists()) {
            if (target.length() != data.length || !DiagnosticArchive.hash(Files.readAllBytes(target.toPath())).equals(hash))
                throw new IOException("EXISTING_FILE_CONFLICT");
        } else {
            checkStorageBudget(folder, data.length);
            atomic(new File(folder, id + ".part"), target, data);
        }
        if (!DiagnosticArchive.hash(Files.readAllBytes(target.toPath())).equals(hash)) throw new IOException("DISK_READBACK_FAILED");
        String receipt = "{\"kind\":\"G2A_DIAGNOSTIC_COMMITTED\",\"id\":\""+id+"\",\"sha256\":\""+hash+"\",\"bytes\":"+data.length+"}";
        atomic(new File(folder, id + ".receipt.part"), new File(folder, id + ".receipt.json"), receipt.getBytes(StandardCharsets.UTF_8));
        return target;
    }
    /** Bounded diagnostic storage; old receipts/data are never silently deleted. */
    public static void checkStorageBudget(File folder, long incoming) throws IOException {
        File[] files = folder.listFiles();
        if(files == null) throw new IOException("STORAGE_DIRECTORY_UNREADABLE");
        long total = incoming;
        for(File file:files) if(file.isFile()) total += file.length();
        if(total > 100L*1024*1024) throw new IOException("DIAGNOSTIC_STORAGE_100MIB_FULL_NO_AUTO_DELETE");
        if(folder.getUsableSpace() < incoming + 50L*1024*1024) throw new IOException("LOW_DISK_SPACE");
    }
    private static void atomic(File temporary, File target, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.flush(); out.getFD().sync(); }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
