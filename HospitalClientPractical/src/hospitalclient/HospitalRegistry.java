package hospitalclient;
import java.util.ArrayList;

/** All names, facilities and bed counts here are FICTIONAL demonstration data. */
public class HospitalRegistry {
    public static ArrayList<Hospital> createHospitals(){
        ArrayList<Hospital> hospitals=new ArrayList<>();
        hospitals.add(new Hospital("H001","DEMO South Hospital (not real)","GALLE","Demo",
                true,true,true,15,5));
        hospitals.add(new Hospital("H002","DEMO Colombo Hospital (not real)","COLOMBO","Demo",
                true,true,true,20,8));
        hospitals.add(new Hospital("H003","DEMO Kandy Hospital (not real)","KANDY","Demo",
                true,true,false,18,6));
        HospitalInventoryStore.restore(hospitals);
        return hospitals;
    }
    public static void displayHospitals(ArrayList<Hospital> hospitals){
        System.out.println("\n===== DEMONSTRATION HOSPITALS (NOT VERIFIED REAL FACILITIES) =====");
        for(int i=0;i<hospitals.size();i++){
            Hospital hospital=hospitals.get(i);
            System.out.println((i+1)+". "+hospital.getHospitalName()+" ("+hospital.getLocation()+")");
        }
    }
}
