(ns beat-carabiner.math-test
  (:require [beat-carabiner.math :as sut]
            [clojure.test :as t])
  (:import (java.util.concurrent TimeUnit)))

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

(t/deftest limited-tempo-difference
  (t/is (= (sut/limited-tempo-difference 100 110 0.02) 2.0))
  (t/is (= (sut/limited-tempo-difference 100 90 0.03) -3.0)))

(t/deftest beat-difference
  (t/is (= (sut/beat-difference (- 128.0 120.0) 30000) 4.0))
  (t/is (= (sut/beat-difference (- 115.0 120.0) 6000) -0.5)))

(t/deftest convergence-time
  ;; The boundary case: we exactly match what could be achieved without stretching.
  (t/is (sut/close (sut/convergence-time -0.05 (- 102.0 100.0) 500) 2000.0))
  ;; The same in the opposite direction.
  (t/is (sut/close (sut/convergence-time 0.05 (- 98.0 100.0) 500) 2000.0))
  ;; Now we need to start stretching.
  (t/is (sut/close (sut/convergence-time -0.06 (- 102.0 100.0) 500) 2300.0))
  (t/is (sut/close (sut/convergence-time 0.1 (- 97.0 100.0) 500) 2500.0))
  ;; If we get rid of one of the ramp sections, we regain some time.
  (t/is (sut/close (sut/convergence-time 0.1 (- 97.0 100.0) 500 -3.0) 2250.0))
  ;; Additional tests from Gabriele:
  ;;
  ;; 0.4 of a beat is 200 ms at 120 bpm, and 2 per cent of 120 is
  ;; 122.4, so this is 200/0.02 plus one ramp. A round number to
  ;; anchor the simple case.
  (t/is (sut/close (sut/convergence-time -0.4 (- 122.4 120.0) 500) 10500.0))
  ;; the same thing in the other direction. It must give exactly the
  ;; same number, and that is worth pinning down.
  (t/is (sut/close (sut/convergence-time 0.4 (- 117.6 120.0) 500) 10500.0))
  ;; The four argument path: the Link timeline is already running one
  ;; per cent fast when the new correction arrives. It has to come out
  ;; much shorter than the first one, because we are already pushing.
  (t/is (sut/close (sut/convergence-time -0.4 (- 123.624 120.0) 500 1.2) 7039.7350993378)))

(t/deftest ramp
  (t/is (= 120.0 (sut/ramp 120.0 128.0 0)))
  (t/is (= 128.0 (sut/ramp 120.0 128.0 1)))
  (t/is (= 124.0 (sut/ramp 120.0 128.0 0.5)))
  (t/is (sut/close 121.171572875 (sut/ramp 120.0 128.0 0.25)))
  (t/is (sut/close 126.828427124 (sut/ramp 120.0 128.0 0.75))))

(t/deftest current-tempo-difference
  (let [ramp-ms        500
        ramp-ns        (.toNanos TimeUnit/MILLISECONDS ramp-ms)
        convergence-ms 3000
        convergence-ns (.toNanos TimeUnit/MILLISECONDS convergence-ms)
        start-ns       (rand-int 10000000)]
    ;; Probe points along the transition with and without a special starting tempo difference.
    (t/is (= 0.0 (sut/current-tempo-difference start-ns start-ns convergence-ms ramp-ms 0.3)))
    (t/is (= -0.1 (sut/current-tempo-difference start-ns start-ns convergence-ms ramp-ms 0.3 -0.1)))
    (t/is (sut/close 0.15 (sut/current-tempo-difference start-ns (+ start-ns (/ ramp-ns 2))
                                                        convergence-ms ramp-ms 0.3)))
    (t/is (sut/close 0.1 (sut/current-tempo-difference start-ns (+ start-ns (/ ramp-ns 2))
                                                       convergence-ms ramp-ms 0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns ramp-ns) convergence-ms ramp-ms 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns ramp-ns) convergence-ms ramp-ms 0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (/ convergence-ns 2)) convergence-ms ramp-ms 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (/ convergence-ns 2)) convergence-ms ramp-ms
                                               0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (- convergence-ns ramp-ns))
                                               convergence-ms ramp-ms 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (- convergence-ns ramp-ns))
                                               convergence-ms ramp-ms 0.3 -0.1)))
    (t/is (sut/close 0.15 (sut/current-tempo-difference start-ns (+ start-ns convergence-ns (/ ramp-ns -2))
                                                        convergence-ms ramp-ms 0.3)))
    (t/is (sut/close 0.15 (sut/current-tempo-difference start-ns (+ start-ns convergence-ns (/ ramp-ns -2))
                                                        convergence-ms ramp-ms 0.3 -0.1)))
    (t/is (= 0.0 (sut/current-tempo-difference start-ns (+ start-ns convergence-ns) convergence-ms ramp-ms 0.3)))
    (t/is (= 0.0 (sut/current-tempo-difference start-ns (+ start-ns convergence-ns) convergence-ms ramp-ms 0.3 -0.1)))

    ;; Test the trivial cases with no ramping.
    (t/is (= 0.3 (sut/current-tempo-difference start-ns start-ns convergence-ms 0 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns start-ns convergence-ms 0 0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (/ ramp-ns 2))
                                                        convergence-ms 0 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (/ ramp-ns 2))
                                                       convergence-ms 0 0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns ramp-ns) convergence-ms 0 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns ramp-ns) convergence-ms 0 0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (/ convergence-ns 2)) convergence-ms 0 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (/ convergence-ns 2)) convergence-ms 0 0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (- convergence-ns ramp-ns))
                                               convergence-ms 0 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns (- convergence-ns ramp-ns))
                                               convergence-ms 0 0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns convergence-ns (/ ramp-ns -2))
                                                        convergence-ms 0 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns convergence-ns (/ ramp-ns -2))
                                                        convergence-ms 0 0.3 -0.1)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns convergence-ns) convergence-ms 0 0.3)))
    (t/is (= 0.3 (sut/current-tempo-difference start-ns (+ start-ns convergence-ns) convergence-ms 0 0.3 -0.1)))))
