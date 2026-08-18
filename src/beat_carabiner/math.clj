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
       (println ramp-fraction unramped-fraction ramp-in-mean ramp-out-mean)
       (+ (* ramp-fraction ramp-in-mean) (* unramped-fraction target-tempo) (* ramp-fraction ramp-out-mean))))))
