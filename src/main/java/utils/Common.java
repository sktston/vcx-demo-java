package utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.JsonPath;
import com.sun.jna.Library;
import com.sun.jna.Native;
import io.leonard.Base58;
import okhttp3.*;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;
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

    public static String getUidWithMessages(String messages) {
        String message = JsonPath.parse((LinkedHashMap)JsonPath.read(messages,"$.[0].msgs[0]")).jsonString();
        return JsonPath.read(message, "$.uid");
    }

    public static String getHash(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(bytes);
        byte[] digest = md.digest();
        return Base58.encode(digest);
    }

    public static String decodeBase64(String encodedString) {
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] decodedBytes = decoder.decode(encodedString);
        return new String(decodedBytes);
    }

    static OkHttpClient getClient(int timeout) {
        return new OkHttpClient.Builder()
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout, TimeUnit.SECONDS)
                .build();
    }

    public static String requestGET(String url) {
        OkHttpClient client = getClient(60);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] requestGETtoBytes(String url) {
        OkHttpClient client = getClient(60);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try {
            Response response = client.newCall(request).execute();
            return response.body().bytes();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String requestPUT(String url, RequestBody body) {
        OkHttpClient client = getClient(60);
        Request request = new Request.Builder()
                .url(url)
                .put(body)
                .build();
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
