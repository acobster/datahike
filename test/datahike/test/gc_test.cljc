(ns datahike.test.gc-test
  (:require
   #?(:cljs [cljs.test :as t :refer-macros [is deftest testing]]
      :clj  [clojure.test :as t :refer [is deftest testing]])
   [clojure.set :as set]
   [superv.async :refer [<?? S]]
   [datahike.api :as d]
   [datahike.index.interface :refer [-mark]]
   [datahike.experimental.gc :refer [gc!]]
   [datahike.experimental.versioning :refer [branch! delete-branch! merge!
                                             branch-history]]
   [konserve.core :as k]
   [datahike.test.core-test])
  (:import [java.util Date]))

#?(:cljs (def Throwable js/Error))

(defn- count-store [db]
  (count (k/keys (:store db) {:sync? true})))

(def count-query '[:find (count ?e) .
                   :where
                   [?e :age _]])

(def txs (vec (for [i (range 1000)] {:age i})))

(def cfg {:store              {:backend :file}
          :keep-history?      true
          :schema-flexibility :write
          :index              :datahike.index/persistent-set})

(def schema [{:db/ident       :age
              :db/cardinality :db.cardinality/one
              :db/valueType   :db.type/long}])

(deftest datahike-gc-test
  (let [cfg (assoc-in cfg [:store :path] "/tmp/dh-gc-test")
        conn (do
               (d/delete-database cfg)
               (d/create-database cfg)
               (d/connect cfg))
          ;; everything will fit into the root nodes of each index here
        num-roots 3
        fresh-count (+ num-roots 3) ;; :branches + :db + cid + roots
        history-count 3]
    (testing "Test initial store counts."
      (is (= 1 (count (-mark (:eavt @conn)))))
      (is (= fresh-count (count-store @conn)))
      (d/transact conn schema)
      (is (= 1 (count (-mark (:eavt @conn)))))
      (is (= (+ 1 history-count fresh-count num-roots) (count-store @conn))))
    (testing "Delete old db with roots."
      (is (= (+ num-roots 1) (count (<?? S (gc! @conn (Date.))))))
      (is (= (+ history-count fresh-count) (count-store @conn))))
    (testing "Try to run on dirty index and fail."
      (is (thrown-with-msg? Throwable #"Index needs to be properly flushed before marking."
                            (-mark (:eavt
                                    (:db-after
                                     (d/with @conn [{:db/id 100
                                                     :age   5}])))))))

    (testing "Check that we can still read the data."
      (let [new-conn (d/connect cfg)]
        (d/transact conn txs)
        (<?? S (gc! @conn (Date.)))
        (is (= 1000 (d/q count-query @new-conn)))
        (d/release new-conn)))
    (d/release conn)))

(deftest datahike-gc-versioning-test
  (let [cfg          (assoc-in cfg [:store :path] "/tmp/dh-gc-versioning-test")
        conn         (do
                       (d/delete-database cfg)
                       (d/create-database cfg)
                       (d/connect cfg))
        _            (d/transact conn schema)
        ;; create two more branches
        _            (branch! conn :db :branch1)
        cfg1         (assoc cfg :branch :branch1)
        conn-branch1 (d/connect cfg1)
        _            (branch! conn :db :branch2)
        cfg2         (assoc cfg :branch :branch2)
        conn-branch2 (d/connect cfg2)]
    (testing "Check branches."
      (d/transact conn-branch1 txs)
      (d/transact conn-branch2 txs)
      (<?? S (gc! @conn (Date.)))
      (is (nil? (d/q count-query @conn)))
      (is (= 1000 (d/q count-query @conn-branch1)))
      (is (= 1000 (d/q count-query @conn-branch2)))
      (delete-branch! conn :branch2)
      (<?? S (gc! @conn (Date.))))

    (d/release conn)
    (d/release conn-branch1)
    (d/release conn-branch2)

    (testing "Removed branch and after gc check."
      (let [cfg          (assoc-in cfg [:store :path] "/tmp/dh-gc-versioning-test")
            conn         (d/connect cfg)
            ;; create two more branches
            cfg1         (assoc cfg :branch :branch1)
            conn-branch1 (d/connect cfg1)
            cfg2         (assoc cfg :branch :branch2)]
        (is (nil? (d/q count-query @conn)))
        (is (= 1000 (d/q count-query @conn-branch1)))
        (is (thrown-with-msg? Throwable #"Database does not exist."
                              (d/connect cfg2)))
        (d/release conn)
        (d/release conn-branch1)))))

(deftest datahike-gc-range-test
  (let [cfg           (assoc-in cfg [:store :path] "/tmp/dh-gc-range-test")
        conn          (do
                        (d/delete-database cfg)
                        (d/create-database cfg)
                        (d/connect cfg))
        _             (d/transact conn schema)
        ;; create a branch
        _             (branch! conn :db :branch1)
        conn-branch1  (d/connect (assoc cfg :branch :branch1))
        ;; transact on each
        _             (d/transact conn txs)
        _             (d/transact conn-branch1 txs)
        ;; record before-date for gc
        _             (Thread/sleep 100)
        remove-before (Date.)]
    (Thread/sleep 100)
    ;; transact
    (d/transact conn [{:age 42}])
    (d/transact conn-branch1 [{:age 42}])
    ;; transact again
    (d/transact conn [{:age 42}])
    (d/transact conn-branch1 [{:age 42}])
    ;; merge back
    (merge! conn #{:branch1} [])
    (let [db-history       (<?? S (branch-history conn))
          branch1-history  (<?? S (branch-history conn-branch1))
          _ (delete-branch! conn :branch1)
          _ (testing "Check branch counts"
              (is (= 9 (count db-history)))
              (is (= 5 (count branch1-history)))
              (is (= 9 (count (set/union (set db-history) (set branch1-history))))))
          new-history      (set (filter (fn [db]
                                          (let [db-date ^Date (or (get-in db [:meta :datahike/updated-at])
                                                                  (get-in db [:meta :datahike/created-at]))]
                                            (> (.getTime db-date)
                                               (.getTime remove-before))))
                                        (concat db-history branch1-history)))
          ;; gc
          _                (<?? S (gc! @conn remove-before))
          history-after-gc (set (<?? S (branch-history conn)))]
      (testing "Check that newer db roots are still there and counts after gc."
        (is (set/subset? new-history history-after-gc))
        (is (= 5 (count new-history)))
        (is (= 7 (count history-after-gc)))))
    (d/release conn)
    (d/release conn-branch1)))
