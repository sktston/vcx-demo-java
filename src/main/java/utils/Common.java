package utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.JsonPath;
import com.sun.jna.Library;
import com.sun.jna.Native;
import org.apache.commons.cli.*;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.logging.*;

public class Common {

    static boolean loggerInitialized = false;

    public static Logger getDemoLogger() {
        if (loggerInitialized) return Logger.getGlobal();

        // remove rootLogger
        Logger rootLogger = Logger.getLogger("");
        Handler[] handlers = rootLogger.getHandlers();
        if (handlers[0] instanceof ConsoleHandler) {
            rootLogger.removeHandler(handlers[0]);
        }

        Logger logger = Logger.getGlobal();
        logger.setLevel(Level.INFO);

        Handler handler = new ConsoleHandler();
        handler.setFormatter(new LogFormatter());
        logger.addHandler(handler);

        loggerInitialized = true;
        return logger;
    }

    public static void setLibraryLogger(String logLevel) {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, logLevel);
    }

    public static class LogFormatter extends Formatter {
        public String format(LogRecord rec) {
            StringBuffer buf = new StringBuffer(1000);

            buf.append("[").append(rec.getSourceMethodName()).append("] ");
            buf.append(rec.getLevel()).append(" ").append(rec.getSourceClassName()).append(" - ");
            buf.append(rec.getMessage()).append("\n");

            return buf.toString();
        }
    }

    public static String prettyJson(String jsonString) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(JsonParser.parseString(jsonString));
    }

    private interface NullPayApi extends Library {
        public int nullpay_init();
    }

    public static void loadNullPayPlugin(){
        NullPayApi nullPayApi = Native.loadLibrary("nullpay", NullPayApi.class);
        nullPayApi.nullpay_init();
    }

    private interface PostgresApi extends Library {
        public int postgresstorage_init();
    }

    public static void loadPostgresPlugin(){
        PostgresApi postgresApi = Native.loadLibrary("indystrgpostgres", PostgresApi.class);
        postgresApi.postgresstorage_init();
    }

    public static int getRandomInt(int min, int max) {
        if (min >= max)
            throw new IllegalArgumentException("max must be greater than min");
        Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }

    public static CommandLine getCommandLine(String[] args) {
        Option help = new Option("h", "help", false, "Display this usage guide.");
        Option postgres = new Option("p", "postgres", false,"If specified, postgres wallet will be used.");

        Options options = new Options();
        options.addOption(help);
        options.addOption(postgres);

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine line = parser.parse(options, args);
            if(line.hasOption("help")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp( "task", options );
                return null;
            }
            return line;
        } catch (ParseException exp) {
            System.err.println("Parsing failed. Reason: " + exp.getMessage());
        }
        return null;
    }

    public static String requestGet(String requestUrl, String body) throws Exception {
        URL url = new URL(requestUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept-Charset", "UTF-8");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        StringBuilder sb = new StringBuilder();
        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
        } else {
            throw new IOException("ResponseCode is not HTTP_OK" + conn.getResponseCode());
        }
        return sb.toString();
    }

    public static String requestPost(String requestUrl, String body) throws Exception {
        URL url = new URL(requestUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept-Charset", "UTF-8");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();

        StringBuilder sb = new StringBuilder();
        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
        } else {
            throw new IOException("ResponseCode is not HTTP_OK" + conn.getResponseCode());
        }
        return sb.toString();
    }

    public static String agencyUpdateWebhookUrl(String provisionConfig, String vcxConfig, String webhookUrl) throws Exception {
        String body = JsonPath.parse("{ webhookUrl : '" + webhookUrl + "' }").jsonString();

        WebClient webClient = WebClient.create(JsonPath.read(provisionConfig, "$.agency_url"));
        String response =  webClient.post()
                .uri("/agent/" + JsonPath.read(vcxConfig, "$.remote_to_sdk_did"))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .syncBody(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(3));

        return response;
    }

    public static String getUidWithMessages(String messages) {
        String message = JsonPath.parse((LinkedHashMap)JsonPath.read(messages,"$.[0].msgs[0]")).jsonString();
        return JsonPath.read(message, "$.uid");
    }
}
