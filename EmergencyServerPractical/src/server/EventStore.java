package server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Exclusive append-only fsync'd journal. No patient names or free-text notes. */
public final class EventStore implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<String,String> lastState=new HashMap<>();
    private final Map<String,String[]> details=new HashMap<>();
    private long nextId=1;
    private static final Set<String> TERMINAL=Set.of(
            "COMPLETED","CANCELLED","FAILED","TIMED_OUT",
            "RECOVERED_COMPLETED","RECOVERED_CANCELLED");

    public EventStore(Path file) throws IOException {
        Path parent=file.toAbsolutePath().getParent();
        if(parent!=null)Files.createDirectories(parent);
        channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        FileLock obtained;
        try { obtained=channel.tryLock(); }
        catch(java.nio.channels.OverlappingFileLockException e) { obtained=null; }
        if(obtained==null){channel.close();throw new IOException("Journal is already locked; stop server before offline reconciliation");}
        lock=obtained;
        try {
            // Read through the SAME locked FileChannel: opening another file handle
            // can fail on Windows, where Eclipse users commonly run this project.
            long length=channel.size();
            if(length>20_000_000)throw new IOException("Journal exceeds 20 MB; archive with staff oversight");
            ByteBuffer input=ByteBuffer.allocate((int)length);
            channel.position(0);
            while(input.hasRemaining() && channel.read(input)>0) { }
            input.flip();
            String content=StandardCharsets.UTF_8.decode(input).toString();
            for(String line:content.split("\\R")) {
                if(line.isBlank())continue;
                String[] p=line.split("\\t",-1);
                if(p.length!=9 || !p[1].matches("REQ[0-9]+"))
                    throw new IOException("Invalid journal record: manual review required");
                lastState.put(p[1],p[2]);
                details.put(p[1],new String[]{p[3],p[4],p[5],p[6],p[7]});
                nextId=Math.max(nextId,Long.parseLong(p[1].substring(3))+1);
            }
            // All non-terminal states are uncertain after a crash. Do NOT re-dispatch.
            for(String id:new HashSet<>(lastState.keySet())) {
                if(!TERMINAL.contains(lastState.get(id)) && !"REQUIRES_REVIEW".equals(lastState.get(id))){
                    String[] d=details.get(id);
                    record(id,"REQUIRES_REVIEW",d[0],d[1],d[2],d[3],d[4],"SERVER_RESTART");
                }
            }
        }catch(IOException | RuntimeException e){lock.release();channel.close();throw e;}
    }
    public synchronized long nextId(){return nextId;}
    public synchronized Map<String,String> states(){return Map.copyOf(lastState);}
    public synchronized String state(String id){return lastState.get(id);}
    public synchronized Set<String> unresolvedHospitals(){
        Set<String> result=new HashSet<>();
        for(String id:lastState.keySet()){
            if(!"REQUIRES_REVIEW".equals(lastState.get(id)))continue;
            String hospital=details.get(id)[4];
            if(!hospital.equals("NONE") && !hospital.equals("UNKNOWN"))result.add(hospital);
        }
        return Set.copyOf(result);
    }
    public synchronized void recordRecovery(String id,String status,String operator) throws IOException {
        if(!"REQUIRES_REVIEW".equals(lastState.get(id)))throw new IOException("Request does not require recovery review");
        if(!Set.of("RECOVERED_COMPLETED","RECOVERED_CANCELLED").contains(status))throw new IOException("Invalid recovery status");
        if(!operator.matches("[A-Za-z0-9_-]{3,40}"))throw new IOException("Operator ID must be 3-40 letters/digits");
        String[] d=details.get(id);
        record(id,status,d[0],d[1],d[2],d[3],d[4],"OFFLINE_REVIEW_BY_"+operator);
    }
    public synchronized void record(String id,String status,String ambulance,String type,
                      String priority,String bed,String hospital,String reason) throws IOException {
        for(String x:new String[]{id,status,ambulance,type,priority,bed,hospital,reason})
            if(x==null || !x.matches("[A-Za-z0-9_-]+"))throw new IOException("Illegal audit field");
        String event=Instant.now()+"\t"+id+"\t"+status+"\t"+ambulance+"\t"+type
                +"\t"+priority+"\t"+bed+"\t"+hospital+"\t"+reason+"\n";
        channel.position(channel.size());
        ByteBuffer buffer=StandardCharsets.UTF_8.encode(event);
        while(buffer.hasRemaining())channel.write(buffer);
        channel.force(true);
        lastState.put(id,status);
        details.put(id,new String[]{ambulance,type,priority,bed,hospital});
        if(id.matches("REQ[0-9]+"))nextId=Math.max(nextId,Long.parseLong(id.substring(3))+1);
    }
    @Override public synchronized void close() throws IOException {
        try { lock.release(); }
        finally { channel.close(); }
    }
}
