(defproject metabase/firebolt-driver "3.0.3"
  :min-lein-version "3.0.0"

  :dependencies
  [[io.firebolt/firebolt-jdbc "3.0.3"]]

  :repositories [["snapshots" {:sign-releases false
                               :url "https://repo.repsy.io/mvn/firebolt/maven-snapshots"
                               :username :env/REPSY_USER
                               :password :env/REPSY_PASSWORD}]
                 ["releases" {:sign-releases false
                              :url "https://repo.repsy.io/mvn/firebolt/maven"
                              :username :env/REPSY_USER
                              :password :env/REPSY_PASSWORD}]
                 ["project" "file:repo"]]

  :aliases
  {"test"       ["with-profile" "test"]}

  :profiles
  {:provided
   {:dependencies [[com.firebolt/metabase-core "1.40"]]}

   :uberjar
   {:auto-clean     true
    :aot            :all
    :javac-options  ["-target" "1.8", "-source" "1.8"]
    :target-path    "target/%s"
    :manifest {
                "Implementation-Title" "Firebolt JDBC driver"
                "Implementation-Version" "3.0.3"
                }
    :uberjar-name   "firebolt.metabase-driver.jar"}})

