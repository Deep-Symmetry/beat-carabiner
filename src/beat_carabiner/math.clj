(ns beat-carabiner.math
  "Functions supporting the calculations needed by the tempo-nudge
  mechanism for subtly keeping the Ableton Link timeline aligned with
  the CDJs."
  (:require [clojure.set :as set])
  (:import (java.util.concurrent TimeUnit)))

(defn target-tempo
  "Given a beat skew (the fraction of a beat that the Ableton Link
  timeline is ahead or behind), the current tempo, and a number of
  milliseconds over which that convergence should take place, returns
  the Ableton Link tempo that would achieve the desired convergence."
  [beat-skew tempo convergence-ms]
  (let [convergence-minutes (/ convergence-ms (.toMillis TimeUnit/MINUTES 1))
        bpm-skew            (/ beat-skew convergence-minutes)]
    (- tempo bpm-skew)))

(defn effective-tempo
  "Given a current tempo, target tempo, the convergence interval in
  milliseconds, and the ramp time in milliseconds (which is consumed
  at both the start and end of the convergence interval), return the
  average tempo that will be achieved over that period, taking ramps
  into account. In the case of an adjustment that is taking over from
  one that was already in effect, the starting tempo is the current
  modified tempo, and an additional argument is used to supply the
  ending tempo (the current tempo of the CDJs), since the two ramps
  will be different.

  The ramp time can be zero if ramps are not being used, in which case
  the effective tempo is trivially the target tempo."
  ([current-tempo target-tempo convergence-ms ramp-ms]
   (effective-tempo current-tempo target-tempo convergence-ms ramp-ms current-tempo))
  ([current-tempo target-tempo convergence-ms ramp-ms ending-tempo]
   (if (zero? ramp-ms)
     target-tempo
     (let [ramp-fraction     (/ ramp-ms convergence-ms)
           unramped-fraction (- 1 (* 2 ramp-fraction))
           ramp-in-mean      (/ (+ current-tempo target-tempo) 2)
           ramp-out-mean     (/ (+ target-tempo ending-tempo) 2)]
       (+ (* ramp-fraction ramp-in-mean) (* unramped-fraction target-tempo) (* ramp-fraction ramp-out-mean))))))

;; Algebra notes. In order to reverse the above function, I simplified
;; it into a single algebraic equation so I could solve for effective-tempo.
;;
;; First I assigned letters to the input parameters and return value:
;;
;;  current-tempo: c
;;  target-tempo: t
;;  effective-tempo: e
;;  ending-tempo: z
;;
;; Then I assigned letters to the intermediate calculation variables
;; so I could build up a single equation:
;;
;;  ramp-ms: m
;;  convergence-ms: v
;;  ramp-fraction: r
;;  unramped-fraction: u
;;  ramp-in-mean: i
;;  ramp-out-mean: o
;;
;; These have the following values:
;;
;;  r = (m/v)
;;  u = (1 - 2r)
;;  i = ((c + t) / 2)
;;  o = ((t + z) / 2)
;;
;; Giving the overall equation:
;;
;;  e = ri + ut + ro
;;
;; Using Emacs' query-replace I expanded the intermediate values
;; in-place, yielding the equation solely in terms of its inputs:
;;
;;  e = (m/v)((c + t) / 2) + (1 - 2(m/v))t + (m/v)((t + z) / 2)
;;
;; Feeding this to a symbolic mathematics system and asking to solve
;; for t, yields:
;;
;;       2ev - mc - mz
;;  t = ---------------  ; v != 0, m != v
;;         2(v - m)
;;
;; The stipulated inequalities are already enforced by the contract of
;; the follow mode parameters, so this was easily translated into the
;; following Clojure:

(defn adjusted-target-tempo
  "Given a current tempo, the desired effective tempo, the convergence
  interval in milliseconds, and the ramp time in milliseconds (which
  is consumed at both the start and end of the convergence interval),
  return the target tempo that will result in the desired effective
  over that period, taking ramps into account. In the case of an
  adjustment that is taking over from one that was already in effect,
  the starting tempo is the current modified tempo, and an additional
  argument is used to supply the ending tempo (the current tempo of
  the CDJs), since the two ramps will be different.

  The ramp time can be zero if ramps are not being used, in which case
  the effective tempo is trivially the target tempo."
  ([current-tempo effective-tempo convergence-ms ramp-ms]
   (adjusted-target-tempo current-tempo effective-tempo convergence-ms ramp-ms current-tempo))
  ([current-tempo effective-tempo convergence-ms ramp-ms ending-tempo]
   (/ (- (* 2 effective-tempo convergence-ms) (* ramp-ms current-tempo) (* ramp-ms ending-tempo))
      (* 2 (- convergence-ms ramp-ms)))))

(def epsilon
  "How close we want two floating-point numbers to be in order to
  consider them equal."
  0.000000001)

(defn close
  "Checks whether two floating point values are within epsilon of each
  other."
  [a b]
  (< (Math/abs (- a b)) epsilon))

(defn tempo-within-limit?
  "Given the current tempo, the target tempo desired for an adjustment,
  and the tempo change limit as a fraction (i.e. 0.02 would mean 2%),
  returns true if the adjusted tempo fits within the specified limit
  of the current tempo."
  [current-tempo target-tempo tempo-change-limit]
  (let [distance (Math/abs (- 1.0 (/ target-tempo current-tempo)))]
    (or (< distance tempo-change-limit) (close distance tempo-change-limit))))

