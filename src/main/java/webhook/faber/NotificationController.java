package webhook.faber;

import com.evernym.sdk.vcx.ErrorCode;
import com.evernym.sdk.vcx.VcxException;
import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefApi;
import com.evernym.sdk.vcx.issuer.IssuerApi;
import com.evernym.sdk.vcx.proof.DisclosedProofApi;
import com.evernym.sdk.vcx.proof.GetProofResult;
import com.evernym.sdk.vcx.proof.ProofApi;
import com.evernym.sdk.vcx.schema.SchemaApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import utils.Common;

import java.util.LinkedHashMap;
import java.util.logging.Logger;

import static utils.Common.prettyJson;
import static utils.State.StateType;
import static utils.State.ProofState;

@RestController
public class NotificationController {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();
    static final Boolean AUTO_SEND_OFFER = true;
    static final Boolean AUTO_SEND_CREDENTIAL = true;
    static final Boolean AUTO_SEND_PROOF_REQUEST = true;

    @PostMapping("/notifications")
    public ResponseEntity notificationsHandler(@RequestBody(required = false) NotificationRequestDto body) throws Exception {
        logger.info("[" + java.time.LocalDateTime.now() + "] " + body.toString());

        String messages = UtilsApi.vcxGetMessages(body.getMsgStatusCode(), body.getMsgUid(), body.getPwDid()).get();
        logger.info( "Messages: " +  prettyJson(messages));

        String pwDid = JsonPath.read(messages,"$.[0].pairwiseDID");
        String connectionRecord = WalletApi.getRecordWallet("connection", pwDid, "").get();
        String connection = JsonPath.read(connectionRecord,"$.value");
        logger.info("Get record - connection:\n" + prettyJson(connection));

        LinkedHashMap<String, Object> message = JsonPath.read(messages,"$.[0].msgs[0]");

        String decryptedPayload = (String)message.get("decryptedPayload");
        String uid = (String)message.get("uid");
        //logger.info( "Decrypted payload: " + decryptedPayload + ", UID: " + uid);

        String payloadMessage = JsonPath.read(decryptedPayload,"$.@msg");
        //logger.info( "Payload message: " + payloadMessage);

        String type = JsonPath.read(decryptedPayload,"$.@type.name");
        //logger.info( "Type: " + type);

        switch(type) {
            //connection response or ack of proof request
            case "aries":
                String innerType = JsonPath.read(payloadMessage,"$.@type");

                //connection request - At Inviter: after receiving invitation from Invitee
                if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/request")) {
                    logger.info("connections/1.0/request");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();
                    int connectionState = ConnectionApi.vcxConnectionUpdateStateWithMessage(connectionHandle, JsonPath.parse(message).jsonString()).get();

                    if (connectionState == StateType.RequestReceived) {
                        // new relationship
                        String newPwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();

                        connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info("Add record - connection:\n" + prettyJson(connection));
                        WalletApi.addRecordWallet("connection", newPwDid, connection).get();
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    ConnectionApi.connectionRelease(connectionHandle);
                }
                // notification ack - At Inviter: after connection request from Invitere
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/notification/1.0/ack")){
                    logger.info("notification/1.0/ack");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();
                    int connectionState = ConnectionApi.vcxConnectionUpdateStateWithMessage(connectionHandle, JsonPath.parse(message).jsonString()).get();

                    if (connectionState == StateType.Accepted) {

                        if (AUTO_SEND_OFFER) {
                            DocumentContext schemaAttrs = JsonPath.parse("{" +
                                    "  name: 'alice'," +
                                    "  last_name: 'clark'," +
                                    "  date: '05-2018'," +
                                    "  degree: 'maths'," +
                                    "  age: '25'" +
                                    "}");

                            logger.info("#12 Create an IssuerCredential object using the schema and credential definition\n"
                                    + prettyJson(schemaAttrs.jsonString()));

                            String credDefRecord = WalletApi.getRecordWallet("credentialDef", "defaultCredentialDef", "").get();
                            String credDef = JsonPath.read(credDefRecord,"$.value");
                            int credDefHandle = CredentialDefApi.credentialDefDeserialize(credDef).get();

                            int credentialHandle = IssuerApi.issuerCreateCredential("alice_degree",
                                    credDefHandle,
                                    null,
                                    schemaAttrs.jsonString(),
                                    "cred",
                                    0).get();
                            logger.info("#13 Issue credential offer to alice");
                            IssuerApi.issuerSendCredentialOffer(credentialHandle, connectionHandle).get();

                            String credential = IssuerApi.issuerCredentialSerialize(credentialHandle).get();
                            String threadId = JsonPath.read(credential,"$.data.issuer_sm.state.OfferSent.thread_id");
                            logger.info("Add record - credential:\n" + prettyJson(credential));
                            WalletApi.addRecordWallet("credential", threadId, credential).get();

                            IssuerApi.issuerCredentialRelease(credentialHandle);
                            CredentialDefApi.credentialDefRelease(credDefHandle);
                        }

                        connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info("Update record - connection:\n" + prettyJson(connection));
                        WalletApi.updateRecordWallet("connection", pwDid, connection).get();
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    ConnectionApi.connectionRelease(connectionHandle);
                }
                // connection response - At Issuer: After issuerSendCredentialOffer
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/request-credential")) {
                    logger.info("issue-credential/1.0/request-credential");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
                    String credentialRecord = WalletApi.getRecordWallet("credential", threadId, "").get();
                    String credential = JsonPath.read(credentialRecord,"$.value");
                    logger.info("Get record - credential:\n" + prettyJson(credential));

                    // TODO: Must replace connection_handle in credential - Need to consider better way
                    DocumentContext credentialJson = JsonPath.parse(credential);
                    credentialJson.set("$.data.issuer_sm.state.OfferSent.connection_handle", Integer.toUnsignedLong(connectionHandle));
                    credential = credentialJson.jsonString();

                    int credentialHandle = IssuerApi.issuerCredentialDeserialize(credential).get();
                    int credentialState = IssuerApi.issuerCredentialUpdateState(credentialHandle).get();

                    if (credentialState == StateType.RequestReceived) {
                        if (AUTO_SEND_CREDENTIAL) {
                            logger.info("#17 Issue credential to alice");
                            IssuerApi.issuerSendCredential(credentialHandle, connectionHandle).get();

                            credential = IssuerApi.issuerCredentialSerialize(credentialHandle).get();
                            logger.info("Update record - credential:\n" + prettyJson(credential));
                            WalletApi.updateRecordWallet("credential", threadId, credential).get();
                        }

                        // After issuing credential, issuer does not receive Ack for that
                        // We send proof request here
                        if (AUTO_SEND_PROOF_REQUEST) {
                            String vcxConfigRecord = WalletApi.getRecordWallet("vcxConfig", "defaultVcxConfig", "").get();
                            String vcxConfig = JsonPath.read(vcxConfigRecord,"$.value");
                            DocumentContext vcxConfigJson = JsonPath.parse(vcxConfig);

                            DocumentContext proofAttributes = JsonPath.parse("[" +
                                    "  {" +
                                    "    names: ['name', 'last_name']," +
                                    "    restrictions: [{ issuer_did: " + vcxConfigJson.read("$.institution_did") + " }]" +
                                    "  }," +
                                    "  {" +
                                    "    name: 'date'," +
                                    "    restrictions: { issuer_did: " + vcxConfigJson.read("$.institution_did") + " }" +
                                    "  }," +
                                    "  {" +
                                    "    name: 'degree'," +
                                    "    restrictions: { 'attr::degree::value': 'maths' }" +
                                    "  }" +
                                    "]");

                            DocumentContext proofPredicates = JsonPath.parse("[" +
                                    "  {" +
                                    "    name: 'age'," +
                                    "    p_type: '>='," +
                                    "    p_value: 20," +
                                    "    restrictions: [{ issuer_did: " + vcxConfigJson.read("$.institution_did") + " }]" +
                                    "  }" +
                                    "]");

                            logger.info("#19 Create a Proof object\n" +
                                    "proofAttributes: " + prettyJson(proofAttributes.jsonString()) + "\n" +
                                    "proofPredicates: " + prettyJson(proofPredicates.jsonString()));
                            int proofHandle = ProofApi.proofCreate("proof_uuid",
                                    proofAttributes.jsonString(),
                                    proofPredicates.jsonString(),
                                    "{}",
                                    "proof_from_alice").get();

                            logger.info("#20 Request proof of degree from alice");
                            ProofApi.proofSendRequest(proofHandle, connectionHandle).get();

                            String proof = ProofApi.proofSerialize(proofHandle).get();
                            threadId = JsonPath.read(proof,"$.data.verifier_sm.state.PresentationRequestSent.presentation_request.@id");
                            logger.info("Add record - proof: \n" + prettyJson(proof));
                            logger.info("threadId: " + threadId);
                            WalletApi.addRecordWallet("proof", threadId, proof).get();

                            ProofApi.proofRelease(proofHandle);
                        }
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    IssuerApi.issuerCredentialRelease(credentialHandle);
                    ConnectionApi.connectionRelease(connectionHandle);
                }
                // present-proof presentation - At Issuer: After proofSendRequest
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/presentation")) {
                    logger.info("present-proof/1.0/presentation");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String threadId = JsonPath.read(payloadMessage, "$.~thread.thid");
                    String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
                    String proof = JsonPath.read(proofRecord, "$.value");
                    logger.info("Get record - proof:\n" + prettyJson(proof));

                    // TODO: Must replace connection_handle in proof - Need to consider better way
                    DocumentContext proofJson = JsonPath.parse(proof);
                    proofJson.set("$.data.verifier_sm.state.PresentationRequestSent.connection_handle", Integer.toUnsignedLong(connectionHandle));
                    proof = proofJson.jsonString();

                    int proofHandle = ProofApi.proofDeserialize(proof).get();
                    int proofState = ProofApi.proofUpdateState(proofHandle).get();

                    if (proofState == StateType.Accepted) {
                        logger.info("#27 Process the proof provided by alice");
                        GetProofResult proofResult = ProofApi.getProof(proofHandle, connectionHandle).get();

                        logger.info("#28 Check if proof is valid");
                        if (proofResult.getProof_state() == ProofState.Verified) {
                            logger.info("Proof is verified");
                        }
                        else {
                            logger.info("Could not verify proof");
                        }
                    }

                    proof = ProofApi.proofSerialize(proofHandle).get();
                    logger.info("Update record - proof: \n" + prettyJson(proof));
                    WalletApi.updateRecordWallet("proof", threadId, proof).get();

                    ProofApi.proofRelease(proofHandle);
                    ConnectionApi.connectionRelease(connectionHandle);
                }



                // connection response - At Invitee:
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response")){
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();
                    int state = ConnectionApi.vcxConnectionUpdateStateWithMessage(connectionHandle, JsonPath.parse(message).jsonString()).get();
                    connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                    logger.info("connections/1.0/response - Serialized connection:\n" + prettyJson(connection));

                    if (state == StateType.Accepted) {
                        connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info( "Serialized connection: " + prettyJson(connection));
                        WalletApi.updateRecordWallet("connection", pwDid, connection).get();
                    }

                    ConnectionApi.connectionRelease(connectionHandle);
                }
                //ack of proof request
                else if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/ack")){
                    String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
                    String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
                    String proof = JsonPath.read(proofRecord,"$.value");

