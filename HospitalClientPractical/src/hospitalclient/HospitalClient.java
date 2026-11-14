
package hospitalclient;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Scanner;

public class HospitalClient {

    private static final Scanner scanner = new Scanner(System.in);

    private static final ArrayList<Hospital> hospitals =
            HospitalRegistry.createHospitals();

    private static final CopyOnWriteArrayList<EmergencyRequest> requests =
            new CopyOnWriteArrayList<>();

    private static Hospital hospital = null;
    private static boolean connected = false;

    public static void main(String[] args) {

        int choice;

        do {
            receiveServerMessages();
            showMenu();
            choice = readNumber();

            // Process any messages that arrived while waiting for input
            receiveServerMessages();

            if (choice == 1) {
                selectHospital();
            } else if (choice == 2) {
                if (checkHospital()) hospital.displayHospitalInfo();
            } else if (choice == 3) {
                updateBeds(false);
            } else if (choice == 4) {
                updateBeds(true);
            } else if (choice == 5) {
                checkEmergencyFacility();
            } else if (choice == 6) {
                showRequests();
            } else if (choice == 7) {
                decideRequest(true);
            } else if (choice == 8) {
                decideRequest(false);
            } else if (choice == 9) {
                updateHospitalStatus();
            } else if (choice == 11) {
                reconcile(true);
            } else if (choice == 12) {
                reconcile(false);
            } else if (choice == 10) {
                System.out.println("Exiting Hospital Client.");
            } else {
                System.out.println("Invalid choice.");
            }

        } while (choice != 10);

        HospitalNetwork.close();
        scanner.close();
    }

    private static void showMenu() {
        System.out.println("\n===== HOSPITAL CLIENT =====");
        System.out.println("Selected Hospital: " +
                (hospital == null ? "None" : hospital.getHospitalName()));

        System.out.println("1. Select and Register Hospital");
        System.out.println("2. View Hospital Information");
        System.out.println("3. Update GENERAL Beds");
        System.out.println("4. Update ICU Beds");
        System.out.println("5. Check Emergency Facility");
        System.out.println("6. View Emergency Requests");
        System.out.println("7. Accept Emergency Request");
        System.out.println("8. Reject Emergency Request");
        System.out.println("9. Update Hospital Status");
        System.out.println("11. Confirm Patient Arrival (consumes bed)");
        System.out.println("12. Release Held Bed after staff review (no arrival)");
        System.out.println("10. Exit");
        System.out.print("Enter your choice: ");
    }

