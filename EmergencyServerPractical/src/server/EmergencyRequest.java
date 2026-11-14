package server;

import java.util.HashSet;
import java.util.Set;

/** Mutable workflow state is accessed only under its EmergencyManager's monitor. */
public class EmergencyRequest {
    public enum Status { OFFERED, ASSIGNED, CANCELLED, COMPLETED, FAILED, TIMED_OUT, ARRIVAL_PENDING, REQUIRES_REVIEW }

    public final String requestId;
    public final String ambulanceId;
    public final String emergencyType;
    public final String priority;
    public final String requiredBedType;
    public final String region;
    final ClientHandler ambulance;
    final Set<String> attemptedHospitals = new HashSet<>();
    String selectedHospital;
    ClientHandler hospital;
    Status status = Status.OFFERED;
    long deadlineNanos;

    EmergencyRequest(String requestId, String ambulanceId, String emergencyType,
                     String priority, String requiredBedType, ClientHandler ambulance) {
        this(requestId, ambulanceId, emergencyType, priority, requiredBedType, "UNKNOWN", ambulance);
    }

    EmergencyRequest(String requestId, String ambulanceId, String emergencyType,
                     String priority, String requiredBedType, String region, ClientHandler ambulance) {
        this.requestId = requestId;
        this.ambulanceId = ambulanceId;
        this.emergencyType = emergencyType;
        this.priority = priority;
        this.requiredBedType = requiredBedType;
        this.region = region;
        this.ambulance = ambulance;
    }

    boolean active() { return status == Status.OFFERED || status == Status.ASSIGNED || status == Status.ARRIVAL_PENDING || status == Status.REQUIRES_REVIEW; }
}
