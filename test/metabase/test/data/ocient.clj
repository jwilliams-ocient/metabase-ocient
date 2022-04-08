(ns metabase.test.data.ocient
  (:require [clj-time.core :as time]
            [clj-time.coerce :as tc]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.reader.edn :as edn]
            [clojure.tools.logging :as log]
            [java-time :as t]
            [honeysql.format :as hformat]
            [honeysql.helpers :as h]
            [medley.core :as m]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.driver.sql.util :as sql.u]
            [metabase.test.data.interface :as tx]
            [metabase.test.data.impl :as tx.impl]
            [metabase.test.data.sql :as sql.tx]
            [metabase.test.data.sql-jdbc :as sql-jdbc.tx]
            [metabase.test.data.sql-jdbc.execute :as execute]
            [metabase.test.data.sql-jdbc.load-data :as load-data]
            [metabase.test.data.sql-jdbc.spec :as spec]
            [metabase.test.data.sql.ddl :as ddl]
            [metabase.util :as u]
            [metabase.util.date-2 :as u.date]
            [metabase.util.honeysql-extensions :as hx])
  (:import java.sql.SQLException))

(sql-jdbc.tx/add-test-extensions! :ocient)

;;Use the public schema for all tables
(defonce session-schema (str "public"))

;;Additional columns required by the Ocient database 
(defonce id-column-key (str "id"))
(defonce timestamp-column-key (str "created"))

;;Define the primary key type
(defmethod sql.tx/pk-sql-type :ocient [_] "INT NOT NULL")