(defn limited-tempo-difference
  "Once we have determined that our target tempo is behond the
  tempo-change limit, this function returns the closest legal tempo
  difference to the one we wanted to use."
  [current-tempo target-tempo tempo-change-limit]
  (if (> target-tempo current-tempo)
    (- (* current-tempo (+ 1.0 tempo-change-limit)) current-tempo)
    (- (* current-tempo (- 1.0 tempo-change-limit)) current-tempo)))

(defn beat-difference
  "Given a tempo difference, as well as the number of milliseconds for
  which the difference has been used, returns the number of beats
  gained or lost during that time compared to the base tempo."
  [tempo-difference convergence-ms]
  (let [convergence-minutes (/ convergence-ms (.toMillis TimeUnit/MINUTES 1))]
    (* tempo-difference convergence-minutes)))

(defn convergence-time
  "Once we have determined that we cannot converge within the desired
  time window without exceeding the tempo limit, calculate the actual
  time at which convergence will occur. Note that in the case of an
  aborted in-progress adjustment, we start with a non-zero tempo
  difference, which was in effect when the former adjustment was
  canceled, this is supported by `starting-difference` in the
  four-argument arity.

  Note that this code assumes that the determination has already been
  made that ramp-ms time is non-zero, and our adjustment is being
  constrained by the tempo change limit, so there will be a positive
  amount of time left after both ramps are taken into account."
  ([beat-skew tempo-difference ramp-ms]
   (convergence-time beat-skew tempo-difference ramp-ms 0))
  ([beat-skew tempo-difference ramp-ms starting-difference]
   (let [ramp-in           (beat-difference (/ (+ starting-difference tempo-difference) 2.0) ramp-ms)
         ramp-out          (beat-difference (/ tempo-difference 2.0) ramp-ms)
         remaining-skew    (+ beat-skew ramp-in ramp-out)
         remaining-minutes (/ remaining-skew tempo-difference)]
     (+ (* ramp-ms 2) (* (Math/abs remaining-minutes) (.toMillis TimeUnit/MINUTES 1))))))

(defn ramp
  "Calculates our shifted cosine ramp from the starting to ending value
  at the specified fraction of the path."
  [start end fraction]
  (let [scale (- 1.0 (Math/cos (* fraction Math/PI)))]
    (+ start (* (- end start) scale 0.5))))

(defn current-tempo-difference
  "Helper function to implement the ramping in and out of the tempo
  difference over the duration of our convergence interval. Takes the
  time (in nanoseconds, since we are using the system monotonic clock)
  at which the adjustment began, the current time according to that
  same clock, the total convergence and ramp times in
  milliseconds (ramp time may be zero, which leads to a trivial
  return), and the tempo difference to which we are ramping. If we
  took over from an aborted adjustment, the starting difference will
  not be zero, and will be supplied using the six-argument arity."
  ([start-ns now-ns convergence-ms ramp-ms tempo-difference]
   (current-tempo-difference start-ns now-ns convergence-ms ramp-ms tempo-difference 0.0))
  ([start-ns now-ns convergence-ms ramp-ms tempo-difference starting-difference]
   (if (zero? ramp-ms)
     tempo-difference
     (let [convergence-ns (.toNanos TimeUnit/MILLISECONDS convergence-ms)
           ramp-ns        (.toNanos TimeUnit/MILLISECONDS ramp-ms)
           elapsed-ns     (- now-ns start-ns)
           remaining-ns   (- convergence-ns elapsed-ns)]
       (if (< elapsed-ns ramp-ns)
         (ramp starting-difference tempo-difference (/ elapsed-ns ramp-ns))
         (if (< remaining-ns ramp-ns)
           (ramp 0.0 tempo-difference (/ remaining-ns ramp-ns))
           tempo-difference))))))

;; In order to be able to react properly to a median offset which may
;; have been older than the last-received beat, we are now tracking a
;; map rather than the simple offsets. It has the following keys:
;;
;; :offset-ms how far the beat was from when it should have occurred,
;;            in milliseconds.
;;
;; :beat-skew how far the beat was from when it should have occurred,
;;            in fractions of a beat.
;;
;; :tempo     the tempo of the Ableton Link timeline when the beat was
;;            received.
;;
;; :adjust-ms if present, we are currently in an adjustment which is
;;            still going to add or subtract the specified number of
;;            milliseconds, so :offset-ms should be added to this
;;            amount before calculating a median.

(defn effective-offset
  "Given an offset record, computes the effective offset it represents,
  given any adjustment that was already in progress at the time."
  [offset]
  (+ (:offset-ms offset) (:adjust-ms offset 0)))

(defn median
  "Find the median of a collection of beat offset records."
  [coll]
  (when-let [sorted (seq (sort (fn [x y] (compare (effective-offset x) (effective-offset y))) coll))]
    (let [n        (count sorted)
          midpoint (quot n 2)]
      (if (odd? n)
        (nth sorted midpoint)
        (let [lower     (dec midpoint)
              lower-val (nth sorted lower)
              upper-val (nth sorted midpoint)
              all-keys  (set/union (set (keys lower-val)) (set (keys upper-val)))]
          (reduce (fn [acc k]
                    (assoc acc k (/ (+ (get lower-val k 0) (get upper-val k 0)) 2.0)))
                  {}
                  all-keys))))))

(defn beat-skew-to-offset-ms
  "Given a beat skew and a tempo, returns the offset in milliseconds that represents."
  [beat-skew tempo]
  (Math/round (* (.toMillis TimeUnit/MINUTES  1) (/ beat-skew tempo))))

(defn offset-ms-to-beat-skew
  "Given a beat offset in milliseconds and a tempo, return the beat skew that represents"
  [offset-ms tempo]
  (/ offset-ms (/ (.toMillis TimeUnit/MINUTES 1) tempo)))
