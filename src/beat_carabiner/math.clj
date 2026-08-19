(ns beat-carabiner.math
  "Functions supporting the calculations needed by the tempo-nudge
  mechanism for subtly keeping the Ableton Link timeline aligned with
  the CDJs."
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
