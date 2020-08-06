import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefPrepareForEndorserResult;
import com.evernym.sdk.vcx.issuer.IssuerApi;
import com.evernym.sdk.vcx.proof.GetProofResult;
import com.evernym.sdk.vcx.proof.ProofApi;
import com.evernym.sdk.vcx.schema.SchemaApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.vcx.VcxApi;

import com.jayway.jsonpath.JsonPath;

import okhttp3.*;
import org.apache.commons.cli.CommandLine;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Logger;

import utils.Common;
import utils.ProofState;
import utils.VcxState;

import static utils.Common.*;

public class Faber {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();
    static final String tailsFileRoot = System.getProperty("user.home") + "/.indy_client/tails";
    static final String tailsServerUrl = "http://13.124.169.12";

    public static void main(String[] args) throws Exception {
        // Library logger setup - ERROR|WARN|INFO|DEBUG|TRACE
        Common.setLibraryLogger("ERROR");

        CommandLine options = Common.getCommandLine(args);
        if (options == null) System.exit(0);

        logger.info("#0 Initialize");
        Common.loadNullPayPlugin();

        long utime = System.currentTimeMillis() / 1000;
        String provisionConfig  = JsonPath.parse("{" +
                "  agency_url: 'http://13.125.5.122:8080'," + // skt node agency testnet
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

        if (options.hasOption("postgres")) {
            Common.loadPostgresPlugin();
            provisionConfig = JsonPath.parse(provisionConfig).put("$", "wallet_type", "postgres_storage")
                    .put("$", "storage_config", "{\"url\":\"localhost:5432\"}")
                    .put("$", "storage_credentials", "{\"account\":\"postgres\",\"password\":\"mysecretpassword\"," +
                            "\"admin_account\":\"postgres\",\"admin_password\":\"mysecretpassword\"}").jsonString();
            logger.info("Running with PostreSQL wallet enabled! Config = " + JsonPath.read(provisionConfig, "$.storage_config"));
        } else {
            logger.info("Running with builtin wallet.");
        }

        logger.info("#1 Config used to provision agent in agency: \n" + prettyJson(provisionConfig));
        String vcxConfig = UtilsApi.vcxProvisionAgent(provisionConfig);

        vcxConfig = JsonPath.parse(vcxConfig).put("$", "institution_name", "faber")
                .put("$", "institution_logo_url", "http://robohash.org/234")
                .put("$", "protocol_version", "2")
                .put("$", "genesis_path", System.getProperty("user.dir") + "/genesis.txn").jsonString(); // file configured for skt testnet
                //.put("$", "genesis_path", "http://54.180.86.51/genesis").jsonString(); // or url can be configured
        logger.info("#2 Using following agent provision to initialize VCX\n" + prettyJson(vcxConfig));
        VcxApi.vcxInitWithConfig(vcxConfig).get();

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
                JsonPath.read(vcxConfig, "$.institution_did")).get();

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

        logger.info("#5 Create a connection to alice and print out the invite details");
        int connectionHandle = ConnectionApi.vcxConnectionCreate("alice").get();
        ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get();
        String details = ConnectionApi.connectionInviteDetails(connectionHandle, 0).get();
        logger.info("\n**invite details**");
        logger.info("**You'll be queried to paste this data to alice side of the demo. This is invitation to connect.**");
        logger.info("**It's assumed this is obtained by Alice from Faber by some existing secure channel.**");
        logger.info("**Could be on website via HTTPS, QR code scanned at Faber institution, ...**");
        logger.info("\n******************\n");
        logger.info(details);
        logger.info("\n******************\n");

        logger.info("#6 Polling agency and waiting for alice to accept the invitation. (start alice now)");
        int connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();
        while (connectionState != VcxState.Accepted.getValue()) {
            Thread.sleep(2000);
            connectionState = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();
        }
        logger.info("Connection to alice was Accepted!");

        String schemaAttrs = JsonPath.parse("{" +
                "  name: 'alice'," +
                "  last_name: 'clark'," +
                "  date: '05-2018'," +
                "  degree: 'maths'," +
                "  age: '25'" +
                "}").jsonString();

        logger.info("#12 Create an IssuerCredential object using the schema and credential definition\n"
                + prettyJson(schemaAttrs));
        int credentialHandle = IssuerApi.issuerCreateCredential("alice_degree",
                credDefHandle,
                null,
                schemaAttrs,
                "cred",
                0).get();

        logger.info("#13 Issue credential offer to alice");
        IssuerApi.issuerSendCredentialOffer(credentialHandle, connectionHandle).get();

        logger.info("#14 Poll agency and wait for alice to send a credential request");
        int credentialState = IssuerApi.issuerCredentialUpdateState(credentialHandle).get();
        while (credentialState != VcxState.RequestReceived.getValue()) {
            Thread.sleep(2000);
            credentialState = IssuerApi.issuerCredentialUpdateState(credentialHandle).get();
        }

        logger.info("#17 Issue credential to alice");
        IssuerApi.issuerSendCredential(credentialHandle, connectionHandle).get();

        logger.info("#18 Wait for alice to accept credential");
        credentialState = IssuerApi.issuerCredentialUpdateState(credentialHandle).get();
        while (credentialState != VcxState.Accepted.getValue()) {
            Thread.sleep(2000);
            credentialState = IssuerApi.issuerCredentialUpdateState(credentialHandle).get();
        }

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

        long unixTime = System.currentTimeMillis() / 1000L;
        int proofHandle = ProofApi.proofCreate("proof_uuid",
                proofAttributes,
                proofPredicates,
                "{\"to\": " + unixTime + "}",
                "proof_from_alice").get();

        logger.info("#20 Request proof of degree from alice");
        ProofApi.proofSendRequest(proofHandle, connectionHandle).get();

        logger.info("#21 Poll agency and wait for alice to provide proof");
        int proofState = ProofApi.proofUpdateState(proofHandle).get();
        while (proofState != VcxState.Accepted.getValue()) {
            Thread.sleep(2000);
            proofState = ProofApi.proofUpdateState(proofHandle).get();
            if (proofState == VcxState.None.getValue()) {
                logger.info("Incorrect proof is received");
                System.exit(0);
            }
        }

        logger.info("#27 Process the proof provided by alice");
        GetProofResult proofResult = ProofApi.getProof(proofHandle, connectionHandle).get();

        logger.info("#28 Check if proof is valid");
        if (proofResult.getProof_state() == ProofState.Validated.getValue()) {
            String encodedProof = JsonPath.read(proofResult.getResponse_data(), "$.presentations~attach.[0].data.base64");
            String decodedProof = decodeBase64(encodedProof);
            String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(decodedProof, "$.requested_proof")).jsonString();
            logger.info("Requested proof:" + prettyJson(requestedProof));
            logger.info("Proof is verified");
        } else if (proofResult.getProof_state() == ProofState.Invalid.getValue()) {
            logger.warning("Proof verification failed, credential has been revoked");
        }
        else {
            logger.warning("Unexpected proof state" + proofResult.getProof_state());
        }

        System.exit(0);
    }
}
