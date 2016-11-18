(ns imcljs.query
  (:require [imcljs.path :as path]
            [clojure.string :refer [join]]))

(defn value [x] (str "<value>" x "</value>"))


(defn rename-key [m old-k new-k]
  (-> m (assoc new-k (get m old-k)) (dissoc old-k)))

(defn add-id [s]
  (if (= ".id" (subs s (- (count s) (count ".id"))))
    s
    (str s ".id")))

(defn ids->constraint [c]
  (-> c
      (rename-key :ids :values)
      (assoc :op "ONE OF")
      (update :path add-id)))

(defn map->xmlstr
  "xml string representation of an edn map.
  (map->xlmstr constraint {:key1 val1 key2 val2}) => <constraint key1=val1 key2=val2 />"
  [elem m]
  (let [m      (cond-> m (contains? m :ids) ids->constraint)
        m      (select-keys m [:path :value :values :type :op :code])
        values (:values m)]

    (str "<" elem " "
         (reduce (fn [total [k v]]
                   (if (not= k :values)
                     (str total (if total " ") (name k)
                          "="
                          (str \" v \"))
                     total))
                 nil m)
         (if values
           (str ">" (apply str (map value values)) "</" elem ">")
           "/>"))))

(defn stringiy-map
  [m]
  (reduce (fn [total [k v]] (str total (if total " ") (name k) "=" (str \" v \"))) nil m))

(defn enforce-origin [query]
  (if (nil? (:from query))
    (assoc query :from (first (clojure.string/split (first (:select query)) ".")))
    query))

(defn enforce-views-have-class [query]
  (update query :select
          (partial mapv
                   (fn [path]
                     (let [path (name path)]
                       (if (= (:from query) (first (clojure.string/split path ".")))
                         path
                         (str (:from query) "." path)))))))

(defn enforce-constraints-have-class [query]
  (update query :where
          (partial mapv
                   (fn [constraint]
                     (let [path (:path constraint)]
                       (if (= (:from query) (first (clojure.string/split path ".")))
                        constraint
                        (assoc constraint :path (str (:from query) "." path))))))))

(defn enforce-sorting [query]
  (update query :orderBy
          (partial map
                   (fn [order]
                     (let [order (if (nil? (:path order))
                                   {:path      (str (name (first (first (seq order)))))
                                    :direction (second (first (seq order)))}
                                   order)]
                       (if (= (:from query) (first (clojure.string/split (:path order) ".")))
                         order
                         (assoc order :path (str (:from query) "." (:path order)))))))))


(def sterilize-query (comp
                       enforce-sorting
                       enforce-constraints-have-class
                       enforce-views-have-class
                       enforce-origin))


(defn ->xml
  "Returns the stringfied XML representation of an EDN intermine query."
  [model query]
  ;(if (nil query) (throw (js/Error. "Oops!")))
  (let [query           (sterilize-query query)
        head-attributes {:model     (:name model)
                         :view      (clojure.string/join " " (:select query))
                         :sortOrder (clojure.string/join " " (flatten (map (juxt :path :direction) (:orderBy query))))}]
    (str "<query " (stringiy-map head-attributes) ">"
         (apply str (map (partial map->xmlstr "constraint") (:where query)))
         "</query>")))


(defn deconstruct-by-class
  "Deconstructs a query by its views and groups them by class.
  (deconstruct-by-class model query)
  {:Gene {Gene.homologues.homologue {:from Gene :select [Gene.homologues.homologue.id] :where [...]}
         {Gene {:from Gene :select [Gene.id] :where [...]}}}"
  [model query]
  (let [query (sterilize-query query)]
    (reduce (fn [path-map next-path]
              (update path-map (path/class model next-path)
                      assoc (path/trim-to-last-class model next-path)
                      (assoc query :select [(str (path/trim-to-last-class model next-path) ".id")])))
            {} (:select query))))

