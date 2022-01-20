# Metabase Firebolt Driver

## Installation

Beginning with Metabase 0.32, drivers must be stored in a `plugins` directory in the same directory where `metabase.jar` is, or you can specify the directory by setting the environment variable `MB_PLUGINS_DIR`. There are a few options to get up and running with a custom driver.

### Download Metabase Jar and Run

1. Download a fairly recent Metabase binary release (jar file) from the [Metabase distribution page](https://metabase.com/start/jar.html).
2. Download the Firebolt driver jar from the ["Firebolt Driver"](https://drive.google.com/drive/u/0/folders/1mcfoI8It1tW2XdtUt36WmIXVbtiz_QCL).
3. Download the firebolt jdbc driver jar from the ["Firebolt JDBC Driver"](https://docs.firebolt.io/integrations/connecting-via-jdbc).
4. Create a directory and copy the `metabase.jar` to it.
5. In that directory create a sub-directory called `plugins`.
6. Copy the Firebolt driver jar and Firebolt jdbc driver jar to the `plugins` directory.
7. Make sure you are the in the directory where your `metabase.jar` lives.
8. Run `java -jar metabase.jar`.

In either case, you should see a message on startup similar to:

```
04-15 06:14:08 DEBUG plugins.lazy-loaded-driver :: Registering lazy loading driver :firebolt...
04-15 06:14:08 INFO driver.impl :: Registered driver :firebolt (parents: [:sql-jdbc]) 🚚
```

## Configuring

1. Once you've started up Metabase, open http://localhost:3000 , go to add a database and select "Firebolt".
2. You'll need to provide the Host, Port, Database Name, Username and Password.

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
   git clone https://github.com/raghavSharmaSigmoid/metabase-firebolt-driver
   ```

3. Download Firebolt jdbc driver from the [Firebolt generic jdbc driver](https://docs.firebolt.io/integrations/connecting-via-jdbc) and paste it in metabase-firebolt-driver directory to make a local maven repo.

   ```shell
   cp ../../target/uberjar/metabase.jar metabase-firebolt-driver/
   cd metabase-firebolt-driver
   mkdir repo
   mvn deploy:deploy-file -Durl=file:repo -DgroupId=com.firebolt -DartifactId=metabase-core -Dversion=1.40 -Dpackaging=jar -Dfile=metabase.jar
   mvn deploy:deploy-file -Durl=file:repo -DgroupId=com.firebolt -DartifactId=firebolt-jdbc -Dversion=1.0.0 -Dpackaging=jar -Dfile=firebolt-jdbc-1.18-jar-with-dependencies.jar
   ```

4. Build the jar

   ```shell
   LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
   ```

5. Let's assume we download `metabase.jar` from the [Metabase jar](https://www.metabase.com/docs/latest/operations-guide/running-the-metabase-jar-file.html) to `~/metabase/` and we built the project above. Copy the built jar and also Firebolt generic jdbc driver jar to the Metabase plugins directly and run Metabase from there!

   ```shell
   cd ~/metabase/
   java -jar metabase.jar
   ```

You should see a message on startup similar to:

```
2019-05-07 23:27:32 INFO plugins.lazy-loaded-driver :: Registering lazy loading driver :firebolt...
2019-05-07 23:27:32 INFO metabase.driver :: Registered driver :firebolt (parents: #{:sql-jdbc}) 🚚
```
