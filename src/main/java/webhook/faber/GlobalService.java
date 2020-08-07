package webhook.faber;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefPrepareForEndorserResult;
import com.evernym.sdk.vcx.issuer.IssuerApi;
import com.evernym.sdk.vcx.proof.GetProofResult;
import com.evernym.sdk.vcx.proof.ProofApi;
import com.evernym.sdk.vcx.schema.SchemaApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.vcx.VcxApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.JsonPath;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import org.springframework.stereotype.Service;
import utils.Common;
import utils.NotificationsRequestDto;
import utils.ProofState;
import utils.VcxState;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Logger;

import static utils.Common.*;

@Service
public class GlobalService {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();

    static final String webhookUrl = "http://localhost:7201/notifications";
    static final String tailsFileRoot = System.getProperty("user.home") + "/.indy_client/tails";
    static final String tailsServerUrl = "http://13.124.169.12";

    @PostConstruct
    public void initialize() throws Exception {
        logger.info("#0 Initialize");
        Common.loadNullPayPlugin();

        long utime = System.currentTimeMillis() / 1000;
        String provisionConfig  = JsonPath.parse("{" +
                "  agency_url: 'http://localhost:8080'," + // use local
                "  agency_did: 'VsKV7grR1BUE29mG2Fm2kX'," +
                "  agency_verkey: 'Hezce2UWMZ3wUhVkh2LfKSs8nDzWwzs2Win7EzNN3YaR'," +
                "  wallet_name: 'node_vcx_demo_faber_wallet_" + utime + "'," +
                "  wallet_key: '123'," +
                "  payment_method: 'null'," +
                "  enterprise_seed: '00000000000000000000000Endorser1'" + // SEED of faber's DID already registered in the ledger
                "}").jsonString();

        // Communication method. aries.
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "protocol_type", "4.0").jsonString();
        logger.info("Running with Aries VCX Enabled! Make sure VCX agency is configured to use protocol_type 4.0");

