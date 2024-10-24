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

  :repositories [["releases" {:sign-releases true
                               :url "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                               :username :env/MAVEN_REPO_USERNAME
                               :password :env/MAVEN_REPO_PASSWORD}]
                 ["snapshots" {:sign-releases true
                              :url "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                              :username :env/MAVEN_REPO_USERNAME
                              :password :env/MAVEN_REPO_PASSWORD}]
                 ["project" "file:repo"]]

  :signing {:gpg-key ~(System/getenv "SIGN_KEY_ID")}

  :plugins [[lein-pprint "1.3.2"]
            [lein-shell "0.5.0"]]

  :aliases {"file-name" ["with-profile" "uberjar" "pprint" "--no-pretty" "--" ":uberjar-name"]
            "project-version" ["pprint" "--no-pretty" "--" ":version"]
            "get-metabase-core" ["do" ["shell" "bash" "-c"
                                       "TMP_DIR=\\$(mktemp -d) && \\
                                       wget -nv https://downloads.metabase.com/\\$METABASE_VERSION/metabase.jar -O \\$TMP_DIR/metabase.jar && \\
                                       mkdir -p repo && \\
                                       mvn deploy:deploy-file -Durl=file:repo -DgroupId=com.firebolt -DartifactId=metabase-core -Dversion=1.40 -Dpackaging=jar -Dfile=\\$TMP_DIR/metabase.jar"]]
            "sign" ["shell" "gpg" "-b" "-a" "--yes" "-u" ~(System/getenv "SIGN_KEY_ID")]
            ;; lein just doesn't know how to deploy uberjar, so we have to do everything manually
            "deploy-uberjar" ["do"
                              "uberjar,"
                              "pom,"
                              "sign" ~(str uberjar-file ",")
                              "sign" "pom.xml,"
                              ;; There is some internal magic that renames pom.xml.asc to xml.asc during deploy,
                              ;; so we have to hack it
                              "shell" "mv" "pom.xml.asc" "pom.pom.asc,"
                              "deploy" "releases" "io.firebolt/firebolt.metabase-driver" ~(str version)
                              ~(str uberjar-file) "pom.xml"
                              ~(str uberjar-file ".asc") "pom.pom.asc"
                              ]
            }

  :pom-plugins [[org.apache.maven.plugins/maven-source-plugin "3.2.1"
                 ;; this section is optional, values have the same syntax as pom-addition
                 {:executions ([:execution [:id "attach-sources"]
                                [:goals ([:goal "jar"])]
                                [:phase "deploy"]])}]
                [org.apache.maven.plugins/maven-javadoc-plugin "3.3.1"
                 {:executions ([:execution [:id "attach-javadocs"]
                                [:goals ([:goal "jar"])]
                                [:phase "deploy"]])}]]

  ;; Fix issue with Azure <-> Maven communication
  ;; issue happens when we're trying to deploy from GitHub Actions
  ;; we would receive a SockedClosed exception
  :jvm-opts ["-Dmaven.wagon.http.pool=false"
             "-Dmaven.wagon.http.connectionManager.ttlSeconds=60000"
             "-Dmaven.wagon.http.timeout=120000000"
             "-Dmaven.wagon.http.retryHandler.count=20"]

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

