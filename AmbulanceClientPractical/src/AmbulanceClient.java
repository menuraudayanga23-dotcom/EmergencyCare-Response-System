
import java.io.*;
import java.net.*;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;

public class AmbulanceClient {

    private static final int PORT = 5000;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String[] TYPES = {
            "CARDIAC", "ACCIDENT", "BURN",
            "STROKE", "MATERNITY", "OTHER"
    };

    private static final String[] PRIORITIES = {
            "CRITICAL", "HIGH", "MEDIUM", "LOW"
    };

    private static final String[] REGIONS = { "GALLE", "COLOMBO", "KANDY", "JAFFNA", "OTHER" };

    private static final String[] BED_TYPES = {
            "ICU", "GENERAL", "EMERGENCY", "MATERNITY"
    };

    // History: time, type, priority/bed, status, server-generated request ID.
    // All requests remain visible, not just the latest emergency.
    private static final List<String[]> history =
            new CopyOnWriteArrayList<>();

    private static volatile boolean waiting = false;
    private static volatile boolean connected = false;
    private static volatile boolean registered = false;

    private static String now() {
        return LocalTime.now().format(TIME);
    }

    public static void main(String[] args) {

        // Reset state for a new run.
        waiting = false;
        connected = false;
        registered = false;
        history.clear();

        Scanner sc = new Scanner(System.in);

        System.out.print(
                "Server IP (press Enter for localhost): ");

        String host = sc.nextLine().trim();

        if (host.isEmpty()) {
            host = "127.0.0.1";
        }

        boolean tls = Boolean.getBoolean("emergency.tls");
        if (!tls && !host.equalsIgnoreCase("localhost") && !host.equals("127.0.0.1")) {
            System.out.println("Remote connections require TLS with a trusted server certificate.");
            return;
        }
        Socket socket;
        try { socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket(); }
        catch (IOException e) { System.out.println("Cannot create secure socket: " + e.getMessage()); return; }

        // Connect to server.
        try {
            System.out.println(
                    "Connecting to " + host + ":" + PORT + "...");

            socket.connect(
                    new InetSocketAddress(host, PORT),
                    CONNECT_TIMEOUT_MS
            );

            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
            }
            connected = true;

        } catch (SocketTimeoutException e) {
            System.out.println(
                    "Connection timed out. Check server IP and port.");
            return;

        } catch (IOException e) {
            System.out.println(
                    "Cannot connect. Make sure EmergencyServer is running.");
            return;
        }

        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(
                     s.getOutputStream(), true)) {

            s.setSoTimeout(8000);
            String greeting = in.readLine();
            String[] welcome = greeting == null ? new String[0] : greeting.split("\\|", -1);
            if(welcome.length != 3 || !"CONNECTED".equals(welcome[0])) {
                System.out.println("Authentication challenge missing; check server V3 version.");
                return;
            }
            String nonce = welcome[2];
            s.setSoTimeout(0);
            System.out.println("Server connected. Authentication required.");

            // Listen for server messages.
            Thread listener = new Thread(() -> {
                try {
                    String line;

                    while ((line = in.readLine()) != null) {
                        handleServerMessage(line);
                    }

                } catch (IOException e) {
                    // Socket closed or connection lost.
                } finally {
                    connected = false;
                    waiting = false;

                    System.out.println(
                            "\n[" + now()
                            + "] Disconnected from server.");
                }
            });

            listener.setDaemon(true);
            listener.start();

            // Allow welcome message to arrive.
            Thread.sleep(500);

            if (!connected) {
                System.out.println("Server disconnected.");
                return;
            }

            // Register ambulance.
            String id;

            while (true) {
                System.out.print(
                        "Enter Ambulance ID (e.g. AMB001): ");

                id = sc.nextLine().trim().toUpperCase();

                if (!id.isEmpty()
                        && !id.contains("|")
                        && !id.contains(" ")) {
                    break;
                }

                System.out.println(
                        "Invalid ID. Do not use spaces or '|'.");
            }

            String token = System.getenv().getOrDefault("AMBULANCE_TOKEN",
                    System.getProperty("ambulance.token", "")).trim();
            if (token.isEmpty()) {
                System.out.print("Ambulance demo access token (see README): ");
                token = sc.nextLine().trim();
            }
            out.println("AMBULANCE_REGISTER|" + id + "|" +
                    proof(token, nonce, "AMBULANCE", id));

            if (out.checkError()) {
                System.out.println(
                        "Failed to send registration.");
                return;
            }

            // Wait for registration confirmation.
            for (int i = 0;
                 i < 30 && !registered && connected;
                 i++) {

                Thread.sleep(100);
            }

            if (!registered) {
                System.out.println(
                        "Registration failed or was not confirmed.");
                System.out.println(
                        "Check the Emergency Server console.");
                return;
            }

            // Main menu.
            while (connected) {

                System.out.println();
                System.out.println(
                        "========= AMBULANCE " + id + " =========");

                System.out.println(
                        "Status: " + (waiting
                                ? "WAITING FOR HOSPITAL ASSIGNMENT"
                                : "READY"));

                System.out.println("1. Send Emergency Request");
                System.out.println("2. View Request History");
                System.out.println("3. Complete an Assigned Emergency");
                System.out.println("4. Cancel an Emergency");
                System.out.println("5. Exit");
                System.out.print("Choose: ");

                String choice = sc.nextLine().trim();

                if (!connected) {
                    System.out.println(
                            "Connection lost. Cannot continue.");
                    break;
                }

                switch (choice) {

                    case "1":

                        if (waiting) {
                            System.out.println(
                                    "An emergency request is already waiting.");
                            break;
                        }

                        String type = pick(
                                sc, "Emergency type", TYPES);

                        String priority = pick(
                                sc, "Priority", PRIORITIES);

                        String bedType = pick(
                                sc, "Bed type needed", BED_TYPES);
                        String region = pick(sc, "Ambulance region (approximate, not driving ETA)", REGIONS);

                        if (!connected) {
                            System.out.println(
                                    "Server disconnected.");
                            break;
                        }

                        String request =
                                "EMERGENCY_REQUEST|"
                                + id + "|"
                                + type + "|"
                                + priority + "|"
                                + bedType + "|" + region;

                        history.add(new String[]{
                                now(),
                                type,
                                priority + "/" + bedType,
                                "WAITING",
                                "AWAITING_ID"
                        });

                        waiting = true;

                        out.println(request);

                        if (out.checkError()) {
                            updateLastStatus("SEND FAILED");
                            waiting = false;

                            System.out.println(
                                    "Failed to send emergency request.");
                            break;
                        }

                        System.out.println(
                                "\n[" + now()
                                + "] Emergency request sent.");

                        System.out.println(
                                "Type: " + type);

                        System.out.println(
                                "Priority: " + priority);

                        System.out.println(
                                "Bed needed: " + bedType);
                        System.out.println("Region: " + region);

                        System.out.println(
                                "Waiting for server response...");
                        break;

                    case "2":

                        if (history.isEmpty()) {
                            System.out.println(
                                    "No emergency requests yet.");
                        } else {

                            System.out.printf(
                                    "%-4s %-9s %-12s %-10s %-16s %s%n",
                                    "#", "Time", "Request ID", "Type",
                                    "Priority/Bed", "Status");

                            for (int i = 0;
                                 i < history.size();
                                 i++) {

                                String[] h = history.get(i);

                                System.out.printf(
                                        "%-4d %-9s %-12s %-10s %-16s %s%n",
                                        i + 1,
                                        h[0],
                                        h[4],
                                        h[1],
                                        h[2],
                                        h[3]);
                            }
                        }
                        break;

                    case "3":
                        changeRequest(sc, out, id, true);
                        break;

                    case "4":
                        changeRequest(sc, out, id, false);
                        break;

                    case "5":

                        if (hasActiveRequests()) {
                            System.out.print(
                                    "Active emergencies may be cancelled by disconnecting. "
                                    + "Exit anyway? (Y/N): ");

                            String answer =
                                    sc.nextLine().trim();

                            if (!answer.equalsIgnoreCase("Y")) {
                                break;
                            }
                        }

                        System.out.println("Disconnecting...");
                        return;

                    default:

                        if (!choice.isEmpty()) {
                            System.out.println(
                                    "Invalid menu option.");
                        }
                        break;
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Program interrupted.");

        } catch (IOException e) {
            System.out.println(
                    "Network error: " + e.getMessage());

        } catch (Exception e) {
            System.out.println(
                    "Unexpected error: " + e.getMessage());
            e.printStackTrace();

        } finally {
            connected = false;
            waiting = false;

            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // Handle messages received from server.
    private static void handleServerMessage(String line) {

        System.out.println(
                "\n[" + now() + "] SERVER MESSAGE: " + line);

        String[] p = line.split("\\|", -1);

        if (p.length == 0) {
            return;
        }

        String cmd = p[0].trim().toUpperCase();

        String rest = line.contains("|")
                ? line.substring(line.indexOf('|') + 1)
                : "";

        switch (cmd) {

            case "CONNECTED":

                System.out.println(
                        "Connected: " + rest);
                break;

            case "AMBULANCE_REGISTERED":

                registered = true;

                System.out.println(
                        "Ambulance registered: " + rest);
                break;

            case "EMERGENCY_REQUEST_RECEIVED":

                if (p.length >= 2) rememberRequestId(p[1]);
                System.out.println(
                        "Server received emergency request.");

                if (!rest.isEmpty()) {
                    System.out.println(
                            "Request details: " + rest);
                }

                System.out.println(
                        "Waiting for a suitable hospital...");
                break;

            case "HOSPITAL_ASSIGNED":

                // Expected:
                // HOSPITAL_ASSIGNED|REQ000001|H001|ICU
                if (p.length < 3) {
                    System.out.println(
                            "Invalid assignment message: " + line);
                    break;
                }

                String hospitalId = p[2];
                String assignedRequestId = p.length > 1
                        ? p[1] : "";

                String assignedBed = p.length > 3
                        ? p[3] : "UNKNOWN";

                updateStatusForId(assignedRequestId,
                        "ASSIGNED -> " + hospitalId);

                System.out.println(
                        "\n*** HOSPITAL ASSIGNED ***");

                System.out.println(
                        "Request ID: " + assignedRequestId);

                System.out.println(
                        "Hospital ID: " + hospitalId);

                System.out.println(
                        "Bed Type: " + assignedBed);

                System.out.println(
                        "Emergency request ACCEPTED.");
                System.out.println("You can select 1 to send ANOTHER emergency without restarting.");
                System.out.println("After reporting arrival, the HOSPITAL must verify arrival using its option 11.");
                System.out.println("Select 3 to report arrival for " + assignedRequestId + ".");
                break;

            case "ASSIGNED":

                // Compatibility with older server responses.
                String legacyHospital = p.length > 1
                        ? p[p.length - 1] : "UNKNOWN";

                updateLastStatus(
                        "ASSIGNED -> " + legacyHospital);
                waiting = false;

                System.out.println(
                        "Hospital assigned: " + legacyHospital);
                break;

            case "EMERGENCY_CANCELLED":
                if (p.length >= 2) updateStatusForId(p[1], "CANCELLED");
                System.out.println("Emergency cancelled: " + rest);
                System.out.println("Choose 1 to send another emergency.");
                break;

            case "EMERGENCY_ARRIVAL_PENDING":
                if (p.length >= 2) updateStatusForId(p[1], "ARRIVAL_PENDING");
                System.out.println("Ambulance reported arrival. Waiting for hospital staff confirmation.");
                break;
            case "EMERGENCY_REVIEW_REQUIRED":
                if (p.length >= 2) updateStatusForId(p[1], "REQUIRES_REVIEW");
                System.out.println("Transfer requires MANUAL hospital review. Bed hold remains.");
                break;
            case "EMERGENCY_COMPLETED":
                if (p.length >= 2) updateStatusForId(p[1], "COMPLETED");
                System.out.println("Emergency completed: " + rest);
                System.out.println("The program stays running. Choose 1 for a new request.");
                break;

            case "EMERGENCY_FAILED":
            case "EMERGENCY_TIMED_OUT":
            case "NO_HOSPITAL_AVAILABLE":

                String failedRequestId = p.length > 1
                        ? p[1] : "UNKNOWN";
                updateStatusForId(failedRequestId,
                        cmd.equals("EMERGENCY_TIMED_OUT") ? "TIMED_OUT" : "FAILED");

                String reason = p.length > 2
                        ? p[2].replace("_", " ")
                        : "No suitable hospital";

                System.out.println(
                        "\n*** EMERGENCY REQUEST FAILED ***");

                System.out.println(
                        "Request ID: " + failedRequestId);

                System.out.println(
                        "Reason: " + reason);

                System.out.println(
                        "Check hospital registration, "
                        + "bed availability, and server matching.");
                System.out.println("Choose 1 to send the next emergency.");
                break;

            case "REQUEST_STATUS":
                System.out.println("Current request state: " + rest);
                break;

            case "ERROR":

                if (waiting) {
                    updateLastStatus("ERROR");
                }

                waiting = false;

                System.out.println(
                        "Server error: " + rest);
                break;

            default:

                System.out.println(
                        "Unrecognized server response: " + line);
                break;
        }
    }

    /** Match responses to their request IDs; older requests must not be overwritten. */
    private static synchronized void rememberRequestId(String id) {
        for (int i = history.size() - 1; i >= 0; i--) {
            String[] h = history.get(i);
            if ("AWAITING_ID".equals(h[4])) {
                h[4] = id;
                System.out.println("Request ID: " + id);
                return;
            }
        }
    }

    private static synchronized void updateStatusForId(String id, String status) {
        for (String[] h : history) {
            if (h[4].equals(id)) {
                if ("WAITING".equals(h[3])) waiting = false;
                h[3] = status;
                return;
            }
        }
        // Older servers sometimes do not return a request ID.
        if ("UNKNOWN".equals(id)) updateLastStatus(status);
    }

    private static synchronized void updateLastStatus(String status) {
        if (!history.isEmpty()) {
            String[] h = history.get(history.size() - 1);
            if ("WAITING".equals(h[3])) waiting = false;
            h[3] = status;
        }
    }

    private static synchronized boolean hasActiveRequests() {
        for (String[] h : history)
            if ("WAITING".equals(h[3]) || h[3].startsWith("ASSIGNED")) return true;
        return false;
    }

    /** Complete a confirmed transfer, or cancel a pending/assigned request. */
    private static void changeRequest(Scanner sc, PrintWriter out, String ambulanceId,
                                      boolean complete) {
        String expected = complete ? "ASSIGNED" : "WAITING / ASSIGNED";
        System.out.println("Enter the Request ID to " + (complete ? "report arrival for" : "cancel")
                + " (see Option 2 for IDs; state must be " + expected + "): ");
        String requestId = sc.nextLine().trim().toUpperCase();
        if (!requestId.matches("REQ[0-9]+")) {
            System.out.println("Enter a valid server request ID such as REQ000001.");
            return;
        }
        String[] found = null;
        synchronized (AmbulanceClient.class) {
            for (String[] h : history) if (requestId.equals(h[4])) { found = h; break; }
            if (found == null || (complete ? !found[3].startsWith("ASSIGNED")
                    : !(found[3].startsWith("ASSIGNED") || "WAITING".equals(found[3])))) {
                System.out.println("That request is not in the required state.");
                return;
            }
        }
        out.println((complete ? "EMERGENCY_COMPLETE|" : "EMERGENCY_CANCEL|")
                + requestId + "|" + ambulanceId);
        if (out.checkError()) {
            System.out.println("Could not send the update. Check the connection.");
        } else {
            System.out.println("Request update sent. Waiting for server confirmation...");
        }
    }

    private static String proof(String secret, String nonce, String role, String id) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((nonce + "|" + role + "|" + id)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for(byte b : digest) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException("Authentication failure", e); }
    }

    // Display numbered selection menu.
    private static String pick(
            Scanner sc,
            String label,
            String[] options) {

        while (true) {

            System.out.println("\n" + label + ":");

            for (int i = 0; i < options.length; i++) {
                System.out.println(
                        "  " + (i + 1) + ". " + options[i]);
            }

            System.out.print("Select: ");

            try {
                int n = Integer.parseInt(
                        sc.nextLine().trim());

                if (n >= 1 && n <= options.length) {
                    return options[n - 1];
                }

            } catch (NumberFormatException ignored) {
            }

            System.out.println(
                    "Invalid selection. Try again.");
        }
    }
}
