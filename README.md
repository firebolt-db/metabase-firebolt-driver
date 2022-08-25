# Metabase Firebolt Driver

## Installation

Please follow Firebolt's [documentation](https://docs.firebolt.io/integrations/business-intelligence/connecting-to-metabase.html) to install the Metabase Firebolt driver on your Metabase instance. If you are using Metabase Cloud, no installation is required since the driver comes preinstalled.

## Configuring

1. Once you've started up Metabase, open http://localhost:3000 , go to add a database and select "Firebolt".
2. You'll need to provide the Database Name, Username and Password.

### Prerequisites

- [Leiningen](https://leiningen.org/)

### Build from source

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
   mvn deploy:deploy-file -Durl=file:repo -DgroupId=com.firebolt -DartifactId=metabase-core -Dversion=1.40 -Dpackaging=jar -Dfile=metabase.jar
   ```

4. Build the jar

   ```shell
   LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
   ```

5. Let's assume we download `metabase.jar` from the [Metabase jar](https://www.metabase.com/docs/latest/operations-guide/running-the-metabase-jar-file.html) to `~/metabase/` and we built the project above. Copy the built jar to the Metabase plugins directly and run Metabase from there!

   ```shell
   cd ~/metabase/
   java -jar metabase.jar
   ```

You should see a message on startup similar to:

```
2019-05-07 23:27:32 INFO plugins.lazy-loaded-driver :: Registering lazy loading driver :firebolt...
2019-05-07 23:27:32 INFO metabase.driver :: Registered driver :firebolt (parents: #{:sql-jdbc}) 🚚
```
