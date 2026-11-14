package server;

import java.io.IOException;
import java.net.Socket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import javax.net.ssl.SSLServerSocketFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EmergencyServer {

    private static final int PORT = 5000;
    private static final Logger LOG = Logger.getLogger(EmergencyServer.class.getName());

    public static void main(String[] args) {

        System.out.println("====================================");
        System.out.println(" Emergency Coordination Server");
        System.out.println("====================================");

        try {
            boolean legacyTests = Boolean.getBoolean("emergency.legacyTests");
            TrustedDirectory directory = legacyTests ? null : new TrustedDirectory();
            boolean tls = Boolean.getBoolean("emergency.tls");
            if (tls && (legacyTests || directory.isDemo() || directory.usesSampleTokens()))
                throw new IllegalStateException("Remote TLS mode requires -Demergency.directory=approved_file. No demo directory or publicly known demo tokens are allowed remotely.");
            if (tls && System.getProperty("javax.net.ssl.keyStore")==null)
                throw new IllegalStateException("TLS requires javax.net.ssl.keyStore and password");
            ClientRegistry registry = new ClientRegistry(directory);
            ServerSocket listener = tls ? SSLServerSocketFactory.getDefault().createServerSocket(PORT)
                    : new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
            try (EventStore audit = legacyTests ? null : new EventStore(Path.of(System.getProperty("emergency.auditFile", "emergency-audit.tsv")));
                 EmergencyManager emergencies = new EmergencyManager(registry,
                         Long.getLong("emergency.offerTimeoutMillis", 240000L),
                         Long.getLong("emergency.assignmentTimeoutMillis", 300000L), audit);
                 ServerSocket serverSocket = listener) {
            System.out.println(tls ? "TLS server mode: remote network" : "LOCALHOST ONLY: unencrypted classroom simulation");
            System.out.println(legacyTests ? "INSECURE LEGACY TEST MODE: localhost only" :
                    directory.isDemo() ? "CLASSROOM DEMO clients only - hospital information NOT verified" :
                    "Administrator directory loaded; approval must be validated outside this software");
            if(audit!=null)for(String hospital: audit.unresolvedHospitals()){
                registry.quarantine(hospital);
                System.out.println("SAFETY QUARANTINE: " + hospital + " cannot receive new offers until offline reconciliation.");
            }
            long unresolved=audit==null?0:audit.states().values().stream().filter(x->x.equals("REQUIRES_REVIEW")).count();
            if(unresolved>0) System.out.println("WARNING: " + unresolved + " request(s) require manual review after restart. See emergency-audit.tsv.");
            MessageRouter router = new MessageRouter(registry, emergencies);

            LOG.info("Server started on port " + PORT);
            System.out.println("Waiting for clients...");

            while (true) {

                Socket clientSocket = serverSocket.accept();

                LOG.info("Client connected: " + clientSocket.getInetAddress().getHostAddress()
                        + ":" + clientSocket.getPort());

                ClientHandler handler =
                        new ClientHandler(clientSocket, registry, router);

                Thread thread =
                        new Thread(handler, "client-" + clientSocket.getRemoteSocketAddress());

                thread.start();
            }

            }
        } catch (Exception e) {

            LOG.log(Level.SEVERE, "Server error", e);
        }
    }
}
