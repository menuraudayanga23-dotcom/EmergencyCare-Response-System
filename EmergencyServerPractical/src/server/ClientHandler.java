package server;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.logging.*;

public class ClientHandler implements Runnable {
    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());
    private final Socket socket;
    private final ClientRegistry registry;
    private final MessageRouter router;
    private final Object sendLock = new Object();
    private PrintWriter output;
    private String clientId;
    private ClientRegistry.Role role;
    private final String nonce = makeNonce();
    private static String makeNonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format("%02x", b & 255));
        return text.toString();
    }

    public ClientHandler(Socket socket, ClientRegistry registry, MessageRouter router) {
        this.socket = socket; this.registry = registry; this.router = router;
    }
    @Override
    public void run() {
        try (Socket connection = socket;
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     connection.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                     connection.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setSoTimeout(15000); // Limit time spent by unregistered sockets.
            synchronized (sendLock) { output = writer; }
            sendMessage("CONNECTED|Emergency Server" + (registry.authenticationRequired() ? "|" + nonce : ""));
            String message;
            while ((message = readBoundedLine(input)) != null) handleMessage(message);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Socket error for " + socket.getRemoteSocketAddress(), e);
        } finally {
            if (clientId != null) registry.remove(clientId, this);
            if (router.getEmergencyManager() != null) router.getEmergencyManager().disconnected(this);
            synchronized (sendLock) { output = null; }
            LOG.info("Client disconnected: " + socket.getRemoteSocketAddress());
        }
    }
    /** Reject unbounded input instead of allocating arbitrary-length protocol lines. */
    private static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') {
                if (line.length() > 0 && line.charAt(line.length()-1) == '\r')
                    line.setLength(line.length()-1);
                return line.toString();
            }
            if (line.length() >= 512) throw new IOException("Protocol message exceeds 512 characters");
            line.append((char)next);
        }
        return line.length() == 0 ? null : line.toString();
    }

    /** Serializes complete lines from handler and routing threads. */
    public boolean sendMessage(String message) {
        synchronized (sendLock) {
            if (output == null || socket.isClosed()) return false;
            output.println(message);
            if (!output.checkError()) return true;
            LOG.warning("Socket write failed for " + socket.getRemoteSocketAddress());
            try { socket.close(); }
            catch (IOException e) { LOG.log(Level.WARNING, "Socket close failed", e); }
            return false;
        }
    }
    private void handleMessage(String message) {
        String[] parts = message.split("\\|", -1);
        String command = parts[0];
        int expected;
        switch (command) {
            case "AMBULANCE_REGISTER":
            case "HOSPITAL_REGISTER": expected = registry.authenticationRequired() ? 3 : 2; break;
            case "BED_UPDATE": expected = 4; break;
            case "HOSPITAL_CAPABILITIES": expected = 3; break;
            case "HOSPITAL_STATUS": expected = 3; break;
            case "EMERGENCY_REQUEST": expected = registry.authenticationRequired() ? 6 : (parts.length == 5 ? 5 : 4); break;
            case "HOSPITAL_CONFIRM_ARRIVAL":
            case "HOSPITAL_RELEASE_HOLD":
            case "HOSPITAL_ACCEPT":
            case "HOSPITAL_REJECT":
            case "EMERGENCY_CANCEL":
            case "EMERGENCY_COMPLETE":
            case "EMERGENCY_STATUS": expected = 3; break;
            default: sendMessage("ERROR|Unknown command"); return;
        }
        if (parts.length != expected) { sendMessage("ERROR|Invalid message format"); return; }
        for (String field : parts) {
            if (!field.matches("[A-Za-z0-9_-]+")) {
                sendMessage("ERROR|Invalid or missing field"); return;
            }
        }
        if (command.endsWith("_REGISTER")) {
            register(parts[1], command.equals("AMBULANCE_REGISTER")
                    ? ClientRegistry.Role.AMBULANCE : ClientRegistry.Role.HOSPITAL,
                    parts.length == 3 ? parts[2] : "");
            return;
        }
        if (command.equals("HOSPITAL_CAPABILITIES")) {
            if (role != ClientRegistry.Role.HOSPITAL || !parts[1].equals(clientId)
                    || !registry.updateCapabilities(clientId, this, parts[2])) {
                sendMessage("ERROR|Invalid hospital capabilities");
                return;
            }
            sendMessage("CAPABILITIES_RECEIVED");
            return;
        }
        if (command.equals("HOSPITAL_STATUS")) {
            if (role != ClientRegistry.Role.HOSPITAL || !parts[1].equals(clientId)
                    || !registry.updateHospitalStatus(clientId, this, parts[2])) {
                sendMessage("ERROR|Invalid hospital status");
                return;
            }
            sendMessage("HOSPITAL_STATUS_RECEIVED|" + parts[2]);
            return;
        }
        if (expected == 3) {
            boolean hospitalResponse = command.startsWith("HOSPITAL_");
            ClientRegistry.Role requiredRole = hospitalResponse
                    ? ClientRegistry.Role.HOSPITAL : ClientRegistry.Role.AMBULANCE;
            if (role != requiredRole || !parts[2].equals(clientId)) {
                sendMessage("ERROR|Unauthorized role or sender ID"); return;
            }
            EmergencyManager manager = router.getEmergencyManager();
            if (manager == null) { sendMessage("ERROR|Assignment service unavailable"); return; }
            if (command.equals("HOSPITAL_CONFIRM_ARRIVAL") || command.equals("HOSPITAL_RELEASE_HOLD"))
                manager.hospitalReconcile(this, command, parts[1], parts[2]);
            else if (hospitalResponse) manager.respond(this, parts[1], parts[2], command.equals("HOSPITAL_ACCEPT"));
            else manager.ambulanceCommand(this, command, parts[1], parts[2]);
            return;
        }
        ClientRegistry.Role required = command.equals("BED_UPDATE")
                ? ClientRegistry.Role.HOSPITAL : ClientRegistry.Role.AMBULANCE;
        if (role != required || !parts[1].equals(clientId)) {
            sendMessage("ERROR|Unauthorized role or sender ID"); return;
        }
        if (command.equals("BED_UPDATE")) {
            try {
                int count = Integer.parseInt(parts[3]);
                if (!parts[3].matches("[0-9]+") || count < 0) throw new NumberFormatException();
                if (!registry.updateBeds(clientId, this, parts[2], count)) {
                    sendMessage("ERROR|Registration unavailable or count below reserved beds"); return;
                }
                LOG.info("Bed availability updated for " + clientId + ": " + parts[2] + "=" + count);
                sendMessage("BED_UPDATE_RECEIVED");
            } catch (NumberFormatException e) {
                sendMessage("ERROR|Bed count must be a non-negative integer");
            }
        } else {
            LOG.info("Emergency request received from " + clientId);
            if (parts.length == 4) router.routeEmergency(this, clientId, parts[2], parts[3]);
            else if (router.getEmergencyManager() == null) sendMessage("ERROR|Assignment service unavailable");
            else if (parts.length == 5)
                router.getEmergencyManager().submit(this, clientId, parts[2], parts[3], parts[4]);
            else router.getEmergencyManager().submit(this, clientId, parts[2], parts[3], parts[4], parts[5]);
        }
    }
    private void register(String id, ClientRegistry.Role requestedRole, String proof) {
        // Publish the registration and its acknowledgement under the output lock:
        // a concurrent broadcast must not arrive before the registration response.
        synchronized (sendLock) {
        if (clientId != null || !registry.authenticate(id, requestedRole, nonce, proof)) {
            sendMessage("ERROR|Authentication failed or client not approved");
            LOG.warning("Authentication denied for role " + requestedRole + " (no token logged)");
            return;
        }
        if (!registry.register(id, requestedRole, this)) {
            LOG.info("Duplicate or repeated registration rejected");
            sendMessage("ERROR|Duplicate ID or repeated registration"); return;
        }
        clientId = id; role = requestedRole;
        try { socket.setSoTimeout(0); } catch (IOException e) { LOG.log(Level.WARNING, "Cannot reset socket timeout", e); }
        LOG.info(role + " registered: " + clientId);
        sendMessage(role + "_REGISTERED|" + clientId);
        }
    }
}
