(def version "3.0.5")
(def jdbc-version "3.1.0")

(defproject metabase/firebolt-driver version
  :min-lein-version "3.0.0"

  :dependencies
  [[io.firebolt/firebolt-jdbc jdbc-version]]

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
                "Implementation-Title" "Firebolt Metabase driver"
                "Implementation-Version" version
                }
    :uberjar-name   "firebolt.metabase-driver.jar"}})

