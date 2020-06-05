package webhook.alice;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credential.CredentialApi;
import com.evernym.sdk.vcx.proof.DisclosedProofApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import utils.Common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Logger;

import static utils.Common.prettyJson;
import static utils.State.StateType;

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
        //logger.info("Get record - connection:\n" + prettyJson(connection));

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

                // connection response - At Invitee:
                if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response")){
                    logger.info("aries - connections/1.0/response");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();
                    int connectionState = ConnectionApi.vcxConnectionUpdateStateWithMessage(connectionHandle, JsonPath.parse(message).jsonString()).get();

                    if (connectionState == StateType.Accepted) {
                        connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                        logger.info("Update record - connection:\n" + prettyJson(connection));
                        WalletApi.updateRecordWallet("connection", pwDid, connection).get();
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    ConnectionApi.connectionRelease(connectionHandle);
                }
                //ack of proof request
                else if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/ack")){
                    logger.info("aries - present-proof/1.0/ack");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
                    String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
                    String proof = JsonPath.read(proofRecord,"$.value");

                    // TODO: Must replace connection_handle in credential - Need to consider better way
                    DocumentContext proofJson = JsonPath.parse(proof);
                    proofJson.set("$.data.prover_sm.state.PresentationSent.connection_handle", Integer.toUnsignedLong(connectionHandle));
                    proof = proofJson.jsonString();

                    int proofHandle = DisclosedProofApi.proofDeserialize(proof).get();
                    int proofState = DisclosedProofApi.proofUpdateState(proofHandle).get();

                    if (proofState == StateType.Accepted) {
                        logger.info("Faber received the proof");
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    ConnectionApi.connectionRelease(connectionHandle);
                    DisclosedProofApi.proofRelease(proofHandle);

                    // Supposed here is exit
                    System.exit(0);
                }
                break;
            case "credential-offer":
                if (true) {
                    logger.info("credential-offer");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    //Create a credential object from the credential offer
                    List<String> credentialOffer = JsonPath.read(payloadMessage, "$");
                    String offer = JsonPath.parse(credentialOffer).jsonString();
                    logger.info("credential offer:\n: " + prettyJson(offer));
                    int credentialHandle = CredentialApi.credentialCreateWithOffer("credential", offer).get();

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
            case "credential":
                if (true) {
                    logger.info("credential");
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    String claimOfferId = JsonPath.read(payloadMessage, "$.claim_offer_id"); // same as threadId
                    String credentialRecord = WalletApi.getRecordWallet("credential", claimOfferId, "").get();
                    String credential = JsonPath.read(credentialRecord, "$.value");

                    // TODO: Must replace connection_handle in credential - Need to consider better way
                    DocumentContext credentialJson = JsonPath.parse(credential);
                    credentialJson.set("$.data.holder_sm.state.RequestSent.connection_handle", Integer.toUnsignedLong(connectionHandle));
                    credential = credentialJson.jsonString();

                    int credentialHandle = CredentialApi.credentialDeserialize(credential).get();

                    int credentialState = CredentialApi.credentialUpdateState(credentialHandle).get();
                    if (credentialState == StateType.Accepted) {
                        logger.info("#16 Accepted credential from faber");
                    }
                    else {
                        logger.severe("Unexpected state type");
                    }

                    //Serialize the object
                    credential = CredentialApi.credentialSerialize(credentialHandle).get();
                    String threadId = JsonPath.read(credential, "$.data.holder_sm.thread_id");
                    logger.info("Update record credential: " + prettyJson(credential));
                    WalletApi.updateRecordWallet("credential", threadId, credential).get();

                    CredentialApi.credentialRelease(credentialHandle);
                    ConnectionApi.connectionRelease(connectionHandle);
                }
                break;
            case "presentation-request":
                if (true) {
                    int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

                    //Create a Disclosed proof object from proof request
                    LinkedHashMap<String, Object> request = JsonPath.read(payloadMessage,"$");
                    logger.info("proof request:\n" + prettyJson(JsonPath.parse(request).jsonString()));

                    logger.info("#23 Create a Disclosed proof object from proof request");
                    int proofHandle = DisclosedProofApi.proofCreateWithRequest("proof", JsonPath.parse(request).jsonString()).get();

                    logger.info("#24 Query for credentials in the wallet that satisfy the proof request");
                    DocumentContext credentials = JsonPath.parse(DisclosedProofApi.proofRetrieveCredentials(proofHandle).get());

                    LinkedHashMap<String, Object> attrs = credentials.read("$.attrs");
                    for(String key : attrs.keySet()){
                        DocumentContext attr = JsonPath.parse((LinkedHashMap)credentials.read("$.attrs." + key + ".[0]"));
                        credentials.set("$.attrs." + key, JsonPath.parse("{\"credential\":"+ attr.jsonString() + "}").json());
                    }

                    logger.info("#25 Generate the proof");
                    DisclosedProofApi.proofGenerate(proofHandle, credentials.jsonString(), "{}").get();

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

        }

        return ResponseEntity.ok().build();
    }
}