                    int proofHandle = DisclosedProofApi.proofDeserialize(proof).get();
                    //For now, Java wrapper doesn't implement proofUpdateStateWithMessage API, and it is fixed in https://github.com/hyperledger/indy-sdk/pull/2156
                    //int state = DisclosedProofApi.proofUpdateStateWithMessage(proofHandle, JsonPath.parse(message).jsonString()).get();
                    int proofState = DisclosedProofApi.proofUpdateState(proofHandle).get();

                    if (proofState == 4) {
                        logger.info( "Proof is OK");
                    }

                    DisclosedProofApi.proofRelease(proofHandle);
                }
                break;
            case "credential-offer":
                //handleCredentialOffer(connection, payloadMessage, pwDid, uid);
                break;
            case "credential":
                //handleCredential(payloadMessage);
                break;
            case "presentation-request":
                //handlePresentationRequest(connection, payloadMessage, pwDid, uid);
                break;
            default:

        }

        return ResponseEntity.ok().build();
    }

    @GetMapping("create-invitation")
    public String createInvitationHandler() throws Exception{
        logger.info("createInvitationHandler called");
        int connectionHandle = ConnectionApi.vcxConnectionCreate("alice").get();
        ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get();
        ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

        DocumentContext details = JsonPath.parse(ConnectionApi.connectionInviteDetails(connectionHandle, 0).get());

        String connection = ConnectionApi.connectionSerialize(connectionHandle).get();
        String pwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();
        logger.info("Add record - connection: \n" + prettyJson(connection));
        WalletApi.addRecordWallet("connection", pwDid, connection).get();
        ConnectionApi.connectionRelease(connectionHandle);

        return details.jsonString();
    }
}
