package webhook.faber;

import com.evernym.sdk.vcx.credentialDef.CredentialDefApi;
import com.evernym.sdk.vcx.schema.SchemaApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.vcx.VcxApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.DocumentContext;
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
public class InitService {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();

    static final String webhookUrl = "http://localhost:7201/notifications";

    @PostConstruct
    public void initialize() throws Exception {
        logger.info("#0 Initialize");
        Common.loadNullPayPlugin();

        long utime = System.currentTimeMillis() / 1000;
        DocumentContext provisionConfig = JsonPath.parse("{" +
                //"  agency_url: 'http://15.165.161.165:8080'," + // skt test dummy cloud agent
                "  agency_url: 'http://localhost:8080'," + // use local
                "  agency_did: 'VsKV7grR1BUE29mG2Fm2kX'," +
                "  agency_verkey: 'Hezce2UWMZ3wUhVkh2LfKSs8nDzWwzs2Win7EzNN3YaR'," +
                "  wallet_name: 'node_vcx_demo_faber_wallet_" + utime + "'," +
                "  wallet_key: '123'," +
                "  payment_method: 'null'," +
                "  enterprise_seed: '000000000000000000000000Steward1'" + // SEED of faber's DID already registered in the ledger
                "}");

        // Communication method. aries.
        provisionConfig.put("$", "protocol_type", "3.0");
        logger.info("Running with Aries VCX Enabled! Make sure VCX agency is configured to use protocol_type 3.0");

        // add webhook url to config
        provisionConfig.put("$", "webhook_url", webhookUrl);
        logger.info("Running with webhook notifications enabled! Webhook url = " + webhookUrl);

        logger.info("#1 Config used to provision agent in agency: \n" + prettyJson(provisionConfig.jsonString()));
        DocumentContext vcxConfig = JsonPath.parse(UtilsApi.vcxProvisionAgent(provisionConfig.jsonString()));

        vcxConfig.put("$", "institution_name", "faber")
                .put("$", "institution_logo_url", "http://robohash.org/234")
                .put("$", "protocol_version", "2")
                .put("$", "genesis_path", System.getProperty("user.dir") + "/genesis.txn"); // file configured for skt testnet
        //.put("$", "genesis_path", "http://54.180.86.51/genesis"); // or url can be configured
        logger.info("#2 Using following agent provision to initialize VCX\n" + prettyJson(vcxConfig.jsonString()));
        VcxApi.vcxInitWithConfig(vcxConfig.jsonString()).get();

        WalletApi.addRecordWallet("vcxConfig", "defaultVcxConfig", vcxConfig.jsonString()).get();

        // TODO: may vcxUpdateWebhookUrl is called during vcxInitWithConfig
        VcxApi.vcxUpdateWebhookUrl(webhookUrl).get();

        createSchema();
        createCredentialDefinition();
    }

    public void createSchema() throws Exception {
        // define schema with actually needed
        String version = getRandomInt(1, 99) + "." + getRandomInt(1, 99) + "." + getRandomInt(1, 99);
        DocumentContext schemaData = JsonPath.parse("{" +
                "  schema_name: 'degree_schema'," +
                "  schema_version: '" + version + "'," +
                "  attributes: ['name', 'last_name', 'date', 'degree', 'age']" +
                "}");
        logger.info("#3 Create a new schema on the ledger: \n" + prettyJson(schemaData.jsonString()));
        int schemaHandle = SchemaApi.schemaCreate("schema_uuid",
                schemaData.read("$.schema_name"),
                schemaData.read("$.schema_version"),
                JsonPath.parse((List)schemaData.read("$.attributes")).jsonString(),
                0).get();
        String schemaId = SchemaApi.schemaGetSchemaId(schemaHandle).get();
        logger.info("Created schema with id " + schemaId + " and handle " + schemaHandle);

        String schema = SchemaApi.schemaSerialize(schemaHandle).get();
        logger.info("Serialized schema: \n" + prettyJson(schema));

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
        DocumentContext credDefData = JsonPath.parse("{" +
                "  schemaId: '" + schemaId + "'," +
                "  tag: 'tag1'," +
                "  config: {" +
                "    support_revocation: false," +
                "    tails_file: '/tmp/tails'," +
                "    max_creds: 5" +
                "  }" +
                "}");
        logger.info("#4 Create a new credential definition on the ledger: \n" + prettyJson(credDefData.jsonString()));
        int credDefHandle = CredentialDefApi.credentialDefCreate("'cred_def_uuid'",
                "cred_def_name",
                credDefData.read("$.schemaId"),
                null,
                credDefData.read("$.tag"),
                JsonPath.parse((LinkedHashMap)credDefData.read("$.config")).jsonString(),
                0).get();
        String credDefId = CredentialDefApi.credentialDefGetCredentialDefId(credDefHandle).get();
        logger.info("Created credential with id " + credDefId + " and handle " + credDefHandle);

        String credDef = CredentialDefApi.credentialDefSerialize(credDefHandle).get();
        logger.info("Serialized credential definition: \n" + prettyJson(credDef));

        WalletApi.addRecordWallet("credentialDef", "defaultCredentialDef", credDef).get();
        CredentialDefApi.credentialDefRelease(credDefHandle);
    }
}
