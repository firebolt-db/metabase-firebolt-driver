(ns metabase.driver.firebolt
  (:require [clojure
             [string :as str]
             [set :as set]]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging  :as log]
            [honeysql.core :as hsql]
            [java-time :as t]
            [honeysql.format :as hformat]
            [medley.core :as m]
            [metabase.driver :as driver]
            [metabase.driver
             [common :as driver.common]]
            [metabase.driver.sql-jdbc
             [common :as sql-jdbc.common]
             [connection :as sql-jdbc.conn]
             [execute :as sql-jdbc.execute]
             [sync :as sql-jdbc.sync]]
            [metabase.driver.sql-jdbc.sync.interface :as i]
            [metabase.driver.sql-jdbc.sync.common :as common]
            [metabase.driver.sql-jdbc.execute.legacy-impl :as legacy]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.util :as u]
            [metabase.util
             [date-2 :as u.date]
             [honeysql-extensions :as hx]
             [ssh :as ssh]]
            [metabase.models
             [field :as field :refer [Field]]]
            [metabase.mbql.util :as mbql.u]
            [metabase.query-processor.util :as qputil]
            [toucan.db :as db]
            [toucan.models :as models])
  (:import [java.sql DatabaseMetaData Types Connection ResultSet]
           [java.time LocalTime OffsetDateTime OffsetTime ZonedDateTime]))

