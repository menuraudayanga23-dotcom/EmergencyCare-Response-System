package server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Server-owned directory. Sample entries are approved ONLY for the classroom demo. */
public final class TrustedDirectory {
    public static final class Profile {
        public final String id, role, name, region;
        public final Set<String> services, bedTypes;
        public final int maxBeds;
        public final boolean approved;
        private final String secret;
        private Profile(String id, String role, String name, String region,
                        Set<String> services, Set<String> bedTypes, int maxBeds,
                        boolean approved, String secret) {
            this.id=id; this.role=role; this.name=name; this.region=region;
            this.services=services; this.bedTypes=bedTypes;
            this.maxBeds=maxBeds; this.approved=approved; this.secret=secret;
        }
    }
    private final Map<String,Profile> profiles = new HashMap<>();
    private final boolean demo;
    private boolean hasSampleTokens;

    public TrustedDirectory() throws IOException {
        Properties properties = new Properties();
        String file = System.getProperty("emergency.directory", "").trim();
        demo = file.isEmpty();
        if (demo) {
            try (InputStream in = TrustedDirectory.class.getResourceAsStream("demo_directory.properties")) {
                if (in == null) throw new IOException("Missing demo_directory.properties. Import all server source resources.");
                properties.load(in);
            }
        } else {
            try (InputStream in = Files.newInputStream(Path.of(file))) { properties.load(in); }
        }
        for (String name : properties.stringPropertyNames()) {
            if (!name.endsWith(".token")) continue;
            String prefix = name.substring(0, name.length()-6);
            String[] p = prefix.split("\\.", -1);
            if (p.length != 2 || !(p[0].equals("hospital") || p[0].equals("ambulance")))
                throw new IOException("Invalid directory entry: " + name);
            String token = properties.getProperty(name, "").trim();
            if (token.startsWith("DEMO_")) hasSampleTokens=true;
            if (token.length() < 12) throw new IOException("Token too short for " + p[1]);
            String base = prefix + ".";
            Set<String> services = parseList(properties.getProperty(base + "services", ""));
            Set<String> beds = parseList(properties.getProperty(base + "beds", ""));
            int maxBeds;
            try { maxBeds = Integer.parseInt(properties.getProperty(base+"maxBeds", "0")); }
            catch (NumberFormatException e) { throw new IOException("Invalid maxBeds for " + p[1], e); }
            boolean approved = Boolean.parseBoolean(properties.getProperty(base+"approved", "false"));
            Profile profile = new Profile(p[1], p[0].toUpperCase(),
                    properties.getProperty(base+"name", p[1]),
                    properties.getProperty(base+"region", "UNKNOWN"),
                    services, beds, maxBeds, approved, token);
            profiles.put(p[0].toUpperCase()+":"+p[1], profile);
        }
        if (profiles.isEmpty()) throw new IOException("No approved clients in directory");
    }
    private static Set<String> parseList(String source) {
        if (source.isBlank()) return Collections.emptySet();
        Set<String> items = new HashSet<>();
        for (String s : source.split(",")) {
            s=s.trim(); if (!s.matches("[A-Z_]+")) throw new IllegalArgumentException("Bad directory value: " + s);
            items.add(s);
        }
        return Collections.unmodifiableSet(items);
    }
    public Profile find(String role, String id) { return profiles.get(role+":"+id); }
    public boolean isDemo() { return demo; }
    public boolean usesSampleTokens() { return hasSampleTokens; }
    public boolean verify(String role, String id, String nonce, String providedProof) {
        Profile p=find(role,id);
        if (p == null || !p.approved || !providedProof.matches("[a-f0-9]{64}")) return false;
        try {
            byte[] correct = hmac(p.secret, nonce + "|" + role + "|" + id);
            return MessageDigest.isEqual(correct, hexDecode(providedProof));
        } catch (Exception e) { return false; }
    }
    public static String proof(String secret, String nonce, String role, String id) {
        try {
            byte[] value=hmac(secret,nonce+"|"+role+"|"+id);
            StringBuilder s=new StringBuilder();
            for (byte b:value) s.append(String.format("%02x", b & 255));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException("Authentication unavailable",e); }
    }
    private static byte[] hmac(String secret,String message) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] hexDecode(String hex) {
        byte[] bytes=new byte[hex.length()/2];
        for (int i=0;i<bytes.length;i++) bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return bytes;
    }
}
