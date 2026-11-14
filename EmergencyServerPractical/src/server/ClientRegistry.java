package server;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Live session registry. The optional trusted directory enables approved-client checks. */
public class ClientRegistry {
    public enum Role { AMBULANCE, HOSPITAL }
    private static class Registration {
        final ClientHandler handler;
        final Role role;
        final TrustedDirectory.Profile profile;
        final Map<String,Integer> beds=new HashMap<>();
        final Map<String,Integer> reserved=new HashMap<>();
        Set<String> capabilities=null;
        String hospitalStatus="AVAILABLE";
        long lastBeds, lastStatus, lastCapabilities;
        Registration(ClientHandler h,Role role,TrustedDirectory.Profile profile) {
            this.handler=h; this.role=role; this.profile=profile;
        }
    }
    private final Map<String, Registration> clients=new ConcurrentHashMap<>();
    private final TrustedDirectory directory;
    private final Set<String> quarantinedHospitals=ConcurrentHashMap.newKeySet();
    public void quarantine(String hospitalId){quarantinedHospitals.add(hospitalId);}
    public Set<String> quarantined(){return Set.copyOf(quarantinedHospitals);}
    private final long freshnessMillis;
    public ClientRegistry() { this(null); }
    public ClientRegistry(TrustedDirectory directory) {
        this.directory=directory;
        freshnessMillis=Long.getLong("emergency.inventoryFreshnessMillis",120000L);
    }
    public boolean authenticationRequired() { return directory!=null; }
    public boolean authenticate(String id, Role role, String nonce, String proof) {
        return directory == null || directory.verify(role.name(),id,nonce,proof);
    }
    public synchronized boolean register(String id,Role role,ClientHandler handler) {
        if(id==null || id.isBlank() || role==null || handler==null) return false;
        TrustedDirectory.Profile profile=directory == null ? null : directory.find(role.name(),id);
        if(directory!=null && (profile==null || !profile.approved)) return false;
        for(Registration r:clients.values()) if(r.handler==handler)return false;
        return clients.putIfAbsent(id,new Registration(handler,role,profile))==null;
    }
    public synchronized boolean updateCapabilities(String id,ClientHandler handler,String encoded) {
        Registration r=clients.get(id);
        if(r==null || r.handler!=handler || r.role!=Role.HOSPITAL) return false;
        Set<String> s=new HashSet<>();
        if(!"NONE".equals(encoded)) {
            for(String x:encoded.split("_")) {
                if(!x.matches("[A-Z]+"))return false;
                s.add(x);
            }
        }
        if(r.profile!=null && !r.profile.services.containsAll(s))return false;
        r.capabilities=s;
        r.lastCapabilities=System.currentTimeMillis();
        return true;
    }
    public synchronized boolean updateHospitalStatus(String id,ClientHandler handler,String status) {
        Registration r=clients.get(id);
        if(r==null || r.handler!=handler || r.role!=Role.HOSPITAL)return false;
        if(!Set.of("AVAILABLE","LIMITED","UNAVAILABLE").contains(status))return false;
        r.hospitalStatus=status;
        r.lastStatus=System.currentTimeMillis();
        return true;
    }
    public synchronized boolean isHospitalAvailable(String id) {
        Registration r=clients.get(id);
        if(r==null || r.role!=Role.HOSPITAL || "UNAVAILABLE".equals(r.hospitalStatus)
                || quarantinedHospitals.contains(id))return false;
        if(r.profile==null)return true; // legacy unit-test registry only
        long now=System.currentTimeMillis();
        return r.profile.approved && r.lastBeds>0 && r.lastStatus>0 && r.lastCapabilities>0
                && now-r.lastBeds<freshnessMillis && now-r.lastStatus<freshnessMillis
                && now-r.lastCapabilities<freshnessMillis;
    }
    public synchronized boolean supportsEmergency(String id,String type) {
        Registration r=clients.get(id);
        if(r==null)return false;
        if(type==null)return true;
        if(r.profile!=null && !r.profile.services.contains(type))return false;
        return r.capabilities==null ? r.profile==null : r.capabilities.contains(type);
    }
    public synchronized String region(String hospitalId) {
        Registration r=clients.get(hospitalId);
        return r==null || r.profile==null ? "UNKNOWN" : r.profile.region;
    }
    public ClientHandler getClient(String id){Registration r=clients.get(id);return r==null?null:r.handler;}
    public Role getRole(String id){Registration r=clients.get(id);return r==null?null:r.role;}
    public int count(){return clients.size();}
    public synchronized Map<String,ClientHandler> getHospitals(){
        Map<String,ClientHandler> result=new HashMap<>();
        clients.forEach((id,r)->{if(r.role==Role.HOSPITAL)result.put(id,r.handler);});
        return result;
    }
    public synchronized void remove(String id, ClientHandler handler){
        Registration r=clients.get(id);
        if(r!=null && r.handler==handler)clients.remove(id,r);
    }
    public synchronized boolean updateBeds(String id,ClientHandler handler,String type,int count) {
        if(type==null || type.isBlank() || count<0)return false;
        Registration r=clients.get(id);
        if(r==null || r.handler!=handler || r.role!=Role.HOSPITAL)return false;
        if(r.profile!=null && (!r.profile.bedTypes.contains(type) || count>r.profile.maxBeds))return false;
        if(count<r.reserved.getOrDefault(type,0))return false;
        r.beds.put(type,count);
        r.lastBeds=System.currentTimeMillis();
        return true;
    }
    public synchronized Map<String,Integer> getBedAvailability(String id) {
        Registration r=clients.get(id);
        Map<String,Integer> result=new HashMap<>();
        if(r!=null)r.beds.forEach((type,n)->result.put(type,n-r.reserved.getOrDefault(type,0)));
        return result;
    }
    synchronized boolean reserveBed(String id,ClientHandler owner,String type) {
        Registration r=clients.get(id);
        if(r==null || r.handler!=owner || r.role!=Role.HOSPITAL || !isHospitalAvailable(id))return false;
        int held=r.reserved.getOrDefault(type,0);
        if(r.beds.getOrDefault(type,0)<=held)return false;
        r.reserved.put(type,held+1);
        return true;
    }
    synchronized void releaseBed(String id,ClientHandler owner,String type) {
        Registration r=clients.get(id);
        if(r!=null && r.handler==owner) {
            int held=r.reserved.getOrDefault(type,0);
            if(held>0)r.reserved.put(type,held-1);
        }
    }
    /** Confirmed hospital arrival consumes the occupied bed, rather than freeing it. */
    synchronized boolean consumeBed(String id,ClientHandler owner,String type) {
        Registration r=clients.get(id);
        if(r==null || r.handler!=owner)return false;
        int held=r.reserved.getOrDefault(type,0);
        int total=r.beds.getOrDefault(type,0);
        if(held<=0 || total<=0)return false;
        r.reserved.put(type,held-1);
        r.beds.put(type,total-1);
        return true;
    }
}
