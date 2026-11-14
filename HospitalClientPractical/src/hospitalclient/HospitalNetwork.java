
package hospitalclient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class HospitalNetwork {

	private static final String HOST = System.getProperty("hospital.serverHost", "127.0.0.1");
    private static final int PORT = 5000;

    private static Socket socket;
    private static BufferedReader input;
    private static PrintWriter output;
    private static Thread listener;

    private static final BlockingQueue<String> messages =
            new LinkedBlockingQueue<>();

    public static synchronized boolean connect(Hospital hospital, String token) {

        if (socket != null && !socket.isClosed()) {
            System.out.println("Already connected.");
            return true;
        }

        try {
            boolean tls = Boolean.getBoolean("emergency.tls");
            if (!tls && !HOST.equalsIgnoreCase("localhost") && !HOST.equals("127.0.0.1")) {
                System.out.println("Remote connection blocked: configure TLS and trusted server certificates.");
                return false;
            }
            socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT), 3000);
            socket.setSoTimeout(8000);
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket)socket;
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
            }

            input = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8));

            output = new PrintWriter(
                    new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8),
                    true);

            String greeting = input.readLine();

            String[] welcome = greeting == null ? new String[0] : greeting.split("\\|", -1);
            if (welcome.length != 3 || !"CONNECTED".equals(welcome[0])
                    || !"Emergency Server".equals(welcome[1])) {
                System.out.println("Unexpected greeting: " + greeting);
                close();
                return false;
            }

            System.out.println("Connected to Emergency Server.");

            output.println("HOSPITAL_REGISTER|" + hospital.getHospitalId() + "|"
                    + proof(token, welcome[2], "HOSPITAL", hospital.getHospitalId()));

            String response = input.readLine();

            String expected =
                    "HOSPITAL_REGISTERED|" + hospital.getHospitalId();

            if (!expected.equals(response)) {
                System.out.println("Registration failed: " + response);
                close();
                return false;
            }

            socket.setSoTimeout(0);
            System.out.println("Hospital authenticated (DEMO directory, not real-world verified).");

            messages.clear();

            final BufferedReader reader = input;
            listener = new Thread(() -> {
                try {
                    String message;

                    while ((message = reader.readLine()) != null) {
                        // Every server message must reach HospitalClient.receiveServerMessages().
                        // Previously offers were printed here but never queued, so Options 6/7/8
                        // could not find the request even though the offer was visible.
                        messages.offer(message);
                        // Update the hospital's request list immediately, even while
                        // the operator is waiting at the console menu.
                        HospitalClient.receiveServerMessages();
                    }
                } catch (IOException e) {
                    if (socket != null && !socket.isClosed()) {
                        System.out.println(
                                "\nConnection lost: " + e.getMessage());
                    }
                }
            });

            listener.setDaemon(true);
            listener.start();
            Thread heartbeat = new Thread(() -> {
                while (socket != null && !socket.isClosed()) {
                    try { Thread.sleep(30000); }
                    catch (InterruptedException e) { return; }
                    if (socket == null || socket.isClosed()) break;
                    sendHospitalStatus(hospital);
                    sendCapabilities(hospital);
                    sendAvailability(hospital);
                }
            }, "hospital-inventory-heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            return true;

        } catch (IOException e) {
            System.out.println("Connection failed: " + e.getMessage());
            close();
            return false;
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

    public static synchronized void sendMessage(String message) {

        if (output == null || socket == null || socket.isClosed()) {
            System.out.println("Not connected to the real server.");
            return;
        }

        output.println(message);

        if (output.checkError()) {
            System.out.println("Failed to send message.");
        }
    }

    public static void sendHospitalStatus(Hospital hospital) {
        sendMessage("HOSPITAL_STATUS|" + hospital.getHospitalId()
                + "|" + hospital.getStatus().name());
    }

    /** Advertise clinical capabilities for this demo (not clinically verified). */
    public static void sendCapabilities(Hospital hospital) {
        StringBuilder capabilities = new StringBuilder();
        for (EmergencyType type : EmergencyType.values()) {
            if (hospital.supportsEmergency(type)) {
                if (capabilities.length() > 0) capabilities.append('_');
                capabilities.append(type.name());
            }
        }
        sendMessage("HOSPITAL_CAPABILITIES|" + hospital.getHospitalId() + "|"
                + (capabilities.length() == 0 ? "NONE" : capabilities.toString()));
    }

    public static void sendAvailability(Hospital hospital) {

        sendMessage("BED_UPDATE|"
                + hospital.getHospitalId()
                + "|GENERAL|"
                + hospital.getGeneralBeds());

        sendMessage("BED_UPDATE|"
                + hospital.getHospitalId()
                + "|ICU|"
                + hospital.getIcuBeds());
    }


public static void sendRequestDecision(
        String hospitalId,
        String requestId,
        RequestStatus decision) {

    if (decision == RequestStatus.ACCEPTED) {
        sendMessage("HOSPITAL_ACCEPT|" + requestId + "|" + hospitalId);

    } else if (decision == RequestStatus.REJECTED) {
        sendMessage("HOSPITAL_REJECT|" + requestId + "|" + hospitalId);
    }
}


    public static String getNextMessage() {
        return messages.poll();
    }

    public static synchronized void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            System.out.println("Error closing connection.");
        }

        socket = null;
        input = null;
        output = null;
    }
}
