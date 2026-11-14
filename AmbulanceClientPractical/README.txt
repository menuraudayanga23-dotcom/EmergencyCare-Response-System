AmbulanceClientPractical

# Emergency Hospital Coordination - PRACTICAL V3 (Educational Prototype)

IMPORTANT: Not approved for actual emergency operations, dispatch, medical decisions, or patient-care use. All hospital identities and sample tokens are invented. Hospital registration and reported beds are NOT independently verified. Human operators must use official emergency channels for real emergencies.

## Features implemented
- Three independently running Java applications: EmergencyServer, HospitalClient, AmbulanceClient, compatible with Java 21+ (tested here with Java 21).
- Continuous ambulance requests, unique server-generated request IDs, history, acceptance/rejection, cancellation and staff-confirmed arrival.
- Registered demo hospitals with stated service capabilities, regions, approved bed types and maximum inventory limits.
- Check of hospital status, service compatibility, currently reported available beds (and freshness), and approximate region preference. Region is NOT road distance, travel time, or clinical triage.
- HMAC-SHA256 nonce-based client authentication with administrator-owned directory of allowed IDs; sample tokens are insecure demo credentials.
- Local-only unencrypted classroom mode; optional TLS for remote deployment using a trusted server certificate, separate private client tokens and an administrator directory. TLS server mode refuses the included demo directory or demo token values.
- Append-only fsync server event journal, persistent request IDs, cautious restart recovery with human review/quarantine of unresolved assignments, and offline recovery command.
- Local hospital bed inventory saved to disk and arrival confirmation by hospital staff.

## Quick start in Eclipse (one Windows computer)
1. Extract COMPLETE zip. File > Import > General > Existing Projects into Workspace > Select root directory (directory containing the three project folders) > select all three. Or select archive ZIP if supported. DO NOT merge into old projects without a backup.
2. No module-info.java is needed. If Eclipse imports a named module by mistake, delete module-info.java. JRE: Java 21 or newer (matching an installed JDK). Ensure `demo_directory.properties` remains under EmergencyServerPractical/src/server/.
3. Run `server.EmergencyServer` from EmergencyServerPractical. Console must say `LOCALHOST ONLY` and `Waiting for clients...`. Port 5000 must be free.
4. Run `hospitalclient.HospitalClient` from HospitalClientPractical. Choose menu 1, hospital 1 for H001, enter token `DEMO_H001_TOKEN_2026`. It sends hospital status, capabilities, and beds to the server. Change beds using menu 3 or 4. It must be AVAILABLE.
5. Run `AmbulanceClient` from AmbulanceClientPractical. Press Enter for server localhost; ID `AMB001`; token `DEMO_AMB001_TOKEN_2026`.
6. In ambulance menu choose 1, CARDIAC, CRITICAL, ICU, region GALLE. In hospital console select 6 to view received request; 7 to accept with exact REQ number. In ambulance console see `HOSPITAL_ASSIGNED`.
7. To verify arrival, ambulance menu 3 with assigned REQ; hospital menu 11 with that REQ and type `YES` after simulated staff verification. To release a held bed when patient never arrived, hospital menu 12 after confirming circumstances. NEVER mark arrival if unsure.
8. Ambulance menu 1 sends another request, without restarting programs; hospital menu 6/7/8 handles subsequent requests.
9. Servers and clients each need a separate running Console in Eclipse. Use Console's 'Display Selected Console' dropdown; do not terminate apps while switching consoles.

## Demo directory, identity, and deployment boundary
The server directory in `src/server/demo_directory.properties` contains sample H001/H002/H003 hospitals and AMB001/AMB002 credentials. These are DEMONSTRATIONS ONLY; no real hospital/ambulance has been checked. Non-demo use requires external verification by authorized administrators and a private directory with independently provisioned secrets, plus an operational protocol. THIS SOFTWARE DOES NOT COMPLETE EXTERNAL VERIFICATION.

Without TLS, the server binds ONLY to 127.0.0.1 (no multi-laptop plain-text network). Multi-laptop testing requires setting -Demergency.tls=true on each application, a secure keystore for server, a client truststore with verified server certificate, -Demergency.directory=full_path for a private client directory, and the hospital IP via -Dhospital.serverHost=server_hostname; ambulance enters server hostname at its prompt. Avoid public sample tokens, self-signed/unverified cert trust, and public network exposure.

## Server audit and crash safety
The server writes `emergency-audit.tsv` in its working directory. Do NOT delete it casually: it preserves request IDs and assignment state. On unexpected shutdown it marks nonterminal requests REQUIRES_REVIEW; hospitals with unresolved assignments are quarantined on restart. Authorized personnel must inspect real evidence and reconcile offline before future matching. The `server.AuditReconcile` command can record `NO_ARRIVAL` or `ARRIVED`; it is a manually approved operator action and must NOT be automated. Keep audit records private, backed up, access controlled and compliant with the applicable records policy.

## Limitations requiring REAL validation before deployment
- Demo directory and hospital services are NOT verified; hospital updates are manually reported and not synchronized with a real hospital information system.
- No GPS/geocoding, road ETA, traffic, EMS dispatch authorization, national ambulance integration, clinical triage protocols, physician signoff, or patient record system.
- No HA cluster, shared transactional database, exactly-once delivery/idempotency guarantees, operational monitoring/alerting, disaster-recovery proof, access-control administration UI, or independent penetration test.
- Local log/credentials, audit retention, privacy and regulatory/security requirements need professional design and governance; do NOT enter real patient data into this prototype.
- TLS test demonstrates transport encryption and server certificate verification mechanics, NOT a security certification or medical device clearance.
- A hospital cannot be assumed clinically suitable solely because type and bed count match. Human clinical decisions and real-time capacity confirmation are mandatory.
- Not safe for live emergency coordination or real patient decisions.

## Automated tests (requires Python 3, JDK, and no other server on port 5000)
Tests executed during preparation (test harness is not included in the ZIP): Java compilation, 75 Phase1 and 111 Phase2 regression checks, three-process loop/arrival/rejection/restart/auth tests, edge cases (staleness, quarantine, regional fallbacks), plus Java TLS handshake.
