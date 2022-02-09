(ns metabase.test.data.firebolt
  (:require [metabase.test.data
             [interface :as tx]
             [sql :as sql.tx]
             [sql-jdbc :as sql-jdbc.tx]]
            [clojure.set :as set]
            [metabase
             [config :as config]
             [driver :as driver]]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.test.data.sql-jdbc
             [execute :as execute]
             [load-data :as load-data]]
            [metabase.test.data.sql.ddl :as ddl]
            [metabase.driver.sql.util :as sql.u]
            [clojure.java.jdbc :as jdbc]
            [metabase.connection-pool :as connection-pool]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [clojure.tools.logging :as log]
            [metabase.util :as u])
  (:import [java.sql ResultSet Connection DriverManager PreparedStatement SQLException]))


(sql-jdbc.tx/add-test-extensions! :firebolt)

; during unit tests don't treat firebolt as having FK support
(defmethod driver/supports? [:firebolt :foreign-keys] [_ _] (not config/is-test?))

;;; ----------------------------------------------- Connection Details -----------------------------------------------

; Return the connection details map that should be used to connect to the Databas
(defmethod tx/dbdef->connection-details :firebolt
  [_ context {:keys [database-name]}]
  (merge {:user               (tx/db-test-env-var-or-throw :firebolt :user)
          :host               (tx/db-test-env-var-or-throw :firebolt :host)
          :port               (tx/db-test-env-var-or-throw :firebolt :port)
          :password           (tx/db-test-env-var-or-throw :firebolt :password)
          :db                 (tx/db-test-env-var-or-throw :firebolt :db)
          :additional-options (tx/db-test-env-var-or-throw :firebolt :additional-options)}))
;;; ----------------------------------------------- Sync -----------------------------------------------

; Map firebolt data types to base type
(doseq [[base-type sql-type] {:type/Array          "Array"
                              :type/DateTime       "DateTime"
                              :type/Date           "Date"
                              :type/Time           "String"
                              :type/Float          "Float"
                              :type/Integer        "Int"
                              :type/Text           "String"
                              :type/Boolean        "Boolean"
                              :type/BigInteger     "BIGINT"}]
  (defmethod sql.tx/field-base-type->sql-type [:firebolt base-type] [_ _] sql-type))

;;; ----------------------------------------------- Query handling -----------------------------------------------

; Implement this because the tests try to add a table with a hyphen and Firebolt doesn't support that
(defmethod tx/format-name :firebolt
  [_ s]
  (clojure.string/replace (format "%s" s) #"-" "_"))

; Return a vector of String names that can be used to refer to a Database, Table, or Field
(defmethod sql.tx/qualified-name-components :firebolt
  ([_ db-name]                       [db-name])
  ([_ db-name table-name]            [(apply str (take-last 30 (str db-name "_" table-name)))])
  ([_ db-name table-name field-name] [table-name field-name]))

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
  (let [quote-name    #(sql.u/quote-name driver :field (tx/format-name driver %))
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
(defmethod sql.tx/pk-sql-type :firebolt [_] "INTEGER")

; I'm not sure why `driver/supports?` above doesn't rectify this, but make `add-fk-sql a noop
(defmethod sql.tx/add-fk-sql :firebolt [& _] nil)

; loads data by adding ids
(defmethod load-data/load-data! :firebolt [& args]
  (apply load-data/load-data-add-ids! args))

; The firebolt JDBC driver doesn't support parameterized queries.So go ahead and deparameterize all the statements for now.
(defmethod ddl/insert-rows-ddl-statements :firebolt
  [driver table-identifier row-or-rows]
  (for [sql+args ((get-method ddl/insert-rows-ddl-statements :sql-jdbc/test-extensions) driver table-identifier row-or-rows)]
    (unprepare/unprepare driver sql+args)))

; create a fresh connection from the DriverManager
(defn- jdbc-spec->connection
  ^Connection [jdbc-spec]
  (DriverManager/getConnection (format "jdbc:%s:%s" (:subprotocol jdbc-spec) (:subname jdbc-spec))
                               (connection-pool/map->properties (select-keys jdbc-spec [:host :user :password]))))

; by default, firebolt allows to run a query of 50000 characters.
; So setting the max_ast_elements flag to huge number to make the bulk insert queries work
(defmethod load-data/do-insert! :firebolt
  [driver spec table-identifier row-or-rows]
  (let [test (format "set max_ast_elements=10000000")]
    (with-open [conn (jdbc-spec->connection spec)]
      (with-open [^PreparedStatement stmt (.prepareStatement conn test)]
        (.executeQuery stmt)
        )))
  (let [statements (ddl/insert-rows-ddl-statements driver table-identifier row-or-rows)]
    (with-open [conn (jdbc-spec->connection spec)]
      (try
        (doseq [sql+args statements]
          (log/tracef "[insert] %s" (pr-str sql+args))
          (jdbc/execute! spec sql+args {:set-parameters (fn [stmt params]
                                                          (sql-jdbc.execute/set-parameters! driver stmt params))}))
        (catch SQLException e
          (println (u/format-color 'red "INSERT FAILED: \n%s\n" statements))
          (jdbc/print-sql-exception-chain e)
          (throw e))))))

; Modified the table name to be in the format of db_name_table_name.
; So get the table and view names to make all test cases to use this format while forming the query to run tests
(defmethod driver/describe-database :firebolt
  [_ {:keys [details] :as database}]
  (let [db (update database :name :firebolt)]
  {:tables
   (with-open [conn (jdbc/get-connection (sql-jdbc.conn/db->pooled-connection-spec db))]
     (set/union
      (set (for [{:keys [database table_name]} (jdbc/query {:connection conn} ["show tables"])]
             {:name table_name :schema (when (seq database) database)}))
      (set(for [{:keys [database view_name]} (jdbc/query {:connection conn} ["show views"])]
            {:name view_name :schema (when (seq database) database)}))))}))

; Fix NaN issue in the integration test case -- Need to return null where firebolt returns NaN
(defmethod sql-jdbc.execute/read-column-thunk :firebolt
  [_ ^ResultSet rs _ ^Integer i]
  (fn []
    (let [obj (.getObject rs i)]
      (if (= "NaN" (str obj)) nil obj))))

