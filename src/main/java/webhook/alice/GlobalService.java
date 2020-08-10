package webhook.alice;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credential.CredentialApi;
import com.evernym.sdk.vcx.proof.DisclosedProofApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.vcx.VcxApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.JsonPath;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import utils.Common;
import utils.NotificationsRequestDto;
import utils.VcxState;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.logging.Logger;

import static utils.Common.*;
import static utils.Common.getHash;

@Service
public class GlobalService {
    // get logger for demo - INFO configured
    static final Logger log = Common.getDemoLogger();
    static final String tailsFileRoot = System.getProperty("user.home") + "/.indy_client/tails";
    static final String tailsServerUrl = "http://13.124.169.12";

    static final String webhookUrl = "http://localhost:7202/notifications"; // alice (me)
    static final String invitationUrl = "http://localhost:7201/invitations"; // faber

    // check options
    static boolean enablePostgres = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_POSTGRES", "false"));

    @PostConstruct
    public void initialize() throws Exception {
        log.info("#0 Initialize");
        Common.loadNullPayPlugin();

        // static configuration
        long utime = System.currentTimeMillis() / 1000;
        String provisionConfig = JsonPath.parse("{" +
                "  agency_url: 'http://localhost:8080'," + // use local
                "  agency_did: 'VsKV7grR1BUE29mG2Fm2kX'," +
                "  agency_verkey: 'Hezce2UWMZ3wUhVkh2LfKSs8nDzWwzs2Win7EzNN3YaR'," +
                "  wallet_name: 'node_vcx_demo_alice_wallet_" + utime + "'," +
                "  wallet_key: '123'," +
                "  payment_method: 'null'," +
                "  enterprise_seed: '000000000000000000000000000User1'" + // SEED of alice's DID that does not need to be registered in the ledger
                "}").jsonString();

        // Communication method. aries.
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "protocol_type", "4.0").jsonString();
        log.info("Running with Aries VCX Enabled! Make sure VCX agency is configured to use protocol_type 4.0");

        if (enablePostgres) {
            Common.loadPostgresPlugin();
            provisionConfig = JsonPath.parse(provisionConfig).put("$", "wallet_type", "postgres_storage")
                    .put("$", "storage_config", "{\"url\":\"localhost:5432\"}")
                    .put("$", "storage_credentials", "{\"account\":\"postgres\",\"password\":\"mysecretpassword\"," +
                            "\"admin_account\":\"postgres\",\"admin_password\":\"mysecretpassword\"}").jsonString();
            log.info("Running with PostreSQL wallet enabled! Config = " + JsonPath.read(provisionConfig, "$.storage_config"));
        } else {
            log.info("Running with builtin wallet.");
        }

