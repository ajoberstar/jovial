(ns org.ajoberstar.jovial.engine
  (:refer-clojure :exclude [descriptor])
  (:require [clojure.set :as set]
            [clojure.string :as string])
  (:import [org.ajoberstar.jovial ClojureNamespaceDescriptor ClojureVarDescriptor]
           [org.junit.platform.engine
            DiscoverySelector EngineDiscoveryRequest ExecutionRequest
            SelectorResolutionResult TestTag UniqueId UniqueId$Segment]
           [org.junit.platform.engine.discovery
            UniqueIdSelector DirectorySelector FileSelector
            ClasspathResourceSelector ClasspathRootSelector ClassSelector]
           [org.junit.platform.engine.support.descriptor
            EngineDescriptor ClasspathResourceSource ClassSource FileSource]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specification of an Engine
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol Engine
  (id [engine]
    "Returns the UniqueId of the engine")
  (discover [engine ^EngineDiscoveryRequest request ^UniqueId root-id]
    "Discovers tests among the candidates, should return a test descriptor.")
  (execute [engine ^ExecutionRequest request]
    "Executes the tests in the request's descriptor and reports results to the request's listener."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:private excluded-tags #{:ns :file :line :column :doc :author :test :name})

(defn tags [var]
  (let [var-meta (-> var meta)
        ns-meta (-> var-meta :ns meta)
        full-meta (merge ns-meta var-meta)
        xf (comp (filter second) ; if meta value is truthy, use it as a tag
                 (map first) ; just need the keyword
                 (remove excluded-tags) ; these are unlikely to be meant as a tag
                 (map name)
                 (map #(TestTag/create %)))]
    (into #{} xf full-meta)))

(defn- id->map [^UniqueId id]
  (let [xf (map (fn [^UniqueId$Segment segment]
                  [(keyword (.getType segment)) (.getValue segment)]))]
    (into {} xf (.getSegments id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Discovery Selector Support
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:dynamic *all-vars* nil)

(defn all-vars []
  (let [fqsym (fn [namespace]
                (fn [[sym _]] (symbol (name namespace) (name sym))))
        nssyms (fn [namespace]
                 (map (fqsym namespace) (ns-publics namespace)))]
    (into #{} (mapcat nssyms) (all-ns))))

(defrecord TestCandidate [source sym])

(defn select-new-vars [source loader]
  (let [before @*all-vars*]
    (try
      (loader)
      (let [after (reset! *all-vars* (all-vars))
            loaded-vars (set/difference after before)]
        (map #(->TestCandidate source %) loaded-vars))
      (catch Exception _
        nil))))

(defprotocol Selector
  (-select [this]
    "Builds a test descriptor meeting the selector's criteria."))

(extend-protocol Selector
  Object
  (-select [this]
    nil)

  UniqueIdSelector
  (-select [this parent-node]
    (let [{:keys [namespace name]} (id->map (.getUniqueId this))]
      (when (and namespace name)
        (->TestCandidate nil (symbol namespace name)))))

  FileSelector
  (-select [this parent-node]
    (let [path (str (.getPath this))
          source (FileSource/from (.getFile this))]
      (when (or (string/ends-with? path ".clj")
                (string/ends-with? path ".cljc"))
        (select-new-vars source (fn [] (load-file path))))))
  DirectorySelector
  (-select [this]
    ;; TODO implement
    nil)

  ClasspathResourceSelector
  (-select [this]
    (let [name (.getClasspathResourceName this)
          source (ClasspathResourceSource/from name)]
      (when (or (string/ends-with? name ".clj")
                (string/ends-with? name ".cljc"))
        (select-new-vars source (fn [] (load name))))))
  ClassSelector
  (-select [this]
    (let [name (.getClassName this)
          ns-name (-> name
                      (string/replace "__init" "")
                      (string/replace "_" "-"))
          ns-sym (symbol ns-name)
          source (ClassSource/from name)]
      (when (string/ends-with? name "__init")
        (select-new-vars source (fn [] (require ns-sym))))))
  ClasspathRootSelector
  (-select [this]
    ;; TODO implement
    nil))

(defn select [^EngineDiscoveryRequest request ^UniqueId id]
  (binding [*all-vars* (atom (all-vars))]
    (let [listener (.getDiscoveryListener request)
          selectors (.getSelectorsByType request DiscoverySelector)]
      (loop [result []
             head (first selectors)
             tail (rest selectors)]
        (let [candidates (try
                           (-select head)
                           (catch Exception e
                             (.selectorProcessed listener id head (SelectorResolutionResult/failed e))
                             :failed))]
          ;; notify listener of result
          (cond
            (= :failed candidates)
            nil

            (some? candidates)

            (.selectorProcessed listener id head (SelectorResolutionResult/resolved))

            :else
            (.selectorProcessed listener id head (SelectorResolutionResult/unresolved)))

          ;; continue with remaining selectors
          (if tail
            (recur (concat result candidates) (first tail) (rest tail))
            (concat result candidates)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Discovery Descriptor Support
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- var->descriptor [^UniqueId parent-id {:keys [source sym]}]
  (let [var-id (.append parent-id "name" (name sym))
        var (find-var sym)]
    (ClojureVarDescriptor. var-id var source (tags var))))

(defn- ns->descriptor [^UniqueId parent-id [ns-sym candidates]]
  (let [ns-id (.append parent-id "namespace" (name ns-sym))
        source (->> candidates
                    (group-by :source)
                    (apply max-key second)
                    first)
        ns (find-ns ns-sym)
        ns-desc (ClojureNamespaceDescriptor. ns-id ns source (tags ns))]
    (doseq [var-desc (map #(var->descriptor ns-id %) candidates)]
      (.addChild ns-desc var-desc))
    ns-desc))

(defn selections->descriptor [engine ^UniqueId id candidates]
  (let [engine-desc (EngineDescriptor. id (id engine))
        ns-descs (->> candidates
                      (group-by (comp namespace :sym))
                      (map #(ns->descriptor id %)))]
    (doseq [ns-desc ns-descs]
      (.addChild engine-desc ns-desc))
    engine-desc))
