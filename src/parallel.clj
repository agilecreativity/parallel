(ns parallel
  (:refer-clojure :exclude [interleave eduction sequence frequencies drop])
  (:require [parallel.educe :as educe]
            [clojure.core.reducers :as r])
  (:import [parallel.educe Educe]
           [java.util.concurrent.atomic AtomicInteger]
           java.util.concurrent.ConcurrentHashMap
           [java.util HashMap Collections Map]))

(set! *warn-on-reflection* true)
(def ^:const ncpu (.availableProcessors (Runtime/getRuntime)))

(def ^:dynamic *mutable* false)

(defn eduction
  [& xforms]
  (Educe. (apply comp (butlast xforms)) (last xforms)))

(defn sequence
  ([xform coll]
     (or (clojure.lang.RT/chunkIteratorSeq
         (educe/create xform (clojure.lang.RT/iter coll)))
       ()))
  ([xform coll & colls]
     (or (clojure.lang.RT/chunkIteratorSeq
         (educe/create
           xform
           (map #(clojure.lang.RT/iter %) (cons coll colls))))
       ())))

(defn interleave
  [coll]
  (fn [rf]
    (let [fillers (volatile! (seq coll))]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result input]
         (if-let [[filler] @fillers]
           (let [step (rf result input)]
             (if (reduced? step)
               step
               (do
                 (vswap! fillers next)
                 (rf step filler))))
           (reduced result)))))))

(defn frequencies
  "Like clojure.core/frequencies, but executes in parallel.
  It takes an optional list of transducers to apply to coll before
  the frequency is calculated. Restrictions:
    * It does not support nil values.
    * Only stateless transducers are allowed in xforms."
  [coll & xforms]
  (let [coll (into [] coll)
        m (ConcurrentHashMap. (quot (count coll) 2) 0.75 ncpu)
        combinef (fn ([] m) ([_ _]))
        rf (fn [^Map m k]
             (let [^AtomicInteger v (or (.get m k) (.putIfAbsent m k (AtomicInteger. 1)))]
               (when v (.incrementAndGet v))
               m))
        reducef (if (seq xforms) ((apply comp xforms) rf) rf)]
    (r/fold combinef reducef coll)
    (if *mutable* m (into {} m))))

(defn update-vals
  "Use f to update the values of a map in parallel. It performs well
  with non-trivial f, otherwise is outperformed by reduce-kv.
  For larger maps (> 100k keys), the final transformation
  from mutable to persistent dominates over trivial f trasforms.
  You can access the raw mutable result setting the dynamic
  binding *mutable* to true. Restrictions:
    * Does not support nil values."
  [^Map input f]
  (let [ks (into [] (keys input))
        output (ConcurrentHashMap. (count ks) 1. ncpu)]
    (r/fold
      (fn ([] output) ([_ _]))
      (fn [^Map m k]
        (.put m k (f (.get input k)))
        m)
      ks)
    (if *mutable* output (into {} output))))

(defn compose [xrf]
  (if (vector? xrf)
    ((last xrf) (first xrf))
    (xrf)))

(defn xrf [rf & xforms]
  [rf (apply comp xforms)])

(defn- foldvec
  "Like standard foldvec, but unwrap reducef before
  use, triggering any state initialization."
  [v n combinef reducef]
  (cond
    (empty? v) (combinef)
    (<= (count v) n) (r/reduce (compose reducef) (combinef) v)
    :else
    (let [split (quot (count v) 2)
          v1 (subvec v 0 split)
          v2 (subvec v split (count v))
          fc (fn [child] #(foldvec child n combinef reducef))]
      (#'r/fjinvoke
        #(let [f1 (fc v1)
               t2 (#'r/fjtask (fc v2))]
           (#'r/fjfork t2)
           (combinef (f1) (#'r/fjjoin t2)))))))

(defn- splitting
  "Calculates split sizes as they would be generated by
  a parallel fold with n=1."
  [coll]
  (iterate
    #(mapcat
       (fn [n] [(quot n 2) (- n (quot n 2))]) %)
    [(count coll)]))

(defn chunk-size
  "Calculates the necessary chunk-size to obtain
  the given number of splits during a parallel fold.
  nchunks needs to be a power of two."
  [coll nchunks]
  {:pre [(== (bit-and nchunks (- nchunks)) nchunks)]}
  (->> (splitting coll)
       (take-while #(<= (count %) nchunks))
       last
       (apply max)))

(defn folder
  ([coll]
   (reify r/CollFold
     (coll-fold [this n combinef reducef]
       (foldvec coll n combinef reducef))))
  ([coll nchunks]
   (reify r/CollFold
     (coll-fold [this _ combinef reducef]
       (foldvec coll (chunk-size coll nchunks) combinef reducef)))))

(defn drop
  ([n]
   (fn [rf]
     (fn []
       ((clojure.core/drop n) rf))))
  ([n coll]
   (clojure.core/drop n coll)))

(defn fold
  "Like reducers fold, but with stateful transducers support.
  n is the number-of-chunks instead of chunk size.
  n must be a power of 2 and defaults to 32."
  ([reducef coll]
   (fold (first reducef) reducef coll))
  ([combinef reducef coll]
   (fold 32 combinef reducef coll))
  ([n combinef reducef coll]
   (r/fold ::ignored combinef reducef (folder coll n))))
