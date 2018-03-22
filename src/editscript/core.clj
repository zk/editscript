(ns editscript.core
  (:require [clojure.set :as set])
  (:import [clojure.lang Seqable PersistentVector IPersistentCollection]))

;; (set! *warn-on-reflection* true)

(defprotocol IEdit
  (add-data [this path value])
  (delete-data [this path])
  (replace-data [this path value]))

(defprotocol IEditScript
  (edit-distance [this] "Report the edit instance")
  (get-edits [this] "Report the edits as a vector")
  (get-adds-num [this] "Report the number of additions")
  (get-dels-num [this] "Report the number of deletions")
  (get-reps-num [this] "Report the number of replacements"))

(deftype EditScript [original
                     ^:volatile-mutable ^PersistentVector edits
                     ^:volatile-mutable ^long adds-num
                     ^:volatile-mutable ^long dels-num
                     ^:volatile-mutable ^long reps-num]
  :load-ns true

  IEdit
  (add-data [this path value]
    (locking this
      (set! adds-num (inc adds-num))
      (set! edits (conj edits [path ::+ value]))))
  (delete-data [this path]
    (locking this
      (set! dels-num (inc dels-num))
      (set! edits (conj edits [path ::-]))))
  (replace-data [this path value]
    (locking this
      (set! reps-num (inc reps-num))
      (set! edits (conj edits [path ::r]))))

  IEditScript
  (get-edits [this] edits)
  (get-adds-num [this] adds-num)
  (get-dels-num [this] dels-num)
  (get-reps-num [this] reps-num)
  (edit-distance [this] (+ adds-num dels-num reps-num))

  Seqable
  (seq [this]
    (.seq edits))

  IPersistentCollection
  (count [this]
    (.count edits))
  (cons [this o]
    (.cons edits o))
  (empty [this]
    (EditScript. original [] 0 0 0))
  (equiv [this o]
    (.equiv edits o)))

(defn get-type [v]
  (cond
    (nil? v)    :nil
    (map? v)    :map
    (vector? v) :vec
    (set? v)    :set
    (list? v)   :lst
    :else       :val))

(defn path? [p] (and (vector? p) (::path (meta p))))

(declare diff*)

