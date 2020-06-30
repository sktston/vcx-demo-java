package webhook.alice;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credential.CredentialApi;
import com.evernym.sdk.vcx.proof.DisclosedProofApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import utils.Common;
import utils.VcxState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Logger;

import static utils.Common.prettyJson;

@RestController
public class GlobalController {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();
    static final Boolean AUTO_SEND_OFFER = true;
    static final Boolean AUTO_SEND_CREDENTIAL = true;
    static final Boolean AUTO_SEND_PROOF_REQUEST = true;

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

                // STEP.4 - connection created
                // connection response - At Invitee:
                if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response")){
                    logger.info("aries - connections/1.0/response");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();
                    int connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

                    if (connectionState == VcxState.Accepted.getValue()) {
                        connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info("Update record - connection:\n" + prettyJson(connection));
                        WalletApi.updateRecordWallet("connection", pwDid, connection).get();
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    ConnectionApi.connectionRelease(connectionHandle);
                }
                // STEP.13 - receive proof ACK
                //ack of proof request
                else if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/ack")){
                    logger.info("aries - present-proof/1.0/ack");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

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
                        logger.info("Faber received the proof");
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    ConnectionApi.connectionRelease(connectionHandle);
                    DisclosedProofApi.proofRelease(proofHandle);

                    // Supposed here is the end
                    logger.info("Faber demo is completed (Exit manually)");
                }
                break;
            // STEP.7 - accept credential offer & request credential
            case "credential-offer":
                if (true) {
                    logger.info("credential-offer");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String offers = CredentialApi.credentialGetOffers(connectionHandle).get();
                    String credentialOffer = JsonPath.parse((LinkedHashMap)JsonPath.read(offers, "$.[0]")).jsonString();
                    logger.info("credential offer:\n" + prettyJson(credentialOffer));

                    // Create a credential object from the credential offer
                    int credentialHandle = CredentialApi.credentialCreateWithOffer("credential", credentialOffer).get();

                    logger.info("#15 After receiving credential offer, send credential request");
                    CredentialApi.credentialSendRequest(credentialHandle, connectionHandle, 0).get();

                    //Serialize the object
                    String credential = CredentialApi.credentialSerialize(credentialHandle).get();
                    String threadId = JsonPath.read(credential, "$.data.holder_sm.thread_id");
                    logger.info("Add record credential: " + prettyJson(credential));
                    WalletApi.addRecordWallet("credential", threadId, credential).get();

                    CredentialApi.credentialRelease(credentialHandle);
                    ConnectionApi.connectionRelease(connectionHandle);
                }
                break;
            // STEP.9 - accept credential
            case "credential":
                if (true) {
                    logger.info("credential");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String threadId = JsonPath.read(payloadMessage, "$.~thread.thid");
                    String credentialRecord = WalletApi.getRecordWallet("credential", threadId, "").get();
                    String credential = JsonPath.read(credentialRecord, "$.value");

                    // TODO: Must replace connection_handle in credential - Need to consider better way
                    credential = JsonPath.parse(credential)
                            .set("$.data.holder_sm.state.RequestSent.connection_handle", Integer.toUnsignedLong(connectionHandle))
                            .jsonString();

                    int credentialHandle = CredentialApi.credentialDeserialize(credential).get();

                    int credentialState = CredentialApi.credentialUpdateState(credentialHandle).get();
                    if (credentialState == VcxState.Accepted.getValue()) {
                        logger.info("#16 Accepted credential from faber");
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    //Serialize the object
                    credential = CredentialApi.credentialSerialize(credentialHandle).get();
                    logger.info("Update record credential: " + prettyJson(credential));
                    WalletApi.updateRecordWallet("credential", threadId, credential).get();

                    CredentialApi.credentialRelease(credentialHandle);
                    ConnectionApi.connectionRelease(connectionHandle);
                }
                break;
            // STEP.11 - send proof
            case "presentation-request":
                if (true) {
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String requests = DisclosedProofApi.proofGetRequests(connectionHandle).get();
                    String proofRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(requests, "$.[0]")).jsonString();
                    logger.info("proof request:\n" + prettyJson(proofRequest));

                    logger.info("#23 Create a Disclosed proof object from proof request");
                    int proofHandle = DisclosedProofApi.proofCreateWithRequest("proof", proofRequest).get();

                    logger.info("#24 Query for credentials in the wallet that satisfy the proof request");
                    String credentials = DisclosedProofApi.proofRetrieveCredentials(proofHandle).get();

                    LinkedHashMap<String, Object> attrs = JsonPath.read(credentials, "$.attrs");
                    for(String key : attrs.keySet()){
                        String attr = JsonPath.parse((LinkedHashMap)JsonPath.read(credentials, "$.attrs." + key + ".[0]")).jsonString();
                        credentials = JsonPath.parse(credentials).set("$.attrs." + key, JsonPath.parse("{\"credential\":"+ attr + "}").json()).jsonString();
                    }

                    logger.info("#25 Generate the proof");
                    DisclosedProofApi.proofGenerate(proofHandle, credentials, "{}").get();

                    logger.info("#26 Send the proof to faber");
                    DisclosedProofApi.proofSend(proofHandle, connectionHandle).get();

                    //Serialize the object
                    String proof = DisclosedProofApi.proofSerialize(proofHandle).get();
                    String threadId = JsonPath.read(proof,"$.data.prover_sm.thread_id");
                    logger.info("Add record proof: " + prettyJson(proof));
                    WalletApi.addRecordWallet("proof", threadId, proof).get();

                    DisclosedProofApi.proofRelease(proofHandle);
                    ConnectionApi.connectionRelease(connectionHandle);
                }
                break;
            default:
                logger.severe("Unknown type message");

        }

        return ResponseEntity.ok().build();
    }
}
