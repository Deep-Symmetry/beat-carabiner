(ns beat-carabiner.math-test
  (:require [beat-carabiner.math :as sut]
            [clojure.test :as t]))

(t/deftest target-tempo
  ;; If we have to make up 0.2 beats over a minute, our target tempo goes up by 0.2
  (t/is (= 120.2 (sut/target-tempo -0.2 120.0 60000)))

  ;; If we have to lose 0.2 beats over one second, our target tempo goes down by 12, 60 * 0.2
  (t/is (= 108.0 (sut/target-tempo 0.2 120.0 1000)))

  ;; If we have to gain 0.3 beats over 3 seconds, our target tempo goes up by 6 (0.1 beat per second * 60)
  (t/is (= 106.0 (sut/target-tempo -0.3 100.0 3000))))

(t/deftest effective-tempo
  ;; If we have no ramping, effective is the same as target.
  (t/is (= 130.0 (sut/effective-tempo 128.0 130.0 2000 0)))

  ;; If we ramp for the entire adjustment, effective tempo is halfway between starting and target.
  (t/is (= 129.0 (sut/effective-tempo 128.0 130.0 2000 1000)))

  ;; If we started out at our target tempo, only the end ramp matters.
  (t/is (= 129.5 (sut/effective-tempo 130.0 130.0 2000 1000 128.0)))

  ;; If our ramp time is small relative to the adjustment, the target tempo dominates. 1/10 at 130, 9/10 at 132:
  (t/is (= 131.8 (sut/effective-tempo 128.0 132.0 10000 500))))

(defn adjusted-helper
  "Helper function to make sure that given set of parameters we can
  compute an adjusted target tempo whose effective tempo is our
  desired target tempo."
  ([current-tempo target-tempo convergence-ms ramp-ms]
   (adjusted-helper current-tempo target-tempo convergence-ms ramp-ms current-tempo))
  ([current-tempo target-tempo convergence-ms ramp-ms ending-tempo]
   (let [adjusted-tempo (sut/adjusted-target-tempo current-tempo target-tempo convergence-ms ramp-ms ending-tempo)]
     (t/is (= target-tempo (sut/effective-tempo current-tempo adjusted-tempo convergence-ms ramp-ms ending-tempo))))))

(t/deftest adjusted-target-tempo
  (adjusted-helper 128.0 130.0 2000 0)
  (adjusted-helper 128.0 130.0 2000 1000)
  (adjusted-helper 130.0 130.0 2000 1000 128.0)
  (adjusted-helper 128.0 132.0 10000 500))
