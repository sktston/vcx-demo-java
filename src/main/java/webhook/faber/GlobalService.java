package webhook.faber;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credentialDef.CredentialDefApi;
import com.evernym.sdk.vcx.schema.SchemaApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.vcx.VcxApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Service;
import utils.Common;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Logger;

import static utils.Common.getRandomInt;
import static utils.Common.prettyJson;

@Service
public class GlobalService {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();

    static final String webhookUrl = "http://localhost:7201/notifications";

    // node agency is not support vcxUpdateWebhookUrl currently
    // therefore we directly communicate with agency for now
    // dummy cloud agent -> true, node agency -> false
    static final boolean supportVcxUpdateWebhookUrl = false;

    @PostConstruct
    public void initialize() throws Exception {
        logger.info("#0 Initialize");
        Common.loadNullPayPlugin();

        long utime = System.currentTimeMillis() / 1000;
        String provisionConfig  = JsonPath.parse("{" +
                //"  agency_url: 'http://15.165.161.165:8080'," + // skt test dummy cloud agent
                "  agency_url: 'http://localhost:8080'," + // use local
                "  agency_did: 'VsKV7grR1BUE29mG2Fm2kX'," +
                "  agency_verkey: 'Hezce2UWMZ3wUhVkh2LfKSs8nDzWwzs2Win7EzNN3YaR'," +
                "  wallet_name: 'node_vcx_demo_faber_wallet_" + utime + "'," +
                "  wallet_key: '123'," +
                "  payment_method: 'null'," +
                "  enterprise_seed: '000000000000000000000000Steward1'" + // SEED of faber's DID already registered in the ledger
                "}").jsonString();

        // Communication method. aries.
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "protocol_type", "3.0").jsonString();
        logger.info("Running with Aries VCX Enabled! Make sure VCX agency is configured to use protocol_type 3.0");

        // add webhook url to config
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "webhook_url", webhookUrl).jsonString();
        logger.info("Running with webhook notifications enabled! Webhook url = " + webhookUrl);

        logger.info("#1 Config used to provision agent in agency: \n" + prettyJson(provisionConfig));
        String vcxConfig = UtilsApi.vcxProvisionAgent(provisionConfig);

        vcxConfig = JsonPath.parse(vcxConfig).put("$", "institution_name", "faber")
                .put("$", "institution_logo_url", "http://robohash.org/234")
                .put("$", "protocol_version", "2")
                .put("$", "genesis_path", System.getProperty("user.dir") + "/genesis.txn").jsonString(); // file configured for skt testnet
        //.put("$", "genesis_path", "http://54.180.86.51/genesis").jsonString(); // or url can be configured
        logger.info("#2 Using following agent provision to initialize VCX\n" + prettyJson(vcxConfig));
        VcxApi.vcxInitWithConfig(vcxConfig).get();

        WalletApi.addRecordWallet("vcxConfig", "defaultVcxConfig", vcxConfig).get();

        if (supportVcxUpdateWebhookUrl) {
            // TODO: may vcxUpdateWebhookUrl is called during vcxInitWithConfig
            VcxApi.vcxUpdateWebhookUrl(webhookUrl).get();
        } else {
            Common.agencyUpdateWebhookUrl(provisionConfig, vcxConfig, webhookUrl);
        }

        createSchema();
        createCredentialDefinition();
        createInviation();
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

        logger.info("addRecordWallet - (schema, defaultSchema, " + prettyJson(schema) + ")");
        WalletApi.addRecordWallet("schema", "defaultSchema", schema).get();
        SchemaApi.schemaRelease(schemaHandle);
    }

    public void createCredentialDefinition() throws Exception {
        String schemaRecord = WalletApi.getRecordWallet("schema", "defaultSchema", "").get();
        String schema = JsonPath.read(schemaRecord,"$.value");
        int schemaHandle = SchemaApi.schemaDeserialize(schema).get();
        String schemaId = SchemaApi.schemaGetSchemaId(schemaHandle).get();
        SchemaApi.schemaRelease(schemaHandle);

        // define credential definition with actually needed
        String version = getRandomInt(1, 99) + "." + getRandomInt(1, 99) + "." + getRandomInt(1, 99);
        String credDefData = JsonPath.parse("{" +
                "  schemaId: '" + schemaId + "'," +
                "  tag: 'tag." + version + "'," +
                "  config: {" +
                "    support_revocation: true," +
                "    tails_file: '/tmp/tails'," +
                "    max_creds: 5" +
                "  }" +
                "}").jsonString();
        logger.info("#4 Create a new credential definition on the ledger: \n" + prettyJson(credDefData));
        int credDefHandle = CredentialDefApi.credentialDefCreate("'cred_def_uuid'",
                "cred_def_name",
                JsonPath.read(credDefData, "$.schemaId"),
                null,
                JsonPath.read(credDefData, "$.tag"),
                JsonPath.parse((LinkedHashMap)JsonPath.read(credDefData,"$.config")).jsonString(),
                0).get();
        String credDefId = CredentialDefApi.credentialDefGetCredentialDefId(credDefHandle).get();
        logger.info("Created credential with id " + credDefId + " and handle " + credDefHandle);

        String credDef = CredentialDefApi.credentialDefSerialize(credDefHandle).get();

        logger.info("addRecordWallet - (credentialDef, defaultCredentialDef, " + prettyJson(credDef) + ")");
        WalletApi.addRecordWallet("credentialDef", "defaultCredentialDef", credDef).get();
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

        logger.info("addRecordWallet - (invitation, defaultInvitation, " + prettyJson(details) + ")");
        WalletApi.addRecordWallet("invitation", "defaultInvitation", details).get();

        String connection = ConnectionApi.connectionSerialize(connectionHandle).get();
        String pwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();
        logger.info("addRecordWallet - (connection, " + pwDid + ", " + prettyJson(connection) + ")");
        WalletApi.addRecordWallet("connection", pwDid, connection).get();
        ConnectionApi.connectionRelease(connectionHandle);
    }
}
