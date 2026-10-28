package com.rawwave.core;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class TransferFilesTest {
    @Test public void sendStoreRestartReusesBytesAndAckKeepsSource() throws Exception {
        File root=temp.newFolder();
        SendStore first=new FileSendStore(root,ReceiveStore.Kind.DIAGNOSTIC,dir -> {});
        var payload=first.prepare(id -> Map.of("evidence.txt",new byte[]{1,2,3}));
        SendStore replacement=new FileSendStore(root,ReceiveStore.Kind.DIAGNOSTIC,dir -> {});
        var retry=replacement.prepare(id -> { throw new IOException("must reuse"); });
        assertEquals(payload,retry);
        try(InputStream in=replacement.open(retry)) { assertEquals(payload.sha256(),DiagnosticArchive.hash(in.readAllBytes())); }
        assertThrows(IOException.class,() -> replacement.acknowledge(retry,new SendStore.Confirmation(retry.id(),"0".repeat(64),retry.bytes(),"phone",1)));
        assertFalse(new File(root,"outgoing/"+retry.id()+".ack.json").exists());
        replacement.acknowledge(retry,new SendStore.Confirmation(retry.id(),retry.sha256(),retry.bytes(),"phone",1));
        assertTrue(retry.file().isFile());
        assertNotEquals(retry.id(),replacement.prepare(id -> Map.of("next.txt",new byte[]{4})).id());
    }
    @Test public void sendStoreChangedSourceCannotBeAcknowledged() throws Exception {
        File root=temp.newFolder(); SendStore port=new FileSendStore(root,ReceiveStore.Kind.DIAGNOSTIC,dir -> {});
        var payload=port.prepare(id -> Map.of("evidence.txt",new byte[]{1}));
        byte[] bytes=Files.readAllBytes(payload.file().toPath()); bytes[0]^=1; Files.write(payload.file().toPath(),bytes);
        assertThrows(IOException.class,() -> port.acknowledge(payload,new SendStore.Confirmation(payload.id(),payload.sha256(),payload.bytes(),"phone",1)));
        assertFalse(new File(root,"outgoing/"+payload.id()+".ack.json").exists());
    }
    @Test public void sendStoreFactoryFailurePublishesNoZip() throws Exception {
        File root=temp.newFolder(); SendStore port=new FileSendStore(root,ReceiveStore.Kind.DIAGNOSTIC,dir -> {});
        assertThrows(IOException.class,() -> port.prepare(id -> {throw new IOException("snapshot failed");}));
        assertEquals(0,new File(root,"outgoing").listFiles(f -> f.getName().endsWith(".zip")).length);
    }
    @Test public void sendStoreDurabilityFailureDoesNotReturnSuccess() throws Exception {
        File root=temp.newFolder(); SendStore port=new FileSendStore(root,ReceiveStore.Kind.DIAGNOSTIC,dir -> {});
        var payload=port.prepare(id -> Map.of("evidence.txt",new byte[]{1}));
        SendStore failed=new FileSendStore(root,ReceiveStore.Kind.DIAGNOSTIC,dir -> {throw new IOException("sync failed");});
        assertThrows(IOException.class,() -> failed.acknowledge(payload,new SendStore.Confirmation(payload.id(),payload.sha256(),payload.bytes(),"phone",1)));
        assertTrue(payload.file().isFile()); // failure may leave an ACK on disk, never removes evidence
    }
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final String id = "12345678-1234-1234-1234-123456789abc";
    @Test public void twentyOldReportsDoNotBlockNextCaptureDiagnostic() throws Exception {
        File dir=temp.newFolder(); byte[] data=TransferFiles.fixture(); String hash=DiagnosticArchive.hash(data);
        for(int i=0;i<21;i++) TransferFiles.commit(dir,UUID.randomUUID().toString(),hash,data);
        assertEquals(42,dir.listFiles().length);
    }
    @Test public void fixtureHasKnownSizeAndRepeatableBytes() { assertEquals(10240, TransferFiles.fixture().length); assertArrayEquals(TransferFiles.fixture(), TransferFiles.fixture()); }
    @Test public void exactLengthDoesNotWaitForChannelClose() throws Exception {
        assertArrayEquals(new byte[]{1,2}, TransferFiles.readExact(new ByteArrayInputStream(new byte[]{1,2,3}), 2));
    }
    @Test public void truncatedPayloadFails() { assertThrows(EOFException.class, () -> TransferFiles.readExact(new ByteArrayInputStream(new byte[]{1}), 2)); }
    @Test public void invalidEnvelopeRejected() {
        assertThrows(IOException.class, () -> TransferFiles.validate("../a", "a".repeat(64), 1));
        assertThrows(IOException.class, () -> TransferFiles.validate(id, "bad", 1));
        assertThrows(IOException.class, () -> TransferFiles.validate(id, "a".repeat(64), TransferFiles.MAX_BYTES+1L));
    }
    @Test public void oldWindowAckCannotCompleteNewAttempt() {
        String hash="a".repeat(64);
        assertFalse(TransferFiles.sameAttempt("new",id,hash,10,"old",id,hash,10));
        assertFalse(TransferFiles.sameAttempt("new",id,hash,10,"new",id,hash,11));
        assertTrue(TransferFiles.sameAttempt("new",id,hash,10,"new",id,hash,10));
    }
    @Test public void commitAndDuplicateRevalidateDisk() throws Exception {
        File dir = temp.newFolder(); byte[] data=TransferFiles.fixture(); String hash=DiagnosticArchive.hash(data);
        File file=TransferFiles.commit(dir,id,hash,data);
        assertArrayEquals(data,Files.readAllBytes(file.toPath()));
        assertTrue(new File(dir,id+".receipt.json").exists());
        assertEquals(file,TransferFiles.commit(dir,id,hash,data));
        Files.write(file.toPath(),new byte[]{9});
        assertThrows(IOException.class, () -> TransferFiles.commit(dir,id,hash,data));
    }
    @Test public void wrongHashNeverCreatesReceipt() throws Exception {
        File dir=temp.newFolder();
        assertThrows(IOException.class, () -> TransferFiles.commit(dir,id,"0".repeat(64),new byte[]{1}));
        assertFalse(new File(dir,id+".receipt.json").exists());
    }
    @Test public void sameIdDifferentContentCannotOverwrite() throws Exception {
        File dir=temp.newFolder(); byte[] a={1}; byte[] b={2};
        TransferFiles.commit(dir,id,DiagnosticArchive.hash(a),a);
        assertThrows(IOException.class, () -> TransferFiles.commit(dir,id,DiagnosticArchive.hash(b),b));
        assertArrayEquals(a,Files.readAllBytes(new File(dir,id+".zip").toPath()));
    }
}