(defn- diff-map [script path a b]
  (reduce-kv
   (fn [_ ka va]
     (let [path' (conj path ka)]
       (if-some [vb (get b ka)]
        (diff* script path' va vb)
        (diff* script path' va nil))))
   nil
   a)
  (reduce-kv
   (fn [_ kb vb]
     (when-not (contains? a kb)
       (diff* script (conj path kb) nil vb)))
   nil
   b))

(defn- vec-edits*
  "Based on 'Wu, S. et al., 1990, An O(NP) Sequence Comparison Algorithm,
  Information Processing Letters, 35:6, p317-23.'"
  [a b n m]
  (let [delta (- n m)
        fp    (transient {})
        snake (fn [k x]
                (loop [x x y (- x k)]
                  (let [ax (get a x) by (get b y)]
                    (if (and (< x n)
                             (< y m)
                             (= (type ax) (type by))
                             (= ax by))
                      (recur (inc x) (inc y))
                      x))))
        fp-fn (fn [fp k]
                (let [[dk-1 vk-1] (get fp (dec k) [-1 []])
                      dk-1        (inc dk-1)
                      [dk+1 vk+1] (get fp (inc k) [-1 []])
                      x           (max dk-1 dk+1)
                      sk          (snake k x)
                      ops         (let [es (if (> dk-1 dk+1)
                                             (conj vk-1 :-)
                                             (conj vk+1 :+))]
                                    (if (> sk x)
                                      (conj es (- sk x))
                                      es))]
                  (assoc! fp k [sk ops])))
        fp    (loop [p 0]
                (let [fp (loop [k (* -1 p) fp fp]
                           (if (< k delta)
                             (recur (inc k) (fp-fn fp k))
                             fp))
                      fp (loop [k (+ delta p) fp fp]
                           (if (< delta k)
                             (recur (dec k) (fp-fn fp k))
                             fp))]
                  (if-not (= n (first (get (fp-fn fp delta) delta)))
                    (recur (inc p))
                    (persistent! fp))))]
    (-> fp (get delta) second next)))

(defn- swap-ops [edits] (vec (map (fn [op] (case op :+ :- :- :+ op)) edits)))

(defn min+plus->replace
  "Turn isolated consecutive `:-` `:+` into a `:r`,
  do not convert if there's `:-` in front, as it is ambiguous"
  [v]
  {:pre [(vector? v)]}
  (let [n (count v)]
    (loop [r (transient []) i -1 j 0 k 1]
      (let [ei (get v i) ej (get v j) ek (get v k)]
       (cond
         (and (= ej :-)
              (= ek :+)
              (not= ei :-)) (recur (conj! r :r) (+ i 2) (+ j 2) (+ k 2))
         (>= j n)           (persistent! r)
         :else              (recur (conj! r ej) (inc i) (inc j) (inc k)))))))

(defn show [x] (println x) x)

(defn- vec-edits [a b]
  (let [n (count a)
        m (count b)
        v (if (< n m)
            (swap-ops (vec-edits* b a m n))
            (vec-edits* a b n m))]
    (-> v vec min+plus->replace show)))

(defn- diff-vec [script path a b]
  (reduce
   (fn [{:keys [ia ia' ib] :as m} op]
     (case op
       :- (do (diff* script (conj path ia') (get a ia) nil)
              (assoc! m :ia (inc ia)))
       :+ (do (diff* script (conj path ia') nil (get b ib))
              (assoc! m :ia' (inc ia') :ib (inc ib)))
       :r (do (diff* script (conj path ia') (get a ia) (get b ib))
              (assoc! m :ia (inc ia) :ia' (inc ia') :ib (inc ib)))
       (assoc! m :ia (+ ia op) :ia' (+ ia' op) :ib (+ ib op))))
   (transient {:ia 0 :ia' 0 :ib 0})
   (vec-edits a b)))

(defn- diff-set [script path a b]
  (doseq [va (set/difference a b)]
    (diff* script (conj path va) va nil))
  (doseq [vb (set/difference b a)]
    (diff* script (conj path vb) nil vb)))

(defn- diff-lst [script path a b]
  (diff-vec script path (vec a) (vec b)))

(defn diff* [script path a b]
  (println (str "diff* " a " - " b) )
  (let [ta (get-type a) tb (get-type b)]
    (case ta
      :nil (add-data script path b)
      :map (case tb
             :nil (delete-data script path)
             :map (diff-map script path a b)
             (replace-data script path b))
      :vec (case tb
             :nil (delete-data script path)
             :vec (diff-vec script path a b)
             (replace-data script path b))
      :set (case tb
             :nil (delete-data script path)
             :set (diff-set script path a b)
             (replace-data script path b))
      :lst (case tb
             :nil (delete-data script path)
             :lst (diff-lst script path a b)
             (replace-data script path b))
      :val (case tb
             :nil (delete-data script path)
             (when (not= a b)
               (replace-data script path b))))))

(defn diff
  "Create an EditScript that represents the difference between `b` and `a`,
  return nil if `a` and `b` are identical"
  [a b]
  (when-not (identical? a b)
    (let [script (->EditScript a [] 0 0 0)
          path   ^::path []]
      (diff* script path a b)
      script)))

(defn patch
  "Apply the editscript `es` on `a` to produce `b`"
  [a es])

(comment

  (def a {:a {:o 4} :b 'b})
  (def b {:a {:o 3} :b 'c :c 42})

  (get-edits (diff a b))

  (def c [3 'c {:a 3} 4])
  (def d [3 'c {:b 3} 4])
  [[[2 :a] ::-]
   [[2 :b] ::+ 3]]
  (get-edits (diff c d))

  (def e {:a 42})
  (def f {:a 42 :b 43})
  (get-edits (diff e f))

  (def g "abc")
  (def h {:a 42})
  (get-edits (diff g h))

  (def i ["abc" 24 23 {:a [1 2 3]} 1 3 #{1 2}])
  (def j [24 23 {:a [2 3]} 1 3])
  (get-edits (diff i j))

  (def k {:a 42 :b ["a" "b"]})
  (def l ["a" "b" "c"])
  [[[] ::+ ["a" "b" "c"]]]
  (get-edits (diff k l))

  (def a (vec (seq "acebdabbabed")))
  (def b (vec (seq "acbdeacbed")))
  [2 :- 2 :+ 1 :+ 1 :- :- :- 2]
  (get-edits (diff a b))

  (def a (vec (seq "acbdeacbed")))
  (def b (vec (seq "acebdabbabed")))
  (get-edits (diff a b))

  (def a (vec (seq "abcde")))
  (def b (vec (seq "bd")))
  (:- 1 :- 1 :-)
  (get-edits (diff a b))

  (def a (vec (seq "abc")))
  (def b (vec (seq "abd")))
  (2 :- :+)
  (vec-edits a b)
  (get-edits (diff a b))

  (def a (vec (seq "ab")))
  (def b (vec (seq "abc")))
  [2 :+]
  (vec-edits a b)

  (def a (vec (seq "a")))
  (def b (vec (seq "ba")))
  [:+ 1]
  (vec-edits a b)

  (def a (vec (seq "bbabc")))
  (def b (vec (seq "ac")))
  [:- :- 1 :- 1]
  (vec-edits a b)

  (def a (vec (seq "bbabdc")))
  (def b (vec (seq "abc")))
  [:- :- 2 :- 1]
  (vec-edits a b)

  (def a (vec (seq "bbabde")))
  (def b (vec (seq "abc")))
  [:- :- 2 :- :- :+]
  (vec-edits a b)

  (def a #{:a :b :c})
  (def b #{:b :c})
  (get-edits (diff a b))
  )
