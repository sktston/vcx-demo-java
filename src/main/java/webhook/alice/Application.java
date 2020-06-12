package webhook.alice;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.reactive.function.client.WebClient;
import utils.Common;

import java.net.URL;
import java.time.Duration;
import java.util.logging.Logger;

import static utils.Common.prettyJson;

@SpringBootApplication
public class Application {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();
    static final String invitationUrl = "http://localhost:7201/invitations";

    public static void main(String[] args) {
        // InitService.initialize () is automatically called when this application starts.
        SpringApplication.run(Application.class, args);

        try {
            // STEP.2 - receive invitation & create connection A2F
            // accept invitation
            URL url = new URL(invitationUrl);
            WebClient webClient = WebClient.create(url.getProtocol() + "://" + url.getAuthority());
            String details = webClient.get()
                    .uri(url.getPath())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            logger.info("details" + details);

            logger.info("#10 Convert to valid json and string and create a connection to faber");
            int connectionHandle = ConnectionApi.vcxCreateConnectionWithInvite("faber", details).get();
            ConnectionApi.vcxConnectionConnect(connectionHandle, "{\"use_public_did\": true}").get();
            ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

            String connection = ConnectionApi.connectionSerialize(connectionHandle).get();
            String pwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();
            logger.info("Add record - connection: \n" + prettyJson(connection));
            WalletApi.addRecordWallet("connection", pwDid, connection).get();
            ConnectionApi.connectionRelease(connectionHandle);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
