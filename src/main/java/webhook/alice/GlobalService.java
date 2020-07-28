package webhook.alice;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.vcx.VcxApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.JsonPath;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import utils.Common;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.logging.Logger;

import static utils.Common.prettyJson;

@Service
public class GlobalService {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();

    static final String webhookUrl = "http://localhost:7202/notifications"; // alice (me)
    static final String invitationUrl = "http://localhost:7201/invitations"; // faber

    @PostConstruct
    public void initialize() throws Exception {
        logger.info("#0 Initialize");
        Common.loadNullPayPlugin();

        // static configuration
        long utime = System.currentTimeMillis() / 1000;
        String provisionConfig = JsonPath.parse("{" +
                "  agency_url: 'http://localhost:8080'," + // use local
                "  agency_did: 'VsKV7grR1BUE29mG2Fm2kX'," +
                "  agency_verkey: 'Hezce2UWMZ3wUhVkh2LfKSs8nDzWwzs2Win7EzNN3YaR'," +
                "  wallet_name: 'node_vcx_demo_alice_wallet_" + utime + "'," +
                "  wallet_key: '123'," +
                "  payment_method: 'null'," +
                "  enterprise_seed: '000000000000000000000000000User1'" + // SEED of alice's DID that does not need to be registered in the ledger
                "}").jsonString();

        // Communication method. aries.
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "protocol_type", "4.0").jsonString();
        logger.info("Running with Aries VCX Enabled! Make sure VCX agency is configured to use protocol_type 4.0");

        // add webhook url to config
        provisionConfig = JsonPath.parse(provisionConfig).put("$", "webhook_url", webhookUrl).jsonString();
        logger.info("Running with webhook notifications enabled! Webhook url = " + webhookUrl);

        logger.info("#8 Provision an agent and wallet, get back configuration details: \n" + prettyJson(provisionConfig));
        String vcxConfig = UtilsApi.vcxProvisionAgent(provisionConfig);

        vcxConfig = JsonPath.parse(vcxConfig).put("$", "institution_name", "alice")
                .put("$", "institution_logo_url", "http://robohash.org/345")
                .put("$", "protocol_version", "2")
                .put("$", "genesis_path", System.getProperty("user.dir") + "/genesis.txn").jsonString();
        logger.info("#9 Initialize libvcx with new configuration\n" + prettyJson(vcxConfig));
        VcxApi.vcxInitWithConfig(vcxConfig).get();

        logger.info("addRecordWallet (vcxConfig, defaultVcxConfig, " + prettyJson(vcxConfig) + ")");
        WalletApi.addRecordWallet("vcxConfig", "defaultVcxConfig", vcxConfig, "").get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void receiveInvitation() throws Exception {

        // STEP.2 - receive invitation & create connection A2F
        // accept invitation
        WebClient webClient = WebClient.create();
        String details = WebClient.create().get()
                .uri(invitationUrl)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(3));
        logger.info("details" + details);

        logger.info("#10 Convert to valid json and string and create a connection to faber");
        int connectionHandle = ConnectionApi.vcxCreateConnectionWithInvite("faber", details).get();
        ConnectionApi.vcxConnectionConnect(connectionHandle, "{}").get();
        ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

        String connection = ConnectionApi.connectionSerialize(connectionHandle).get();
        String pwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();
        logger.info("addRecordWallet (connection, " + pwDid + ", " + prettyJson(connection) + ")");
        WalletApi.addRecordWallet("connection", pwDid, connection, "").get();
        ConnectionApi.connectionRelease(connectionHandle);
    }
}
