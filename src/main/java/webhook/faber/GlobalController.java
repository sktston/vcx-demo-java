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
import utils.ProofState;
import utils.VcxState;

import java.util.LinkedHashMap;
import java.util.logging.Logger;

import static utils.Common.prettyJson;

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
        String serializedConnection = JsonPath.read(connectionRecord,"$.value");
        int connectionHandle = ConnectionApi.connectionDeserialize(serializedConnection).get();

        switch(type) {
            //connection response or ack of proof request
            case "aries":
                String innerType = JsonPath.read(payloadMessage,"$.@type");

                // STEP.3 - update connection from F to F2A
                //connection request - At Inviter: after receiving invitation from Invitee
                if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/request")) {
                    logger.info("aries - connections/1.0/request");
                    int connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

                    if (connectionState == VcxState.RequestReceived.getValue()) {
                        // new relationship
                        String newPwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();

                        serializedConnection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info("addRecordWallet (connection, " + newPwDid + ", " + prettyJson(serializedConnection) + ")");
                        WalletApi.addRecordWallet("connection", newPwDid, serializedConnection).get();
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }
                }
                // STEP.5 - receive connection created ACK
                // notification ack - At Inviter: after connection request from Invitee
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/notification/1.0/ack")){
                    logger.info("aries - notification/1.0/ack");
                    int connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

                    if (connectionState == VcxState.Accepted.getValue()) {
                        serializedConnection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info("updateRecordWallet (connection, " + pwDid + ", " + prettyJson(serializedConnection) + ")");
                        WalletApi.updateRecordWallet("connection", pwDid, serializedConnection).get();
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

                        String serializedCredential = IssuerApi.issuerCredentialSerialize(credentialHandle).get();
                        String threadId = JsonPath.read(serializedCredential,"$.data.issuer_sm.state.OfferSent.thread_id");
                        logger.info("addRecordWallet (credential, " + threadId + ", " + prettyJson(serializedCredential) + ")");
                        WalletApi.addRecordWallet("credential", threadId, serializedCredential).get();

                        IssuerApi.issuerCredentialRelease(credentialHandle);
                        CredentialDefApi.credentialDefRelease(credDefHandle);
                    }
                }
                // STEP.8 - send credential
                // connection response - At Issuer: After issuerSendCredentialOffer
                else if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/request-credential")) {
                    logger.info("aries - issue-credential/1.0/request-credential");

                    String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
                    String credentialRecord = WalletApi.getRecordWallet("credential", threadId, "").get();
                    String serializedCredential = JsonPath.read(credentialRecord,"$.value");
                    logger.info("Get record - credential:\n" + prettyJson(serializedCredential));

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
                        logger.info("updateRecordWallet (credential, " + threadId + ", " + prettyJson(serializedCredential) + ")");
                        WalletApi.updateRecordWallet("credential", threadId, serializedCredential).get();
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

                        String serializedProof = ProofApi.proofSerialize(proofHandle).get();
                        threadId = JsonPath.read(serializedProof,"$.data.verifier_sm.state.PresentationRequestSent.presentation_request.@id");
                        logger.info("addRecordWallet (proof, " + threadId + ", " + prettyJson(serializedProof) + ")");
                        WalletApi.addRecordWallet("proof", threadId, serializedProof).get();

                        ProofApi.proofRelease(proofHandle);
                    }

                    IssuerApi.issuerCredentialRelease(credentialHandle);
                }
                break;
            case "presentation":
                // STEP.12 - receive & verify proof
                // present-proof presentation - At Issuer: After proofSendRequest
                logger.info("presentation");

                String threadId = JsonPath.read(payloadMessage, "$.~thread.thid");
                String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
                String serializedProof = JsonPath.read(proofRecord, "$.value");
                logger.info("Get record - proof:\n" + prettyJson(serializedProof));

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
                        logger.info("Proof is verified");
                    }
                    else {
                        logger.info("Could not verify proof");
                    }
                }

                serializedProof = ProofApi.proofSerialize(proofHandle).get();
                logger.info("updateRecordWallet (proof, " + threadId + ", " + prettyJson(serializedProof) + ")");
                WalletApi.updateRecordWallet("proof", threadId, serializedProof).get();

                ProofApi.proofRelease(proofHandle);
                break;
            default:
                logger.severe("Unknown type message");

        }

        ConnectionApi.connectionRelease(connectionHandle);

        return ResponseEntity.ok().build();
    }

    @GetMapping("/invitations")
    public String createInvitationHandler() throws Exception{
        // STEP.1 - create connection F & send invitation
        logger.info("createInvitationHandler called");
        String invitationRecord = WalletApi.getRecordWallet("invitation", "defaultInvitation", "").get();
        String invitation = JsonPath.read(invitationRecord,"$.value");

        return invitation;
    }
}
