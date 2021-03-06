(ns metabase.feature-extraction.feature-extractors
  "Feature extractors for various models."
  (:require [bigml.histogram.core :as h.impl]
            [clj-time
             [coerce :as t.coerce]
             [core :as t]
             [format :as t.format]
             [periodic :as t.periodic]]
            [clojure.math.numeric-tower :refer [round]]
            [kixi.stats
             [core :as stats]
             [math :as math]]
            [medley.core :as m]
            [metabase.db.metadata-queries :as metadata]
            [metabase.feature-extraction
             [histogram :as h]
             [costs :as costs]
             [stl :as stl]]
            [metabase.query-processor.middleware.binning :as binning]
            [metabase
             [query-processor :as qp]
             [util :as u]]
            [redux.core :as redux]
            [toucan.db :as db])
  (:import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus))

(def ^:private percentiles (range 0 1 0.1))

(defn rollup
  "Transducer that groups by `groupfn` and reduces each group with `f`.
   Note the contructor airity of `f` needs to be free of side effects."
  [f groupfn]
  (let [init (f)]
    (fn
      ([] (transient {}))
      ([acc]
       (into {}
         (map (fn [[k v]]
                [k (f v)]))
         (persistent! acc)))
      ([acc x]
       (let [k (groupfn x)]
         (assoc! acc k (f (get acc k init) x)))))))

(defn safe-divide
  "Like `clojure.core//`, but returns nil if denominator is 0."
  [x & denominators]
  (when (or (and (not-empty denominators) (not-any? zero? denominators))
            (and (not (zero? x)) (empty? denominators)))
    (apply / x denominators)))

(defn growth
  "Relative difference between `x1` an `x2`."
  [x2 x1]
  (when (every? some? [x2 x1])
    (safe-divide (* (if (neg? x1) -1 1) (- x2 x1)) x1)))

(defn- merge-juxt
  [& fns]
  (fn [x]
    (apply merge ((apply juxt fns) x))))

(def ^:private ^:const ^Double cardinality-error 0.01)

(defn cardinality
  "Transducer that sketches cardinality using HyperLogLog++.
   https://research.google.com/pubs/pub40671.html"
  ([] (HyperLogLogPlus. 14 25))
  ([^HyperLogLogPlus acc] (.cardinality acc))
  ([^HyperLogLogPlus acc x]
   (.offer acc x)
   acc))

