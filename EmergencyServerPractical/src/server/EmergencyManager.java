package server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Serialized request-state machine. V3: staff-confirmed arrival and durable audit. */
public class EmergencyManager implements AutoCloseable {
    private static final Logger LOG=Logger.getLogger(EmergencyManager.class.getName());
    private final ClientRegistry registry;
    private final HospitalSelector selector;
    private final BedReservationManager reservations;
    private final Map<String,EmergencyRequest> requests=new HashMap<>();
    private final ScheduledExecutorService timer;
    private final long offerTimeoutNanos, assignmentTimeoutNanos;
    private final EventStore audit;
    private long nextId=1;

    public EmergencyManager(ClientRegistry registry){this(registry,30000,300000,null);}
    public EmergencyManager(ClientRegistry registry,long offerMs,long assignmentMs){this(registry,offerMs,assignmentMs,null);}
    public EmergencyManager(ClientRegistry registry,long offerMs,long assignmentMs,EventStore audit){
        if(offerMs<=0 || assignmentMs<=0)throw new IllegalArgumentException("Timeouts must be positive");
        this.registry=registry; this.audit=audit;
        this.selector=new HospitalSelector(registry);
        this.reservations=new BedReservationManager(registry);
        this.offerTimeoutNanos=TimeUnit.MILLISECONDS.toNanos(offerMs);
        this.assignmentTimeoutNanos=TimeUnit.MILLISECONDS.toNanos(assignmentMs);
        if(audit!=null)nextId=audit.nextId();
        timer=Executors.newSingleThreadScheduledExecutor(task->{
            Thread t=new Thread(task,"emergency-timeouts");t.setDaemon(true);return t;
        });
        timer.scheduleWithFixedDelay(()->{
            try{expireRequests();}
            catch(RuntimeException e){LOG.log(Level.SEVERE,"Emergency timer stopped state transition",e);}
        },50,50,TimeUnit.MILLISECONDS);
    }
    private void record(EmergencyRequest r,String status,String reason) {
        if(audit==null)return;
        try { audit.record(r.requestId,status,r.ambulanceId,r.emergencyType,r.priority,
                r.requiredBedType,r.selectedHospital==null?"NONE":r.selectedHospital,reason); }
        catch(IOException e){ LOG.log(Level.SEVERE,"AUDIT UNAVAILABLE: refuse further dispatch",e); throw new UncheckedIOException(e); }
    }
    private boolean isOwner(String id,ClientHandler handler,ClientRegistry.Role role){
        return registry.getClient(id)==handler && registry.getRole(id)==role;
    }
    public synchronized void submit(ClientHandler sender,String ambulanceId,String type,String priority,String bed){
        submit(sender,ambulanceId,type,priority,bed,"UNKNOWN");
    }
    public synchronized void submit(ClientHandler sender,String ambulanceId,String type,String priority,String bed,String region){
        if(!isOwner(ambulanceId,sender,ClientRegistry.Role.AMBULANCE)){
            sender.sendMessage("ERROR|Unauthorized ambulance");return;
        }
        if(audit!=null && (!Set.of("CARDIAC","ACCIDENT","TRAUMA","STROKE","BURN","MATERNITY","OTHER").contains(type)
                || !Set.of("CRITICAL","HIGH","MEDIUM","LOW").contains(priority)
                || !Set.of("ICU","GENERAL","EMERGENCY","MATERNITY").contains(bed)
                || !Set.of("GALLE","COLOMBO","KANDY","JAFFNA","OTHER").contains(region))){
            sender.sendMessage("ERROR|Invalid clinical category or region");return;
        }
        String id=String.format(Locale.ROOT,"REQ%06d",nextId++);
        EmergencyRequest r=new EmergencyRequest(id,ambulanceId,type,priority,bed,region,sender);
        record(r,"CREATED","AMBULANCE_REQUEST");
        requests.put(id,r);
        if(!sender.sendMessage("EMERGENCY_REQUEST_RECEIVED|"+id)){
            finish(r,EmergencyRequest.Status.CANCELLED,"AMBULANCE_DISCONNECTED");return;
        }
        offerNext(r,"NO_SUITABLE_HOSPITAL");
    }
    private void offerNext(EmergencyRequest r,String exhaustedReason){
        HospitalSelector.Selection choice;
        while((choice=selector.select(r.emergencyType,r.requiredBedType,r.region,r.attemptedHospitals))!=null){
            r.selectedHospital=choice.hospitalId;
            r.hospital=choice.handler;
            r.attemptedHospitals.add(choice.hospitalId);
            r.status=EmergencyRequest.Status.OFFERED;
            r.deadlineNanos=System.nanoTime()+offerTimeoutNanos;
            record(r,"OFFERED","HOSPITAL_OFFER");
            String payload="EMERGENCY_OFFER|"+r.requestId+"|"+r.ambulanceId+"|"+r.emergencyType
                    +"|"+r.priority+"|"+r.requiredBedType+(audit==null?"":"|"+r.region);
            if(choice.handler.sendMessage(payload)){
                LOG.info("Offer "+r.requestId+" to "+choice.hospitalId);return;
            }
        }
        finish(r,exhaustedReason.equals("OFFER_TIMEOUT")?EmergencyRequest.Status.TIMED_OUT:
                EmergencyRequest.Status.FAILED,exhaustedReason);
    }
    public synchronized void respond(ClientHandler sender,String requestId,String hospitalId,boolean accept){
        EmergencyRequest r=requests.get(requestId);
        if(r==null || !isOwner(hospitalId,sender,ClientRegistry.Role.HOSPITAL)
                || r.hospital!=sender || !hospitalId.equals(r.selectedHospital)
                || r.status!=EmergencyRequest.Status.OFFERED){
            sender.sendMessage("ERROR|Invalid or stale hospital response");return;
        }
        if(System.nanoTime()-r.deadlineNanos>=0){
            sender.sendMessage("ERROR|Offer expired");offerNext(r,"OFFER_TIMEOUT");return;
        }
        if(!accept){
            sender.sendMessage("HOSPITAL_REJECTED|"+requestId);
            offerNext(r,"ALL_HOSPITALS_REJECTED");return;
        }
        if(!registry.isHospitalAvailable(hospitalId)||!registry.supportsEmergency(hospitalId,r.emergencyType)){
            sender.sendMessage("ERROR|Hospital is not verified/available/fresh for this service");
            offerNext(r,"NO_SUITABLE_HOSPITAL");return;
        }
        if(!reservations.reserve(r.requestId,hospitalId,sender,r.requiredBedType)){
            sender.sendMessage("ERROR|Matching bed unavailable");offerNext(r,"NO_BEDS_AVAILABLE");return;
        }
        r.status=EmergencyRequest.Status.ASSIGNED;
        r.deadlineNanos=System.nanoTime()+assignmentTimeoutNanos;
        record(r,"ASSIGNED","HOSPITAL_ACCEPTED");
        String msg="HOSPITAL_ASSIGNED|"+r.requestId+"|"+hospitalId+"|"+r.requiredBedType;
        if(!sender.sendMessage(msg)||!r.ambulance.sendMessage(msg)){
            if(audit==null)finish(r,EmergencyRequest.Status.CANCELLED,"ASSIGNMENT_DELIVERY_FAILED");
            else requireReview(r,"ASSIGNMENT_DELIVERY_UNCERTAIN");
            return;
        }
        LOG.info("Assigned "+r.requestId+" to "+hospitalId);
    }
    public synchronized void ambulanceCommand(ClientHandler sender,String command,String requestId,String ambulanceId){
        EmergencyRequest r=requests.get(requestId);
        if(r==null || r.ambulance!=sender || !r.ambulanceId.equals(ambulanceId)
                || !isOwner(ambulanceId,sender,ClientRegistry.Role.AMBULANCE)){
            sender.sendMessage("ERROR|Unknown request or unauthorized ambulance");return;
        }
        if(command.equals("EMERGENCY_STATUS")){
            sender.sendMessage("REQUEST_STATUS|"+r.requestId+"|"+r.status+"|"
                    +(r.selectedHospital==null?"NONE":r.selectedHospital));return;
        }
        if(command.equals("EMERGENCY_COMPLETE") && r.status==EmergencyRequest.Status.ASSIGNED){
            if(audit==null){finish(r,EmergencyRequest.Status.COMPLETED,"AMBULANCE_REQUEST");return;}
            r.status=EmergencyRequest.Status.ARRIVAL_PENDING;
            r.deadlineNanos=System.nanoTime()+assignmentTimeoutNanos;
            record(r,"ARRIVAL_PENDING","AMBULANCE_REPORTED_ARRIVAL");
            sender.sendMessage("EMERGENCY_ARRIVAL_PENDING|"+requestId+"|WAIT_FOR_HOSPITAL_CONFIRMATION");
            if(r.hospital!=null)r.hospital.sendMessage("ARRIVAL_REPORTED|"+requestId);
            return;
        }
        if(command.equals("EMERGENCY_CANCEL") && r.status==EmergencyRequest.Status.OFFERED){
            finish(r,EmergencyRequest.Status.CANCELLED,audit==null?"AMBULANCE_REQUEST":"AMBULANCE_CANCELLED");return;
        }
        if(command.equals("EMERGENCY_CANCEL") && r.status==EmergencyRequest.Status.ASSIGNED && audit!=null){
            requireReview(r,"AMBULANCE_CANCELLED_AFTER_ASSIGNMENT");return;
        }
        if(command.equals("EMERGENCY_CANCEL") && r.status==EmergencyRequest.Status.ASSIGNED){
            finish(r,EmergencyRequest.Status.CANCELLED,audit==null?"AMBULANCE_REQUEST":"AMBULANCE_CANCELLED");return;
        }
        sender.sendMessage("ERROR|Request is not in the required state");
    }
    /** Human hospital operator must confirm physical arrival, or review an uncertain hold. */
    public synchronized void hospitalReconcile(ClientHandler sender,String command,String id,String hospitalId){
        EmergencyRequest r=requests.get(id);
        if(audit==null || r==null || !hospitalId.equals(r.selectedHospital)
                || !isOwner(hospitalId,sender,ClientRegistry.Role.HOSPITAL)
                || r.hospital!=sender){
            sender.sendMessage("ERROR|Unknown request or unauthorized hospital");return;
        }
        if(command.equals("HOSPITAL_CONFIRM_ARRIVAL") &&
                (r.status==EmergencyRequest.Status.ARRIVAL_PENDING || r.status==EmergencyRequest.Status.REQUIRES_REVIEW)){
            record(r,"COMPLETING","STAFF_CONFIRMED_ARRIVAL");
            if(!reservations.consume(id)){
                requireReview(r,"BED_CONSUMPTION_NOT_CONFIRMED");return;
            }
            r.status=EmergencyRequest.Status.COMPLETED;
            record(r,"COMPLETED","STAFF_CONFIRMED_ARRIVAL");
            r.ambulance.sendMessage("EMERGENCY_COMPLETED|"+id+"|STAFF_CONFIRMED_ARRIVAL");
            sender.sendMessage("EMERGENCY_COMPLETED|"+id+"|STAFF_CONFIRMED_ARRIVAL");
            return;
        }
        if(command.equals("HOSPITAL_RELEASE_HOLD") && r.status==EmergencyRequest.Status.REQUIRES_REVIEW){
            record(r,"RELEASING","STAFF_CONFIRMED_NO_ARRIVAL");
            reservations.release(id);
            r.status=EmergencyRequest.Status.CANCELLED;
            record(r,"CANCELLED","STAFF_CONFIRMED_NO_ARRIVAL");
            r.ambulance.sendMessage("EMERGENCY_CANCELLED|"+id+"|STAFF_CONFIRMED_NO_ARRIVAL");
            sender.sendMessage("EMERGENCY_CANCELLED|"+id+"|STAFF_CONFIRMED_NO_ARRIVAL");
            return;
        }
        sender.sendMessage("ERROR|Request is not awaiting staff reconciliation");
    }
    private void requireReview(EmergencyRequest r,String reason){
        if(r.status==EmergencyRequest.Status.REQUIRES_REVIEW)return;
        r.status=EmergencyRequest.Status.REQUIRES_REVIEW;
        record(r,"REQUIRES_REVIEW",reason);
        r.ambulance.sendMessage("EMERGENCY_REVIEW_REQUIRED|"+r.requestId+"|"+reason);
        if(r.hospital!=null)r.hospital.sendMessage("EMERGENCY_REVIEW_REQUIRED|"+r.requestId+"|"+reason);
        LOG.warning("Manual review required: "+r.requestId+" / "+reason);
    }
    public synchronized void disconnected(ClientHandler handler){
        for(EmergencyRequest r:requests.values()){
            if(!r.active())continue;
            if(r.ambulance==handler){
                if(audit!=null && r.status!=EmergencyRequest.Status.OFFERED)
                    requireReview(r,"AMBULANCE_DISCONNECTED");
                else finish(r,EmergencyRequest.Status.CANCELLED,"AMBULANCE_DISCONNECTED");
            } else if(r.hospital==handler){
                if(r.status==EmergencyRequest.Status.OFFERED)offerNext(r,"HOSPITAL_DISCONNECTED");
                else if(audit!=null)requireReview(r,"HOSPITAL_DISCONNECTED");
                else finish(r,EmergencyRequest.Status.FAILED,"HOSPITAL_DISCONNECTED");
            }
        }
    }
    public synchronized void expireRequests(){
        long now=System.nanoTime();
        for(EmergencyRequest r:requests.values()){
            if(!r.active() || r.status==EmergencyRequest.Status.REQUIRES_REVIEW
                    || now-r.deadlineNanos<0)continue;
            if(r.status==EmergencyRequest.Status.OFFERED){
                if(r.hospital!=null)r.hospital.sendMessage("OFFER_EXPIRED|"+r.requestId);
                offerNext(r,"OFFER_TIMEOUT");
            }else if(audit!=null)requireReview(r,"TRANSFER_ACK_TIMEOUT");
            else finish(r,EmergencyRequest.Status.TIMED_OUT,"ASSIGNMENT_TIMEOUT");
        }
    }
    private void finish(EmergencyRequest r,EmergencyRequest.Status status,String reason){
        record(r,status.name(),reason);
        reservations.release(r.requestId);
        r.status=status;
        String msg="EMERGENCY_"+status+"|"+r.requestId+"|"+reason;
        r.ambulance.sendMessage(msg);
        if(r.hospital!=null)r.hospital.sendMessage(msg);
        LOG.info(r.requestId+" "+status+" "+reason);
    }
    public synchronized EmergencyRequest.Status getStatus(String requestId){
        EmergencyRequest r=requests.get(requestId);return r==null?null:r.status;
    }
    @Override public synchronized void close(){
        timer.shutdownNow();
        for(EmergencyRequest r:requests.values()) if(r.active()){
            if(audit!=null && r.status!=EmergencyRequest.Status.OFFERED)
                requireReview(r,"SERVER_SHUTDOWN");
            else finish(r,EmergencyRequest.Status.CANCELLED,"SERVER_SHUTDOWN");
        }
    }
}
