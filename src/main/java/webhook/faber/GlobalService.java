package webhook.faber;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefPrepareForEndorserResult;
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
}