;;Lowercase and replace hyphens/spaces with underscores
(defmethod tx/format-name :ocient
  [_ s]
  (str/replace (str/lower-case s) #"-| " "_"))

(defmethod sql.tx/qualified-name-components :ocient [& args]
  (apply tx/single-db-qualified-name-components session-schema args))

(defonce ^:private reference-load-durations
  (delay (edn/read-string (slurp "test_resources/load-durations.edn"))))

(def ^:private db-connection-details
  (delay
   {:host     (tx/db-test-env-var :ocient :host "localhost")
    :port     (tx/db-test-env-var :ocient :port "4051")
    :user     (tx/db-test-env-var :ocient :user "admin@system")
    :password (tx/db-test-env-var :ocient :password "admin")
    :additional-options "loglevel=TRACE;logfile=/tmp/metabase/ocient_jdbc.log"}))

(defmethod tx/dbdef->connection-details :ocient
  [driver context {:keys [database-name]}]
  (merge @db-connection-details
         (when (= context :server)
           {:db "system"})
         (when (= context :db)
           {:db (tx/format-name driver database-name)})))

(defn- dbspec []
  (sql-jdbc.conn/connection-details->spec :ocient @db-connection-details))

;; (defmethod tx-make-create-table-stmt :ocient 
;;   [table-name {:keys [field-name, base-type], :as tabledef}]

;; )

(def ocient-type->base-type
  "Function that returns a `base-type` for the given `ocient-type` (can be a keyword or string)."
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"(?i)bool"                       :type/Boolean]
    [#"(?i)tinyint"                    :type/Integer]
    [#"(?i)smallint"                   :type/Integer]
    [#"(?i)integer"                    :type/Integer]
    [#"(?i)bigint"                     :type/BigInteger]
    [#"(?i)real"                       :type/Float]
    [#"(?i)float"                      :type/Float]
    [#"(?i)double"                     :type/Float]
    [#"(?i)decimal.*"                  :type/Decimal]
    [#"(?i)varchar.*"                  :type/Text]
    [#"(?i)char.*"                     :type/Text]
    [#"(?i)varbinary.*"                :type/*]
    [#"(?i)date"                       :type/Date]
    [#"(?i)^timestamp$"                :type/DateTimeWithTZ]
    [#"(?i)^time$"                     :type/Time]
    [#".*"                             :type/*]]))

(doseq [[base-type db-type] {:type/BigInteger     "BIGINT"
                             :type/Boolean        "BOOL"
                             :type/Date           "DATE"
                             :type/DateTime       "TIMESTAMP"
                             :type/DateTimeWithTZ "TIMESTAMP"
                             :type/ZonedDateTime  "TIMESTAMP"
                             :type/Decimal        "DECIMAL(16, 4)"
                             :type/Float          "FLOAT"
                             :type/Integer        "INT"
                             :type/IPAddress      "IPV4"
                             :type/*              "VARCHAR(255)"
                             :type/Text           "VARCHAR(255)"
                             :type/Time           "TIME"
                             :type/TimeWithTZ     "TIMESTAMP"
                             :type/UUID           "UUID"}]
  (defmethod sql.tx/field-base-type->sql-type [:ocient base-type] [_ _] db-type))

;; Ocient doesn't support FKs, at least not adding them via DDL
(defmethod sql.tx/add-fk-sql :ocient
  [_ _ _ _]
  nil)

;; The Ocient JDBC driver barfs when trailing semicolons are tacked onto the statment
(defn- execute-sql-spec!
  [spec sql & {:keys [execute!]
               :or   {execute! jdbc/execute!}}]
  (log/tracef (format "[ocient-execute-sql] %s" (pr-str sql)))
  (let [sql (some-> sql str/trim)]
    (try
      (execute! spec sql)
      (catch SQLException e
        (println "Error executing SQL:" sql)
        (printf "Caught SQLException:\n%s\n"
                (with-out-str (jdbc/print-sql-exception-chain e)))
        (throw e))
      (catch Throwable e
        (println "Error executing SQL:" sql)
        (printf "Caught Exception: %s %s\n%s\n" (class e) (.getMessage e)
                (with-out-str (.printStackTrace e)))
        (throw e)))))

(defn- execute-sql!
  [driver context dbdef sql & options]
  (execute-sql-spec! (spec/dbdef->spec driver context dbdef) sql))

(defmethod execute/execute-sql! :ocient [driver context defdef sql]
  (execute-sql! driver context defdef sql))

;; Ocient being a time series database requires both a timestamp column and a clustering index key
(defmethod sql.tx/create-table-sql :ocient
  [driver {:keys [database-name]} {:keys [table-name field-definitions]}]
  (let [quot          #(sql.u/quote-name driver :field (tx/format-name driver %))]
    (str/join "\n"
              (list
               (format "CREATE TABLE \"%s\".\"%s\"(" session-schema table-name),
               (format "  %s TIMESTAMP TIME KEY BUCKET(1, HOUR) NOT NULL DEFAULT '0'," timestamp-column-key),
               (format "  %s INT NOT NULL," id-column-key),
               (str/join
                ",\n"
                (for [{:keys [field-name base-type field-comment] :as field} field-definitions]
                  (str (format "  %s %s"
                               (quot field-name)
                               (or (cond
                                     (and (map? base-type) (contains? base-type :native))
                                     (:native base-type)

                                     (and (map? base-type) (contains? base-type :natives))
                                     (get-in base-type [:natives driver])

                                     base-type
                                     (sql.tx/field-base-type->sql-type driver base-type))
                                   (throw (ex-info (format "Missing datatype for field %s for driver: %s"
                                                           field-name driver)
                                                   {:field field
                                                    :driver driver
                                                    :database-name database-name}))))
                       (when-let [comment (sql.tx/inline-column-comment-sql driver field-comment)]
                         (str " " comment))))),
               ",",
               (format "  CLUSTERING INDEX idx01 (%s)" id-column-key),
               ")"))))

;;A System Administrator must first create the database before the tests can procede
(defmethod tx/create-db! :ocient
  [driver {:keys [table-definitions database-name] :as dbdef} & {:keys [skip-drop-db?] :as options}]
  ;; first execute statements to drop the DB if needed (this will do nothing if `skip-drop-db?` is true)
  (doseq [statement (apply ddl/drop-db-ddl-statements driver dbdef options)]
    (execute-sql! driver :server dbdef statement))
  ;; now execute statements to create the DB
  (doseq [statement (ddl/create-db-ddl-statements driver dbdef)]
    (execute-sql! driver :server dbdef statement))
  ;; next, get a set of statements for creating the DB & Tables
  (let [statements (apply ddl/create-db-tables-ddl-statements driver dbdef options)]
    ;; TODO Add support for combined statements in JDBC
    ;; execute each statement. Notice we're now executing in the `:db` context e.g. executing 
    ;; them for a specific DB rather than on `:server` (no DB in particular)
    (doseq [statement statements]
      (println (format "EXECUTING [ddl/create-db-tables-ddl-statements] %s" (pr-str statement)))
      (execute-sql! driver :db dbdef statement)))
  ;; Now load the data for each Table
  (doseq [tabledef table-definitions
          :let [reference-duration (or (some-> (get @reference-load-durations [(:database-name dbdef) (:table-name tabledef)])
                                               u/format-nanoseconds)
                                       "NONE")]]
    (u/profile (format "load-data for %s %s %s (reference H2 duration: %s)"
                       (name driver) (:database-name dbdef) (:table-name tabledef) reference-duration)
               (load-data/load-data! driver dbdef tabledef))))


(defprotocol ^:private Insertable
  (^:private ->insertable [this]
    "Convert a value to an appropriate Google type when inserting a new row."))

(extend-protocol Insertable
  nil
  (->insertable [_] nil)

  Object
  (->insertable [this] this)

  clojure.lang.Keyword
  (->insertable [k]
    (u/qualified-name k))

  java.time.temporal.Temporal
  (->insertable [t] (u.date/format-sql t))

  ;; normalize to UTC. BigQuery normalizes it anyway and tends to complain when inserting values that have an offset
  java.time.OffsetDateTime
  (->insertable [t]
    (->insertable (t/local-date-time (t/with-offset-same-instant t (t/zone-offset 0)))))

  ;; for whatever reason the `date time zone-id` syntax that works in SQL doesn't work when loading data
  java.time.ZonedDateTime
  (->insertable [t]
    (->insertable (t/offset-date-time t)))

  ;; normalize to UTC, since Ocient doesn't support TIME WITH TIME ZONE
  java.time.OffsetTime
  (->insertable [t]
    (u.date/format-sql (t/local-time (t/with-offset-same-instant t (t/zone-offset 0)))))

  ;; Convert the HoneySQL `timestamp(...)` form we sometimes use to wrap a `Timestamp` to a plain literal string
  honeysql.types.SqlCall
  (->insertable [{[{s :literal}] :args, fn-name :name}]
    (assert (= (name fn-name) "timestamp"))
    (->insertable (u.date/parse (str/replace s #"'" "")))))


(defn- insert-row-honeysql-form-impl
  [driver row]
  ;; (println row)
  ;; (println (keys row))
  ;; (println (map (fn [x] (->insertable x)) (vals row)))
  ;; (println (->insertable (get row :created_at)))
  ;; (println (type (get row :created_at)))
  (let [rows    (u/one-or-many row)
        columns (keys (first rows))
        values  (for [row rows]
                  (for [value (map row columns)]
                    (sql.qp/->honeysql driver (->insertable value))))]
    (first values)))

;; Ocient has weirdifferent syntax for inserting multiple rows, it looks like
;;
;; INSERT INTO table
;;     SELECT val1,val2 UNION ALL
;;     SELECT val1,val2 UNION ALL;
;;     SELECT val1,val2 UNION ALL;
;;
;; So this custom HoneySQL type below generates the correct DDL statement
(defmethod ddl/insert-rows-honeysql-form :ocient
  [driver table-identifier row-or-rows]
  (reify hformat/ToSql
    (to-sql [_]
      (format
       "INSERT INTO \"%s\".\"%s\" SELECT %s"
       session-schema
       ((comp last :components) (into {} table-identifier))
       (for [row  (u/one-or-many row-or-rows)
             :let [columns (keys row)]]

         ((println (pr-str (type row)))
          (println (pr-str row))
          (println (str/join "," (apply pr-str (insert-row-honeysql-form-impl driver row))))
          (println (insert-row-honeysql-form-impl driver row))
          (str/join "," (insert-row-honeysql-form-impl driver row))))))))
      ;;  (let [rows    (u/one-or-many row-or-rows)
      ;;        columns (keys (first rows))
      ;;        values  (for [row rows]
      ;;                  (for [value (map row columns)]
      ;;                    (pr-str value)))]
      ;;    (str/join
      ;;     " UNION ALL SELECT "
      ;;     (map (fn [row] (str/join "," row)) values)))))))


(defmethod load-data/do-insert! :ocient
  [driver spec table-identifier row-or-rows]
  (let [statements (ddl/insert-rows-ddl-statements driver table-identifier row-or-rows)]
    ;; `set-parameters` might try to look at DB timezone; we don't want to do that while loading the data because the
    ;; DB hasn't been synced yet
    (try
        ;; TODO - why don't we use `execute/execute-sql!` here like we do below?
      (doseq [sql+args statements]
        (log/infof "[insert] %s" (pr-str sql+args))
        (execute-sql-spec! spec (unprepare/unprepare driver sql+args)))
      (catch SQLException e
        (println (u/format-color 'red "INSERT FAILED: \n%s\n" statements))
        (jdbc/print-sql-exception-chain e)
        (throw e)))))

;; "Add ID and TIMESTAMP columns to each row in `rows`. These columns are required by Ocient. 
;; This isn't meant for composition with `load-data-get-rows`; "
(defn- add-ids-and-timestamps
  [rows]
  (for [[i row] (m/indexed rows)]
    (into {(keyword timestamp-column-key) (tc/to-long (time/now))} (into {(keyword id-column-key) (inc i)} row))))

(defn- load-data-add-ids-and-timestamps-impl
  [insert!]
  (fn [rows]
    (insert! (vec (add-ids-and-timestamps rows)))))

(def ^{:arglists '([driver dbdef tabledef])} load-data-add-ids-timestamps!
  (load-data/make-load-data-fn load-data-add-ids-and-timestamps-impl load-data/load-data-chunked))

;; Ocient requires an id and a timestamp for each row
(defmethod load-data/load-data! :ocient [driver dbdef tabledef]
  (load-data-add-ids-timestamps! driver dbdef tabledef))

(defmethod tx/destroy-db! :ocient [driver dbdef]
  (println "Ocient destroy-db! entered")
  nil)

(defmethod sql.tx/drop-table-if-exists-sql :ocient
  [driver {:keys [database-name]} {:keys [table-name]}]
  (format "DROP TABLE IF EXISTS %s" (sql.tx/qualify-and-quote driver database-name table-name)))

(defmethod tx.impl/verify-data-loaded-correctly :ocient
  [_ _ _]
  (println "Ocient verify data loaded")
  nil)

(defmethod sql-jdbc.sync/syncable-schemas :ocient
  [driver conn metadata]
  #{session-schema})

(defmethod sql.tx/drop-db-if-exists-sql :ocient [driver {:keys [database-name]}]
  (format "DROP DATABASE IF EXISTS %s" (sql.tx/qualify-and-quote driver database-name)))

(defmethod sql.tx/create-db-sql :ocient [driver {:keys [database-name]}]
  (format "CREATE DATABASE %s" (sql.tx/qualify-and-quote driver database-name)))

(defmethod sql-jdbc.sync/syncable-schemas :ocient
  [driver conn metadata]
  #{session-schema})