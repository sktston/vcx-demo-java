package webhook.alice;

import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import utils.Common;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

import static utils.Common.prettyJson;

@SpringBootApplication
public class Application {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();
    static final String createInvitationUrl = "http://localhost:7201/create-invitation";

    public static void main(String[] args) {
        // InitService.initialize () is automatically called when this application starts.
        SpringApplication.run(Application.class, args);

        try {
            URL url = new URL(createInvitationUrl);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            con.setRequestMethod("GET");

            StringBuilder sb = new StringBuilder();
            if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                br.close();
                logger.info("Alice get invitation from " + createInvitationUrl);
                logger.info(sb.toString());
            } else {
                logger.info("Alice could not get invitation from " + createInvitationUrl);
                logger.info(con.getResponseMessage());
            }

            // accept invitation
            DocumentContext details = JsonPath.parse(sb.toString());
            logger.info("#10 Convert to valid json and string and create a connection to faber");
            int connectionHandle = ConnectionApi.vcxCreateConnectionWithInvite("faber", details.jsonString()).get();
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