(def ^:private Num      [:type/Number :type/*])
(def ^:private DateTime [:type/DateTime :type/*])
(def ^:private Category [:type/* :type/Category])
;(def ^:private Any      [:type/* :type/*])
(def ^:private Text     [:type/Text :type/*])

(defn- equidistant-bins
  [histogram]
  (if (h/categorical? histogram)
    (-> histogram h.impl/bins first :target :counts)
    (let [{:keys [min max]} (h.impl/bounds histogram)]
      (cond
        (nil? min)  []
        (= min max) [[min 1.0]]
        :else       (let [{:keys [min-value num-bins bin-width]}
                          (binning/nicer-breakout
                           {:min-value min
                            :max-value max
                            :num-bins  (->> histogram
                                            h/optimal-bin-width
                                            (binning/calculate-num-bins min max))
                            :strategy  :num-bins})]
                      (->> min-value
                           (iterate (partial + bin-width))
                           (take (inc num-bins))
                           (map (fn [x]
                                  [x (h.impl/sum histogram x)]))
                           (partition 2 1)
                           (map (fn [[[x s1] [_ s2]]]
                                  [x (- s2 s1)]))))))))

(defn- histogram->dataset
  ([field histogram] (histogram->dataset identity field histogram))
  ([keyfn field histogram]
   {:rows    (let [norm (safe-divide (h.impl/total-count histogram))]
               (for [[k v] (equidistant-bins histogram)]
                 [(keyfn k) (* v norm)]))
    :columns [(:name field) "SHARE"]
    :cols [(dissoc field :remapped_from)
           {:name         "SHARE"
            :display_name "Share"
            :description  "Share of corresponding bin in the overall population."
            :base_type    :type/Float}]}))

(defn- field-type
  [field]
  (if (sequential? field)
    (mapv field-type field)
    [(:base_type field) (or (:special_type field) :type/*)]))

(defmulti
  ^{:doc "Returns a transducer that extracts features from given coll.
          What features are extracted depends on the type of corresponding
          `Field`(s), amount of data points available (some algorithms have a
          minimum data points requirement) and `max-cost.computation` setting.
          Note we are heavily using data sketches so some summary values may be
          approximate."
    :arglists '([opts field])}
  feature-extractor #(field-type %2))

(defmulti
  ^{:doc "Make features human-friendly."
    :arglists '([features])}
  x-ray :type)

(defmethod x-ray :default
  [features]
  (dissoc features :has-nils? :all-distinct?))

(defmulti
  ^{:doc "Feature vector for comparison/difference purposes."
    :arglists '([features])}
  comparison-vector :type)

(defmethod comparison-vector :default
  [features]
  (dissoc features :type :field :has-nils? :all-distinct? :percentiles))

(defn- histogram-extractor
  [{:keys [histogram]}]
  (let [nil-count   (h/nil-count histogram)
        total-count (h/total-count histogram)]
    {:histogram   histogram
     :percentiles (apply h.impl/percentiles histogram percentiles)
     :nil%        (/ nil-count (max total-count 1))
     :has-nils?   (pos? nil-count)
     :count       total-count
     :entropy     (h/entropy histogram)}))

(defn- cardinality-extractor
  [{:keys [cardinality histogram]}]
  (let [uniqueness (/ cardinality (max (h/total-count histogram) 1))]
    {:uniqueness    uniqueness
     :cardinality   cardinality
     :all-distinct? (>= uniqueness (- 1 cardinality-error))}))

(defn- field-metadata-extractor
  [field]
  (fn [_]
    {:field field
     :type  (field-type field)}))

(defmethod feature-extractor Num
  [{:keys [max-cost]} field]
  (redux/post-complete
   (redux/fuse {:histogram      h/histogram
                :cardinality    cardinality
                :kurtosis       stats/kurtosis
                :skewness       stats/skewness
                :sum            (redux/with-xform + (remove nil?))
                :sum-of-squares (redux/with-xform + (comp (remove nil?)
                                                          (map math/sq)))})
   (merge-juxt
    histogram-extractor
    cardinality-extractor
    (field-metadata-extractor field)
    (fn [{:keys [histogram cardinality kurtosis skewness sum sum-of-squares]}]
      (let [var         (or (h.impl/variance histogram) 0)
            sd          (math/sqrt var)
            min         (h.impl/minimum histogram)
            max         (h.impl/maximum histogram)
            mean        (h.impl/mean histogram)
            median      (h.impl/median histogram)
            range       (some-> max (- min))]
        (merge
         {:positive-definite? (some-> min (>= 0))
          :%>mean             (some->> mean ((h.impl/cdf histogram)) (- 1))
          :var>sd?            (> var sd)
          :0<=x<=1?           (when min (<= 0 min max 1))
          :-1<=x<=1?          (when min (<= -1 min max 1))
          :cv                 (some->> mean (safe-divide sd))
          :range-vs-sd        (some->> range (safe-divide sd))
          :mean-median-spread (some->> range (safe-divide (- mean median)))
          :min-vs-max         (some->> max (safe-divide min))
          :range              range
          :min                min
          :max                max
          :mean               mean
          :median             median
          :var                var
          :sd                 sd
          :kurtosis           kurtosis
          :skewness           skewness}
         (when (costs/full-scan? max-cost)
           {:sum            sum
            :sum-of-squares sum-of-squares})))))))

(defmethod comparison-vector Num
  [features]
  (select-keys features
               [:histogram :mean :median :min :max :sd :count :kurtosis
                :skewness :entropy :nil% :uniqueness :range :min-vs-max]))

(defmethod x-ray Num
  [{:keys [field count] :as features}]
  (-> features
      (update :histogram (partial histogram->dataset field))
      (dissoc :has-nils? :var>sd? :0<=x<=1? :-1<=x<=1? :all-distinct?
              :positive-definite? :var>sd? :uniqueness :min-vs-max)))

(defmethod feature-extractor [Num Num]
  [_ field]
  (redux/post-complete
   (redux/fuse {:linear-regression (stats/simple-linear-regression first second)
                :correlation       (stats/correlation first second)
                :covariance        (stats/covariance first second)})
   (field-metadata-extractor field)))

(def ^:private ^{:arglists '([t])} to-double
  "Coerce `DateTime` to `Double`."
  (comp double t.coerce/to-long))

(def ^:private ^{:arglists '([t])} from-double
  "Coerce `Double` into a `DateTime`."
  (stats/somef (comp t.coerce/from-long long)))

(defn- fill-timeseries
  "Given a coll of `[DateTime, Any]` pairs with periodicty `step` fill missing
   periods with 0."
  [step ts]
  (let [ts-index (into {} ts)]
    (into []
      (comp (map to-double)
            (take-while (partial >= (-> ts last first)))
            (map (fn [t]
                   [t (ts-index t 0)])))
      (some-> ts
              ffirst
              from-double
              (t.periodic/periodic-seq step)))))

(defn- decompose-timeseries
  "Decompose given timeseries with expected periodicty `period` into trend,
   seasonal component, and reminder.
   `period` can be one of `hour`, `:day`, `day-of-week`, `week`, or `:month`."
  [period ts]
  (let [period (case period
                 :hour        24
                 :day-of-week 7
                 :month       12
                 :week        52
                 :day         365)]
    (when (>= (count ts) (* 2 period))
      (select-keys (stl/decompose period ts) [:trend :seasonal :residual]))))

(defn- last-n-days
  [n offset {:keys [breakout filter] :as query}]
  (let [[[_ datetime-field _]] breakout
        time-range             [:and
                                [:> datetime-field
                                 [:relative-datetime (- (+ n offset)) :day]]
                                [:<= datetime-field
                                 [:relative-datetime (- offset) :day]]]]
    (-> (metadata/query-values
         (db/select-one-field :db_id 'Table :id (:source_table query))
         (-> query
             (dissoc :breakout)
             (assoc :filter (if filter
                              [:and filter time-range]
                              time-range))))
        :rows
        ffirst)))

(defn- rolling-window-growth
  [window query]
  (growth (last-n-days window 0 query) (last-n-days window window query)))

(defmethod feature-extractor [DateTime Num]
  [{:keys [max-cost query]} field]
  (let [resolution (let [[head _ resolution] (-> query :breakout first)]
                     (when (= head "datetime-field")
                       (keyword resolution)))]
    (redux/post-complete
     (redux/pre-step
      (redux/fuse {:linear-regression (stats/simple-linear-regression first second)
                   :series            (if (nil? resolution)
                                        conj
                                        (redux/post-complete
                                         conj
                                         (partial fill-timeseries
                                                  (case resolution
                                                    :month (t/months 1)
                                                    :week  (t/weeks 1)
                                                    :day   (t/days 1)
                                                    :hour  (t/hours 1)))))})
      (fn [[x y]]
        [(-> x t.format/parse to-double) y]))
     (merge-juxt
      (field-metadata-extractor field)
      (fn [{:keys [series linear-regression]}]
        (let [ys-r (->> series (map second) reverse not-empty)]
          (merge {:resolution             resolution
                  :series                 series
                  :linear-regression      linear-regression
                  :growth-series          (->> series
                                               (partition 2 1)
                                               (map (fn [[[_ y1] [x y2]]]
                                                      [x (growth y2 y1)])))
                  :seasonal-decomposition
                  (when (and resolution
                             (costs/unbounded-computation? max-cost))
                    (decompose-timeseries resolution series))}
                 (when (and (costs/alow-joins? max-cost)
                            (:aggregation query))
                   {:YoY (rolling-window-growth 365 query)
                    :MoM (rolling-window-growth 30 query)
                    :WoW (rolling-window-growth 7 query)
                    :DoD (rolling-window-growth 1 query)}))))))))

(defmethod comparison-vector [DateTime Num]
  [features]
  (-> features
      (dissoc :resolution)
      ((get-method comparison-vector :default))))

(defmethod x-ray [DateTime Num]
  [features]
  (dissoc features :series))

;; This one needs way more thinking
;;
;; (defmethod feature-extractor [Category Any]
;;   [opts [x y]]
;;   (rollup (redux/pre-step (feature-extractor opts y) second) first))

(defmethod feature-extractor Text
  [_ field]
  (redux/post-complete
   (redux/fuse {:histogram (redux/pre-step
                            h/histogram
                            (stats/somef (comp count u/jdbc-clob->str)))})
   (merge-juxt
    (field-metadata-extractor field)
    histogram-extractor)))

(defmethod x-ray Text
  [{:keys [field] :as features}]
  (-> features
      (update :histogram (partial histogram->dataset field))
      ((get-method x-ray :default))))

(defn- quarter
  [dt]
  (-> dt t/month (/ 3) Math/ceil long))

(defmethod feature-extractor DateTime
  [_ field]
  (redux/post-complete
   (redux/pre-step
    (redux/fuse {:histogram         (redux/pre-step h/histogram t.coerce/to-long)
                 :histogram-hour    (redux/pre-step h/histogram-categorical
                                                    (stats/somef t/hour))
                 :histogram-day     (redux/pre-step h/histogram-categorical
                                                    (stats/somef t/day-of-week))
                 :histogram-month   (redux/pre-step h/histogram-categorical
                                                    (stats/somef t/month))
                 :histogram-quarter (redux/pre-step h/histogram-categorical
                                                    (stats/somef quarter))})
    t.format/parse)
   (merge-juxt
    histogram-extractor
    (field-metadata-extractor field)
    (fn [{:keys [histogram histogram-hour histogram-day histogram-month
                 histogram-quarter]}]
      {:earliest          (h.impl/minimum histogram)
       :latest            (h.impl/maximum histogram)
       :histogram-hour    histogram-hour
       :histogram-day     histogram-day
       :histogram-month   histogram-month
       :histogram-quarter histogram-quarter}))))

(defn- round-to-month
  [dt]
  (if (<= (t/day dt) 15)
    (t/floor dt t/month)
    (t/date-time (t/year dt) (inc (t/month dt)))))

(defn- month-frequencies
  [earliest latest]
  (let [earilest    (round-to-month latest)
        latest      (round-to-month latest)
        start-month (t/month earliest)
        duration    (t/in-months (t/interval earliest latest))]
    (->> (range (dec start-month) (+ start-month duration))
         (map #(inc (mod % 12)))
         frequencies)))

(defn- quarter-frequencies
  [earliest latest]
  (let [earilest      (round-to-month latest)
        latest        (round-to-month latest)
        start-quarter (quarter earliest)
        duration      (round (/ (t/in-months (t/interval earliest latest)) 3))]
    (->> (range (dec start-quarter) (+ start-quarter duration))
         (map #(inc (mod % 4)))
         frequencies)))

(defn- weigh-periodicity
  [weights card]
  (let [baseline (apply min (vals weights))]
    (update card :rows (partial map (fn [[k v]]
                                      [k (* v (/ baseline (weights k)))])))))

(defmethod x-ray DateTime
  [{:keys [field earliest latest histogram] :as features}]
  (let [earliest (from-double earliest)
        latest   (from-double latest)]
    (-> features
        (assoc  :earliest          earliest)
        (assoc  :latest            latest)
        (update :histogram         (partial histogram->dataset from-double field))
        (update :percentiles       (partial m/map-vals from-double))
        (update :histogram-hour    (partial histogram->dataset
                                            {:name         "HOUR"
                                             :display_name "Hour of day"
                                             :base_type    :type/Integer
                                             :special_type :type/Category}))
        (update :histogram-day     (partial histogram->dataset
                                            {:name         "DAY"
                                             :display_name "Day of week"
                                             :base_type    :type/Integer
                                             :special_type :type/Category}))
        (update :histogram-month   (fn [histogram]
                                     (when-not (h/empty? histogram)
                                       (->> histogram
                                            (histogram->dataset
                                             {:name         "MONTH"
                                              :display_name "Month of year"
                                              :base_type    :type/Integer
                                              :special_type :type/Category})
                                            (weigh-periodicity
                                             (month-frequencies earliest
                                                                latest))))))
        (update :histogram-quarter (fn [histogram]
                                     (when-not (h/empty? histogram)
                                       (->> histogram
                                            (histogram->dataset
                                             {:name         "QUARTER"
                                              :display_name "Quarter of year"
                                              :base_type    :type/Integer
                                              :special_type :type/Category})
                                            (weigh-periodicity
                                             (quarter-frequencies earliest
                                                                  latest)))))))))

(defmethod feature-extractor Category
  [_ field]
  (redux/post-complete
   (redux/fuse {:histogram   h/histogram-categorical
                :cardinality cardinality})
   (merge-juxt
    histogram-extractor
    cardinality-extractor
    (field-metadata-extractor field))))

(defmethod x-ray Category
  [{:keys [field] :as features}]
  (-> features
      (update :histogram (partial histogram->dataset field))
      ((get-method x-ray :default))))

(defmethod feature-extractor :default
  [_ field]
  (redux/post-complete
   (redux/fuse {:total-count stats/count
                :nil-count   (redux/with-xform stats/count (filter nil?))})
   (merge-juxt
    (field-metadata-extractor field)
    (fn [{:keys [total-count nil-count]}]
      {:count     total-count
       :nil%      (/ nil-count (max total-count 1))
       :has-nils? (pos? nil-count)
       :type [nil (field-type field)]}))))

(prefer-method feature-extractor Category Text)
(prefer-method feature-extractor Num Category)
(prefer-method x-ray Category Text)
(prefer-method x-ray Num Category)
(prefer-method comparison-vector Category Text)
(prefer-method comparison-vector Num Category)
