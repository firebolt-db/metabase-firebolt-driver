(ns metabase.driver.firebolt-test
  (:require [clojure.test :refer :all]
            [metabase.driver :as driver]
            [metabase.driver.sql-jdbc
             [connection :as sql-jdbc.conn]
             [sync :as sql-jdbc.sync]]
            [metabase.test :as mt]
            [metabase.test.data :as data]
            [metabase.test.data
             [datasets :as datasets]]
            [metabase.test.data.sql.ddl :as ddl]
            [metabase.util.honeysql-extensions :as hx]
            [metabase.query-processor-test :as qp.test]
            [honeysql.core :as hsql]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.test.data.sql :as sql.tx]
            [metabase.test.data.dataset-definitions :as dataset-defs]
            [clojure.string :as str]
            [metabase.driver.common :as driver.common]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase
             [models :refer [Table]]
             [sync :as sync]
             [util :as u]]
            [metabase.test.data
             [interface :as tx]]
            [clojure.java.jdbc :as jdbc]
            [toucan.db :as db]
            [metabase.models
             [database :refer [Database]]])
  (:import [java.sql DatabaseMetaData Types Connection ResultSet]
           [java.time LocalTime OffsetDateTime OffsetTime ZonedDateTime]))

; TEST - Connection details specifiaction
(deftest connection-details->spec-test
  (doseq [[^String expected-spec details]
          [[
             {:classname "com.firebolt.FireboltDriver",
              :ssl true,
              :subname "//api.app.firebolt.io/",
              :subprotocol "firebolt"}
            ]]]
  (let [actual-spec (sql-jdbc.conn/connection-details->spec :firebolt details)]
    (is (= (dissoc expected-spec)
           (dissoc actual-spec)))
    )))

; TEST - connection
(deftest can-connect-test
  (datasets/test-driver  :firebolt
     (letfn [(can-connect? [details]
                           (driver/can-connect? :firebolt details))]
       (is (= true
              (can-connect? (:details (data/db))))
           "can-connect? should return true for normal Firebolt details")
       (is (thrown? java.sql.SQLException
                    (mt/suppress-output
                     (can-connect? (assoc (:details (mt/db)) :db (mt/random-name)))))
           "can-connect? should throw for Firebolt databases that don't exist (#9511)"))))

; TEST - datatypes mapping between driver type and base type
(deftest database-type->base-type-test
  (testing "make sure the various types we use for running tests are actually mapped to the correct DB type"
     (are [db-type expected] (= expected
                                (sql-jdbc.sync/database-type->base-type :firebolt db-type))
                      :Int64      :type/BigInteger
                      :UInt64     :type/BigInteger
                      :Int        :type/Integer
                      :Float      :type/Float
                      :String     :type/Text
                      :DateTime   :type/DateTime
                      :Date       :type/Date
                      :UUID       :type/UUID
                      :Decimal    :type/Decimal)))