; Firebolt driver registration
(driver/register! :firebolt, :parent #{:sql-jdbc ::legacy/use-legacy-classes-for-read-and-set})

;;; ---------------------------------------------- sql-jdbc.connection -----------------------------------------------

; Create database specification and obtain connection properties for connecting to a Firebolt database.
(defmethod sql-jdbc.conn/connection-details->spec :firebolt
  [_ {:keys [db]
      :or    {db ""}
      :as   details}]
  (let [spec {:classname "com.firebolt.FireboltDriver", :subprotocol "firebolt", :subname (str "//api.app.firebolt.io/" db), :ssl true}]
    (-> (merge spec (select-keys details [:password :classname :subprotocol :user :subname :additional-options]))
        (sql-jdbc.common/handle-additional-options  (select-keys details [:password :classname :subprotocol :user :subname :additional-options]))
        )))

; Testing the firebolt database connection
(defmethod driver/can-connect? :firebolt [driver details]
   (let [connection (sql-jdbc.conn/connection-details->spec driver (ssh/include-ssh-tunnel! details))]
     (= 1 (first (vals (first (jdbc/query connection ["SELECT 1"])))))))

;;; ------------------------------------------------- sql-jdbc.sync --------------------------------------------------

; Define mapping of firebolt data types to base type
(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
    [[#"Array"              :type/Array]
     [#"Int64"              :type/BigInteger]
     [#"UInt64"             :type/BigInteger]
     [#"Int"                :type/Integer]
     [#"Float"              :type/Float]
     [#"String"             :type/Text]
     [#"DateTime"           :type/DateTime]
     [#"Date"               :type/Date]
     [#"UUID"               :type/UUID]
     [#"Decimal"            :type/Decimal]
     ]))

; Map firebolt data types to base types
(defmethod sql-jdbc.sync/database-type->base-type :firebolt [_ database-type]
   (database-type->base-type database-type))

; Concatenate the elements of an array based on array elemets type (coverting array data type to string type to apply filter on array data)
(defn is-string-array? [os]
  (if (= (type (first (vec os))) java.lang.String) (str "['" (clojure.string/join "','" os) "']") (str "[" (clojure.string/join "," os) "]")))

; Handle array data type
(defmethod metabase.driver.sql-jdbc.execute/read-column-thunk [:firebolt Types/ARRAY]
   [_ ^ResultSet rs _ ^Integer i]
   (fn []
     (def os (object-array (.getArray (.getArray rs i))))
     (is-string-array? os)))

; Helpers for Date extraction

;;; ------------------------------------------------- date functions -------------------------------------------------

; If `expr` is a date, we need to cast it to a timestamp before we can truncate to a finer granularity
(defmethod sql.qp/date [:firebolt :minute]          [_ _ expr] (hsql/call :date_trunc (hx/literal :minute) (hx/cast :timestamp expr)))
(defmethod sql.qp/date [:firebolt :hour]            [_ _ expr] (hsql/call :date_trunc (hx/literal :hour) (hx/cast :timestamp expr)))
(defmethod sql.qp/date [:firebolt :day]             [_ _ expr] (hsql/call :date_trunc (hx/literal :day) expr))
(defmethod sql.qp/date [:firebolt :month]           [_ _ expr] (hsql/call :date_trunc (hx/literal :month) expr))
(defmethod sql.qp/date [:firebolt :quarter]         [_ _ expr] (hsql/call :date_trunc (hx/literal :quarter) expr))
(defmethod sql.qp/date [:firebolt :year]            [_ _ expr] (hsql/call :date_trunc (hx/literal :year) expr))

; Extraction functions
(defmethod sql.qp/date [:firebolt :minute-of-hour]  [_ _ expr] (hsql/call :to_minute (hx/cast :timestamp expr)))
(defmethod sql.qp/date [:firebolt :hour-of-day]     [_ _ expr] (hsql/call :to_hour (hx/cast :timestamp expr)))
(defmethod sql.qp/date [:firebolt :day-of-week]     [_ _ expr] (sql.qp/adjust-day-of-week :firebolt (hsql/call :to_day_of_week expr)))
(defmethod sql.qp/date [:firebolt :day-of-month]    [_ _ expr] (hsql/call :to_day_of_month expr))
(defmethod sql.qp/date [:firebolt :day-of-year]     [_ _ expr] (hsql/call :to_day_of_year expr))
(defmethod sql.qp/date [:firebolt :week-of-year]    [_ _ expr] (hsql/call :to_week expr))
(defmethod sql.qp/date [:firebolt :month-of-year]   [_ _ expr] (hsql/call :to_month expr))
(defmethod sql.qp/date [:firebolt :quarter-of-year] [_ _ expr] (hsql/call :to_quarter expr))

; Set start of week to be monday
(defmethod driver/db-start-of-week :firebolt
  [_]
  :monday)

; Generic date-trunc function to cast date expr to timstamp
(defn- date-trunc [unit expr]
  (hsql/call :date_trunc (hx/literal unit) (hx/->timestamp expr)))

; Modify start of week to monday
(defmethod sql.qp/date [:firebolt :week]
  [_ _ expr] (sql.qp/adjust-start-of-week :firebolt (partial date-trunc :week) expr))

; Return a appropriate HoneySQL form for converting a Unix timestamp integer field or value to an proper SQL Timestamp.
(defmethod sql.qp/unix-timestamp->honeysql [:firebolt :seconds] [_ _ expr] (hsql/call :to_timestamp expr))

; Return a HoneySQL form that performs represents addition of some temporal interval to the original `hsql-form'.
(defmethod sql.qp/add-interval-honeysql-form :firebolt [driver hsql-form amount unit]
   (if (= unit :quarter)
     (recur driver hsql-form (hx/* amount 3) :month)
     (hsql/call :date_add
                (hx/literal (name unit))
                (hsql/raw (int amount))
                (hx/->timestamp hsql-form))))

; Helper function to get the current datetime in honeysql form
(defmethod sql.qp/current-datetime-honeysql-form :firebolt [_]
   (hx/cast :timestamp (hsql/raw "NOW()")))

; Return a native query that will fetch the current time
(defmethod driver.common/current-db-time-native-query :firebolt [_]
  "SELECT CAST(CAST(NOW() AS TIMESTAMP) AS VARCHAR(24))")

; Time date formatters to parse the current time returned by `current-db-time-native-query`
(defmethod driver.common/current-db-time-date-formatters :firebolt [_]
   (driver.common/create-db-time-formatters "yyyy-MM-dd HH:mm:ss"))

; Return the current time and timezone from the perspective of `database`
(defmethod driver/current-db-time :firebolt [& args]
  (apply driver.common/current-db-time args))

; Format a temporal value `t` as a SQL-style literal string, converting time datatype to SQL-style literal string
(defmethod unprepare/unprepare-value [:firebolt LocalTime]
  [_ t]
  (format "'%s'" t))

; Converting ZonedDateTime datatype to SQL-style literal string
(defmethod unprepare/unprepare-value [:firebolt ZonedDateTime]
  [_ t]
  (format "timestamp '%s'" (u.date/format-sql (t/local-date-time t))))

; Converting OffsetDateTime datatype to SQL-style literal string
(defmethod unprepare/unprepare-value [:firebolt OffsetDateTime]
  [_ t]
  (format "timestamp '%s'" (u.date/format-sql (t/local-date-time t))))

; Converting OffsetTime datatype to SQL-style literal string
(defmethod unprepare/unprepare-value [:firebolt OffsetTime]
  [_ t]
  (format "timestamp '%s'" (u.date/format-sql (t/local-date-time t))))

(defmethod sql.qp/cast-temporal-string [:firebolt :Coercion/ISO8601->Time]
  [_driver _semantic_type expr]
  (hx/maybe-cast :string expr))

;;; ------------------------------------------------- query handling -------------------------------------------------

(models/defmodel Table :metabase_table)

; ignore the schema when producing the identifier
(defn qualified-name-components
  "Return the pieces that represent a path to `field`, of the form `[table-name parent-fields-name* field-name]`."
  [{field-name :name, table-id :table_id}]
  [(db/select-one-field :name Table, :id table-id) field-name])

(defmethod sql.qp/field->identifier :firebolt
  [_ field]
  (apply hsql/qualify (qualified-name-components field)))

; Get the active tables of configured database
(defmethod sql-jdbc.sync/active-tables :firebolt [& args]
  (apply sql-jdbc.sync/post-filtered-active-tables args))

; De-parameterize the query and substitue values
(defmethod driver/execute-reducible-query :firebolt
  [driver {:keys [database settings], {sql :query, :keys [params], :as inner-query} :native, :as outer-query} context respond]
  (let [inner-query (-> (assoc inner-query
                               :remark (qputil/query->remark :firebolt outer-query)
                               :query  (if (seq params)
                                         (unprepare/unprepare driver (cons sql params))
                                         sql)
                               :max-rows (mbql.u/query->max-rows-limit outer-query))
                        (dissoc :params))
        query       (assoc outer-query :native inner-query)]
    ((get-method driver/execute-reducible-query :sql-jdbc) driver query context respond)))

;;; ------- Methods to handle Views, Describe database to not return Agg and Join indexes in Firebolt ----------------
;;; All the functions below belong to describe-table.clj which are all private in metabase and cant be called or
;;; extended directly. Hence needed to implement the entire function chain
(defmethod driver/describe-database :firebolt
  [_ {:keys [details] :as database}]
  {:tables
   (with-open [conn (jdbc/get-connection (sql-jdbc.conn/db->pooled-connection-spec database))]
     (set/union
      (set (for [{:keys [database table_name]} (jdbc/query {:connection conn} ["show tables"])]
             {:name table_name :schema (when (seq database) database)}))
      (set(for [{:keys [database view_name]} (jdbc/query {:connection conn} ["show views"])]
            {:name view_name :schema (when (seq database) database)}))))})

(defn- database-type->base-type-or-warn
  "Given a `database-type` (e.g. `VARCHAR`) return the mapped Metabase type (e.g. `:type/Text`)."
  [driver database-type]
  (or (i/database-type->base-type driver (keyword database-type))
      (do (log/warn (format "Don't know how to map column type '%s' to a Field base_type, falling back to :type/*."
                            database-type))
        :type/*)))

(defn- calculated-semantic-type
  "Get an appropriate semantic type for a column with `column-name` of type `database-type`."
  [driver ^String column-name ^String database-type]
  (when-let [semantic-type (i/column->semantic-type driver database-type column-name)]
    (assert (isa? semantic-type :type/*)
            (str "Invalid type: " semantic-type))
    semantic-type))

(defn- fallback-fields-metadata-from-select-query
  "In some rare cases `:column_name` is blank (eg. SQLite's views with group by) fallback to sniffing the type from a
  SELECT * query."
  [driver ^Connection conn table-schema table-name]
  ;; some DBs (:sqlite) don't actually return the correct metadata for LIMIT 0 queries
  (let [[sql & params] (i/fallback-metadata-query driver table-schema table-name)]
    (reify clojure.lang.IReduceInit
      (reduce [_ rf init]
        (with-open [stmt (common/prepare-statement driver conn sql params)
                    rs   (.executeQuery stmt)]
          (let [metadata (.getMetaData rs)]
            (reduce
             ((map (fn [^Integer i]
                     {:name          (.getColumnName metadata i)
                      :database-type (.getColumnTypeName metadata i)})) rf)
             init
             (range 1 (inc (.getColumnCount metadata))))))))))

(defn- jdbc-fields-metadata
  "Reducible metadata about the Fields belonging to a Table, fetching using JDBC DatabaseMetaData methods."
  [driver ^Connection conn db-name-or-nil schema table-name]
  (common/reducible-results #(.getColumns (.getMetaData conn)
                                          db-name-or-nil
                                          (some->> schema (driver/escape-entity-name-for-metadata driver))
                                          (some->> table-name (driver/escape-entity-name-for-metadata driver))
                                          nil)
                            (fn [^ResultSet rs]
                              #(merge
                                {:name          (.getString rs "COLUMN_NAME")
                                 :database-type (.getString rs "TYPE_NAME")}
                                (when-let [remarks (.getString rs "REMARKS")]
                                  (when-not (str/blank? remarks)
                                    {:field-comment remarks}))))))

(defn- fields-metadata
  "Returns reducible metadata for the Fields in a `table`."
  [driver ^Connection conn {schema :schema, table-name :name} & [^String db-name-or-nil]]
  {:pre [(instance? Connection conn) (string? table-name)]}
  (reify clojure.lang.IReduceInit
    (reduce [_ rf init]
      ;; 1. Return all the Fields that come back from DatabaseMetaData that include type info.
      ;;
      ;; 2. Iff there are some Fields that don't have type info, concatenate
      ;;    `fallback-fields-metadata-from-select-query`, which fetches the same Fields using a different method.
      ;;
      ;; 3. Filter out any duplicates between the two methods using `m/distinct-by`.
      (let [has-fields-without-type-info? (volatile! true)
            jdbc-metadata                 (eduction
                                           (remove (fn [{:keys [database-type]}]
                                                     (when (str/blank? database-type)
                                                       (vreset! has-fields-without-type-info? true)
                                                       true)))
                                           (jdbc-fields-metadata driver conn db-name-or-nil schema table-name))
            fallback-metadata             (reify clojure.lang.IReduceInit
                                            (reduce [_ rf init]
                                              (reduce
                                               rf
                                               init
                                               (when @has-fields-without-type-info?
                                                 (fallback-fields-metadata-from-select-query driver conn schema table-name)))))]
        ;; VERY IMPORTANT! DO NOT REWRITE THIS TO BE LAZY! IT ONLY WORKS BECAUSE AS NORMAL-FIELDS GETS REDUCED,
        ;; HAS-FIELDS-WITHOUT-TYPE-INFO? WILL GET SET TO TRUE IF APPLICABLE AND THEN FALLBACK-FIELDS WILL RUN WHEN
        ;; IT'S TIME TO START EVALUATING THAT.
        (reduce
         ((comp cat (m/distinct-by :name)) rf)
         init
         [jdbc-metadata fallback-metadata])))))

(defn describe-table-fields
  "Returns a set of column metadata for `table` using JDBC Connection `conn`."
  [driver conn table & [db-name-or-nil]]
  (into
   #{}
   (map-indexed (fn [i {:keys [database-type], column-name :name, :as col}]
                  (merge
                   (u/select-non-nil-keys col [:name :database-type :field-comment])
                   {:base-type         (database-type->base-type-or-warn driver database-type)
                    :database-position i}
                   (when-let [semantic-type (calculated-semantic-type driver column-name database-type)]
                     {:semantic-type semantic-type}))))
   (fields-metadata driver conn table db-name-or-nil)))

(defn add-table-pks
  "Using `metadata` find any primary keys for `table` and assoc `:pk?` to true for those columns."
  [^DatabaseMetaData metadata table]
  (let [pks (into #{} (common/reducible-results #(.getPrimaryKeys metadata nil nil (:name table))
                                                (fn [^ResultSet rs]
                                                  #(.getString rs "COLUMN_NAME"))))]
    (update table :fields (fn [fields]
                            (set (for [field fields]
                                   (if-not (contains? pks (:name field))
                                     field
                                     (assoc field :pk? true))))))))

(defmethod driver/describe-table :firebolt
  [driver database table]
  (let [spec (sql-jdbc.conn/db->pooled-connection-spec database)]
    (with-open [conn (jdbc/get-connection spec)]
      (->> (assoc (select-keys table [:name :schema])
                  :fields (describe-table-fields driver conn table nil))
           ;; find PKs and mark them
           (add-table-pks (.getMetaData conn))))))

; making between clause in a query to be inside brackets
(defmethod hformat/fn-handler "between" [_ field lower upper]
  (str "(" (hformat/to-sql-value field) " BETWEEN " (hformat/to-sql-value lower) " AND " (hformat/to-sql-value upper) ")"))

;-------------------------Supported features---------------------------

(defmethod driver/supports? [:firebolt :basic-aggregations]  [_ _] true)

(defmethod driver/supports? [:firebolt :expression-aggregations]  [_ _] true)

(defmethod driver/supports? [:firebolt :percentile-aggregations]  [_ _] true)

(defmethod driver/supports? [:firebolt :foreign-keys]  [_ _] true)

(defmethod driver/supports? [:firebolt :binning]  [_ _] true)

(defmethod driver/supports? [:firebolt :regex]  [_ _] false)

(defmethod driver/supports? [:firebolt :standard-deviation-aggregations]  [_ _] false)

(defmethod driver/supports? [:firebolt :nested-queries]  [_ _] false)

(defmethod driver/supports? [:firebolt :case-sensitivity-string-filter-options]  [_ _] false)

(defmethod driver/supports? [:firebolt :set-timezone]  [_ _] false)

(defmethod driver/supports? [:firebolt :nested-fields]  [_ _] false)




