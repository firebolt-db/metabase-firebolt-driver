(ns metabase.test.data.firebolt
  (:require [metabase.test.data
             [sql :as sql.tx]
             [sql-jdbc :as sql-jdbc.tx]
             [interface :as tx]]
            [clojure.set :as set]
            [metabase
             [config :as config]
             [driver :as driver]]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.test.data.sql-jdbc
             [execute :as execute]
             [load-data :as load-data]]
            [metabase.driver.sql.util :as sql.u]
            [clojure.java.jdbc :as jdbc]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
    )
  (:import [java.sql ResultSet]))


(sql-jdbc.tx/add-test-extensions! :firebolt)

; Report that firebolt JDBC doesn't send JVM timezone setting to the server
(defmethod driver/database-supports? [:firebolt :test/jvm-timezone-setting] [_ _ _] false)

;;; ----------------------------------------------- Connection Details -----------------------------------------------

; Return the connection details map that should be used to connect to the Databas
(defmethod tx/dbdef->connection-details :firebolt
  [_ context {:keys [database-name]}]
  (merge {:user               (tx/db-test-env-var-or-throw :firebolt :user)
          :password           (tx/db-test-env-var-or-throw :firebolt :password)
          :db                 (tx/db-test-env-var-or-throw :firebolt :db)
          :additional-options (tx/db-test-env-var-or-throw :firebolt :additional-options)}))
;;; ----------------------------------------------- Sync -----------------------------------------------

; Map firebolt data types to base type
(doseq [[base-type sql-type] {:type/Array          "array"
                              :type/DateTime       "timestamp"
                              :type/DateTimeWithTZ "timestamptz"
                              :type/Date           "date"
                              :type/Time           "text"
                              :type/Float          "double precision"
                              :type/Integer        "int"
                              :type/Text           "text"
                              :type/Boolean        "boolean"
                              :type/BigInteger     "bigint"}]
  (defmethod sql.tx/field-base-type->sql-type [:firebolt base-type] [_ _] sql-type))

;;; ----------------------------------------------- Query handling -----------------------------------------------
;; use underscore instead of hyphen in table names everywhere since it's internally inconsistent in Metabase
(defmethod ddl.i/format-name :firebolt
  [_ s]
  (clojure.string/replace (format "%s" s) #"-" "_"))


(defn make-table-name [db-name table-name]
  (apply str (take-last 30 (str db-name "_" table-name))))

; Return a vector of String names that can be used to refer to a Database, Table, or Field
(defmethod sql.tx/qualified-name-components :firebolt
  ([_ db-name]                       [db-name])
  ([_ db-name table-name]            [(make-table-name db-name table-name)])
  ([_ db-name table-name field-name] [(make-table-name db-name table-name) field-name]))

; firebolt can only execute one statement at a time
(defmethod execute/execute-sql! :firebolt [& args]
  (apply execute/sequentially-execute-sql! args))

; running the complete test suite in a single database. So ignoring the drop and create db sql
(defmethod sql.tx/drop-db-if-exists-sql :firebolt [& _] nil)
(defmethod sql.tx/create-db-sql         :firebolt [& _] nil)

; Implement this to drop table with table name only
(defmethod sql.tx/drop-table-if-exists-sql :firebolt
  [driver {:keys [database-name]} {:keys [table-name]}]
  (format "DROP TABLE IF EXISTS %s" (sql.tx/qualify-and-quote driver database-name table-name)))

; Customize the create table to create DIMENSION TABLE
(defmethod sql.tx/create-table-sql :firebolt
  [driver {:keys [database-name], :as dbdef} {:keys [table-name field-definitions]}]
  (let [quote-name    #(sql.u/quote-name driver :field (ddl.i/format-name driver %))
        pk-field-name (quote-name (sql.tx/pk-field-name driver))]
    (format "CREATE DIMENSION TABLE %s (%s %s, %s) PRIMARY INDEX %s"
            (sql.tx/qualify-and-quote driver database-name table-name)
            pk-field-name (sql.tx/pk-sql-type driver)
            (->> field-definitions
                 (map (fn [{:keys [field-name base-type]}]
                        (format "%s %s NULL" (quote-name field-name) (if (map? base-type)
                                                                  (:native base-type)
                                                                  (sql.tx/field-base-type->sql-type driver base-type)))))
                 (interpose ", ")
                 (apply str))
            pk-field-name)))

; Implement this to set the type of primary key field
(defmethod sql.tx/pk-sql-type :firebolt [_] "int")

; I'm not sure why `driver/supports?` above doesn't rectify this, but make `add-fk-sql a noop
(defmethod sql.tx/add-fk-sql :firebolt [& _] nil)

; loads data by adding ids
(defmethod load-data/load-data! :firebolt [& args]
  (apply load-data/load-data-add-ids! args))

; Modified the table name to be in the format of db_name_table_name.
; So get the table and view names to make all test cases to use this format while forming the query to run tests
(defmethod driver/describe-database :firebolt
  [_ {:keys [details] :as database}]
  (let [db (update database :name :firebolt)]
  {:tables
   (with-open [conn (jdbc/get-connection (sql-jdbc.conn/db->pooled-connection-spec db))]
     (set/union
      (set (for [{:keys [database table_name]} (jdbc/query {:connection conn} ["SELECT table_name from information_schema.tables WHERE table_schema LIKE 'public' AND table_type NOT LIKE 'EXTERNAL'"])]
             {:name table_name :schema (when (seq database) database)}))
      (set(for [{:keys [database table_name]} (jdbc/query {:connection conn} ["SELECT table_name from information_schema.views WHERE table_schema LIKE 'public'"])]
            {:name table_name :schema (when (seq database) database)}))))}))

; Fix NaN issue in the integration test case -- Need to return null where firebolt returns NaN
(defmethod sql-jdbc.execute/read-column-thunk :firebolt
  [_ ^ResultSet rs _ ^Integer i]
  (fn []
    (let [obj (.getObject rs i)]
      (if (= "NaN" (str obj)) nil obj))))

(defmethod tx/sorts-nil-first? :firebolt [_ _] false)
(defmethod tx/supports-time-type? :firebolt [_driver] false)

