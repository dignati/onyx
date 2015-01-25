(ns onyx.planning
  (:require [com.stuartsierra.dependency :as dep]
            [onyx.extensions :as extensions])
  (:import [java.util UUID]))

(defn only [coll]
  (assert (not (next coll)))
  (if-let [result (first coll)]
    result
    (assert false)))

(defn find-task [catalog task-name]
  (let [matches (filter #(= task-name (:onyx/name %)) catalog)]
    (only matches)))

(defn egress-ids-from-children [elements]
  (into {} (map #(hash-map (:id %) (java.util.UUID/randomUUID)) elements)))

(defmulti create-task
  (fn [catalog task-name parents children-names]
    (:onyx/type (find-task catalog task-name))))

(defmethod create-task :default
  [catalog task-name parents children-names]
  (let [element (find-task catalog task-name)
        children (map (partial find-task catalog) children-names)]
    (extensions/create-io-task element parents children)))

(defn onyx-function-task [catalog task-name parents children-names]
  (let [element (find-task catalog task-name)
        children (map (partial find-task catalog) children-names)]
    {:id (UUID/randomUUID)
     :name (:onyx/name element)
     :egress-ids (egress-ids-from-children children)
     :consumption (:onyx/consumption element)}))

(defmethod create-task :function
  [catalog task-name parents children-names]
  (onyx-function-task catalog task-name parents children-names))

(defmethod create-task :grouper
  [catalog task-name parents children-names]
  (onyx-function-task catalog task-name parents children-names))

(defmethod create-task :aggregator
  [catalog task-name parents children-names]
  (onyx-function-task catalog task-name parents children-names))

(defmethod extensions/create-io-task :input
  [element parent children]
  {:id (UUID/randomUUID)
   :name (:onyx/name element)
   :egress-ids (egress-ids-from-children children)
   :consumption (:onyx/consumption element)})

(defmethod extensions/create-io-task :output
  [element parents children]
  (let [task-name (:onyx/name element)]
    {:id (UUID/randomUUID)
     :name (:onyx/name element)
     :consumption (:onyx/consumption element)}))

(defn to-dependency-graph [workflow]
  (reduce (fn [g edge]
            (apply dep/depend g (reverse edge)))
          (dep/graph) workflow))

(defn discover-tasks [catalog workflow]
  (let [dag (to-dependency-graph workflow)
        sorted-dag (dep/topo-sort dag)]
    (map-indexed
     #(assoc %2 :phase %1)
     (reduce
      (fn [tasks element]
        (let [parents (dep/immediate-dependencies dag element)
              children (dep/immediate-dependents dag element)
              parent-entries (filter #(some #{(:name %)} parents) tasks)]
          (conj tasks (create-task catalog element parent-entries children))))
      []
      sorted-dag))))

(defn unpack-map-workflow
  ([workflow] (vec (unpack-map-workflow workflow [])))
  ([workflow result]
     (let [roots (keys workflow)]
       (if roots
         (concat result
                 (mapcat
                  (fn [k]
                    (let [child (get workflow k)]
                      (if (map? child)
                        (concat (map (fn [x] [k x]) (keys child))
                                (unpack-map-workflow child result))
                        [[k child]])))
                  roots))
         result))))

