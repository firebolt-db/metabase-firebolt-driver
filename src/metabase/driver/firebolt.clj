(ns metabase.driver.firebolt
  (:require [clojure
             [string :as str]
             [set :as set]]
            [clojure.java.jdbc :as jdbc]
            [java-time.api :as t]
            [metabase.driver :as driver]
            [metabase.driver.common :as driver.common]
            [metabase.driver.sql-jdbc.sync.describe-table :as sql-jdbc.describe-table]
            [metabase.driver.sql-jdbc
             [common :as sql-jdbc.common]
             [connection :as sql-jdbc.conn]
             [sync :as sql-jdbc.sync]]
            [metabase.driver.sql-jdbc.execute.legacy-impl :as legacy]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.util
             [i18n :as i18n]
             [date-2 :as u.date]
             [ssh :as ssh]
             [log :as log]]
             [metabase.util.honey-sql-2 :as h2x])
  (:import [java.sql Types Connection ResultSet]
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

(defn set-connection-timezone [conn timezone]
  (try
    (with-open [stmt (.createStatement conn)]
      (.execute stmt (str "SET time_zone = '" timezone "'")))
    (catch Throwable e
      (log/error e (i18n/trs "Failed to set timezone ''{0}'' for {1} database" timezone :firebolt)))))

; Set timezone if provided in connection options
(defmethod sql-jdbc.execute/do-with-connection-with-options :firebolt
  [driver db-or-id-or-spec options f]
  (sql-jdbc.execute/do-with-resolved-connection
    driver
    db-or-id-or-spec
    options
    (fn [^Connection conn]
      (let [timezone (get options :session-timezone)]
        (when timezone
          (set-connection-timezone conn timezone)))
      (f conn))))

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
    (keyword "double precision") :type/Float
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
  (sql.qp/adjust-day-of-week :firebolt (extract "isodow" expr)))

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

;; insert datetime with timezone in UTC
(defmethod sql-jdbc.execute/set-parameter [::use-legacy-classes-for-read-and-set java.time.OffsetTime]
  [driver preparedStmt idx timestamp]
  (sql-jdbc.execute/set-parameter driver preparedStmt idx (t/sql-time (t/with-offset-same-instant timestamp (t/zone-offset 0)))))

(defmethod sql-jdbc.execute/set-parameter [::use-legacy-classes-for-read-and-set java.time.OffsetDateTime]
  [driver preparedStmt idx timestamp]
  (sql-jdbc.execute/set-parameter driver preparedStmt idx (t/sql-timestamp (t/with-offset-same-instant timestamp (t/zone-offset 0)))))

(defmethod sql-jdbc.execute/set-parameter [:firebolt java.time.ZonedDateTime]
  [driver preparedStmt idx timestamp]
  (sql-jdbc.execute/set-parameter driver preparedStmt idx (t/sql-timestamp (t/with-zone-same-instant timestamp (t/zone-id "UTC")))))
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

; Choose post-filtered-active-tables method for firebolt, it's described as
; Alternative implementation of `active-tables` best suited for DBs with little or no support for schemas
(defmethod sql-jdbc.sync/active-tables :firebolt
  [& args]
  (apply sql-jdbc.sync/post-filtered-active-tables args))

; Exclude information_schema schema from syncing
(defmethod sql-jdbc.sync/excluded-schemas :firebolt
  [_]
  #{"information_schema"})

(defmethod sql-jdbc.describe-table/get-table-pks :firebolt
  [_ ^Connection conn db-name-or-nil table]
  (let [table-name (get table :name)
        schema (get table :schema)
        base-sql "SELECT primary_index FROM information_schema.tables WHERE table_name = ?"
        base-params [table-name]
        sql (cond-> base-sql
              schema (str " AND table_schema = ?")
              db-name-or-nil (str " AND table_catalog = ?")
        )
        params (cond-> base-params
                 schema (conj schema)
                 db-name-or-nil (conj db-name-or-nil)
        )
        pk-result (jdbc/query {:connection conn}
                              (concat [sql] params))
        pks ((first pk-result) :primary-index)
        ]
    (if (nil? pks) [] (vec (map str/trim (str/split pks ","))))
    )
  )

;-------------------------Supported features---------------------------
(doseq [[feature supported?] {:basic-aggregations                    true
                             :expression-aggregations                true
                             :foreign-keys                           false
                             :binning                                false
                             :regex                                  true
                             :standard-deviation-aggregations        false
                             :nested-queries                         false
                             :case-sensitivity-string-filter-options false
                             :set-timezone                           true
                             :nested-fields                          false
                             :advanced-math-expressions              false
                             :percentile-aggregations                false
                             :schemas                                false}]
(defmethod driver/database-supports? [:firebolt feature] [_driver _feature _db] supported?))
