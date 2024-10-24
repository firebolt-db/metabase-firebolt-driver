(def version "3.0.6")
(def uberjar-name (str "firebolt.metabase-driver-" version ".jar"))
(def uberjar-file (str "target/uberjar/" uberjar-name))

(defproject io.firebolt/firebolt.metabase-driver version

  :description "A driver for Metabase to allow connecting to Firebolt"
  :url "https://github.com/firebolt-db/metabase-firebolt-driver"

  :license {:name "The Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.txt"}

  :min-lein-version "2.0.0"

  :scm {:name "git"
        :url "https://github.com/firebolt-db/metabase-firebolt-driver"}

  :pom-addition ([:developers
                  [:developer
                   [:id "sburlakov"]
                   [:name "Stepan Burlakov"]
                   [:email "stepan.burlakov@firebolt.io"]]
                  [:developer
                   [:id "ptiurin"]
                   [:name "Petro Tiurin"]
                   [:email "petro.tiurin@firebolt.io"]]
                  ])

  :dependencies
  [[io.firebolt/firebolt-jdbc "3.1.0"]]

  :repositories [["project" "file:repo"]]

  :plugins [[lein-pprint "1.3.2"]
            [lein-shell "0.5.0"]]

  :aliases {"file-name" ["with-profile" "uberjar" "pprint" "--no-pretty" "--" ":uberjar-name"]
            "project-version" ["pprint" "--no-pretty" "--" ":version"]
            "get-metabase-core" ["do" ["shell" "bash" "-c"
                                       "TMP_DIR=\\$(mktemp -d) && \\
                                       wget -nv https://downloads.metabase.com/\\$METABASE_VERSION/metabase.jar -O \\$TMP_DIR/metabase.jar && \\
                                       mkdir -p repo && \\
                                       mvn deploy:deploy-file -Durl=file:repo -DgroupId=com.firebolt -DartifactId=metabase-core -Dversion=1.40 -Dpackaging=jar -Dfile=\\$TMP_DIR/metabase.jar"]]
            }

  :profiles
  {:provided
   {:dependencies [[com.firebolt/metabase-core "1.40"]]}

   :uberjar
   {:auto-clean    true
    :aot           :all
    :javac-options ["-target" "1.8", "-source" "1.8"]
    :target-path   "target/%s"
    :manifest      {"Implementation-Title"   "Firebolt Metabase driver"
                    "Implementation-Version" version}
    :uberjar-name  ~(str uberjar-name)}})

