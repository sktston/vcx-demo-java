import org.hyperledger.indy.sdk.did.Did;
import org.hyperledger.indy.sdk.did.DidJSONParameters;
import org.hyperledger.indy.sdk.did.DidResults;
import org.hyperledger.indy.sdk.ledger.Ledger;
import org.hyperledger.indy.sdk.pool.Pool;
import org.hyperledger.indy.sdk.pool.PoolJSONParameters;
import org.hyperledger.indy.sdk.wallet.Wallet;
import org.json.JSONObject;
import utils.Common;

import java.io.File;
import java.util.logging.Logger;

import static utils.Common.prettyJson;

public class RegisterDid {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();

    static final String GENESIS_FILE_PATH = System.getProperty("user.dir") + "/genesis.txn";
    static final String POOL_NAME = "default_pool";
    static final String WALLET = "Wallet1";
    static final String TYPE = "default";
    static final String WALLET_CONFIG = "{ \"id\":\"" + WALLET + "\", \"storage_type\":\"" + TYPE + "\"}";
    static final String WALLET_CREDENTIALS = "{\"key\":\"8dvfYSt5d1taSd6yJdpjq4emkwsPDDLYxkNFysFD2cZY\", \"key_derivation_method\":\"RAW\"}";
    static final String TRUSTEE_SEED = "000000000000000000000000Trustee1";

    static final String NEW_SEED = System.getenv().getOrDefault("SEED", null);
    static final String NEW_ROLE = System.getenv().getOrDefault("ROLE", "ENDORSER");
    static final String NEW_ALIAS = System.getenv().getOrDefault("ALIAS", null);

    static Pool pool;
    static Wallet wallet;

    public static void main(String[] args) throws Exception {
        // Library logger setup - ERROR|WARN|INFO|DEBUG|TRACE
        Common.setLibraryLogger("ERROR");

        logger.info("#0 Create Pool and Wallet (if stopped, remove ~/.indy_client/pool/" + POOL_NAME + " and check genesis.txn)");
        createPoolAndWallet();

        logger.info("#1 Create and store SUBMITTER DID to the Wallet");
        // SUBMITTER is TRUSTEE (can change STEWARD)
        String submitterDidJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(null, TRUSTEE_SEED, null, null).toJson();
        DidResults.CreateAndStoreMyDidResult submitterDidResult = Did.createAndStoreMyDid(wallet, submitterDidJson).get();
        String submitterDid = submitterDidResult.getDid();

        logger.info("#2 Create and store a NEW DID to the Wallet");
        String newDidJson = "{}"; // create with RANDOM SEED
        if (NEW_SEED != null)
            newDidJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(null, NEW_SEED, null, null).toJson();
        DidResults.CreateAndStoreMyDidResult newDidResult = Did.createAndStoreMyDid(wallet, newDidJson).get();
        String newDid = newDidResult.getDid();
        String newVerkey = newDidResult.getVerkey();

        logger.info("#3 Make the request to register a NEW DID");
        String nymRequest = Ledger.buildNymRequest(submitterDid, newDid, newVerkey, NEW_ALIAS, NEW_ROLE).get();

        logger.info("#4 Submit the registration request with the sign of SUBMITTER\n");
        Ledger.signAndSubmitRequest(pool, wallet, submitterDid, nymRequest).get();

        logger.info("#5 Make the request to get the NEW DID from ledger");
        String getNymRequest = Ledger.buildGetNymRequest(newDid, newDid).get();

        logger.info("#6 Submit the get request without any sign");
        String getNymResponse = Ledger.submitRequest(pool, getNymRequest).get();

        String nymDataJson = Ledger.parseGetNymResponse(getNymResponse).get();
        JSONObject nymData = new JSONObject(nymDataJson);
        /*
         * {
         *     did: DID as base58-encoded string for 16 or 32 bit DID value.
         *     verkey: verification key as base58-encoded string.
         *     role: Role associated number
         *                             null (common USER)
         *                             0 - TRUSTEE
         *                             2 - STEWARD
         *                             101 - TRUST_ANCHOR
         *                             101 - ENDORSER - equal to TRUST_ANCHOR that will be removed soon
         *                             201 - NETWORK_MONITOR
         * }
         */
        logger.info("#7 Registered new DID:\n" + prettyJson(nymData.toString()));

        logger.info("#0 Delete Pool and Wallet");
        deletePoolAndWallet();
        System.exit(0);
    }

    static void createPoolLedgerConfig(String poolName) throws Exception {
        File genesisTxnFile = new File(GENESIS_FILE_PATH);
        PoolJSONParameters.CreatePoolLedgerConfigJSONParameter createPoolLedgerConfigJSONParameter
                = new PoolJSONParameters.CreatePoolLedgerConfigJSONParameter(genesisTxnFile.getAbsolutePath());
        Pool.createPoolLedgerConfig(poolName, createPoolLedgerConfigJSONParameter.toJson()).get();
    }

    static void createPoolAndWallet() throws Exception {
        createPoolLedgerConfig(POOL_NAME);
        pool = Pool.openPoolLedger(POOL_NAME, null).get();

        Wallet.createWallet(WALLET_CONFIG, WALLET_CREDENTIALS).get();
        wallet = Wallet.openWallet(WALLET_CONFIG, WALLET_CREDENTIALS).get();
    }

    static void deletePoolAndWallet() throws Exception {
        pool.closePoolLedger().get();
        Pool.deletePoolLedgerConfig(POOL_NAME);
        wallet.closeWallet().get();
        Wallet.deleteWallet(WALLET_CONFIG, WALLET_CREDENTIALS).get();
    }

}
