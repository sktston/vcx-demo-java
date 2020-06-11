package webhook.faber;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefApi;
import com.evernym.sdk.vcx.issuer.IssuerApi;
import com.evernym.sdk.vcx.proof.GetProofResult;
import com.evernym.sdk.vcx.proof.ProofApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import utils.Common;
import utils.State;

import java.util.LinkedHashMap;
import java.util.logging.Logger;

import static utils.Common.prettyJson;
import static utils.State.StateType;
import static utils.State.ProofState;

@RestController
public class GlobalController {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();
    static final boolean AUTO_SEND_OFFER = true;
    static final boolean AUTO_SEND_PROOF_REQUEST = true;

    @Autowired
    GlobalService globalService;

    @PostMapping("/notifications")
    public ResponseEntity notificationsHandler(@RequestBody(required = false) NotificationsRequestDto body) throws Exception {
        logger.info("notificationsHandler - body: " + body.toString());

        // Get the message from mediator using notification information
        String messages = UtilsApi.vcxGetMessages(body.getMsgStatusCode(), body.getMsgUid(), body.getPwDid()).get();
        logger.info( "Messages: " +  prettyJson(messages));
        String message = JsonPath.parse((LinkedHashMap)JsonPath.read(messages,"$.[0].msgs[0]")).jsonString();
        String decryptedPayload = JsonPath.read(message, "$.decryptedPayload");
        String payloadMessage = JsonPath.read(decryptedPayload,"$.@msg");
        String type = JsonPath.read(decryptedPayload,"$.@type.name");

        // pwDid is used as a connectionId
        String pwDid = JsonPath.read(messages,"$.[0].pairwiseDID");
        String connectionRecord = WalletApi.getRecordWallet("connection", pwDid, "").get();
        String connection = JsonPath.read(connectionRecord,"$.value");
        //logger.info("Get record - connection:\n" + prettyJson(connection));

        switch(type) {
            //connection response or ack of proof request
            case "aries":
                String innerType = JsonPath.read(payloadMessage,"$.@type");

                // STEP.3 - update connection from F to F2A
                //connection request - At Inviter: after receiving invitation from Invitee
                if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/request")) {
                    logger.info("aries - connections/1.0/request");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();
                    int connectionState = ConnectionApi.vcxConnectionUpdateStateWithMessage(connectionHandle, JsonPath.parse(message).jsonString()).get();

                    if (connectionState == State.StateType.RequestReceived) {
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
                // STEP.5 - receive connection created ACK
                // notification ack - At Inviter: after connection request from Invitee
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/notification/1.0/ack")){
                    logger.info("aries - notification/1.0/ack");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();
                    int connectionState = ConnectionApi.vcxConnectionUpdateStateWithMessage(connectionHandle, message).get();

                    if (connectionState == StateType.Accepted) {
                        connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info("Update record - connection:\n" + prettyJson(connection));
                        WalletApi.updateRecordWallet("connection", pwDid, connection).get();
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    // STEP.6 - send credential offer
                    // After issuing credential, issuer does not receive Ack for that
                    // We send proof request here
                    if (AUTO_SEND_OFFER) {
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

                        String credential = IssuerApi.issuerCredentialSerialize(credentialHandle).get();
                        String threadId = JsonPath.read(credential,"$.data.issuer_sm.state.OfferSent.thread_id");
                        logger.info("Add record - credential:\n" + prettyJson(credential));
                        WalletApi.addRecordWallet("credential", threadId, credential).get();

                        connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info("Update record - connection:\n" + prettyJson(connection));
                        WalletApi.updateRecordWallet("connection", pwDid, connection).get();

                        IssuerApi.issuerCredentialRelease(credentialHandle);
                        CredentialDefApi.credentialDefRelease(credDefHandle);
                    }

                    ConnectionApi.connectionRelease(connectionHandle);
                }
                // STEP.8 - send credential
                // connection response - At Issuer: After issuerSendCredentialOffer
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/request-credential")) {
                    logger.info("aries - issue-credential/1.0/request-credential");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
                    String credentialRecord = WalletApi.getRecordWallet("credential", threadId, "").get();
                    String credential = JsonPath.read(credentialRecord,"$.value");
                    logger.info("Get record - credential:\n" + prettyJson(credential));

                    // TODO: Must replace connection_handle in credential - Need to consider better way
                    credential = JsonPath.parse(credential)
                            .set("$.data.issuer_sm.state.OfferSent.connection_handle", Integer.toUnsignedLong(connectionHandle))
                            .jsonString();

                    int credentialHandle = IssuerApi.issuerCredentialDeserialize(credential).get();
                    int credentialState = IssuerApi.issuerCredentialUpdateState(credentialHandle).get();

                    if (credentialState == StateType.RequestReceived) {
                        logger.info("#17 Issue credential to alice");
                        IssuerApi.issuerSendCredential(credentialHandle, connectionHandle).get();

                        credential = IssuerApi.issuerCredentialSerialize(credentialHandle).get();
                        logger.info("Update record - credential:\n" + prettyJson(credential));
                        WalletApi.updateRecordWallet("credential", threadId, credential).get();
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    // STEP.10 - request proof
                    // After issuing credential, issuer does not receive Ack for that
                    // We send proof request here
                    if (AUTO_SEND_PROOF_REQUEST) {
                        Thread.sleep(1000); // wait 1 sec to get credential at holder

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

                        logger.info("#19 Create a Proof object\n" +
                                "proofAttributes: " + prettyJson(proofAttributes) + "\n" +
                                "proofPredicates: " + prettyJson(proofPredicates));
                        int proofHandle = ProofApi.proofCreate("proof_uuid",
                                proofAttributes,
                                proofPredicates,
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

                    IssuerApi.issuerCredentialRelease(credentialHandle);
                    ConnectionApi.connectionRelease(connectionHandle);
                }
                // STEP.12 - receive & verify proof
                // present-proof presentation - At Issuer: After proofSendRequest
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/presentation")) {
                    logger.info("aries - present-proof/1.0/presentation");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String threadId = JsonPath.read(payloadMessage, "$.~thread.thid");
                    String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
                    String proof = JsonPath.read(proofRecord, "$.value");
                    logger.info("Get record - proof:\n" + prettyJson(proof));

                    // TODO: Must replace connection_handle in proof - Need to consider better way
                    proof = JsonPath.parse(proof)
                            .set("$.data.verifier_sm.state.PresentationRequestSent.connection_handle", Integer.toUnsignedLong(connectionHandle))
                            .jsonString();

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
            default:

        }

        return ResponseEntity.ok().build();
    }

    @GetMapping("/invitations")
    public String createInvitationHandler() throws Exception{
        // STEP.1 - create connection F & send invitation
        logger.info("createInvitationHandler called");
        logger.info("#5 Create a connection to alice and return the invite details");
        int connectionHandle = ConnectionApi.vcxConnectionCreate("alice").get();
        ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get();
        String details = ConnectionApi.connectionInviteDetails(connectionHandle, 0).get();
        logger.info("**invite details**");
        logger.info(details);

        String connection = ConnectionApi.connectionSerialize(connectionHandle).get();
        String pwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();
        logger.info("Add record - connection: \n" + prettyJson(connection));
        WalletApi.addRecordWallet("connection", pwDid, connection).get();
        ConnectionApi.connectionRelease(connectionHandle);

        return details;
    }
}
