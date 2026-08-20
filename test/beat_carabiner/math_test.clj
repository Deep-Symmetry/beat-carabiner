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
     (t/is (sut/close target-tempo
                  (sut/effective-tempo current-tempo adjusted-tempo convergence-ms ramp-ms ending-tempo))))))

(t/deftest adjusted-target-tempo
  ;; From Gabriele, sanity check that if we have an offset of 30 ms,
  ;; convergence time of 2000 ms, and a ramp time of 500 ms, we should
  ;; be right at the probable tempo-change limit of 2%. For example,
  ;; at 100 bpm, we would need a beat skew of -0.05 to be 30 ms
  ;; behind.
  (dotimes [_ 10]
    (let [tempo (inc (rand 140))
          skew (- (/ tempo 2000))
          target (sut/target-tempo skew tempo 2000)]
      (t/is (sut/close (* 1.02 tempo) (sut/adjusted-target-tempo tempo target 2000 500)))))

  ;; Various tests for reversibility.
  (adjusted-helper 128.0 130.0 2000 0)
  (adjusted-helper 128.0 130.0 2000 1000)
  (adjusted-helper 130.0 130.0 2000 1000 128.0)
  (adjusted-helper 128.0 132.0 10000 500))

(t/deftest tempo-within-limit
  (t/is (sut/tempo-within-limit? 100 102 0.02))
  (t/is (not (sut/tempo-within-limit? 100 102.01 0.02)))
  (t/is (sut/tempo-within-limit? 100 98 0.02))
  (t/is (not (sut/tempo-within-limit? 100 97.999 0.02))))

(t/deftest limited-tempo
  (t/is (= (sut/limited-tempo 100 110 0.02) 102.0))
  (t/is (= (sut/limited-tempo 100 90 0.03) 97.0)))

(t/deftest beat-difference
  (t/is (= (sut/beat-difference 120.0 128.0 30000) 4.0))
  (t/is (= (sut/beat-difference 120.0 115.0 6000) -0.5)))

(t/deftest convergence-time
  ;; The boundary case: we exactly match what could be achieved without stretching.
  (t/is (sut/close (sut/convergence-time -0.05 100.0 102.0 500) 2000.0))
  ;; The same in the opposite direction.
  (t/is (sut/close (sut/convergence-time 0.05 100.0 98.0 500) 2000.0))
  ;; Now we need to start stretching.
  (t/is (sut/close (sut/convergence-time -0.06 100.0 102.0 500) 2300.0))
  (t/is (sut/close (sut/convergence-time 0.1 100.0 97.0 500) 2500.0))
  ;; If we get rid of one of the ramp sections, we regain some time.
  (t/is (sut/close (sut/convergence-time 0.1 97.0 97.0 500 100.0) 2250.0)))
