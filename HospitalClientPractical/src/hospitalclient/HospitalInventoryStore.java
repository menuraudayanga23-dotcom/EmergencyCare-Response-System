package hospitalclient;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Properties;

/** Small durable local demo inventory cache; NOT a live hospital information system. */
public final class HospitalInventoryStore {
    private static final Path FILE = Path.of(System.getProperty("hospital.inventoryFile", "hospital-inventory.properties"));
    private HospitalInventoryStore() {}
    public static synchronized void restore(ArrayList<Hospital> hospitals) {
        if(!Files.exists(FILE))return;
        Properties p=new Properties();
        try(InputStream in=Files.newInputStream(FILE)) { p.load(in); }
        catch(IOException e) { throw new IllegalStateException("Cannot read hospital inventory file: stop and inspect",e); }
        for(Hospital hospital:hospitals){
            String id=hospital.getHospitalId();
            String general=p.getProperty(id+".GENERAL");
            String icu=p.getProperty(id+".ICU");
            if(general==null && icu==null)continue;
            try {
                if(general!=null) {
                    int count=Integer.parseInt(general);
                    if(count<0 || count>10000)throw new NumberFormatException();
                    hospital.updateGeneralBeds(count);
                }
                if(icu!=null) {
                    int count=Integer.parseInt(icu);
                    if(count<0 || count>10000)throw new NumberFormatException();
                    hospital.updateIcuBeds(count);
                }
            } catch(NumberFormatException e) {
                throw new IllegalStateException("Corrupt local inventory for "+id+". Manual review needed",e);
            }
        }
    }
    public static synchronized boolean save(Hospital hospital){
        Properties p=new Properties();
        try {
            if(Files.exists(FILE))try(InputStream in=Files.newInputStream(FILE)){p.load(in);}
            p.setProperty(hospital.getHospitalId()+".GENERAL",Integer.toString(hospital.getGeneralBeds()));
            p.setProperty(hospital.getHospitalId()+".ICU",Integer.toString(hospital.getIcuBeds()));
            Path parent=FILE.toAbsolutePath().getParent();
            if(parent!=null)Files.createDirectories(parent);
            Path temp=Files.createTempFile(parent, "inventory-", ".tmp");
            try {
                try(OutputStream out=Files.newOutputStream(temp)){p.store(out,"DEMO HOSPITAL LOCAL INVENTORY - NOT PHYSICALLY VERIFIED");}
                try {Files.move(temp,FILE,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(java.nio.file.AtomicMoveNotSupportedException e){
                    Files.move(temp,FILE,StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {Files.deleteIfExists(temp);}
            return true;
        } catch(IOException e) {
            System.out.println("INVENTORY SAVE FAILED: "+e.getMessage());
            return false;
        }
    }
}