    private static int readNumber() {
        String text = scanner.nextLine().trim();

        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean checkHospital() {
        if (hospital == null) {
            System.out.println("Select a hospital using Option 1 first.");
            return false;
        }
        return true;
    }

    private static void selectHospital() {

        if (connected) {
            System.out.println(
                    "A hospital is already registered on this connection.");
            System.out.println(
                    "Restart the client to select another hospital.");
            return;
        }

        HospitalRegistry.displayHospitals(hospitals);
        System.out.print("Select hospital number: ");

        int number = readNumber();

        if (number < 1 || number > hospitals.size()) {
            System.out.println("Invalid hospital number.");
            return;
        }

        hospital = hospitals.get(number - 1);
        System.out.println("Selected: " + hospital.getHospitalName());

        String token = System.getenv().getOrDefault("HOSPITAL_TOKEN",
                System.getProperty("hospital.token", "")).trim();
        if (token.isEmpty()) {
            System.out.print("Hospital demo access token (see README): ");
            token = scanner.nextLine().trim();
        }
        connected = HospitalNetwork.connect(hospital, token);

        if (connected) {
            HospitalNetwork.sendHospitalStatus(hospital);
            HospitalNetwork.sendCapabilities(hospital);
            HospitalNetwork.sendAvailability(hospital);

            System.out.println("Hospital connected. Bed updates sent.");
            System.out.println(
                    "Keep this client running to receive emergencies.");
        } else {
            System.out.println(
                    "Could not connect. Check that the server is running.");
        }
    }

    private static void updateBeds(boolean icu) {

        if (!checkHospital()) return;

        System.out.print(icu
                ? "Enter available ICU beds: "
                : "Enter available GENERAL beds: ");

        int beds = readNumber();

        if (beds < 0) {
            System.out.println("Enter a valid non-negative number.");
            return;
        }

        if (icu) {
            hospital.updateIcuBeds(beds);
        } else {
            hospital.updateGeneralBeds(beds);
        }

        if (!HospitalInventoryStore.save(hospital)) {
            hospital.updateStatus(HospitalStatus.UNAVAILABLE);
            if(connected)HospitalNetwork.sendHospitalStatus(hospital);
            System.out.println("Hospital marked UNAVAILABLE until local inventory can be saved.");
            return;
        }
        if (connected) {
            String type = icu ? "ICU" : "GENERAL";

            HospitalNetwork.sendMessage(
                    "BED_UPDATE|" + hospital.getHospitalId()
                    + "|" + type + "|" + beds);

            System.out.println("Bed update sent to server.");
        } else {
            System.out.println(
                    "Beds updated locally. Server is not connected.");
        }
    }

    private static void checkEmergencyFacility() {

        if (!checkHospital()) return;

        EmergencyType[] types = EmergencyType.values();
        for (int i = 0; i < types.length; i++) {
            System.out.println((i + 1) + ". " + types[i]);
        }
        System.out.print("Select emergency type: ");

        int type = readNumber();

        if (type < 1 || type > types.length) {
            System.out.println("Invalid emergency type.");
            return;
        }

        EmergencyType emergencyType =
                types[type - 1];

        System.out.println(
                hospital.supportsEmergency(emergencyType)
                ? "Hospital supports this emergency."
                : "Hospital does not support this emergency.");
    }

    public static synchronized void receiveServerMessages() {

        String message;

        while ((message = HospitalNetwork.getNextMessage()) != null) {

            System.out.println("\n[NETWORK RECEIVED] " + message);

            if (message.startsWith("EMERGENCY_OFFER|")) {

                String[] parts = message.split("\\|", -1);

                if (parts.length != 6 && parts.length != 7) {
                    System.out.println(
                            "Invalid emergency offer: " + message);
                    continue;
                }

                try {
                    String requestId = parts[1];
                    String ambulanceId = parts[2];

                    EmergencyType type =
                            EmergencyType.valueOf(parts[3]);

                    if (findRequest(requestId) == null) {
                        requests.add(new EmergencyRequest(
                                requestId, ambulanceId, type,
                                parts[4], parts[5]));
                    }

                    System.out.println(
                            "\n====================================");
                    System.out.println(
                            "!!! NEW EMERGENCY OFFER !!!");
                    System.out.println(
                            "====================================");
                    System.out.println("Request ID: " + requestId);
                    System.out.println("Ambulance ID: " + ambulanceId);
                    System.out.println("Emergency Type: " + parts[3]);
                    System.out.println("Priority: " + parts[4]);
                    System.out.println("Required Bed: " + parts[5]);
                    if (parts.length == 7) System.out.println("Ambulance region (not travel ETA): " + parts[6]);
                    System.out.println(
                            "Select Option 7 to accept or 8 to reject.");
                    System.out.println(
                            "Enter Request ID: " + requestId);
                    System.out.println(
                            "====================================");

                } catch (IllegalArgumentException e) {
                    System.out.println(
                            "Unknown emergency type in offer: "
                            + parts[3]);
                }

            } else if (message.startsWith("EMERGENCY|")
                    || message.startsWith("NEW_EMERGENCY|")) {

                String[] parts = message.split("\\|", -1);

                if (parts.length != 4) {
                    System.out.println(
                            "Invalid emergency: " + message);
                    continue;
                }

                try {
                    String ambulanceId = parts[1];

                    EmergencyType type =
                            EmergencyType.valueOf(parts[2]);

                    System.out.println(
                            "\nLegacy emergency broadcast received.");
                    System.out.println("Ambulance ID: " + ambulanceId);
                    System.out.println("Emergency Type: " + parts[2]);
                    System.out.println("Priority: " + parts[3]);
                    System.out.println(
                            "This is not a managed emergency offer.");
                    System.out.println(
                            "Ask the Ambulance Client to include a bed type.");

                } catch (IllegalArgumentException e) {
                    System.out.println(
                            "Unknown emergency type: " + parts[2]);
                }

            } else if (message.startsWith("OFFER_EXPIRED|")) {

                String[] parts = message.split("\\|", -1);

                if (parts.length >= 2) {
                    EmergencyRequest request = findRequest(parts[1]);

                    if (request != null
                            && request.getStatus()
                                    == RequestStatus.PENDING) {
                        request.expireRequest();
                    }
                }

                System.out.println("Server: " + message);

            } else if (message.startsWith("HOSPITAL_REJECTED|")) {

                String[] parts = message.split("\\|", -1);
                if (parts.length >= 2) {
                    EmergencyRequest request = findRequest(parts[1]);
                    if (request != null) request.rejectRequest();
                }
                System.out.println("Server confirmed hospital rejection: " + message);

            } else if (message.startsWith("ARRIVAL_REPORTED|")) {
                String[] parts = message.split("\\|", -1);
                if (parts.length >= 2) {
                    EmergencyRequest request = findRequest(parts[1]);
                    if (request != null) request.arrivalPending();
                    System.out.println("Ambulance reported arrival for " + parts[1]
                            + ". Confirm patient physically arrived using option 11.");
                }
            } else if (message.startsWith("EMERGENCY_REVIEW_REQUIRED|")) {
                String[] parts = message.split("\\|", -1);
                if (parts.length >= 2) {
                    EmergencyRequest request = findRequest(parts[1]);
                    if (request != null) request.requireReview();
                    System.out.println("MANUAL REVIEW REQUIRED for " + parts[1]
                            + ". Verify arrival before option 11 or 12.");
                }
            } else if (message.startsWith("EMERGENCY_COMPLETED|")) {
                String[] parts = message.split("\\|", -1);
                if (parts.length >= 2) {
                    EmergencyRequest request = findRequest(parts[1]);
                    if (request != null && request.getStatus() != RequestStatus.COMPLETED) {
                        request.completeRequest();
                        if (hospital != null) {
                            if ("ICU".equals(request.getRequiredBedType()))
                                hospital.updateIcuBeds(Math.max(0, hospital.getIcuBeds() - 1));
                            else if ("GENERAL".equals(request.getRequiredBedType()))
                                hospital.updateGeneralBeds(Math.max(0, hospital.getGeneralBeds() - 1));
                            if (!HospitalInventoryStore.save(hospital)) {
                                hospital.updateStatus(HospitalStatus.UNAVAILABLE);
                                HospitalNetwork.sendHospitalStatus(hospital);
                                System.out.println("INVENTORY PERSISTENCE FAILED: hospital marked UNAVAILABLE.");
                            } else {
                                HospitalNetwork.sendAvailability(hospital);
                            }
                        }
                    }
                    System.out.println("Request " + parts[1]
                            + " arrived and occupied a bed; manual update required after discharge.");
                }

            } else if (message.startsWith("EMERGENCY_FAILED|")
                    || message.startsWith("EMERGENCY_TIMED_OUT|")
                    || message.startsWith("EMERGENCY_CANCELLED|")) {

                String[] parts = message.split("\\|", -1);
                if (parts.length >= 2) {
                    EmergencyRequest request = findRequest(parts[1]);
                    if (request != null) {
                        if (message.startsWith("EMERGENCY_CANCELLED|")) request.cancelRequest();
                        else if (message.startsWith("EMERGENCY_TIMED_OUT|")) request.expireRequest();
                        else request.failRequest();
                    }
                }
                System.out.println("Server: " + message);

            } else if (message.startsWith("HOSPITAL_ASSIGNED|")) {

                String[] parts = message.split("\\|", -1);

                System.out.println(
                        "\n===== HOSPITAL ASSIGNMENT CONFIRMED =====");

                if (parts.length >= 4) {
                    EmergencyRequest request = findRequest(parts[1]);
                    if (request != null) request.acceptRequest();
                    System.out.println("Request ID: " + parts[1]);
                    System.out.println("Hospital ID: " + parts[2]);
                    System.out.println("Bed Type: " + parts[3]);
                } else {
                    System.out.println(message);
                }

            } else {
                System.out.println("Server: " + message);
            }
        }
    }

    private static void showRequests() {

        receiveServerMessages();

        if (requests.isEmpty()) {
            System.out.println("No emergency offers received yet.");
            System.out.println(
                    "Ask the ambulance client to send an emergency.");
            return;
        }

        for (EmergencyRequest request : requests) {
            request.displayRequest();
        }
    }

    private static EmergencyRequest findRequest(String id) {
        for (EmergencyRequest request : requests) {
            if (request.getRequestId().trim().equalsIgnoreCase(id.trim())) {
                return request;
            }
        }
        return null;
    }

    private static void decideRequest(boolean accept) {

        if (!checkHospital()) return;

        if (!connected) {
            System.out.println("Not connected to server.");
            return;
        }

        receiveServerMessages();

        System.out.print("Enter request ID: ");
        String requestId = scanner.nextLine().trim();

        // Check again for offers that arrived during user input
        receiveServerMessages();

        EmergencyRequest request = findRequest(requestId);

        if (request == null) {
            System.out.println("Request not found.");
            System.out.println(
                    "Use the REQ... ID received from the server.");
            return;
        }

        if (request.getStatus() != RequestStatus.PENDING) {
            System.out.println("Request is no longer pending.");
            return;
        }

        if (accept) {

            if (!hospital.supportsEmergency(
                    request.getEmergencyType())) {
                System.out.println(
                        "Hospital does not support this emergency. Select Option 8 to reject.");
                return;
            }

            if (hospital.getStatus() == HospitalStatus.UNAVAILABLE
                    || !hospital.hasAvailableBeds()) {
                System.out.println(
                        "Hospital is unavailable or has no beds.");
                return;
            }

            // The server matches the exact bed type (not just any free bed).
            String bedType = request.getRequiredBedType();
            if (("ICU".equals(bedType) && hospital.getIcuBeds() <= 0)
                    || ("GENERAL".equals(bedType) && hospital.getGeneralBeds() <= 0)) {
                System.out.println("No available " + bedType + " beds.");
                return;
            }

            System.out.print("Confirm acceptance (yes/no): ");
            String confirmation = scanner.nextLine().trim();

            if (!confirmation.equalsIgnoreCase("yes")) {
                System.out.println("Acceptance cancelled.");
                return;
            }

        }

        // The server validates the offer and makes the final decision
        HospitalNetwork.sendRequestDecision(
                hospital.getHospitalId(),
                request.getRequestId(),
                accept ? RequestStatus.ACCEPTED
                       : RequestStatus.REJECTED);

        System.out.println(
                "Decision sent. Waiting for server response.");

        // Process any response already received
        receiveServerMessages();
    }

    private static void reconcile(boolean arrived) {
        if (!checkHospital() || !connected) return;
        receiveServerMessages();
        System.out.print("Request ID for staff review: ");
        String id = scanner.nextLine().trim().toUpperCase();
        receiveServerMessages();
        EmergencyRequest request = findRequest(id);
        if (request == null) { System.out.println("Unknown request ID."); return; }
        boolean permitted = arrived
                ? (request.getStatus() == RequestStatus.ARRIVAL_PENDING
                        || request.getStatus() == RequestStatus.REQUIRES_REVIEW)
                : (request.getStatus() == RequestStatus.REQUIRES_REVIEW);
        if (!permitted) {
            System.out.println("Request is not waiting for this staff action. Current: " + request.getStatus());
            return;
        }
        System.out.print(arrived
                ? "Physically verified patient arrival and available bed assignment? Type YES: "
                : "Verified that the patient NEVER arrived and hold can be released? Type YES: ");
        if (!"YES".equalsIgnoreCase(scanner.nextLine().trim())) {
            System.out.println("No action taken."); return;
        }
        HospitalNetwork.sendMessage((arrived ? "HOSPITAL_CONFIRM_ARRIVAL|" : "HOSPITAL_RELEASE_HOLD|")
                + id + "|" + hospital.getHospitalId());
        System.out.println("Staff decision sent. Check server acknowledgement.");
    }

    private static void updateHospitalStatus() {

        if (!checkHospital()) return;

        System.out.println("1. AVAILABLE");
        System.out.println("2. LIMITED");
        System.out.println("3. UNAVAILABLE");
        System.out.print("Select status: ");

        int status = readNumber();

        if (status < 1 || status > 3) {
            System.out.println("Invalid status.");
            return;
        }

        hospital.updateStatus(
                HospitalStatus.values()[status - 1]);

        if (connected) {
            HospitalNetwork.sendHospitalStatus(hospital);
            System.out.println("Hospital status sent to the server.");
        } else {
            System.out.println("Status updated locally. Not connected to server.");
        }
    }
}
