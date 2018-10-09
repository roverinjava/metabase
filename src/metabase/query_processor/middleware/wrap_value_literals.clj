(ns metabase.query-processor.middleware.wrap-value-literals
  (:require [metabase.mbql
             [schema :as mbql.s]
             [util :as mbql.u]]
            [metabase.models.field :refer [Field]]
            [metabase.query-processor.store :as qp.store]
            [metabase.util.date :as du]
            [schema.core :as s]))

;;; --------------------------------------------------- Type Info ----------------------------------------------------

(defmulti ^:private ^{:doc (str "Get information about database, base, and special types for an object. This is passed "
                                "to along to various `->honeysql` method implementations so drivers have the "
                                "information they need to handle raw values like Strings, which may need to be parsed "
                                "as a certain type.")}
  type-info
  mbql.u/dispatch-by-clause-name-or-class)

(defmethod type-info :default [_] nil)

(defmethod type-info (class Field) [this]
  (let [field-info (select-keys this [:base_type :special_type :database_type])]
    (merge
     field-info
     ;; add in a default unit for this Field so we know to wrap datetime strings in `absolute-datetime` below based on
     ;; its presence. It will get replaced by `:datetime-field` unit if we're wrapped by one
     (when (mbql.u/datetime-field? field-info)
       {:unit :default}))))

(defmethod type-info :field-id [[_ field-id]]
  (type-info (qp.store/field field-id)))

(defmethod type-info :fk-> [[_ _ dest-field]]
  (type-info dest-field))

(defmethod type-info :datetime-field [[_ field unit]]
  (assoc (type-info field) :unit unit))


;;; ------------------------------------------------- add-type-info --------------------------------------------------

(defmulti ^:private add-type-info (fn [x info & [{:keys [parse-datetime-strings?]}]]
                                    (class x)))

(defmethod add-type-info nil [_ info & _]
  [:value nil info])

(defmethod add-type-info Object [this info & _]
  [:value this info])

(defmethod add-type-info java.util.Date [this info & _]
  [:absolute-datetime (du/->Timestamp this) (get info :unit :default)])

(defmethod add-type-info java.sql.Timestamp [this info & _]
  [:absolute-datetime this (get info :unit :default)])

(defmethod add-type-info String [this info & [{:keys [parse-datetime-strings?]
                                               :or   {parse-datetime-strings? true}}]]
  (if-let [unit (when (and (du/date-string? this)
                           parse-datetime-strings?)
                  (:unit info))]
    [:absolute-datetime (du/->Timestamp this) unit] ; TODO - what about timezone ?!
    [:value this info]))


;;; -------------------------------------------- wrap-literals-in-clause ---------------------------------------------

(def ^:private raw-value? (complement mbql.u/mbql-clause?))

(s/defn ^:private wrap-value-literals* :- mbql.s/Query
  [query]
  (mbql.u/replace-in query [:query :filter]
    [(clause :guard #{:= :!= :< :> :<= :>=}) field (x :guard raw-value?)]
    [clause field (add-type-info x (type-info field))]

    [:between field (min-val :guard raw-value?) (max-val :guard raw-value?)]
    [:between
     field
     (add-type-info min-val (type-info field))
     (add-type-info max-val (type-info field))]

    [(clause :guard #{:starts-with :ends-with :contains}) field (s :guard string?) & more]
    (apply vector clause field (add-type-info s (type-info field) {:parse-datetime-strings? false}) more)))

(defn wrap-value-literals
  "Middleware that wraps ran value literals in `:value` (for integers, strings, etc.) or `:absolute-datetime` (for
  datetime strings, etc.) clauses which include info about the Field they are being compared to. This is done mostly
  to make it easier for drivers to write implementations that rely on multimethod dispatch (by clause name) -- they
  can dispatch directly off of these clauses."
  [qp]
  (comp qp wrap-value-literals*))