        // add webhook url to config
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "webhook_url", webhookUrl).jsonString();
        logger.info("Running with webhook notifications enabled! Webhook url = " + webhookUrl);

        logger.info("#1 Config used to provision agent in agency: \n" + prettyJson(provisionConfig));
        String vcxConfig = UtilsApi.vcxProvisionAgent(provisionConfig);

        vcxConfig = JsonPath.parse(vcxConfig).put("$", "institution_name", "faber")
                .put("$", "institution_logo_url", "http://robohash.org/234")
                .put("$", "protocol_version", "2")
                .put("$", "genesis_path", System.getProperty("user.dir") + "/genesis.txn").jsonString();
        logger.info("#2 Using following agent provision to initialize VCX\n" + prettyJson(vcxConfig));
        VcxApi.vcxInitWithConfig(vcxConfig).get();

        logger.config("addRecordWallet (vcxConfig, defaultVcxConfig, " + prettyJson(vcxConfig) + ")");
        WalletApi.addRecordWallet("vcxConfig", "defaultVcxConfig", vcxConfig, "").get();

        createSchema();
        createCredentialDefinition();
        createInviation();

        logger.info("Setting of schema and credential definition is done. Run alice now.");
    }

    public void createSchema() throws Exception {
        // define schema with actually needed
        String version = getRandomInt(1, 99) + "." + getRandomInt(1, 99) + "." + getRandomInt(1, 99);
        String schemaData = JsonPath.parse("{" +
                "  schema_name: 'degree_schema'," +
                "  schema_version: '" + version + "'," +
                "  attributes: ['name', 'last_name', 'date', 'degree', 'age']" +
                "}").jsonString();
        logger.info("#3 Create a new schema on the ledger: \n" + prettyJson(schemaData));
        int schemaHandle = SchemaApi.schemaCreate("schema_uuid",
                JsonPath.read(schemaData, "$.schema_name"),
                JsonPath.read(schemaData, "$.schema_version"),
                JsonPath.parse((List)JsonPath.read(schemaData, "$.attributes")).jsonString(),
                0).get();
        String schemaId = SchemaApi.schemaGetSchemaId(schemaHandle).get();
        logger.info("Created schema with id " + schemaId + " and handle " + schemaHandle);

        String schema = SchemaApi.schemaSerialize(schemaHandle).get();

        logger.config("addRecordWallet - (schema, defaultSchema, " + prettyJson(schema) + ")");
        WalletApi.addRecordWallet("schema", "defaultSchema", schema, "").get();
        SchemaApi.schemaRelease(schemaHandle);
    }

    public void createCredentialDefinition() throws Exception {
        String schemaRecord = WalletApi.getRecordWallet("schema", "defaultSchema", "").get();
        String schema = JsonPath.read(schemaRecord, "$.value");
        String schemaId = JsonPath.read(schema, "$.data.schema_id");
        String version = JsonPath.read(schema, "$.data.version"); // not need same with schema version

        String vcxConfigRecord = WalletApi.getRecordWallet("vcxConfig", "defaultVcxConfig", "").get();
        String vcxConfig = JsonPath.read(vcxConfigRecord, "$.value");
        String faberDid = JsonPath.read(vcxConfig, "$.institution_did");

        // define credential definition with actually needed
        String credDefData = JsonPath.parse("{" +
                "  schemaId: '" + schemaId + "'," +
                "  tag: 'tag." + version + "'," +
                "  config: {" +
                "    support_revocation: true," +
                "    tails_file: '" + tailsFileRoot + "'," + // tails file is created here when credentialDefPrepareForEndorser
                "    max_creds: 10" +
                "  }" +
                "}").jsonString();
        logger.info("#4-1 Create a new credential definition object: \n" + prettyJson(credDefData));
        CredentialDefPrepareForEndorserResult credDefObject = CredentialDefApi.credentialDefPrepareForEndorser("'cred_def_uuid'",
                "cred_def_name",
                JsonPath.read(credDefData, "$.schemaId"),
                null,
                JsonPath.read(credDefData, "$.tag"),
                JsonPath.parse((LinkedHashMap)JsonPath.read(credDefData,"$.config")).jsonString(),
                faberDid).get();

        int credDefHandle = credDefObject.getCredentialDefHandle();
        String credDefTrx = credDefObject.getCredDefTransaction();
        String revRegDefTrx = credDefObject.getRevocRegDefTransaction();
        String revRegId = JsonPath.read(revRegDefTrx, "$.operation.id");
        String tailsFileHash = JsonPath.read(revRegDefTrx, "$.operation.value.tailsHash");
        String revRegEntryTrx = credDefObject.getRevocRegEntryTransaction();

        logger.info("#4-2 Publish credential definition and revocation registry on the ledger");
        UtilsApi.vcxEndorseTransaction(credDefTrx).get();
        // we replace tails file location from local to tails server url
        revRegDefTrx = JsonPath.parse(revRegDefTrx).set("$.operation.value.tailsLocation", tailsServerUrl + "/" + revRegId).jsonString();
        UtilsApi.vcxEndorseTransaction(revRegDefTrx).get();
        UtilsApi.vcxEndorseTransaction(revRegEntryTrx).get();
        int credentialDefState = CredentialDefApi.credentialDefUpdateState(credDefHandle).get();
        if (credentialDefState == 1)
            logger.info("Published successfully");
        else
            logger.warning("Publishing is failed");

        logger.info("#4-3 Upload tails file to tails filer server: " + tailsServerUrl + "/" + revRegId);
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("genesis","genesis.txn",
                        RequestBody.create(new File(System.getProperty("user.dir") + "/genesis.txn"),
                                MediaType.parse("application/octet-stream")))
                .addFormDataPart("tails",tailsFileHash,
                        RequestBody.create(new File(tailsFileRoot + "/" + tailsFileHash),
                                MediaType.parse("application/octet-stream")))
                .build();
        String response = requestPUT(tailsServerUrl + "/" + revRegId, body);
        if (response.equals(tailsFileHash))
            logger.info("Uploaded successfully - tails file: " + tailsFileHash);
        else
            logger.warning("Uploading is failed - tails file: " + tailsFileHash);

        String credDefId = CredentialDefApi.credentialDefGetCredentialDefId(credDefHandle).get();
        logger.info("Created credential with id " + credDefId + " and handle " + credDefHandle);

        String credDef = CredentialDefApi.credentialDefSerialize(credDefHandle).get();

        logger.config("addRecordWallet - (credentialDef, defaultCredentialDef, " + prettyJson(credDef) + ")");
        WalletApi.addRecordWallet("credentialDef", "defaultCredentialDef", credDef, "").get();
        CredentialDefApi.credentialDefRelease(credDefHandle);
    }

    public void createInviation() throws Exception {
        //STEP.1 - create connection F & send invitation
        logger.info("#5 Create a connection to alice and return the invite details");
        int connectionHandle = ConnectionApi.vcxConnectionCreate("alice").get();
        ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get();
        String details = ConnectionApi.connectionInviteDetails(connectionHandle, 0).get();
        logger.info("**invite details**");
        logger.info(details);

        logger.config("addRecordWallet - (invitation, defaultInvitation, " + prettyJson(details) + ")");
        WalletApi.addRecordWallet("invitation", "defaultInvitation", details, "").get();

        String serializedConnection = ConnectionApi.connectionSerialize(connectionHandle).get();
        String pwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();
        logger.config("addRecordWallet - (connection, " + pwDid + ", " + prettyJson(serializedConnection) + ")");
        WalletApi.addRecordWallet("connection", pwDid, serializedConnection, "").get();
        ConnectionApi.connectionRelease(connectionHandle);
    }

    public String getInvitation() throws Exception {
        logger.info("getInvitation >>>");
        String invitationRecord = WalletApi.getRecordWallet("invitation", "defaultInvitation", "").get();
        String invitation = JsonPath.read(invitationRecord,"$.value");
        logger.info("getInvitation <<< invitation:" + invitation);
        return invitation;
    }

    public void handleMessage(NotificationsRequestDto body) throws Exception {
        logger.info("handleMessage >>> body: " + body.toString());

        // Get the message from mediator using notification information
        String messages = UtilsApi.vcxGetMessages(body.getMsgStatusCode(), body.getMsgUid(), body.getPwDid()).get();
        logger.info( "Messages: " +  messages);
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
                // STEP.3 - update connection from F to F2A
                if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/request")) {
                    logger.info("- Case(aries, connections/1.0/request) -> acceptConnectionRequest");
                    acceptConnectionRequest(connectionHandle);
                }
                // STEP.5 - receive connection created ACK
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/notification/1.0/ack")){
                    logger.info("- Case(aries, notification/1.0/ack) -> receiveConnectionAck & sendCredentialOffer");
                    receiveConnectionAck(connectionHandle, pwDid);

                    // STEP.6 - send credential offer
                    sendCredentialOffer(connectionHandle);
                }
                // STEP.8 - send credential
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/request-credential")) {
                    logger.info("- Case(aries ,issue-credential/1.0/request-credential) -> sendCredential");
                    sendCredential(connectionHandle, payloadMessage);
                }
                // STEP.10 - request proof
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/ack")) {
                    logger.info("- Case(aries ,issue-credential/1.0/ac) -> sendProofRequest");
                    sendProofRequest(connectionHandle);
                }
                else {
                    logger.severe("Unknown innerType message in aries type");
                }
                break;
            case "presentation":
                // STEP.12 - receive & verify proof
                logger.info("- Case(presentation)");
                verifyProof(connectionHandle, payloadMessage);
                break;
            default:
                logger.severe("Unknown type message");

        }
        ConnectionApi.connectionRelease(connectionHandle);
    }

    void acceptConnectionRequest(int connectionHandle) throws Exception {
        int connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();
        if (connectionState == VcxState.RequestReceived.getValue()) {
            // new relationship
            String newPwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();

            String serializedConnection = ConnectionApi.connectionSerialize(connectionHandle).get();
            logger.config("addRecordWallet (connection, " + newPwDid + ", " + prettyJson(serializedConnection) + ")");
            WalletApi.addRecordWallet("connection", newPwDid, serializedConnection, "").get();
        }
        else {
            logger.severe("Unexpected state type");
        }
    }

    void receiveConnectionAck(int connectionHandle, String pwDid) throws Exception {
        int connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();
        if (connectionState == VcxState.Accepted.getValue()) {
            String serializedConnection = ConnectionApi.connectionSerialize(connectionHandle).get();
            logger.config("updateRecordWallet (connection, " + pwDid + ", " + prettyJson(serializedConnection) + ")");
            WalletApi.updateRecordWallet("connection", pwDid, serializedConnection).get();
        }
        else {
            logger.severe("Unexpected state type");
        }
    }

    void sendCredentialOffer(int connectionHandle) throws Exception {
        String schemaAttrs = JsonPath.parse("{" +
                "  name: 'alice'," +
                "  last_name: 'clark'," +
                "  date: '05-2018'," +
                "  degree: 'maths'," +
                "  age: '25'" +
                "}").jsonString();

        logger.info("#12 Create an IssuerCredential object using the schema and credential definition\n"
                + prettyJson(schemaAttrs));

        String credDefRecord = WalletApi.getRecordWallet("credentialDef", "defaultCredentialDef", "").get();
        String credDef = JsonPath.read(credDefRecord,"$.value");
        int credDefHandle = CredentialDefApi.credentialDefDeserialize(credDef).get();

        int credentialHandle = IssuerApi.issuerCreateCredential("alice_degree",
                credDefHandle,
                null,
                schemaAttrs,
                "cred",
                0).get();
        logger.info("#13 Issue credential offer to alice");
        IssuerApi.issuerSendCredentialOffer(credentialHandle, connectionHandle).get();

        String serializedCredential = IssuerApi.issuerCredentialSerialize(credentialHandle).get();
        String threadId = JsonPath.read(serializedCredential,"$.data.issuer_sm.state.OfferSent.thread_id");
        logger.config("addRecordWallet (credential, " + threadId + ", " + prettyJson(serializedCredential) + ")");
        WalletApi.addRecordWallet("credential", threadId, serializedCredential, "").get();

        IssuerApi.issuerCredentialRelease(credentialHandle);
        CredentialDefApi.credentialDefRelease(credDefHandle);
    }

    void sendCredential(int connectionHandle, String payloadMessage) throws Exception {
        String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
        String credentialRecord = WalletApi.getRecordWallet("credential", threadId, "").get();
        String serializedCredential = JsonPath.read(credentialRecord,"$.value");

        // TODO: Must replace connection_handle in credential - Need to consider better way
        serializedCredential = JsonPath.parse(serializedCredential)
                .set("$.data.issuer_sm.state.OfferSent.connection_handle", Integer.toUnsignedLong(connectionHandle))
                .jsonString();

        int credentialHandle = IssuerApi.issuerCredentialDeserialize(serializedCredential).get();
        int credentialState = IssuerApi.issuerCredentialUpdateState(credentialHandle).get();

        if (credentialState == VcxState.RequestReceived.getValue()) {
            logger.info("#17 Issue credential to alice");
            IssuerApi.issuerSendCredential(credentialHandle, connectionHandle).get();

            serializedCredential = IssuerApi.issuerCredentialSerialize(credentialHandle).get();
            logger.config("updateRecordWallet (credential, " + threadId + ", " + prettyJson(serializedCredential) + ")");
            WalletApi.updateRecordWallet("credential", threadId, serializedCredential).get();
        }
        else {
            logger.severe("Unexpected state type");
        }
        IssuerApi.issuerCredentialRelease(credentialHandle);
    }

    void sendProofRequest(int connectionHandle) throws Exception {
        String vcxConfigRecord = WalletApi.getRecordWallet("vcxConfig", "defaultVcxConfig", "").get();
        String vcxConfig = JsonPath.read(vcxConfigRecord,"$.value");

        String proofAttributes = JsonPath.parse("[" +
                "  {" +
                "    names: ['name', 'last_name']," +
                "    restrictions: [{ issuer_did: " + JsonPath.read(vcxConfig, "$.institution_did") + " }]" +
                "  }," +
                "  {" +
                "    name: 'date'," +
                "    restrictions: { issuer_did: " + JsonPath.read(vcxConfig, "$.institution_did") + " }" +
                "  }," +
                "  {" +
                "    name: 'degree'," +
                "    restrictions: { 'attr::degree::value': 'maths' }" +
                "  }" +
                "]").jsonString();

        String proofPredicates = JsonPath.parse("[" +
                "  {" +
                "    name: 'age'," +
                "    p_type: '>='," +
                "    p_value: 20," +
                "    restrictions: [{ issuer_did: " + JsonPath.read(vcxConfig, "$.institution_did") + " }]" +
                "  }" +
                "]").jsonString();

        long curUnixTime = System.currentTimeMillis() / 1000L;
        String revocationInterval = "{\"to\": " + curUnixTime + "}";

        logger.info("#19 Create a Proof object\n" +
                "proofAttributes: " + prettyJson(proofAttributes) + "\n" +
                "proofPredicates: " + prettyJson(proofPredicates) + "\n" +
                "revocationInterval: " + prettyJson(revocationInterval));
        int proofHandle = ProofApi.proofCreate("proof_uuid",
                proofAttributes,
                proofPredicates,
                revocationInterval,
                "proof_from_alice").get();

        logger.info("#20 Request proof of degree from alice");
        ProofApi.proofSendRequest(proofHandle, connectionHandle).get();

        String serializedProof = ProofApi.proofSerialize(proofHandle).get();
        String threadId = JsonPath.read(serializedProof,"$.data.verifier_sm.state.PresentationRequestSent.presentation_request.@id");
        logger.config("addRecordWallet (proof, " + threadId + ", " + prettyJson(serializedProof) + ")");
        WalletApi.addRecordWallet("proof", threadId, serializedProof, "").get();

        ProofApi.proofRelease(proofHandle);
    }

    void verifyProof(int connectionHandle, String payloadMessage) throws Exception {
        String threadId = JsonPath.read(payloadMessage, "$.~thread.thid");
        String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
        String serializedProof = JsonPath.read(proofRecord, "$.value");

        // TODO: Must replace connection_handle in proof - Need to consider better way
        serializedProof = JsonPath.parse(serializedProof)
                .set("$.data.verifier_sm.state.PresentationRequestSent.connection_handle", Integer.toUnsignedLong(connectionHandle))
                .jsonString();

        int proofHandle = ProofApi.proofDeserialize(serializedProof).get();
        int proofState = ProofApi.proofUpdateState(proofHandle).get();

        if (proofState == VcxState.Accepted.getValue()) {
            logger.info("#27 Process the proof provided by alice");
            GetProofResult proofResult = ProofApi.getProof(proofHandle, connectionHandle).get();

            logger.info("#28 Check if proof is valid");
            if (proofResult.getProof_state() == ProofState.Validated.getValue()) {
                String encodedProof = JsonPath.read(proofResult.getResponse_data(), "$.presentations~attach.[0].data.base64");
                String decodedProof = decodeBase64(encodedProof);
                String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(decodedProof, "$.requested_proof")).jsonString();
                logger.info("Requested proof:" + prettyJson(requestedProof));
                logger.info("Proof is verified");
            }
            else if (proofResult.getProof_state() == ProofState.Invalid.getValue()) {
                logger.warning("Proof verification failed. credential has been revoked");
            }
            else {
                logger.warning("Unexpected proof state" + proofResult.getProof_state());
            }
        }
        else if (proofState == VcxState.None.getValue()) {
            logger.info("Incorrect proof is received");
        }
        else {
            logger.severe("Unexpected state type");
        }

        serializedProof = ProofApi.proofSerialize(proofHandle).get();
        logger.config("updateRecordWallet (proof, " + threadId + ", " + prettyJson(serializedProof) + ")");
        WalletApi.updateRecordWallet("proof", threadId, serializedProof).get();

        ProofApi.proofRelease(proofHandle);
    }
}
