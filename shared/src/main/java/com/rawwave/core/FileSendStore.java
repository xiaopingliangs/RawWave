package com.rawwave.core;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Existing sender directories and ACK formats, isolated from Google transport. */
public final class FileSendStore implements SendStore {
    private final File root, outgoing;
    private final ReceiveStore.Kind kind;
    private final FileReceiveStore.DirectorySync durability;
    public FileSendStore(File root,ReceiveStore.Kind kind,FileReceiveStore.DirectorySync durability) {
        this.root=Objects.requireNonNull(root);this.kind=Objects.requireNonNull(kind);
        this.durability=Objects.requireNonNull(durability);
        outgoing=new File(root,kind==ReceiveStore.Kind.RAW_ACTIVITY?"activity-outgoing":"outgoing");
    }
    public Payload prepare(ReportPayload report) throws IOException {
        if(kind==ReceiveStore.Kind.RAW_ACTIVITY) {
            var batch=ActivityArchive.preparePending(new File(root,"activities"),outgoing);
            return batch==null?null:new Payload(batch.file(),batch.id(),batch.sha256(),batch.bytes(),batch.sessionIds());
        }
        Files.createDirectories(outgoing.toPath());
        File[] pending=outgoing.listFiles(f -> f.getName().endsWith(".zip") && !new File(outgoing,f.getName().replaceFirst("\\.zip$",".ack.json")).exists());
        if(pending==null) throw new IOException("OUTGOING_UNREADABLE");
        Arrays.sort(pending,Comparator.comparingLong(File::lastModified));
        File file;
        if(pending.length>0) file=pending[0];
        else {
            TransferFiles.checkStorageBudget(outgoing,TransferFiles.MAX_BYTES);
            String id=UUID.randomUUID().toString();
            File building=DiagnosticArchive.create(new File(outgoing,id+".building"),Objects.requireNonNull(report).create(id));
            file=new File(outgoing,id+".zip");
            Files.move(building.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);durability.sync(outgoing);
        }
        String id=file.getName().replaceFirst("\\.zip$","");
        TransferFiles.validate(id,"0".repeat(64),file.length());
        return new Payload(file,id,ActivityTransferFiles.hashFile(file),file.length(),List.of());
    }
    private void validateLocation(Payload payload) throws IOException {
        if(!payload.file().getCanonicalFile().equals(new File(outgoing,payload.id()+".zip").getCanonicalFile()))
            throw new IOException("SEND_SOURCE_OUTSIDE_STORE");
        if(kind==ReceiveStore.Kind.RAW_ACTIVITY) ActivityTransferFiles.validate(payload.id(),payload.sha256(),payload.bytes());
        else TransferFiles.validate(payload.id(),payload.sha256(),payload.bytes());
    }
    public InputStream open(Payload payload) throws IOException {
        validateLocation(payload);
        if(payload.file().length()!=payload.bytes()) throw new IOException("SEND_SOURCE_LENGTH_CHANGED");
        return new FileInputStream(payload.file());
    }
    public void acknowledge(Payload payload,Confirmation confirmation) throws IOException {
        validateLocation(payload);
        if(!payload.id().equals(confirmation.id()) || !payload.sha256().equals(confirmation.sha256()) || payload.bytes()!=confirmation.bytes()
            || confirmation.peer()==null || confirmation.peer().isEmpty()) throw new IOException("SEND_CONFIRMATION_MISMATCH");
        // ActivityArchive.acknowledge already hashes and deep-verifies activity bytes.
        if(payload.file().length()!=payload.bytes() || (kind==ReceiveStore.Kind.DIAGNOSTIC && !ActivityTransferFiles.hashFile(payload.file()).equals(payload.sha256())))
            throw new IOException("SEND_ACK_SOURCE_CHANGED");
        if(kind==ReceiveStore.Kind.RAW_ACTIVITY) {
            File activities=new File(root,"activities");
            ActivityArchive.acknowledge(activities,outgoing,new ActivityArchive.Batch(payload.file(),payload.id(),payload.sha256(),payload.bytes(),payload.sessions()),confirmation.utcMs());
            durability.sync(new File(activities,"sync"));durability.sync(outgoing);
        } else {
            byte[] receipt=new Gson().toJson(Map.of("id",payload.id(),"sha256",payload.sha256(),"bytes",payload.bytes(),"peer",confirmation.peer())).getBytes(StandardCharsets.UTF_8);
            File temporary=new File(outgoing,payload.id()+".ack.part");
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(receipt);out.getFD().sync();}
            Files.move(temporary.toPath(),new File(outgoing,payload.id()+".ack.json").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            durability.sync(outgoing);
        }
    }
}
