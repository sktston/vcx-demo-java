# Running the VCX Java Demo

### Alice/Faber demo
The alice/faber demo is widely used in the indy-sdk demo. The description of the VCX node demo explains it well, 
including the operation of the cloud agent. 
[Description here](https://github.com/hyperledger/indy-sdk/tree/master/vcx/wrappers/node#run-demo).

### Pre-requirements
#### Libraries
Before you'll be able to run demo, you need to make sure you've compiled 
- [`libindy`](https://github.com/hyperledger/indy-sdk/tree/master/libindy)
- [`libvcx`](https://github.com/hyperledger/indy-sdk/tree/master/vcx)
- [`libnullpay`](https://github.com/hyperledger/indy-sdk/tree/master/libnullpay)
- Optionally [`libindystrgpostgres`](https://github.com/hyperledger/indy-sdk/tree/master/experimental/plugins/postgres_storage) if you want to run demo
with postgres wallet.

Library binaries must be located `/usr/local/lib` on OSX, `/usr/lib` on Linux. 

#### Java wrapper for VCX library
**You can skip this step** because this code gets the pre-built LibVCX wrapper from skt-develop maven repository.

Or you can build and use the LibVCX wrapper. [Instructions here](https://github.com/hyperledger/indy-sdk/blob/master/vcx/wrappers/java/README.md#jar).

See `dependencies` section in `build.gradle`.

#### Indy pool
**You can skip this step** because this code uses the skt testnet.

Or you can run pool of Indy nodes on your machine. You can achieve by simply running a docker container
which encapsulates multiple interconnected Indy nodes. 
[Instructions here](https://github.com/hyperledger/indy-sdk#how-to-start-local-nodes-pool-with-docker).

See `genesis_path` of `vcxConfig` variable.

#### Dummy Cloud Agent
**You can skip this step** because this code uses the skt test dummy cloud agent.

Or you can run dummy cloud agent on your machine. [Instructions here](https://github.com/hyperledger/indy-sdk/tree/master/vcx/dummy-cloud-agent).

See `agency_url` of `provisionConfig` variable.

### Steps to run demo
- Run Faber agent, representing an institution
```
./gradlew faber
```
- Give it a few seconds, then run Alice's agent which will connect with Faber's agent
```
./gradlew alice
```

### Demo with Posgres wallet
You can also run demo in mode where both Faber and Alice are using Postgres wallets. Follow 
[instructions](https://github.com/hyperledger/indy-sdk/tree/master/experimental/plugins/postgres_storage) to 
compile postgres wallet plugin and startup local postgres docker container. 

Once you have that ready, use these commands to start demo in postgres mode.
```
./gradlew faber_pg
```
```
./gradlew alice_pg
```

### (Temporal) New DID registration demo
Since LibVCX does not provide APIs to register a new DID, this code was written using LibIndy.

- Usage (default) - SEED (Keyphrase of DID): Random, ROLE: ENDORSER, ALIAS: null
```
./gradlew register_did
```
- Usage with parameters (can be omitted)
```
SEED=00000000000000000000000Endorser1 ROLE=ENDORSER ALIAS=faber ./gradlew register_did
```

