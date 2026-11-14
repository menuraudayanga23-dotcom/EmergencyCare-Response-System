package server;

import java.nio.file.Path;

/** Offline recovery tool; MUST be used only by authorized staff after physical verification. */
public final class AuditReconcile {
    public static void main(String[] args) throws Exception {
        if(args.length!=5 || !"I_VERIFIED".equals(args[4])){
            System.err.println("Usage (server STOPPED): AuditReconcile <audit-file> <REQ_ID> <ARRIVED|NO_ARRIVAL> <operator-id> I_VERIFIED");
            System.exit(2);
        }
        String status;
        if("ARRIVED".equals(args[2]))status="RECOVERED_COMPLETED";
        else if("NO_ARRIVAL".equals(args[2]))status="RECOVERED_CANCELLED";
        else throw new IllegalArgumentException("Choose ARRIVED or NO_ARRIVAL after verified record review");
        try(EventStore audit=new EventStore(Path.of(args[0]))){
            audit.recordRecovery(args[1],status,args[3]);
            System.out.println("Offline review recorded for "+args[1]+" by "+args[3]);
            System.out.println("Before restarting: reconcile physical bed inventory, then have hospital re-report actual availability.");
        }
    }
}
