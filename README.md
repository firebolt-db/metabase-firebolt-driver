# Metabase Firebolt Driver

[Firebolt documentation page](https://docs.firebolt.io/Guides/integrations/metabase.html)

## Overview


## Installation

### Get the driver
Download the latest driver jar from the [releases page](https://github.com/firebolt-db/metabase-firebolt-driver/releases). Make sure to check the [compatibility matrix](#compatibility-matrix) to get the right version.
**or**
Build the driver from source as described in the [Build from source](#build-from-source) section.

### Run Metabase locally
1. Download the `metabase.jar` file from [Metabase download page](https://www.metabase.com/start/oss/jar).
2. Run Metabase first time. It will generate all the required files and directories, including the `/plugins` directory.
```shell
   java -jar metabase.jar
   ```
3. Stop Metabase.
4. Copy the Firebolt driver jar to the Metabase `/plugins` directory
5. Start Metabase again. Firebolt connection should be available in the list of databases now.


## Build from source

### Prerequisites

- [Leiningen](https://leiningen.org/)

### Steps

1. Clone and build metabase dependency jar.

   ```shell
   git clone https://github.com/metabase/metabase
   cd metabase
   clojure -X:deps prep
   cd modules/drivers
   clojure -X:deps prep
   cd ../..
   clojure -T:build uberjar
   ```

2. Clone metabase-firebolt-driver repo

   ```shell
   cd modules/drivers
   git clone https://github.com/firebolt-db/metabase-firebolt-driver
   ```

3. Prepare metabase dependencies

   ```shell
   cp ../../target/uberjar/metabase.jar metabase-firebolt-driver/
   cd metabase-firebolt-driver
   mkdir repo
   mvn deploy:deploy-file -Durl=file:repo -DgroupId=com.firebolt -DartifactId=metabase-core -Dversion=1.56 -Dpackaging=jar -Dfile=metabase.jar
   ```

4. Build the jar

   ```shell
   LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
   ```

5. The jar will be available in the `target` directory.

## Compatibility matrix

| Metabase Release | Driver Version | Firebolt Version |
|------------------|----------------|------------------|
| <=0.47.x         | 3.0.1          | 1.0              |
| \>=0.48.x        | 3.1.0          | 1.0 and 2.0      |
| >=0.52           | 3.2.0          | 2.0              |
| >=0.55           | 3.2.3          | 2.0              |