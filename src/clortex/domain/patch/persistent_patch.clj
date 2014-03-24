(ns clortex.domain.patch.persistent-patch
  (require [clortex.protocols :refer :all]
           [datomic.api :as d]))

(extend-type datomic.query.EntityMap PNeuronPatch
  (neurons [this] (:patch/neurons this))
  (neuron-with-index [this index]
   (filter #(= index (neuron-index %)) (neurons this)))
  (neuron-with-id [this id]
   (filter #(= id (neuron-id %)) (neurons this)))
  (columns [this] (:patch/columns this))
  (timestamp [this] (:patch/timestamp this))
  (set-input-sdr [this sdr] this)
  (connect-inputs [this] this)
  (feedforward-synapses [this] [])
  )

(defrecord DatomicPatch [patch-id patch conn]
  PNeuronPatch
  (neurons [this]
   (:patch/neurons patch))
  (neuron-with-index [this index]
   (filter #(= index (neuron-index %)) (neurons this)))
  (neuron-with-id [this id]
   (filter #(= id (neuron-id %)) (neurons this)))
  (columns [this] (:patch/columns patch))
  (timestamp [this] (:patch/timestamp patch))
  (set-input-sdr [this sdr] this)
  (connect-inputs [this] this)
  (feedforward-synapses [this] [])
)


(def empty-patch
  (->DatomicPatch nil nil nil))

(defn find-patch-id
  [ctx patch-uuid]
  (ffirst (d/q '[:find ?patch-id
                 :in $ ?p-uuid
                        :where [?patch-id :patch/uuid ?p-uuid]]
               (d/db (:conn ctx))
               patch-uuid)))

(defn load-patch [ctx ^DatomicPatch patch patch-id]
  (let [conn (:conn ctx)]
    (merge patch {:patch-id patch-id :conn conn :patch (d/entity (d/db conn) patch-id)})))

(defn load-patch-by-uuid [ctx patch-uuid]
  (let [conn (:conn ctx)
        patch-id (find-patch-id ctx patch-uuid)]
    (load-patch ctx empty-patch patch-id)))


(defn create-patch
  [ctx patch-uuid]
  (let [conn (:conn ctx)]
    @(d/transact conn [{:db/id (d/tempid :db.part/user)
                        :patch/uuid patch-uuid}])))

(defn find-patch-uuids
  [ctx]
  (let [conn (:conn ctx)]
    (d/q '[:find ?patch-uuid
           :where [_ :patch/uuid ?patch-uuid]]
         (d/db conn))))

(defn create-patch
  [ctx patch-uuid]
  (let [conn (:conn ctx)]
    @(d/transact conn [{:db/id (d/tempid :db.part/user)
                        :patch/uuid patch-uuid}])))


(defn find-neuron-id
  [ctx patch-id neuron-index]
  (ffirst (d/q '[:find ?neuron-id
                 :in $ ?patch ?neuron-index
                 :where [?patch :patch/neurons ?neuron-id]
                        [?neuron-id :neuron/index ?neuron-index]]
               (d/db (:conn ctx))
               patch-id
               neuron-index)))

(defn add-neuron
  [ctx patch-uuid]
  (let [conn (:conn ctx)
        patch-id (find-patch-id ctx patch-uuid)
        neurons (count
                 (d/q '[:find ?neuron
                        :in $ ?p-id
                        :where [?p-id :patch/neurons ?neuron]]
                      (d/db conn)
                      patch-id)
                     )
        neuron-id (d/tempid :db.part/user)]
    @(d/transact conn [{:db/id neuron-id
                        :neuron/index neurons
                        :neuron/feedforward-potential 0
                        :neuron/predictive-potential 0
                        :neuron/active? false}
                       {:db/id patch-id
                        :patch/neurons neuron-id}])))

(defn add-neurons-to
  [ctx patch-uuid n]
  (let [conn (:conn ctx)
        patch-id (find-patch-id ctx patch-uuid)
        neurons (count
                 (d/q '[:find ?neuron
                        :in $ ?p-id
                        :where [?p-id :patch/neurons ?neuron]]
                      (d/db conn)
                      patch-id)
                     )
        tx-tuples (for [i (range n)
                        :let [neuron-id (d/tempid :db.part/user)
                              neuron-index (+ i neurons)]]
                    [{:db/id neuron-id
                      :neuron/index neuron-index
                      :neuron/active? false}
                     {:db/id patch-id :patch/neurons neuron-id}])
        tx-data (reduce #(conj %1 (%2 0) (%2 1)) [] tx-tuples)]
    tx-data))

(defn add-neurons-to!
  [ctx patch-uuid n]
  @(d/transact (:conn ctx) (add-neurons-to ctx patch-uuid n)))

(defn find-dendrites
  [ctx neuron-id]
  (let [conn (:conn ctx)]
    (d/q '[:find ?dendrite
           :in $ ?neuron
           :where [?neuron :neuron/distal-dendrites ?dendrite]]
         (d/db conn)
         neuron-id)))

(defn add-dendrite!
  [ctx neuron]
  (let [conn (:conn ctx)
        dendrite-id (d/tempid :db.part/user)]
    @(d/transact conn [{:db/id neuron :neuron/distal-dendrites dendrite-id}
                       {:db/id dendrite-id :dendrite/capacity 32}])
    ;(println "Added dendrite" dendrite-id "to neuron" neuron)
    (find-dendrites ctx neuron)))

(defn connect-distal
  [ctx patch-uuid from to]
  (let [conn (:conn ctx)
        randomer (:randomer ctx)
        patch-id (find-patch-id ctx patch-uuid)
        from-id (find-neuron-id ctx patch-id from)
        to-id (find-neuron-id ctx patch-id to)
        synapse-id (d/tempid :db.part/user)
        permanence-threshold 0.2
        permanent? (> (randomer 3) 0)
        permanence (* permanence-threshold (if permanent? 1.1 0.9))
        synapse-tx {:db/id synapse-id
                    :synapse/pre-synaptic-neuron from-id
                    :synapse/permanence permanence
                    :synapse/permanence-threshold permanence-threshold}
        dendrites (find-dendrites ctx to-id)
        dendrites (if (empty? dendrites)
                    (add-dendrite! ctx to-id)
                    dendrites)
        dendrite (ffirst dendrites)]
    ;(println "Connecting " from-id "->" to-id "Adding synapse" synapse-id "to dendrite" dendrite)
    @(d/transact conn [{:db/id dendrite :dendrite/synapses synapse-id}
                       synapse-tx])))

(defn synapse-between
  [ctx patch-uuid from to]
  (let [conn (:conn ctx)
        patch-id (find-patch-id ctx patch-uuid)
        from-id (find-neuron-id ctx patch-id from)
        to-id (find-neuron-id ctx patch-id to)]
    ;(println "checking synapse from neuron " from-id "to" to-id)
    (d/q '[:find ?synapse
           :in $ ?to ?from
           :where
             [?to :neuron/distal-dendrites ?dendrite]
             [?dendrite :dendrite/synapses ?synapse]
             [?synapse :synapse/pre-synaptic-neuron ?from]]
         (d/db conn)
         to-id from-id)))

(defn find-neurons
  [ctx patch-uuid]
  (let [conn (:conn ctx)
        patch-id (find-patch-id ctx patch-uuid)]
    (d/q '[:find ?neuron-index
           :in $ ?patch-id
           :where [?patch-id :patch/neurons ?neuron-id]
           [?neuron-id :neuron/index ?neuron-index]]
         (d/db conn)
         patch-id)))

(defn find-neuron-ids
  [ctx patch-uuid]
  (let [conn (:conn ctx)
        patch-id (find-patch-id ctx patch-uuid)]
    (d/q '[:find ?neuron-id
           :in $ ?patch-id
           :where [?patch-id :patch/neurons ?neuron-id]]
         (d/db conn)
         patch-id)))

