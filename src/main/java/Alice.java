import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credential.CredentialApi;
import com.evernym.sdk.vcx.proof.DisclosedProofApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.vcx.VcxApi;

import com.jayway.jsonpath.JsonPath;

import org.apache.commons.cli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Scanner;
import java.util.logging.Logger;

import utils.Common;
import utils.VcxState;

import static utils.Common.*;

public class Alice {
    // get logger for demo - INFO configured
    static final Logger log = Common.getDemoLogger();
    static final String tailsFileRoot = System.getProperty("user.home") + "/.indy_client/tails";
    static final String tailsServerUrl = "http://13.124.169.12";

    public static void main(String[] args) throws Exception {
        // Library logger setup - ERROR|WARN|INFO|DEBUG|TRACE
        Common.setLibraryLogger("ERROR");

        CommandLine options = Common.getCommandLine(args);
        if (options == null) System.exit(0);

        log.info("#0 Initialize");
        Common.loadNullPayPlugin();

        // static configuration
        long utime = System.currentTimeMillis() / 1000;
        String provisionConfig = JsonPath.parse("{" +
                "  agency_url: 'http://13.125.5.122:8080'," + // skt node agency testnet
                "  agency_did: 'VsKV7grR1BUE29mG2Fm2kX'," +
                "  agency_verkey: 'Hezce2UWMZ3wUhVkh2LfKSs8nDzWwzs2Win7EzNN3YaR'," +
                "  wallet_name: 'vcx_demo_alice_wallet_" + utime + "'," +
                "  wallet_key: '123'," +
                "  payment_method: 'null'," +
                "  enterprise_seed: '000000000000000000000000000User1'" + // SEED of alice's DID that does not need to be registered in the ledger
                "}").jsonString();

        // Communication method. aries.
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "protocol_type", "4.0").jsonString();
        log.info("Running with Aries VCX Enabled! Make sure VCX agency is configured to use protocol_type 4.0");

        if (options.hasOption("postgres")) {
            Common.loadPostgresPlugin();
            provisionConfig = JsonPath.parse(provisionConfig).put("$", "wallet_type", "postgres_storage")
                    .put("$", "storage_config", "{\"url\":\"localhost:5432\"}")
                    .put("$", "storage_credentials", "{\"account\":\"postgres\",\"password\":\"mysecretpassword\"," +
                            "\"admin_account\":\"postgres\",\"admin_password\":\"mysecretpassword\"}").jsonString();
            log.info("Running with PostreSQL wallet enabled! Config = " + JsonPath.read(provisionConfig, "$.storage_config"));
        } else {
            log.info("Running with builtin wallet.");
        }

        log.info("#8 Provision an agent and wallet, get back configuration details: \n" + prettyJson(provisionConfig));
        String vcxConfig = UtilsApi.vcxProvisionAgent(provisionConfig);

        vcxConfig = JsonPath.parse(vcxConfig).put("$", "institution_name", "alice")
                .put("$", "institution_logo_url", "http://robohash.org/345")
                .put("$", "protocol_version", "2")
                .put("$", "genesis_path", System.getProperty("user.dir") + "/genesis.txn").jsonString(); // file configured for skt testnet
                //.put("$", "genesis_path", "http://54.180.86.51/genesis").jsonString(); // or url can be configured
        log.info("#9 Initialize libvcx with new configuration\n" + prettyJson(vcxConfig));
        VcxApi.vcxInitWithConfig(vcxConfig).get();

        log.info("Input faber invitation details\nEnter your invite details:");
        Scanner sc = new Scanner(System.in);
        String details = sc.nextLine();

        log.info("#10 Convert to valid json and string and create a connection to faber");
        int connectionHandle = ConnectionApi.vcxCreateConnectionWithInvite("faber", details).get();
        ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get();
        int connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();
        while (connectionState != VcxState.Accepted.getValue()) {
            Thread.sleep(2000);
            connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();
        }

        log.info("#11 Wait for faber to issue a credential offer");
        String offers = CredentialApi.credentialGetOffers(connectionHandle).get();
        while (JsonPath.read(offers, "$.length()").equals(0)) {
            Thread.sleep(2000);
            offers = CredentialApi.credentialGetOffers(connectionHandle).get();
        }
        log.info("Alice found " + JsonPath.read(offers, "$.length()") + " credential offers.");
        String credentialOffer = JsonPath.parse((LinkedHashMap)JsonPath.read(offers, "$.[0]")).jsonString();
        log.info("credential offer:\n" + prettyJson(credentialOffer));

        // Update agency message status manually (xxxUpdateState automatically update message status, but not here)
        String pwDidAtOffer = ConnectionApi.connectionGetPwDid(connectionHandle).get();
        String messagesAtOffer = UtilsApi.vcxGetMessages("MS-103", null, pwDidAtOffer).get();
        String uidAtOffer = Common.getUidWithMessages(messagesAtOffer);
        UtilsApi.vcxUpdateMessages("MS-106",
                "[{\"pairwiseDID\":\"" + pwDidAtOffer + "\",\"uids\":[\"" + uidAtOffer + "\"]}]");

        // Create a credential object from the credential offer
        int credentialHandle = CredentialApi.credentialCreateWithOffer("credential", credentialOffer).get();

        log.info("#15 After receiving credential offer, send credential request");
        CredentialApi.credentialSendRequest(credentialHandle, connectionHandle, 0).get();

        log.info("#16 Poll agency and accept credential from faber");
        int credentialState = CredentialApi.credentialUpdateState(credentialHandle).get();
        while (credentialState != VcxState.Accepted.getValue()) {
            Thread.sleep(2000);
            credentialState = CredentialApi.credentialUpdateState(credentialHandle).get();
        }

        log.info("#22 Poll agency for a proof request");
        String requests = DisclosedProofApi.proofGetRequests(connectionHandle).get();
        while (JsonPath.read(requests, "$.length()").equals(0)) {
            Thread.sleep(2000);
            requests = DisclosedProofApi.proofGetRequests(connectionHandle).get();
        }
        log.info("Alice found " + JsonPath.read(requests, "$.length()") + " proof requests.");
        String proofRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(requests, "$.[0]")).jsonString();
        log.info("proof request:\n" + prettyJson(proofRequest));

        // Update agency message status manually (xxxUpdateState automatically update message status, but not here)
        String pwDidAtRequest = ConnectionApi.connectionGetPwDid(connectionHandle).get();
        String messagesAtRequest = UtilsApi.vcxGetMessages("MS-103", null, pwDidAtRequest).get();
        String uidAtRequest = Common.getUidWithMessages(messagesAtRequest);
        UtilsApi.vcxUpdateMessages("MS-106",
                "[{\"pairwiseDID\":\"" + pwDidAtRequest + "\",\"uids\":[\"" + uidAtRequest + "\"]}]");

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

        log.info("#27 Wait for Faber to receive the proof");
        int proofState = DisclosedProofApi.proofUpdateState(proofHandle).get();
        while (proofState != VcxState.Accepted.getValue()) {
            Thread.sleep(2000);
            proofState = DisclosedProofApi.proofUpdateState(proofHandle).get();
            if (proofState == VcxState.None.getValue()) {
                log.info("Faber denied the proof (possibly the credential has been revoked)");
                System.exit(0);
            }
        }
        log.info("Faber received the proof");

        System.exit(0);
    }
}
