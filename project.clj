(defproject metabase/firebolt-driver "1.0.0"
  :min-lein-version "2.5.0"

  :dependencies
  [[com.firebolt/firebolt-jdbc "1.0.0"]]

  :repositories {"project" "file:repo"}

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
