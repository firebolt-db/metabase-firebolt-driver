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
            [metabase.util.honey-sql-2 :as hx]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.test.data.sql :as sql.tx]
            [metabase.test.data.interface :as tx]
            [metabase.test.data.dataset-definitions :as dataset-defs]
            [clojure.string :as str]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase
             [models :refer [Table, Database]]
             [sync :as sync]
             [util :as u]]
            [clojure.java.jdbc :as jdbc]
            [toucan2.core :as t2]
            [honeysql.core :as hsql]
    )
  (:import [java.time LocalTime ZonedDateTime]))

; TEST - Connection details specification
(deftest connection-details->spec-test
  (let [expected-spec-default {:classname   "com.firebolt.FireboltDriver",
                               :subname     "//api.app.firebolt.io/",
                               :subprotocol "firebolt"}]
    (doseq [
            [expected-spec details]
            [[
              expected-spec-default,
              {}],
             [
              expected-spec-default,
              {:additional-options nil}],
             [
              expected-spec-default,
              {:additional-options ""}],
             [
              {
               :classname   "com.firebolt.FireboltDriver",
               :subname     "//api.dev.firebolt.io/mydb?environment=dev",
               :subprotocol "firebolt"
               :user        "myuser",
               :password    "mypassword",
               :account     "myaccount",
               :engine_name "myengine"
               },
              {
               :additional-options "environment=dev",
               :user               "myuser",
               :password           "mypassword",
               :account            "myaccount",
               :db                 "mydb",
               :engine_name        "myengine"
               }]
             ]
            ]
      (testing (pr-str details))
        (let [actual-spec (sql-jdbc.conn/connection-details->spec :firebolt details)]
          (is (= (dissoc expected-spec)
                 (dissoc actual-spec)))
          ))))

; TEST - connection
(deftest can-connect-test
  (datasets/test-driver  :firebolt
     (letfn [(can-connect? [details]
                           (driver/can-connect? :firebolt details))]
       (is (= true
              (can-connect? (:details (data/db))))
           "can-connect? should return true for normal Firebolt details"))))

