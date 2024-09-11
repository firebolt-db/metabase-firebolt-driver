/(ns metabase.driver.firebolt
  (:require [clojure
             [string :as str]
             [set :as set]]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging  :as log]
            [java-time.api :as t]
            [medley.core :as m]
            [metabase.driver :as driver]
            [metabase.driver.common :as driver.common]
            [metabase.driver.sql-jdbc
             [common :as sql-jdbc.common]
             [connection :as sql-jdbc.conn]
             [sync :as sql-jdbc.sync]]
            [metabase.driver.sql-jdbc.sync.interface :as i]
            [metabase.driver.sql-jdbc.sync.common :as common]
            [metabase.driver.sql-jdbc.execute.legacy-impl :as legacy]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.util :as u]
            [metabase.util
             [date-2 :as u.date]
             [ssh :as ssh]]
             [metabase.util.honey-sql-2 :as h2x]
    [metabase.models
             [field :as field :refer [Field]]]
            [metabase.mbql.util :as mbql.u]
            [metabase.query-processor.util :as qputil])
  (:import [java.sql DatabaseMetaData Types Connection ResultSet]
           [java.time LocalTime OffsetDateTime OffsetTime ZonedDateTime]))

; Firebolt driver registration
(driver/register! :firebolt, :parent #{:sql-jdbc ::legacy/use-legacy-classes-for-read-and-set})

;;; ---------------------------------------------- sql-jdbc.connection -----------------------------------------------

(defn parse-additional-options [query-string]
      (let [pairs (clojure.string/split (or query-string "") #"&")
            kv-pairs (map #(clojure.string/split % #"=") pairs)]
           (into {} (map (fn [[k v]] [(keyword k) v]) kv-pairs))))

; Create database specification and obtain connection properties for connecting to a Firebolt database.
(defmethod sql-jdbc.conn/connection-details->spec :firebolt
  [_ {:keys [db]
      :or    {db ""}
      :as   details}]
  (let [
        env (System/getProperty "env" (get (parse-additional-options (get details :additional-options "")) :environment "app"))
        spec {
              :classname "com.firebolt.FireboltDriver",
              :subprotocol "firebolt",
              :subname (str "//api." env ".firebolt.io/" db),
        }
        ]
    (-> (merge spec (select-keys details [:password :user :additional-options :account :engine_name]))
        (sql-jdbc.common/handle-additional-options  (select-keys details [:password :classname :subprotocol :user :subname :additional-options :account :engine_name]))
        )))

; Testing the firebolt database connection
(defmethod driver/can-connect? :firebolt [driver details]
   (let [connection (sql-jdbc.conn/connection-details->spec driver (ssh/include-ssh-tunnel! details))]
     (= 1 (first (vals (first (jdbc/query connection ["SELECT 1"])))))))

(defmethod driver/db-default-timezone :firebolt [_ _]  "UTC" ) ; possible parameters db-default-timezone

(defmethod sql.qp/honey-sql-version :firebolt [_] 2)
;;; ------------------------------------------------- sql-jdbc.sync --------------------------------------------------

; Define mapping of firebolt data types to base type
(def ^:private database-type->base-type
  "Map of Firebolt column types -> Field base types."
  { :array         :type/Array
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
    :text          :type/Text
    :timestamp     :type/DateTime
    :timestamptz   :type/DateTimeWithLocalTZ
    :varchar       :type/Text
    (keyword "timestamp with timezone")    :type/DateTime
    (keyword "timestamp without timezone") :type/DateTime})

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

; Set start of week to be monday
(defmethod driver/db-start-of-week :firebolt
  [_]
  :monday)

; Helper functions for date extraction
(defn extract [unit expr] [[:extract [:raw unit " FROM " [:cast expr :timestamptz]]]])
; Helper functions for date truncation
(defn date-trunc [unit expr] [[:date_trunc (h2x/literal unit) [:cast expr :timestamptz]]])

; If `expr` is a date, we need to cast it to a timestamp before we can truncate to a finer granularity
(defmethod sql.qp/date [:firebolt :minute] [_ _ expr] (date-trunc "minute" expr))
(defmethod sql.qp/date [:firebolt :hour] [_ _ expr](date-trunc "hour" expr))
(defmethod sql.qp/date [:firebolt :day] [_ _ expr](date-trunc "day" expr))
(defmethod sql.qp/date [:firebolt :month] [_ _ expr](date-trunc "month" expr))
(defmethod sql.qp/date [:firebolt :quarter] [_ _ expr](date-trunc "quarter" expr))
(defmethod sql.qp/date [:firebolt :year] [_ _ expr](date-trunc "year" expr))
; account for start of week setting in the :week implementation
(defmethod sql.qp/date [:firebolt :week] [_ _ expr]
  (sql.qp/adjust-start-of-week :firebolt #(date-trunc "week" %) expr))

; Extraction functions
(defmethod sql.qp/date [:firebolt :second-of-minute] [_ _ expr] (extract "second" expr))
(defmethod sql.qp/date [:firebolt :minute-of-hour] [_ _ expr] (extract "minute" expr))
(defmethod sql.qp/date [:firebolt :hour-of-day] [_ _ expr] (extract "hour" expr))
(defmethod sql.qp/date [:firebolt :day-of-month] [_ _ expr] (extract "day" expr))
(defmethod sql.qp/date [:firebolt :day-of-year] [_ _ expr] (extract "doy" expr))
(defmethod sql.qp/date [:firebolt :week-of-year-iso]    [_ _ expr] (extract "week" expr))
(defmethod sql.qp/date [:firebolt :month-of-year] [_ _ expr] (extract "month" expr))
(defmethod sql.qp/date [:firebolt :quarter-of-year] [_ _ expr] (extract "quarter" expr))
(defmethod sql.qp/date [:firebolt :year-of-era] [_ _ expr] (extract "year" expr))
; account for start of week setting in the :day-of-week implementation
(defmethod sql.qp/date [:firebolt :day-of-week] [_ _ expr]
  (let [offset (driver.common/start-of-week-offset :firebolt)]
    (if (not= offset 0)
      (extract "isodow" (sql.qp/add-interval-honeysql-form :firebolt expr (- offset 7) :day))
      (extract "isodow" expr)
      )
    ))

; Return a appropriate HoneySQL form for converting a Unix timestamp integer field or value to an proper SQL Timestamp.
(defmethod sql.qp/unix-timestamp->honeysql [:firebolt :seconds] [_ _ expr] [[:to_timestamp expr]])

; Return a HoneySQL form that performs represents addition of some temporal interval to the original `hsql-form'.
(defmethod sql.qp/add-interval-honeysql-form :firebolt [_ dt amount unit] [[:date_add (name unit) (int amount) dt]])

; Format a temporal value `t` as a SQL-style literal string, converting time datatype to SQL-style literal string
(defmethod unprepare/unprepare-value [:firebolt LocalTime]
  [_ t]
  (format "timestamp '%s'" (t/sql-timestamp t)))

; Converting ZonedDateTime datatype to SQL-style literal string
(defmethod unprepare/unprepare-value [:firebolt ZonedDateTime]
  [_ t]
  (format "timestamptz '%s'" (u.date/format-sql (t/offset-date-time t))))

; Converting OffsetDateTime datatype to SQL-style literal string
(defmethod unprepare/unprepare-value [:firebolt OffsetDateTime]
  [_ t]
  (format "timestamptz '%s'" (u.date/format-sql (t/offset-date-time t))))

; Converting OffsetTime datatype to SQL-style literal string
(defmethod unprepare/unprepare-value [:firebolt OffsetTime]
  [_ t]
  (format "timestamptz '%s'" (u.date/format-sql (t/offset-date-time t))))

(defmethod sql.qp/cast-temporal-string [:firebolt :Coercion/ISO8601->Time]
 [_driver _semantic_type expr]
  (h2x/maybe-cast :string expr))
;;; ------------------------------------------------- query handling -------------------------------------------------

;(models/defmodel Table :metabase_table)


; Get the active tables of configured database
(defmethod sql-jdbc.sync/active-tables :firebolt [& args]
  (apply sql-jdbc.sync/post-filtered-active-tables args))

; call REGEXP_MATCHES function when regex-match-first is called
(defmethod sql.qp/->honeysql [:firebolt :regex-match-first]
           [_ [_ arg pattern]]
           [:REGEXP_LIKE (sql.qp/->honeysql :firebolt arg) pattern])

(defmethod sql.qp/->honeysql [:firebolt :contains]
            [_ [_ field value options]]
            (let [hsql-field (sql.qp/->honeysql :firebolt field)
                  hsql-value (sql.qp/->honeysql :firebolt value)]
                    (if (get options :case-sensitive true)
                      [:nest [:> [:STRPOS hsql-field hsql-value] 0]]
                      [:nest [:> [:STRPOS [:LOWER hsql-field] [:LOWER hsql-value]] 0]])))

; escapes all regexp characters in a string, to be later used in a regexp_extract function
(defn escape-regexp-sql [clause]
  [:REGEXP_REPLACE_ALL clause "([!$()*+.:<=>??[\\\\\\]^{|}-])" "\\\\\\\\\\\\1"])

; Override starts-with and ends-with to use REGEXP_EXTRACT instead of LIKE,
; since LIKE only supports constant strings as a pattern
(defmethod sql.qp/->honeysql [:firebolt :starts-with]
            [_ [_ field value options]]
            (let [hsql-field (sql.qp/->honeysql :firebolt field)
                  hsql-value (sql.qp/->honeysql :firebolt value)
                  flags (if (get options :case-sensitive true) "" "i")]
              [:is [:REGEXP_EXTRACT hsql-field [:|| "^" (escape-regexp-sql hsql-value)] flags] [:not nil]]))

(defmethod sql.qp/->honeysql [:firebolt :ends-with]
            [_ [_ field value options]]
            (let [hsql-field (sql.qp/->honeysql :firebolt field)
                  hsql-value (sql.qp/->honeysql :firebolt value)
                  flags (if (get options :case-sensitive true) "" "i")]
              [:is [:REGEXP_EXTRACT hsql-field [:|| (escape-regexp-sql hsql-value) "$"] flags] [:not nil]]))

; Wrap between clause in parenthesis
(defmethod sql.qp/->honeysql [:sql :between]
  [_ [_ field min-val max-val]]
  [:nest [:between (sql.qp/->honeysql :firebolt field) (sql.qp/->honeysql :firebolt min-val) (sql.qp/->honeysql :firebolt max-val)]])


;; ------- Methods to handle Views, Describe database to not return Agg and Join indexes in Firebolt ----------------
;; All the functions below belong to describe-table.clj which are all private in metabase and cant be called or
;; extended directly. Hence, needed to implement the entire function chain
;; As we should only return public tables that are not external, SHOW TABLES/SHOW VIEWS cannot be used.
(defmethod driver/describe-database :firebolt
  [_ {:keys [details] :as database}]
  {:tables
   (with-open [conn (jdbc/get-connection (sql-jdbc.conn/db->pooled-connection-spec database))]
     (set/union
      (set (for [{:keys [database table_name]} (jdbc/query {:connection conn} ["SELECT table_name from information_schema.tables WHERE table_schema LIKE 'public' AND table_type NOT LIKE 'EXTERNAL'"])]
             {:name table_name :schema (when (seq database) database)}))
      (set(for [{:keys [database table_name]} (jdbc/query {:connection conn} ["SELECT table_name from information_schema.views WHERE table_schema LIKE 'public'"])]
            {:name table_name :schema (when (seq database) database)}))))})

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
  (let [[sql & params] (i/fallback-metadata-query driver table-schema nil table-name)]
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

(defmethod get-table-pks :firebolt
  [_ ^Connection conn db-name-or-nil table]
  (let [table-name (get table :name)
        schema (get table :schema)
        sql-query (if (nil? db-name-or-nil)
                          (str "SELECT primary-index FROM information_schema.tables WHERE table_name = ? AND table_schema = ?")
                          (str "SELECT primary-index FROM information_schema.tables WHERE table_name = ? AND table_schema = ? AND table_catalog = ?")
                          )
        sql-params (if (nil? db-name-or-nil)
                          [table-name schema]
                          [table-name schema db-name-or-nil]
                          )
        pk-result (jdbc/query {:connection conn}
                               concat ([sql-query] sql-params))
        ]
    (->> pk-result
         first                                              ;; get the first row
         :primary-index                                     ;; get the primary-index column
         (str/split ",")                                    ;; split the primary-index column
         (map str/trim)                                     ;; trim the column names
         (vec))))                                           ;; convert to vector

;-------------------------Supported features---------------------------

(defmethod driver/database-supports? [:firebolt :basic-aggregations]  [_ _ _] true)

(defmethod driver/database-supports? [:firebolt :expression-aggregations]  [_ _ _] true)

(defmethod driver/database-supports? [:firebolt :percentile-aggregations]  [_ _ _] true)

(defmethod driver/database-supports? [:firebolt :foreign-keys]  [_ _ _] true)

(defmethod driver/database-supports? [:firebolt :binning]  [_ _ _] true)

(defmethod driver/database-supports? [:firebolt :regex]  [_ _ _] true)

(defmethod driver/database-supports? [:firebolt :standard-deviation-aggregations]  [_ _ _] false)

(defmethod driver/database-supports? [:firebolt :nested-queries]  [_ _ _] false)

(defmethod driver/database-supports? [:firebolt :case-sensitivity-string-filter-options]  [_ _ _] false)

(defmethod driver/database-supports? [:firebolt :set-timezone]  [_ _ _] false)

(defmethod driver/database-supports? [:firebolt :nested-fields]  [_ _ _] false)

(defmethod driver/database-supports? [:firebolt :advanced-math-expressions]  [_ _ _] false)

(defmethod driver/database-supports? [:firebolt :percentile-aggregations]  [_ _ _] false)
