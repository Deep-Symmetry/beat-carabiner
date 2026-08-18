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