; TEST - datatypes mapping between driver type and base type
(deftest database-type->base-type-test
  (testing "make sure the various types we use for running tests are actually mapped to the correct DB type"
     (are [db-type expected] (= expected
                                (sql-jdbc.sync/database-type->base-type :firebolt db-type))
                        :array        :type/Array
                        :bigint        :type/BigInteger
                        :integer       :type/Integer
                        :string        :type/Text
                        :bool          :type/Boolean
                        :boolean       :type/Boolean
                        :bytea         :type/*    ; byte array
                        :date          :type/Date
                        :decimal       :type/Decimal
                        :int           :type/Integer
                        :numeric       :type/Decimal
                        :real          :type/Float
                        (keyword "double precision")    :type/Float
                        :text          :type/Text
                        :timestamp     :type/DateTime
                        :timestamptz   :type/DateTimeWithLocalTZ
                        :varchar       :type/Text
                        (keyword "timestamp with timezone")    :type/DateTime
                        (keyword "timestamp without timezone") :type/DateTime)))

; TEST - truncating date functions
(deftest date-functions-test
  (is (= [[:date_trunc (hx/literal "minute") [:cast "2021-06-06 12:12:12" :timestamptz]]]
         (sql.qp/date :firebolt :minute "2021-06-06 12:12:12")))
  (is (= [[:date_trunc (hx/literal "hour") [:cast "2021-06-06 12:12:12" :timestamptz]]]
         (sql.qp/date :firebolt :hour "2021-06-06 12:12:12")))
  (is (= [[:date_trunc (hx/literal "day") [:cast "2021-06-06 12:12:12" :timestamptz]]]
         (sql.qp/date :firebolt :day "2021-06-06 12:12:12")))
  (is (= [[:date_trunc (hx/literal "month") [:cast "2021-06-06 12:12:12" :timestamptz]]]
         (sql.qp/date :firebolt :month "2021-06-06 12:12:12")))
  (is (= [[:date_trunc (hx/literal "quarter") [:cast "2021-06-06 12:12:12" :timestamptz]]]
         (sql.qp/date :firebolt :quarter "2021-06-06 12:12:12")))
  (is (= [[:date_trunc (hx/literal "year") [:cast "2021-06-06 12:12:12" :timestamptz]]]
         (sql.qp/date :firebolt :year "2021-06-06 12:12:12")))
  (is (= [[:to_timestamp "2021-06-06 12:12:12"]]
         (sql.qp/unix-timestamp->honeysql :firebolt :seconds "2021-06-06 12:12:12"))))

; TEST - extracting the part of date functions
(deftest date-extraction-functions-test
  (is (= [[:extract [:raw "minute" " FROM " [:cast "2021-06-06 12:12:12" :timestamptz]]]]
         (sql.qp/date :firebolt :minute-of-hour "2021-06-06 12:12:12")))
  (is (= [[:extract [:raw "hour" " FROM " [:cast "2021-06-06 12:12:12" :timestamptz]]]]
         (sql.qp/date :firebolt :hour-of-day "2021-06-06 12:12:12")))
  (is (= [[:extract [:raw "day" " FROM " [:cast "2021-06-06 12:12:12" :timestamptz]]]]
         (sql.qp/date :firebolt :day-of-month "2021-06-06 12:12:12")))
  (is (= [[:extract [:raw "doy" " FROM " [:cast "2021-06-06 12:12:12" :timestamptz]]]]
         (sql.qp/date :firebolt :day-of-year "2021-06-06 12:12:12")))
  (is (= (hsql/call :ceil
           (hsql/call :/
             [[:extract [:raw "doy" " FROM " [:cast
                 [[:date_add "day" -1
                   [[:date_trunc [:metabase.util.honey-sql-2/literal "week"]
                     [:cast [[:date_add "day" 1 "2021-06-06 12:12:12"]] :timestamptz]]]]]
                 :timestamptz]]]]
             7.0))
         (sql.qp/date :firebolt :week-of-year "2021-06-06 12:12:12")))
  (is (= [[:extract [:raw "month" " FROM " [:cast "2021-06-06 12:12:12" :timestamptz]]]]
         (sql.qp/date :firebolt :month-of-year "2021-06-06 12:12:12")))
  (is (= [[:extract [:raw "quarter" " FROM " [:cast "2021-06-06 12:12:12" :timestamptz]]]]
         (sql.qp/date :firebolt :quarter-of-year "2021-06-06 12:12:12"))))

(deftest current-datetime-honeysql-form-test
  (is (= :%now
     (sql.qp/current-datetime-honeysql-form :firebolt))))

; TODO: Test db-default-timezone instead
;(deftest current-db-time-native-query-test
;  (is (= "SELECT CAST(CAST(NOW() AS TIMESTAMP) AS VARCHAR(24))"
;     (driver.common/current-db-time-native-query :firebolt))))

(deftest unprepare-values-test
  (is (= "class java.time.LocalTime"
     (unprepare/unprepare-value :firebolt LocalTime)))

  (is (= "class java.time.ZonedDateTime"
     (unprepare/unprepare-value :firebolt ZonedDateTime))))

(deftest driver-support-test
  (is (= false
         (driver/database-supports? :firebolt :case-sensitivity-string-filter-options nil)))
  (is (= true
         (driver/database-supports? :firebolt :basic-aggregations nil)))
  (is (= true
         (driver/database-supports? :firebolt :expression-aggregations nil)))
  (is (= false
         (driver/database-supports? :firebolt :standard-deviation-aggregations nil)))
  (is (= false
         (driver/database-supports? :firebolt :percentile-aggregations nil)))
  (is (= false
         (driver/database-supports? :firebolt :nested-fields nil)))
  (is (= true
         (driver/database-supports? :firebolt :set-timezone nil)))
  (is (= false
         (driver/database-supports? :firebolt :nested-queries nil)))
  (is (= false
         (driver/database-supports? :firebolt :binning nil)))
  (is (= true
         (driver/database-supports? :firebolt :regex nil))))


(deftest ddl-statements-test
  (testing "make sure we didn't break the code that is used to generate DDL statements when we add new test datasets"
     (testing "Create DB DDL statements"
        (is (= nil
               (sql.tx/create-db-sql :firebolt (mt/get-dataset-definition dataset-defs/test-data)))))

     (testing "Create Table DDL statements"
        (is (= (map
                #(str/replace % #"\s+" " ")
                ["DROP TABLE IF EXISTS \"test_data_users\""
                 "CREATE DIMENSION TABLE \"test_data_users\" (\"id\" int, \"name\" text NULL, \"last_login\" timestamp NULL, \"password\" text NULL) PRIMARY INDEX \"id\""
                 "DROP TABLE IF EXISTS \"test_data_categories\""
                 "CREATE DIMENSION TABLE \"test_data_categories\" (\"id\" int, \"name\" text NULL) PRIMARY INDEX \"id\""
                 "DROP TABLE IF EXISTS \"test_data_venues\""
                 "CREATE DIMENSION TABLE \"test_data_venues\" (\"id\" int, \"name\" text NULL, \"category_id\" int NULL, \"latitude\" double precision NULL, \"longitude\" double precision NULL, \"price\" int NULL) PRIMARY INDEX \"id\""
                 "DROP TABLE IF EXISTS \"test_data_checkins\""
                 "CREATE DIMENSION TABLE \"test_data_checkins\" (\"id\" int, \"date\" date NULL, \"user_id\" int NULL, \"venue_id\" int NULL) PRIMARY INDEX \"id\""])
               (ddl/create-db-tables-ddl-statements :firebolt (-> (mt/get-dataset-definition dataset-defs/test-data)
                                                                  (update :database-name #(str %)))))))))

(deftest aggregations-test
  (mt/test-driver :firebolt
    (testing (str "make sure queries with two or more of the same aggregation type still work.")
       (let [{:keys [rows columns]} (mt/rows+column-names
                                     (mt/run-mbql-query checkins
                                                        {:aggregation [[:sum $user_id] [:sum $user_id]]}))]
         (is (= ["sum" "sum_2"]
                columns))
         (is (= [[7929 7929]]
                rows)))
       (let [{:keys [rows columns]} (mt/rows+column-names
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
                      (mt/with-temp [Database database {:engine :firebolt, :details (assoc details :db (tx/db-test-env-var-or-throw :firebolt :db))}]
                                    (let [sync! #(sync/sync-database! database)]
                                      ;; create a view
                                      (jdbc/execute! spec ["DROP VIEW IF EXISTS \"example_view\""])
                                      (jdbc/execute! spec ["CREATE VIEW \"example_view\" AS SELECT 'hello world' AS \"name\""])
                                      ;; now sync the DB
                                      (sync!)
                                      ;; now take a look at the Tables in the database, there should be an entry for the view
                                      (is (= [{:name "example_view"}]
                                             (filter (has-value :name "example_view") (map (partial into {})
                                                                                           (t2/select [Table :name] :db_id (u/the-id database))))))))))))