; TEST - truncating date functions
(deftest date-functions-test
  (is (= (hsql/call :date_trunc (hx/literal "minute") (metabase.util.honeysql-extensions/with-type-info (hsql/call :cast "2021-06-06 12:12:12" #sql/raw "timestamp") #:metabase.util.honeysql-extensions{:database-type "timestamp"}))
         (sql.qp/date :firebolt :minute "2021-06-06 12:12:12")))
  (is (= (hsql/call :date_trunc (hx/literal "hour") (metabase.util.honeysql-extensions/with-type-info (hsql/call :cast "2021-06-06 12:12:12" #sql/raw "timestamp") #:metabase.util.honeysql-extensions{:database-type "timestamp"}))
         (sql.qp/date :firebolt :hour "2021-06-06 12:12:12")))
  (is (= (hsql/call :date_trunc (hx/literal "day") "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :day "2021-06-06 12:12:12")))
  (is (= (hsql/call :date_trunc (hx/literal "month") "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :month "2021-06-06 12:12:12")))
  (is (= (hsql/call :date_trunc (hx/literal "quarter") "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :quarter "2021-06-06 12:12:12")))
  (is (= (hsql/call :date_trunc (hx/literal "year") "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :year "2021-06-06 12:12:12")))
  (is (= (hsql/call :to_timestamp "2021-06-06 12:12:12")
         (sql.qp/unix-timestamp->honeysql :firebolt :seconds "2021-06-06 12:12:12"))))

; TEST - extracting the part of date functions
(deftest date-extraction-functions-test
  (is (= (hsql/call :to_minute (metabase.util.honeysql-extensions/with-type-info (hsql/call :cast "2021-06-06 12:12:12" #sql/raw "timestamp") #:metabase.util.honeysql-extensions{:database-type "timestamp"}))
         (sql.qp/date :firebolt :minute-of-hour "2021-06-06 12:12:12")))
  (is (= (hsql/call :to_hour (metabase.util.honeysql-extensions/with-type-info (hsql/call :cast "2021-06-06 12:12:12" #sql/raw "timestamp") #:metabase.util.honeysql-extensions{:database-type "timestamp"}))
         (sql.qp/date :firebolt :hour-of-day "2021-06-06 12:12:12")))
  (is (= (hsql/call :to_day_of_month "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :day-of-month "2021-06-06 12:12:12")))
  (is (= (hsql/call :to_day_of_year "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :day-of-year "2021-06-06 12:12:12")))
  (is (= (hsql/call :to_week "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :week-of-year "2021-06-06 12:12:12")))
  (is (= (hsql/call :to_month "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :month-of-year "2021-06-06 12:12:12")))
  (is (= (hsql/call :to_quarter "2021-06-06 12:12:12")
         (sql.qp/date :firebolt :quarter-of-year "2021-06-06 12:12:12"))))

(deftest current-datetime-honeysql-form-test
  (is (= (metabase.util.honeysql-extensions/with-type-info (hsql/call :cast #sql/raw "NOW()" #sql/raw "timestamp") #:metabase.util.honeysql-extensions{:database-type "timestamp"})
     (sql.qp/current-datetime-honeysql-form :firebolt))))

(deftest current-db-time-native-query-test
  (is (= "SELECT CAST(CAST(NOW() AS TIMESTAMP) AS VARCHAR(24))"
     (driver.common/current-db-time-native-query :firebolt))))

(deftest unprepare-values-test
  (is (= "class java.time.LocalTime"
     (unprepare/unprepare-value :firebolt LocalTime)))

  (is (= "class java.time.ZonedDateTime"
     (unprepare/unprepare-value :firebolt ZonedDateTime))))

(deftest driver-support-test
  (is (= false
         (driver/supports? :firebolt :case-sensitivity-string-filter-options)))
  (is (= true
         (driver/supports? :firebolt :basic-aggregations)))
  (is (= true
         (driver/supports? :firebolt :expression-aggregations)))
  (is (= false
         (driver/supports? :firebolt :standard-deviation-aggregations)))
  (is (= true
         (driver/supports? :firebolt :percentile-aggregations)))
  (is (= false
         (driver/supports? :firebolt :nested-fields)))
  (is (= false
         (driver/supports? :firebolt :set-timezone)))
  (is (= false
         (driver/supports? :firebolt :nested-queries)))
  (is (= true
         (driver/supports? :firebolt :binning))))

(deftest ddl-statements-test
  (testing "make sure we didn't break the code that is used to generate DDL statements when we add new test datasets"
     (testing "Create DB DDL statements"
        (is (= nil
               (sql.tx/create-db-sql :firebolt (mt/get-dataset-definition dataset-defs/test-data)))))

     (testing "Create Table DDL statements"
        (is (= (map
                #(str/replace % #"\s+" " ")
                ["DROP TABLE IF EXISTS \"test_data_users\""
                 "CREATE DIMENSION TABLE \"test_data_users\" (\"id\" INTEGER, \"name\" String NULL, \"last_login\" DateTime NULL, \"password\" String NULL) PRIMARY INDEX \"id\""
                 "DROP TABLE IF EXISTS \"test_data_categories\""
                 "CREATE DIMENSION TABLE \"test_data_categories\" (\"id\" INTEGER, \"name\" String NULL) PRIMARY INDEX \"id\""
                 "DROP TABLE IF EXISTS \"test_data_venues\""
                 "CREATE DIMENSION TABLE \"test_data_venues\" (\"id\" INTEGER, \"name\" String NULL, \"category_id\" Int NULL, \"latitude\" Float NULL, \"longitude\" Float NULL, \"price\" Int NULL) PRIMARY INDEX \"id\""
                 "DROP TABLE IF EXISTS \"test_data_checkins\""
                 "CREATE DIMENSION TABLE \"test_data_checkins\" (\"id\" INTEGER, \"date\" Date NULL, \"user_id\" Int NULL, \"venue_id\" Int NULL) PRIMARY INDEX \"id\""])
               (ddl/create-db-tables-ddl-statements :firebolt (-> (mt/get-dataset-definition dataset-defs/test-data)
                                                                  (update :database-name #(str %)))))))))

(deftest aggregations-test
  (mt/test-driver :firebolt
    (testing (str "make sure queries with two or more of the same aggregation type still work.")
       (let [{:keys [rows columns]} (qp.test/rows+column-names
                                     (mt/run-mbql-query checkins
                                                        {:aggregation [[:sum $user_id] [:sum $user_id]]}))]
         (is (= ["sum" "sum_2"]
                columns))
         (is (= [[7929 7929]]
                rows)))
       (let [{:keys [rows columns]} (qp.test/rows+column-names
                                     (mt/run-mbql-query checkins
                                                        {:aggregation [[:sum $user_id] [:sum $user_id] [:sum $user_id]]}))]
         (is (= ["sum" "sum_2" "sum_3"]
                columns))
         (is (= [[7929 7929 7929]]
                rows))))))

(defn has-value [key value]
  "Returns a predicate that tests whether a map contains a specific value"
  (fn [m]
    (= value (m key))))

(deftest describe-database-views-test
  (mt/test-driver :firebolt
    (testing "describe-database views"
    (let [details (mt/dbdef->connection-details :firebolt :db {:database-name  (tx/db-test-env-var-or-throw :firebolt :db)})
          spec    (sql-jdbc.conn/connection-details->spec :firebolt details)]
    ;; create the DB object
    (mt/with-temp Database [database {:engine :firebolt, :details (assoc details :db  (tx/db-test-env-var-or-throw :firebolt :db))}]
      (let [sync! #(sync/sync-database! database)]
      ;; create a view
      (jdbc/execute! spec ["DROP VIEW IF EXISTS \"example_view\""])
      (jdbc/execute! spec ["CREATE VIEW \"example_view\" AS SELECT 'hello world' AS \"name\""])
        ;; now sync the DB
        (sync!)
        ;; now take a look at the Tables in the database, there should be an entry for the view
        (is (= [{:name "example_view"}]
              (filter (has-value :name "example_view") (map (partial into {})
              (db/select [Table :name] :db_id (u/the-id database))))))))))))
