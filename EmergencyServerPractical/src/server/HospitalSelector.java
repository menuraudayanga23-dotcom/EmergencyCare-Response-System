package server;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Choose the hospital with most matching free beds; break ties by hospital ID. */
public class HospitalSelector {
    public static class Selection {
        public final String hospitalId;
        public final ClientHandler handler;

        Selection(String hospitalId, ClientHandler handler) {
            this.hospitalId = hospitalId;
            this.handler = handler;
        }
    }

    private final ClientRegistry registry;
    public HospitalSelector(ClientRegistry registry) { this.registry = registry; }

    public Selection select(String bedType, Set<String> excluded) {
        return select(null, bedType, excluded); // compatibility with Phase 1 / Phase 2 tests
    }

    public Selection select(String emergencyType, String bedType, Set<String> excluded) {
        return select(emergencyType, bedType, "UNKNOWN", excluded);
    }

    public Selection select(String emergencyType, String bedType, String region, Set<String> excluded) {
        synchronized (registry) {
            Selection best = null;
            int mostBeds = 0;
            boolean sameRegion = false;
            for (Map.Entry<String, ClientHandler> entry : new TreeMap<>(registry.getHospitals()).entrySet()) {
                if (excluded.contains(entry.getKey())) continue;
                if (!registry.isHospitalAvailable(entry.getKey())) continue;
                if (!registry.supportsEmergency(entry.getKey(), emergencyType)) continue;
                int free = registry.getBedAvailability(entry.getKey()).getOrDefault(bedType, 0);
                boolean local = region != null && !"UNKNOWN".equals(region)
                        && region.equals(registry.region(entry.getKey()));
                if (free > 0 && (best == null || (local && !sameRegion)
                        || (local == sameRegion && free > mostBeds))) {
                    best = new Selection(entry.getKey(), entry.getValue());
                    mostBeds = free;
                    sameRegion = local;
                }
            }
            return best;
        }
    }
}