        // add webhook url to config
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "webhook_url", webhookUrl).jsonString();
        log.info("Running with webhook notifications enabled! Webhook url = " + webhookUrl);

        log.info("#8 Provision an agent and wallet, get back configuration details: \n" + prettyJson(provisionConfig));
        String vcxConfig = UtilsApi.vcxProvisionAgent(provisionConfig);

        vcxConfig = JsonPath.parse(vcxConfig).put("$", "institution_name", "alice")
                .put("$", "institution_logo_url", "http://robohash.org/345")
                .put("$", "protocol_version", "2")
                .put("$", "genesis_path", System.getProperty("user.dir") + "/genesis.txn").jsonString();
        log.info("#9 Initialize libvcx with new configuration\n" + prettyJson(vcxConfig));
        VcxApi.vcxInitWithConfig(vcxConfig).get();

        log.config("addRecordWallet (vcxConfig, defaultVcxConfig, " + prettyJson(vcxConfig) + ")");
        WalletApi.addRecordWallet("vcxConfig", "defaultVcxConfig", vcxConfig, "").get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void receiveInvitation() throws Exception {

        // STEP.2 - receive invitation & request connection
        String details = requestGET(invitationUrl);
        log.info("details" + details);

        log.info("#10 Convert to valid json and string and create a connection to faber");
        int connectionHandle = ConnectionApi.vcxCreateConnectionWithInvite("faber", details).get();
        ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get();
        ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

        String connection = ConnectionApi.connectionSerialize(connectionHandle).get();
        String pwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();
        log.config("addRecordWallet (connection, " + pwDid + ", " + prettyJson(connection) + ")");
        WalletApi.addRecordWallet("connection", pwDid, connection, "").get();
        ConnectionApi.connectionRelease(connectionHandle);
    }

    public void handleMessage(NotificationsRequestDto body) throws Exception {
        log.info("handleMessage >>> body: " + body.toString());

        // Get the message from mediator using notification information
        String messages = UtilsApi.vcxGetMessages(body.getMsgStatusCode(), body.getMsgUid(), body.getPwDid()).get();
        String msgUid = body.getMsgUid();
        log.info( "Messages: " +  messages);
        String message = JsonPath.parse((LinkedHashMap)JsonPath.read(messages,"$.[0].msgs[0]")).jsonString();
        String decryptedPayload = JsonPath.read(message, "$.decryptedPayload");
        String payloadMessage = JsonPath.read(decryptedPayload,"$.@msg");
        String type = JsonPath.read(decryptedPayload,"$.@type.name");

        // pwDid is used as a connectionId
        String pwDid = JsonPath.read(messages,"$.[0].pairwiseDID");
        String connectionRecord = WalletApi.getRecordWallet("connection", pwDid, "").get();
        String serializedConnection = JsonPath.read(connectionRecord,"$.value");
        int connectionHandle = ConnectionApi.connectionDeserialize(serializedConnection).get();

        switch(type) {
            case "aries":
                String innerType = JsonPath.read(payloadMessage,"$.@type");

                // STEP.4 - connection created
                if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response")){
                    log.info("- Case(aries ,connections/1.0/response) -> sendConnectionAck");
                    sendConnectionAck(connectionHandle, pwDid);
                }
                // STEP.14 - receive proof ACK
                else if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/ack")){
                    log.info("- Case(aries ,present-proof/1.0/ack) -> receiveProofAck");
                    receiveProofAck(connectionHandle, payloadMessage);
                }
                // SETP.14-1 - receive problem-report (possibly the credential has been revoked)
                else if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/report-problem/1.0/problem-report")){
                    log.info("- Case(aries ,report-problem/1.0/problem-report) -> printProblem");
                    log.info("comment: " + JsonPath.read(payloadMessage, "$.comment"));
                }
                else {
                    log.severe("Unknown innerType message in aries type");
                }
                break;
            // STEP.7 - check credential offer & request credential
            case "credential-offer":
                log.info("- Case(credential-offer) -> sendCredentialRequest");
                sendCredentialRequest(connectionHandle, pwDid, msgUid);
                break;
            // STEP.9 - accept credential
            case "credential":
                log.info("- Case(credential) -> acceptCredential");
                acceptCredential(connectionHandle, payloadMessage);
                break;
            // STEP.12 - send proof
            case "presentation-request":
                log.info("- Case(presentation-request) -> sendProof");
                sendProof(connectionHandle, pwDid, msgUid);
                break;
            default:
                log.severe("Unknown type message");

        }
        ConnectionApi.connectionRelease(connectionHandle);
    }

    void sendConnectionAck(int connectionHandle, String pwDid) throws Exception {
        int connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

        if (connectionState == VcxState.Accepted.getValue()) {
            String serializedConnection = ConnectionApi.connectionSerialize(connectionHandle).get();
            log.config("updateRecordWallet (connection, " + pwDid + ", " + prettyJson(serializedConnection) + ")");
            WalletApi.updateRecordWallet("connection", pwDid, serializedConnection).get();
        }
        else {
            log.severe("Unexpected state type");
        }
    }

    void sendCredentialRequest(int connectionHandle, String pwDid, String msgUid) throws Exception {
        String offers = CredentialApi.credentialGetOffers(connectionHandle).get();
        String credentialOffer = JsonPath.parse((LinkedHashMap)JsonPath.read(offers, "$.[0]")).jsonString();
        log.info("credential offer:\n" + prettyJson(credentialOffer));

        // Update agency message status manually (xxxUpdateState automatically update message status, but not here)
        UtilsApi.vcxUpdateMessages("MS-106",
                "[{\"pairwiseDID\":\"" + pwDid + "\",\"uids\":[\"" + msgUid + "\"]}]");

        // Create a credential object from the credential offer
        int credentialHandle = CredentialApi.credentialCreateWithOffer("credential", credentialOffer).get();

        log.info("#15 After receiving credential offer, send credential request");
        CredentialApi.credentialSendRequest(credentialHandle, connectionHandle, 0).get();

        //Serialize the object
        String serializedCredential = CredentialApi.credentialSerialize(credentialHandle).get();
        String threadId = JsonPath.read(serializedCredential, "$.data.holder_sm.thread_id");
        log.config("addRecordWallet (credential, " + threadId + ", " + prettyJson(serializedCredential) + ")");
        WalletApi.addRecordWallet("credential", threadId, serializedCredential, "").get();

        CredentialApi.credentialRelease(credentialHandle);
    }

    void acceptCredential(int connectionHandle, String payloadMessage) throws Exception {
        String threadId = JsonPath.read(payloadMessage, "$.~thread.thid");
        String credentialRecord = WalletApi.getRecordWallet("credential", threadId, "").get();
        String serializedCredential = JsonPath.read(credentialRecord, "$.value");

        // TODO: Must replace connection_handle in credential - Need to consider better way
        serializedCredential = JsonPath.parse(serializedCredential)
                .set("$.data.holder_sm.state.RequestSent.connection_handle", Integer.toUnsignedLong(connectionHandle))
                .jsonString();

        int credentialHandle = CredentialApi.credentialDeserialize(serializedCredential).get();

        int credentialState = CredentialApi.credentialUpdateState(credentialHandle).get();
        if (credentialState == VcxState.Accepted.getValue()) {
            log.info("#16 Accepted credential from faber");
        }
        else {
            log.severe("Unexpected state type");
        }

        //Serialize the object
        serializedCredential = CredentialApi.credentialSerialize(credentialHandle).get();
        log.config("updateRecordWallet (credential, " + threadId + ", " + prettyJson(serializedCredential) + ")");
        WalletApi.updateRecordWallet("credential", threadId, serializedCredential).get();

        CredentialApi.credentialRelease(credentialHandle);
    }

    void sendProof(int connectionHandle, String pwDid, String msgUid) throws Exception {
        String requests = DisclosedProofApi.proofGetRequests(connectionHandle).get();
        String proofRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(requests, "$.[0]")).jsonString();
        log.info("proof request:\n" + prettyJson(proofRequest));

        // Update agency message status manually (xxxUpdateState automatically update message status, but not here)
        UtilsApi.vcxUpdateMessages("MS-106",
                "[{\"pairwiseDID\":\"" + pwDid + "\",\"uids\":[\"" + msgUid + "\"]}]");

        log.info("#23 Create a Disclosed proof object from proof request");
        int proofHandle = DisclosedProofApi.proofCreateWithRequest("proof", proofRequest).get();

        log.info("#24 Query for credentials in the wallet that satisfy the proof request");
        String credentials = DisclosedProofApi.proofRetrieveCredentials(proofHandle).get();

        LinkedHashMap<String, Object> attrs = JsonPath.read(credentials, "$.attrs");
        for(String key : attrs.keySet()) {
            // use first credential (just one credential in demo)
            String attr = JsonPath.parse((LinkedHashMap)JsonPath.read(credentials, "$.attrs." + key + ".[0]")).jsonString();
            credentials = JsonPath.parse(credentials).set("$.attrs." + key, JsonPath.parse("{\"credential\":"+ attr + "}").json()).jsonString();

            // prepare tails file and add attribute
            String revRegId = JsonPath.read(attr, "$.cred_info.rev_reg_id");
            String tailsFileDir = tailsFileRoot + "/" + revRegId;
            if (Files.notExists(Paths.get(tailsFileDir))) {
                // get tails file from tails file server
                byte[] fileContent = requestGETtoBytes(tailsServerUrl + "/" + revRegId);

                // get file name by hashing
                String tailsFileName = getHash(fileContent);

                // write tails file into tailsFileDir
                String tailsFilePath = tailsFileDir + "/" + tailsFileName;
                Files.createDirectory(Paths.get(tailsFileDir));
                Files.write(Paths.get(tailsFilePath), fileContent);
            }
            credentials = JsonPath.parse(credentials).put("$.attrs." + key, "tails_file", tailsFileDir).jsonString();
        }

        log.info("#25 Generate the proof");
        DisclosedProofApi.proofGenerate(proofHandle, credentials, "{}").get();

        log.info("#26 Send the proof to faber");
        DisclosedProofApi.proofSend(proofHandle, connectionHandle).get();

        //Serialize the object
        String serializedProof = DisclosedProofApi.proofSerialize(proofHandle).get();
        String threadId = JsonPath.read(serializedProof,"$.data.prover_sm.thread_id");
        log.config("addRecordWallet (proof, " + threadId + ", " + prettyJson(serializedProof) + ")");
        WalletApi.addRecordWallet("proof", threadId, serializedProof, "").get();

        DisclosedProofApi.proofRelease(proofHandle);
    }

    void receiveProofAck(int connectionHandle, String payloadMessage) throws Exception {
        String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
        String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
        String proof = JsonPath.read(proofRecord,"$.value");

        // TODO: Must replace connection_handle in credential - Need to consider better way
        proof = JsonPath.parse(proof)
                .set("$.data.prover_sm.state.PresentationSent.connection_handle", Integer.toUnsignedLong(connectionHandle))
                .jsonString();

        int proofHandle = DisclosedProofApi.proofDeserialize(proof).get();
        int proofState = DisclosedProofApi.proofUpdateState(proofHandle).get();

        if (proofState == VcxState.Accepted.getValue()) {
            log.info("Faber received the proof");
        }
        else if (proofState == VcxState.None.getValue()) {
            log.info("Faber denied the proof (possibly the credential has been revoked)");
            System.exit(0);
        }
        else {
            log.severe("Unexpected state type");
        }

        String serializedProof = DisclosedProofApi.proofSerialize(proofHandle).get();
        log.config("updateRecordWallet (proof, " + threadId + ", " + prettyJson(serializedProof) + ")");
        WalletApi.updateRecordWallet("proof", threadId, serializedProof).get();

        DisclosedProofApi.proofRelease(proofHandle);

        // Supposed here is the end
        log.info("Alice demo is completed (Exit manually)");
    }
}
