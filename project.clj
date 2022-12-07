(defproject metabase/firebolt-driver "1.0.9"
  :min-lein-version "2.5.0"

  :dependencies
  [[com.firebolt/firebolt-jdbc "2.2.4"]]

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
    :uberjar-name   "firebolt.metabase-driver.jar"}})
