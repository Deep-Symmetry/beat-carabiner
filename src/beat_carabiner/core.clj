(ns beat-carabiner.core
  "The main entry point for the beat-carabiner library. Simple scenarios
  can just call [[connect]] followed by [[set-sync-mode]], but you
  will likely want to explore the rest of the API."
  (:require [amalloy.ring-buffer :as rb]
            [beat-carabiner.math :as math]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [taoensso.timbre :as timbre])
  (:import [java.net Socket InetSocketAddress]
           [java.util.concurrent TimeUnit]
           [org.deepsymmetry.beatlink DeviceFinder BeatFinder VirtualCdj MasterListener]
           [org.deepsymmetry.libcarabiner Runner]
           [org.deepsymmetry.electro Metronome Snapshot]))

(def ^DeviceFinder device-finder
  "Holds the singleton instance of the Beat
  Link [`DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
  for convenience."
  (DeviceFinder/getInstance))

(def ^VirtualCdj virtual-cdj
  "Holds the singleton instance of the Beat
  Link [`VirtualCDJ`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
  for convenience."
  (VirtualCdj/getInstance))

(def beat-finder
  "Holds the singleton instance of the Beat
  Link [`BeatFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/BeatFinder.html)
  for convenience."
  (BeatFinder/getInstance))

(defonce ^{:private true
           :doc "When connected, holds the socket used to communicate
  with Carabiner, the estimated latency in milliseconds between an
  actual beat played by a CDJ and when we receive the packet, values
  which track the peer count and tempo reported by the Ableton Link
  session and the target tempo we are trying to maintain (when
  applicable), and the `:running` flag which can be used to gracefully
  terminate that thread.

  If we used lib-carabiner to start an embedded instance of Carabiner,
  then `:embedded` will be `true` to let us know it should be stopped
  when we disconnect.

  The `:bar` entry controls whether the Link and Pioneer timelines
  should be aligned at the bar level (if true) or beat level.

  The `:last` entry is used to assign unique integers to each
  `:running` value as we are started and stopped, so a leftover
  background thread from a previous run can know when it is stale and
  should exit.)

 `:sync-mode` can be `:off`, `:manual` (meaning that external code
  will be calling `lock-tempo` and `unlock-tmepo` to manipulate the
  Ableton Link session), `:passive` (meaning Ableton Link always
  follows the Pro DJ Link network, and we do not attempt to control
  other players on that network), or `:full` (bidirectional,
  determined by the Master and Sync states of players on the DJ Link
  network, including Beat Link's `VirtualCdj`, which stands in for the
  Ableton Link session).

  Once we are connected to Carabiner, the current Link session tempo
  will be available under the key `:link-bpm`.

  If we have been told to lock the Link tempo, there will be a
  `:target-bpm` key holding that tempo."}

  client (atom {:port 17000
                :latency   1
                :last      0
                :sync-mode :off
                :embedded  false}))

(def bpm-tolerance
  "The amount by which the Link tempo can differ from our target tempo
  without triggering an adjustment."
  0.00001)

(def skew-tolerance
  "The fraction by which the start of a beat can be off without
  triggering an adjustment. This can't be larger than the normal beat
  packet jitter without causing spurious readjustments. Note that
  since the implementation of `set-follow-mode` this is only used as a
  default value for `:jump-beat-threshold` when in `:jump-only` mode."
  0.0166)

(def ^:private follow-mode
  "Stores the parameters used to control how the Ableton Link timeline
  is made to follow the CDJs. See `set-follow-mode` below for
  documentation."
  (atom [:jump-only]))

(def ^:private follow-state
  "Stores current state needed to implement the more sophisticated
  `:tempo-if-close` follow-mode. See `get-follow-state` below for
  details."
  (atom nil))

(def connect-timeout
  "How long the connection attempt to the Carabiner daemon can take
  before we give up on being able to reach it."
  5000)

(def read-timeout
  "How long reads from the Carabiner daemon should block so we can
  periodically check if we have been instructed to close the
  connection."
  2000)

(defn- validate-follow-mode-args
  "Helper function to perform some basic sanity checks on the arguments
  associated with a follow mode (see `set-follow-mode` for the
  details)."
  [mode {:keys [tempo-ms-threshold rolling-beats convergence-ms ramp-ms tempo-change-limit]}]
  (when (= mode :tempo-if-close)
      (when-not (pos? tempo-ms-threshold)
        (throw (IllegalArgumentException. "tempo-ms-threshold must be positive")))
      (when-not (pos? rolling-beats)
        (throw (IllegalArgumentException. "rolling-beats must be positive")))
      (when (< convergence-ms 200)
        (throw (IllegalArgumentException. "convergence-ms cannot be less than 200")))
      (when (and (< ramp-ms 100) (not (zero? ramp-ms)))
        (throw (IllegalArgumentException. "ramp-ms must be at least 100 if it is not zero")))
      (when (> ramp-ms (quot convergence-ms 2))
        (throw (IllegalArgumentException. "ramp-ms can be no greater than half of convergence-ms")))
      (when (< tempo-change-limit 0.0001)
        (throw (IllegalArgumentException. "tempo-change-limit must be at least 0.0001")))))

(declare cancel-adjustment)  ; We want to be able to do this when setting follow mode.

(defn set-follow-mode
  "Sets how the Ableton Link timeline will be caused to follow the
  CDJs (when a CDJ is set as the master). Calling this function
  reinitializes the follow mechanism, discarding any in-progress tempo
  adjustment. If you want to tweak a parameter or two without such a
  disruption, see `adjust-follow-parameters`.

  The first argument is a keyword with one of two possible values:
  `:jump-only`, the original (and still default) behavior, which
  leaves the Ableton tempo alone and jumps the timeline when the
  divergence exceeds `:jump-beat-threshold` (described below), and

  `:tempo-if-close`, a new approach which will still jump at
  `:jump-beat-threshold` but inside of that, if the divergence exceeds
  `:tempo-ms-threshold` (described below), will instead tweak the
  tempo of Ableton Link to push the timelines into convergence.

  After the required argument, the following optional keyword
  arguments can also be supplied, with values to tune behavior:

  `:jump-beat-threshold`, a floating point value that controls the
  fraction of a beat by which the timelines are allowed to diverge
  before jumping the Ableton Link timeline to realign them. This value
  is used in both follow modes. For backwards compatibility, in
  `:jump-only` mode, this defaults to the value of `skew-tolerance`,
  but in `:tempo-if-close` mode, thanks to the more robust de-jitter
  algorithm, and ability to smoothly realign timelines, it defaults to
  0.4.

  `:tempo-ms-threshold`, an integer, default value 5, that specifies
  the number of milliseconds the timelines are allowed to diverge in
  `:tempo-if-close` mode before the Ableton Link tempo is nudged to
  guide them back into convergence. Note that this must be smaller
  than the fraction of a beat in `:jump-beat-threshold` or it won't
  have a chance to be used.

  `:rolling-beats`, an integer, default value 5, that determines how
  many beats are collected in order to compute a rolling median for
  checking `:tempo-ms-threshold`. This protects against beat packet
  jitter.

  `:convergence-ms`, an integer, default value 2000, used when nudging
  the tempo. It specifies the duration over which we want the
  timelines to be brought into convergence. Smaller values cause
  faster convergence, but more drastic tempo changes, and may be
  overridden by:

  `:ramp-ms`, an integer, default value 500 or half of
  `:convergence-ms`, whichever is smaller. Specifies how long to spend
  ramping in the tempo change at the beginning of the adjustment, and
  to spend ramping it out again at the end, so it is less jarring.
  This can be set to zero, which means the tempo should just be
  adjusted instantly in both cases, but otherwise needs to be set to a
  value 100 or larger, because of the ten-millisecond timing of the
  thread used to perform the adjustments. (It can't be larger than
  half of `:convergence-ms` because at that point the entire period of
  the adjustment is spent ramping in and then back out). A raised
  cosine is used as the ramp shape.

  `:tempo-change-limit`, a floating point value that provides an upper
  bound for how far we are allowed to change the tempo when trying to
  converge within `:convergence-ms`. The default value of 0.02 means
  we can change tempo by as much as 2%, and at that point convergence
  will be forced to take longer. For sanity, this value must be at
  least 0.0001.

  The `:tempo-if-close` algorithm has been developed in collaboration
  with Gabrielle Giletta based on his insightful analysis of ways to
  improve synchronization during Meduza performances."
  [mode & {:keys [jump-beat-threshold tempo-ms-threshold rolling-beats convergence-ms ramp-ms tempo-change-limit]
           :or   {tempo-ms-threshold 5
                  rolling-beats      5
                  convergence-ms     2000
                  tempo-change-limit 0.02}}]
  (when-not (#{:jump-only :tempo-if-close} mode)
    (throw (IllegalArgumentException. (str "mode must be :jump-only or :tempo-if-close, not " mode))))
  (let [base    {:jump-beat-threshold (or jump-beat-threshold (if (= mode :tempo-if-close) 0.4 skew-tolerance))}
        ramp-ms (or ramp-ms (min 500 (quot convergence-ms 2)))
        args    (cond-> base
                  (= mode :tempo-if-close)
                  (merge {:tempo-ms-threshold tempo-ms-threshold
                          :rolling-beats      rolling-beats
                          :convergence-ms     convergence-ms
                          :ramp-ms            ramp-ms
                          :tempo-change-limit tempo-change-limit}))]
    (validate-follow-mode-args mode args)
    (cancel-adjustment)
    (let [result (reset! follow-mode [mode args])]
      (when (= mode :tempo-if-close)
        (swap! follow-state assoc :beat-offsets (rb/ring-buffer rolling-beats)))
      result)))

(defn get-follow-mode
  "Returns the parameters in effect that control how the Ableton Link
  timeline will be caused to follow the CDJs (when the CDJs are set as
  the master)."
  []
  @follow-mode)

(defn adjust-follow-parameters
  "Allows specific tuning parameters of the configured follow mode to be
  changed. All other values are left unchanged."
  [& {:as new-args}]
  (let [extra-keys (set/difference (set (keys new-args)) (-> @follow-mode rest first keys set))]
    (when (seq extra-keys)
      (throw (IllegalArgumentException. (str "Attempt to adjust nonexistent key(s) " (str/join ", " extra-keys))))))
  (let [[former result] (swap-vals! follow-mode (fn [[mode old-args]]
                                                  (let [args (merge old-args (select-keys new-args (keys old-args)))]
                                                    (validate-follow-mode-args mode args)
                                                    [mode args])))]
    (when-let [old-ring-size (get-in former [1 :rolling-beats])]
      (when-let [new-ring-size (:rolling-beats new-args)]
        (when (not= old-ring-size new-ring-size)
          (swap! follow-state update :beat-offsets (fn [old-buffer]
                                                     (let [new-buffer (rb/ring-buffer new-ring-size)]
                                                       (into new-buffer (seq old-buffer))))))))
    result))

(defn get-follow-state
  "Returns information about the follow state needed to implement the
  more sophisticated `:tempo-if-close` follow mode. Elements include:

  `:beat-offsets` a ring buffer that holds the rolling series of
  nth-most-recent timeline offsets along with other information,
  allowing us to compute the median. See the comment above
  `beat-carabiner.math/median` for details.

  `:tempo-delta` will be present when a tempo adjustment is in effect,
  storing the amount that should be added to the Pro DJ Link tempo
  when setting the Ableton Link tempo.

  `:adjustment-began` the timestamp at which the current tempo
  adjustment started, in nanoseconds (since we use the system
  monotonic clock to track it).

  `:adjustment-ns` the total length, in nanoseconds, after which the
  current tempo adjustment will end.

  `:offset-ms` the total number of milliseconds this offset is
  intended to add or remove from the Ableton Link timeline.

  `:adjustment-id` a random UUID assigned to the current tempo
  adjustment so that the thread responsible for implementing it can
  know it should end if a jump or new adjustment takes precedence."
  []
  @follow-state)

(defn state
  "Returns the current state of the Carabiner connection as a map whose
  keys include:

  `:port`, the port on which the Carabiner daemon is listening.

  `:latency`, the estimated latency in milliseconds between an
  actual beat played by a CDJ and when we receive the packet.
  Negative values mean we are receiving beat packets earlier
  than the actual beats are heard.

  `:sync-mode`, which can be:

  * `:off`

  * `:manual` (meaning that external code will be
    calling [[lock-tempo]] and [[unlock-tempo]] to manipulate the
    Ableton Link session)

  * `:passive` (meaning Link always follows the Pro DJ Link network,
    and we do not attempt to control other players on that network), or

  * `:full` (bidirectional, determined by the Master and Sync states
    of players on the DJ Link network, including Beat Link's
    [`VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)).

  `:bar` determines whether the Link and Pioneer timelines should be
  synchronized at the level of entire measures (if present and
  `true`), or individual beats (if not).

  `:running` will have a non-`nil` value if we are connected to
  Carabiner. Once we are connected to Carabiner, the current Link
  session tempo will be available under the key `:link-bpm` and the
  number of Link peers under `:link-peers`.

  If we have been told to lock the Link tempo, there will be a
  `:target-bpm` key holding that tempo. Note that if the current
  follow-mode supports tempo adjustment to recover from timeline
  misalignments, the Link tempo may be intentionally higher or lower
  than `:target-bpm` in order to achieve that."
  []
  (select-keys @client [:port :latency :sync-mode :bar :running :link-bpm :link-peers :target-bpm]))

(defn active?
  "Checks whether there is currently an active connection to a
  Carabiner daemon."
  []
  (:running @client))

(defn adjusting?
  "Checks whether a tempo-based Ableton Link timeline adjustment is
  currently taking place. If so, returns the adjustment length in
  nanoseconds."
  []
  (:adjustment-ns @follow-state))

(defn adjustment-offsets
  "If an adjustment is in progress, returns the map that should be
  merged with a beat offset record to track it. For simplicity, when
  computing the milliseconds remaining to be adjusted, we linearize it
  to the rate between the tempo ramps, clamped to zero and one. The
  ramp period is small enough that this should not be an issue."
  []
  (when-let [{:keys [adjustment-ns adjustment-began offset-ms]} @follow-state]
    (let [elapsed-ns         (- (System/nanoTime) adjustment-began)
          remaining-ns       (- adjustment-ns elapsed-ns)
          ramp-ns            (.toNanos TimeUnit/MILLISECONDS (get-in @follow-mode [1 :ramp-ms] 500))
          remaining-fraction (max 0.0 (min 1.0 (/ (- remaining-ns (/ ramp-ns 2.0)) (- adjustment-ns ramp-ns))))]
    {:adjust-ms (Math/round (- (* offset-ms remaining-fraction)))})))

(defn sync-enabled?
  "Checks whether we have an active connection and are in any sync mode
  other than `:off`."
  []
  (let [state @client]
    (and (:running state)
         (not= :off (:sync-mode state)))))

(defn set-carabiner-port
  "Sets the port to be uesd to connect to Carabiner. Can only be called
  when not connected."
  [port]
  (when (active?)
    (throw (IllegalStateException. "Cannot set port when already connected.")))
  (when-not (<= 1 port 65535)
    (throw (IllegalArgumentException. "port must be in range 1-65535")))
  (swap! client assoc :port port))

(defn set-latency
  "Sets the estimated latency in milliseconds between an actual beat
  played on a CDJ and when we receive the packet. Negative values
  means we are receiving the packets before the beats are actually
  heard."
  [latency]
    (when-not (<= -1000 latency 1000)
    (throw (IllegalArgumentException. "latency must be in range -1000–1000.")))
  (swap! client assoc :latency latency))

(defn set-sync-bars
  "Sets whether we should synchronize the Ableton Link and Pioneer
  timelines at the level of entire measures, rather than simply
  individual beats."
  [bars?]
  (swap! client assoc :bar (boolean bars?)))

(defn- ensure-active
  "Throws an exception if there is no active connection."
  []
  (when-not (active?)
    (throw (IllegalStateException. "No active Carabiner connection."))))

(defn- send-message
  "Sends a message to the active Carabiner daemon."
  [message]
  (ensure-active)
  (let [output-stream (.getOutputStream ^Socket (:socket @client))]
    (.write output-stream (.getBytes (str message "\n") "UTF-8"))
    (.flush output-stream)))

(defn- check-link-tempo
  "If we are supposed to master the Ableton Link tempo, make sure the
  Link tempo is close enough to our target value, and adjust it if
  needed. Otherwise, if the Virtual CDJ is the tempo master, set its
  tempo to match Link's."
  []
  (let [state       @client
        link-bpm   (:link-bpm state 0.0)
        target-bpm (:target-bpm state)]
    (if (some? target-bpm)
      (let [target-bpm (+ target-bpm (:tempo-delta @follow-state 0.0))]
        (when (> (Math/abs ^Double (- link-bpm target-bpm)) ^Double bpm-tolerance)
          (send-message (str "bpm " target-bpm))))
      (when (and (.isTempoMaster virtual-cdj) (pos? link-bpm))
        (.setTempo virtual-cdj link-bpm)))))

(defn- cancel-adjustment
  "If there was a tempo-based Ableton Link timeline adjustment taking
  place, end it and set its tempo back to normal. If `id` is supplied,
  will only cancel the adjustment if it matches the specified id."
  ([]
   (cancel-adjustment nil))
  ([id]
   (let [[old-state new-state] (swap-vals! follow-state
                                           (fn [state]
                                             (if (and id (not= id (:adjustment-id state)))
                                               state
                                               (select-keys state [:beat-offsets]))))]
     (when (not= (:adjustment-id old-state) (:adjustment-id new-state))
       (check-link-tempo)
       (if id
         (timbre/info "tempo-adjustment ended, id:" id)
         (timbre/info "tempo-adjustment canceled, id:" (:adjustment-id old-state)))))))

(def ^{:private true
       :doc "Functions to be called with the updated client state
  whenever we have processed a status update from Carabiner."}

  status-listeners (atom #{}))

(defn add-status-listener
  "Registers a function to be called with the updated client state
  whenever we have processed a status update from Carabiner. When that
  happens, `listener` will be called with a single argument
  containing the same map that would be returned by calling [[state]]
  at that moment.

  This registration can be reversed by
  calling [[remove-status-listener]]."
  [listener]
  (swap! status-listeners conj listener))

(defn remove-status-listener
  "Removes a function from the set that is called whenever we have
  processed a status update from Carabiner. If `listener` had been
  passed to [[add-status-listener]], it will no longer be called."
  [listener]
  (swap! status-listeners disj listener))

(defn- send-status-updates
  "Calls any registered status listeners with the current client state."
  []
  (when-let [listeners (seq @status-listeners)]
      (let [updated (state)]
        (doseq [listener listeners]
          (try
            (listener updated)
            (catch Throwable t
              (timbre/error t "Problem running status-listener.")))))))

(defn- handle-status
  "Processes a status update from Carabiner. Calls any registered status
  listeners with the resulting state, and performs any synchronization
  operations required by our current configuration."
  [status]
  (let [bpm (double (:bpm status))
        peers (int (:peers status))]
    (swap! client assoc :link-bpm bpm :link-peers peers)
    (check-link-tempo)
    (send-status-updates)))

(defn outlier?
  "Helper function to check whether a beat offset record is outside of
  the allowable threshold for avoiding a tempo adjustment, taking into
  account any adjustment which is already underway."
  [tempo-ms-threshold offset]
  (> (Math/abs (+ (:offset-ms offset) (:adjust-ms offset 0))) tempo-ms-threshold))

(defn log-offset
  "Helper function for logging a beat offset record; logs the
  milliseconds, and if an adjustment is taking place, the expected
  remaining offset from that adjustment and the sum of the two."
  [offset]
  (let [offset-ms (:offset-ms offset)]
    (str offset-ms (when-let [adjust-ms (:adjust-ms offset)]
                  (str (when-not (neg? adjust-ms) "+")
                       adjust-ms "=" (+ offset-ms adjust-ms))))))

(defn- should-adjust?
  "Given the current follow mode atom contents, and the contents of
  follow-state after adding the latest rolling beat, check if we
  should begin a tempo-based timeline adjustment. This is calculated
  for the purpose of the logs it produces even if the result is
  meaningless because we are already inside such an adjustment.
  Returns `nil` if we do not need to adjust, or the median offset
  value that proved we need to adjust, so it can be used to configure
  the adjustment.

  `raw-beat` is passed for logging purposes, as it helps Gabriele
  correlate with network captures and compute statistics."
  [mode new-state raw-beat]
  (let [offsets                                    (:beat-offsets new-state)
        {:keys [tempo-ms-threshold rolling-beats]} (second mode)
        total                                      (count offsets)
        outlier-count                              (count (filter (partial outlier? tempo-ms-threshold) offsets))
        already?                                   (boolean (adjusting?))
        adjustment-id-prefix                       (str (when-let [id (:adjustment-id new-state)] (subs (str id) 0 8)))]
    (timbre/debug "tempo-if-close raw-beat:" raw-beat "beat-offsets: ["
                  (str/join ", " (map log-offset offsets)) "] total too far:" outlier-count)
    (if (< total rolling-beats)
      (timbre/debug "tempo-if-close tempo:" (if-let [recent (last offsets)] (:tempo recent) "n/a")
                    "median: n/a, need" rolling-beats "beats, have" total
                    "; already adjusting?" already? adjustment-id-prefix)
      (let [median  (math/median offsets)
            adjust? (outlier? tempo-ms-threshold median)]
        (timbre/debug "tempo-if-close tempo:" (:tempo median) "median:" (log-offset median) "should adjust?" adjust?
                      "already adjusting?" already? adjustment-id-prefix)
        (when adjust? median)))))

(defn- run-adjustment
  "Sets up the necessary state to run a tempo-based timeline
  adjustment (which will kill off any previously-running adjustment),
  then creates the background thread that will perform it."
  [tempo-delta starting-delta convergence-ms ramp-ms offset-ms]
  (let [adjustment-id (random-uuid)]
    (timbre/info "Starting tempo adjustment, tempo-delta:" tempo-delta "starting-delta:" starting-delta
                 "convergence-ms:" convergence-ms "ramp-ms:" ramp-ms "offset-ms:" offset-ms "id:" adjustment-id)
    (swap! follow-state assoc
           :tempo-delta starting-delta
           :adjustment-ns (.toNanos TimeUnit/MILLISECONDS convergence-ms)
           :adjustment-began (System/nanoTime)
           :adjustment-id adjustment-id
           :offset-ms offset-ms)
    (swap! follow-state update :beat-offsets empty)  ; Beats before this adjustment are no longer meaningful.
    (future
      (loop []
        (try
          (Thread/sleep 10)
          (let [state   @follow-state
                now     (System/nanoTime)
                began   (:adjustment-began state 0)
                elapsed (- now began)
                total   (:adjustment-ns state 0)
                ramp-ns (.toNanos TimeUnit/MILLISECONDS ramp-ms)]
            (when (and (= adjustment-id (:adjustment-id state))
                       (< elapsed total))
              (let [current-delta (math/current-tempo-difference began now convergence-ms ramp-ms
                                                                 tempo-delta starting-delta)]
                (swap! follow-state assoc :tempo-delta current-delta)
                (when (or (<= elapsed ramp-ns) (>= elapsed (- total ramp-ns)))
                  (check-link-tempo)))))
          (catch Throwable t
            (timbre/error t "Problem in run-adjustment background thread")))
        (let [state @follow-state]
          (if (and (= adjustment-id (:adjustment-id state))
                   (< (- (System/nanoTime) (:adjustment-began state)) (:adjustment-ns state)))
            (recur)
            (try
              (cancel-adjustment adjustment-id)
              (catch Throwable t
                (timbre/error t "Problem canceling adjustment when ending run-adjustment thread"))))))
      (timbre/info "Thread for tempo adjustment ending for id" adjustment-id))))

(defn- start-adjustment
  "Starts a tempo-based timeline adjustment, given the current contents
  of the follow-mode and client atoms, and the timeline offset in
  milliseconds that we want to correct represented as a beat skew and
  the tempo when that beat was received.

  If `starting-delta` is non-zero, we were in the middle of a
  different adjustment, and are starting the new one with that delta
  instead of the normal zero."
  [mode state beat-skew beat-tempo starting-delta]
  (let [starting-tempo               (:link-bpm state 120.0)
        original-tempo                 (- starting-tempo starting-delta)
        {:keys [convergence-ms ramp-ms
                tempo-change-limit]} (second mode)
        target-tempo                 (math/target-tempo beat-skew original-tempo convergence-ms)
        adjusted-tempo               (math/adjusted-target-tempo starting-tempo target-tempo convergence-ms ramp-ms
                                                                 original-tempo)
        offset-ms                    (math/beat-skew-to-offset-ms beat-skew beat-tempo)]
    (if (math/tempo-within-limit? original-tempo adjusted-tempo tempo-change-limit)
      (let [tempo-delta (- adjusted-tempo original-tempo)]
        (run-adjustment tempo-delta starting-delta convergence-ms ramp-ms offset-ms))
      (let [tempo-delta      (math/limited-tempo-difference original-tempo adjusted-tempo tempo-change-limit)
            convergence-time (math/convergence-time beat-skew tempo-delta ramp-ms starting-delta)]
        (run-adjustment tempo-delta starting-delta convergence-time ramp-ms offset-ms)))))

(defn- handle-beat-at-time
  "Processes a beat probe response from Carabiner."
  [info]
  (let [^Double beat       (:beat info)
        raw-beat           (Math/round beat)
        ^Double raw-skew   (mod beat 1.0)
        ^Double beat-skew  (if (> raw-skew 0.5) (- raw-skew 1.0) raw-skew)
        state              @client
        mode               @follow-mode
        [time beat-number] (:beat state)
        current-tempo      (:link-bpm state 120.0)
        candidate-beat     (if (and beat-number (= time (:when info)))
                             (let [bar-skew   (- (dec beat-number) (mod raw-beat 4))
                                   adjustment (if (<= bar-skew -2) (+ bar-skew 4) bar-skew)]
                               (+ raw-beat adjustment))
                             raw-beat)
        target-beat        (if (neg? candidate-beat) (+ candidate-beat 4) candidate-beat)
        multi-beat         (not= target-beat raw-beat)
        offset-ms          (math/beat-skew-to-offset-ms beat-skew current-tempo)]
    (if (or (> (Math/abs beat-skew) (get-in mode [1 :jump-beat-threshold] skew-tolerance)) multi-beat)
      ;; In either follow mode, if we have strayed far enough, it is time to just jump.
      (do
        (timbre/info "Jumping: raw-beat" raw-beat "target-beat" target-beat "beat-skew" (format "%.5f" beat-skew)
                     offset-ms "ms, multi-beat?" multi-beat)
        (cancel-adjustment)
        (swap! follow-state update :beat-offsets empty)  ; Historic offsets are meaningless after a jump.
        (send-message (str "force-beat-at-time " target-beat " " (:when info) " 4.0")))
      (when (= :tempo-if-close (first mode)) ; We didn't have to jump, see if we should adjust via tempo instead.
        (let [new-state (swap! follow-state update :beat-offsets conj (merge {:offset-ms offset-ms
                                                                              :beat-skew beat-skew
                                                                              :tempo     current-tempo}
                                                                             (when (adjusting?) (adjustment-offsets))))
              adjust?   (should-adjust? mode new-state raw-beat)]
          (when adjust?
            (let [median-skew   (math/offset-ms-to-beat-skew
                                 (math/adjustment-offset adjust? (last (:beat-offsets new-state)))
                                 (:tempo adjust?))
                  current-delta (:tempo-delta new-state)]
              ;; We don't need to worry about canceling an existing adjustment, the change of ID will do that.
              (start-adjustment mode state median-skew current-tempo (or current-delta 0.0)))))))))

(defn- handle-phase-at-time
  "Processes a phase probe response from Carabiner."
  [info]
  (let [state                            @client
        [ableton-now ^Snapshot snapshot] (:phase-probe state)
        align-to-bar                     (:bar state)]
    (if (= ableton-now (:when info))
      (let [desired-phase  (if align-to-bar
                             (/ (:phase info) 4.0)
                             (- (:phase info) (long (:phase info))))
            actual-phase   (if align-to-bar
                             (.getBarPhase snapshot)
                             (.getBeatPhase snapshot))
            phase-delta    (Metronome/findClosestDelta (- desired-phase actual-phase))
            phase-interval (if align-to-bar
                             (.getBarInterval snapshot)
                             (.getBeatInterval snapshot))
            ms-delta       (long (* phase-delta phase-interval))]
        (when (pos? (Math/abs ms-delta))
          ;; We should shift the Pioneer timeline. But if this would cause us to skip or repeat a beat, and we
          ;; are shifting 1/5 of a beat or less, hold off until a safer moment.
          (let [beat-phase (.getBeatPhase (.getPlaybackPosition  virtual-cdj))
                beat-delta (if align-to-bar (* phase-delta 4.0) phase-delta)
                beat-delta (if (pos? beat-delta) (+ beat-delta 0.1) beat-delta)]  ; Account for sending lag.
            (when (or (zero? (Math/floor (+ beat-phase beat-delta)))  ; Staying in same beat, we are fine.
                      (> (Math/abs beat-delta) 0.2))  ; We are moving more than 1/5 of a beat, so do it anyway.
              (timbre/info "Adjusting Pioneer timeline, delta-ms:" ms-delta)
              (.adjustPlaybackPosition virtual-cdj ms-delta)))))
      (timbre/warn "Ignoring phase-at-time response for time" (:when info) "since was expecting" ableton-now))))

(def ^{:private true
       :doc "Functions to be called if we detect the a problematic
       version of Carabiner is running, so the user can be warned in
       some client-specific manner."}
  version-listeners (atom #{}))

(defn add-bad-version-listener
  "Registers a function to be called if we detect a problematic version
  of Carabiner is running; in that circumstance `listener` will be
  called with a single string argument, containing a description of
  the problem to be presented to the user in some client-specific
  manner.

  The registration can be reversed by
  calling [[remove-bad-version-listener]]."
  [listener]
  (swap! version-listeners conj listener))

(defn remove-bad-version-listener
  "Removes a function from the set that is called when we detect a bad
  Carabiner version. If `listener` had been previously passed
  to [[add-bad-version-listener]] it will no longer be called."
  [listener]
  (swap! version-listeners disj listener))

(defn- handle-version
  "Processes the response to a recognized version command. Warns if
  Carabiner should be upgraded."
  [version]
  (timbre/info "Connected to Carabiner daemon, version:" version)
  (when (= version "1.1.0")
    (timbre/warn "Carabiner needs to be upgraded to at least version 1.1.1 to avoid sync glitches.")
    (doseq [listener (@version-listeners)]
      (try
        (listener "You are running an old version of Carabiner, which cannot
properly handle long timestamps. You should upgrade to at least
version 1.1.1, or you might experience synchronization glitches.")
        (catch Throwable t
          (timbre/error t "Problem running bad-version-listener."))))))

(defn- handle-unsupported
  "Processes an unsupported command reponse from Carabiner. If it is to
  our version query, warn the user that they should upgrade Carabiner."
  [command]
  (if (= command 'version)
    (do
      (timbre/warn "Carabiner needs to be upgraded to at least version 1.1.1 to avoid multiple problems.")
      (doseq [listener (@version-listeners)]
        (try
          (listener "You are running an old version of Carabiner, which might lose messages.
You should upgrade to at least version 1.1.1, which can cope with
multiple commands being grouped in the same network packet (this
happens when they are sent near the same time), and can properly parse
long timestamp values. Otherwise you might experience synchronization
glitches.")
          (catch Throwable t
            (timbre/error t "Problem running bad-version-listener.")))))
    (timbre/error "Carabiner complained about not recognizing our command:" command)))

(def ^Runner carabiner-runner
  "The [`Runner`](https://deepsymmetry.org/lib-carabiner/apidocs/org/deepsymmetry/libcarabiner/Runner.html)
  singleton that can manage an embedded Carabiner instance for us."
  (Runner/getInstance))

(def ^{:private true
       :doc "Functions to be called when we close our Carabiner
  connection, so clients can take whatever action they need. The
  function is passed an argument that will be `true` if the
  disconnection was unexpected."}

  disconnection-listeners (atom #{}))

(defn add-disconnection-listener
  "Registers a function to be called when we close our Carabiner
  connection, so clients can take whatever action they need. Whenever
  the connection closes, `listener` is called with an argument that
  will be `true` if the disconnection was unexpected.

  The registration can be reversed by
  calling [[remove-disconnection-listener]]."
  [listener]
  (swap! disconnection-listeners conj listener))

(defn remove-disconnection-listener
  "Removes a function from the set that is called when close our
  Carabiner connection. If `listener` had been passed
  to [[add-disconnection-listener]] it will no longer called."
  [listener]
  (swap! disconnection-listeners disj listener))

(defn- shutdown-embedded-carabiner
  "If the supplied client settings indicate we started the Carabiner
  server we are disconnecting from, shut it down, but do so on another
  thread, and in several milliseconds, so our read loop has time to
  close from its end gracefully first."
  [settings]
  (when (:embedded settings)
    (future
      (Thread/sleep 100)
      (.stop carabiner-runner))))

(defn- clear-client
  "Removes all runtime state values from the client state atom, leaving
  only those elements the user configures and cares about or that we
  need to restart it. Used when we are shutting down, either at the
  user request, or because we lost a connection to the Carabiner
  daemon. Called within `swap!` with the current state of the atom."
  [state]
  (select-keys state [:port :latency :sync-mode :bar :embedded :last]))

(defn- response-handler
  "A loop that reads messages from Carabiner as long as it is supposed
  to be running, and takes appropriate action."
  [^Socket socket running]
  (let [unexpected? (atom false)] ; Tracks whether Carabiner unexpectedly closed the connection from its end.
    (try
      (let [buffer      (byte-array 1024)
            input       (.getInputStream socket)]
        (while (and (= running (:running @client)) (not (.isClosed socket)))
          (try
            (let [n (.read input buffer)]
              (if (and (pos? n) (= running (:running @client))) ; We got data, and were not shut down while reading
                (let [message (String. buffer 0 n "UTF-8")
                      reader  (java.io.PushbackReader. (io/reader (.getBytes message "UTF-8")))]
                  (timbre/debug "Received:" message)
                  (loop [cmd (edn/read reader)]
                    (case cmd
                      status        (handle-status (clojure.edn/read reader))
                      beat-at-time  (handle-beat-at-time (clojure.edn/read reader))
                      phase-at-time (handle-phase-at-time (clojure.edn/read reader))
                      version       (handle-version (clojure.edn/read reader))
                      unsupported   (handle-unsupported (clojure.edn/read reader))
                      (timbre/error "Unrecognized message from Carabiner:" message))
                    (let [next-cmd (clojure.edn/read {:eof ::eof} reader)]
                      (when (not= ::eof next-cmd)
                        (recur next-cmd)))))
                (do ; We read zero, meaning the other side closed, or we have been instructed to terminate.
                  (.close socket)
                  (reset! unexpected? (= running (:running @client))))))
            (catch java.net.SocketTimeoutException _
              #_(timbre/debug "Read from Carabiner timed out, checking if we should exit loop."))
            (catch Throwable t
              (timbre/error t "Problem reading from Carabiner.")))))
      (timbre/info "Ending read loop from Carabiner.")
      (swap! client (fn [oldval]
                      (if (= running (:running oldval))
                        (do       ; We are causing the ending.
                          (shutdown-embedded-carabiner oldval)
                          (reset! follow-state nil) ; Simply discard any running tempo adjustment.
                          (clear-client oldval)) ; Clear out connection state.
                        oldval))) ; Someone else caused the ending, so leave client alone; may be new connection.
      (.close socket) ; Either way, close the socket we had been using to communicate, and update the window state.
      (doseq [listener @disconnection-listeners]
        (try
          (listener @unexpected?)
          (catch Throwable t
            (timbre/error t "Problem running disconnection-listener"))))
      (catch Throwable t
        (timbre/error t "Problem managing Carabiner read loop.")))))

(defn disconnect
  "Closes any active Carabiner connection. The run loop will notice that
  its run ID is no longer current, and gracefully terminate, closing
  its socket without processing any more responses. Also shuts down
  the embedded Carabiner process if we started it, and cancels any
  running tempo-based Ableton Link timeline realignment."
  []
  (cancel-adjustment)
  (swap! client (fn [oldval]
                  (shutdown-embedded-carabiner oldval)
                  (clear-client oldval))))

(defn- connect-internal
  "Helper function that attempts to connect to the Carabiner daemon with
  a particular set of client settings, returning them modified to
  reflect the connection if it succeeded. If the settings indicate we
  have just started an embedded Carabiner instance, keep trying to
  connect every ten milliseconds for up to two seconds, to give it a
  chance to come up."
  [settings]
  (let [running (inc (:last settings))
        socket  (atom nil)
        caught  (atom nil)]
    (loop [tries 200]
      (try
        (reset! socket (Socket.))
        (reset! caught nil)
        (.connect ^Socket @socket (InetSocketAddress. "127.0.0.1" (int (:port settings))) connect-timeout)
        (catch java.net.ConnectException e
          (reset! caught e)))
      (when @caught
        (if (and (:embedded settings) (pos? tries))
          (do
            (Thread/sleep 10)
            (recur (dec tries)))
          (throw @caught))))
    ;; We have connected successfully!
    (.setSoTimeout ^Socket @socket read-timeout)
    (future (response-handler @socket running))
    (merge settings {:running running
                     :last    running
                     :socket  @socket})))

(defn connect
  "Try to establish a connection to Carabiner. First checks if there is
  already an independently managed instance of Carabiner running on
  the configured port (see [[set-carabiner-port]]), and if so, simply
  uses that. Otherwise, checks whether we are on a platform where we
  can install and run our own temporary copy of Carabiner. If so,
  tries to do that and connect to it.

  Returns truthy if the initial open succeeded. Sets up a background
  thread to reject the connection if we have not received an initial
  status report from the Carabiner daemon within a second of opening
  it.

  If `failure-fn` is supplied, it will be called with an explanatory
  message (string) if the connection could not be established, so the
  user can be informed in an appropriate way."
  ([]
   (connect nil))
  ([failure-fn]
   (swap! client (fn [oldval]
                   (if (:running oldval)
                     oldval
                     (try
                       (try
                         (connect-internal oldval)
                         (catch java.net.ConnectException e
                           ;; If we couldn't connect, see if we can run Carabiner ourselves and try again.
                           (if (.canRunCarabiner carabiner-runner)
                             (do
                               (.setPort carabiner-runner (:port oldval))
                               (.start carabiner-runner)
                               (connect-internal (assoc oldval :embedded true)))
                             (throw (IllegalStateException.
                                     (str "Carabiner is not running, and we lack a compatible executable, "
                                          (.getExecutableName carabiner-runner)) e)))))
                       (catch Exception e
                         (timbre/warn e "Unable to connect to Carabiner")
                         (when failure-fn
                           (try
                             (failure-fn
                              "Unable to connect to Carabiner; make sure it is running on the specified port.")
                             (catch Throwable t
                               (timbre/error t "Problem running failure-fn"))))
                         (.stop carabiner-runner)
                         oldval)))))
   (when (active?)
     (future
       (Thread/sleep 1000)
       (if (:link-bpm @client)
         (do ; We are connected! Check version and configure for start/stop sync.
           (send-message "version") ; Probe that a recent enough version is running.
           (send-message "enable-start-stop-sync")) ; Set up support for start/stop triggers.
         (do ; We failed to get a response, maybe we are talking to the wrong process.
           (timbre/warn "Did not receive inital status packet from Carabiner daemon; disconnecting.")
           (when failure-fn
             (try
               (failure-fn
                "Did not receive expected response from Carabiner; is something else running on the specified port?")
               (catch Throwable t
                 (timbre/error t "Problem running failure-fn"))))
           (disconnect)))))
   (active?)))

(defn valid-tempo?
  "Checks whether a tempo request is a reasonable number of beats per
  minute. Link supports the range 20 to 999 BPM. If you want something
  outside that range, pick the closest multiple or fraction; for
  example for 15 BPM, propose 30 BPM."
  [bpm]
  (<= 20.0 bpm 999.0))

(defn- validate-tempo
  "Makes sure a tempo request is a reasonable number of beats per
  minute. Coerces it to a double value if it is in the legal Link
  range, otherwise throws an exception."
  [bpm]
  (if (valid-tempo? bpm)
    (double bpm)
    (throw (IllegalArgumentException. "Tempo must be between 20.0 and 999.0 BPM"))))

(defn lock-tempo
  "Starts holding the tempo of the Link session to the specified number
  of beats per minute. Throws `IllegalStateException` if the current
  sync mode is `:off`."
  [bpm]
  (when (= :off (:sync-mode @client))
    (throw (IllegalStateException. "Must be synchronizing to lock tempo.")))
  (swap! client assoc :target-bpm (validate-tempo bpm))
  (send-status-updates)
  (check-link-tempo))

(defn unlock-tempo
  "Allow the tempo of the Link session to be controlled by other
  participants."
  []
  (swap! client dissoc :target-bpm)
  (send-status-updates))

(defn beat-at-time
  "Find out what beat falls at the specified time in the Link timeline,
  assuming 4 beats per bar since we are dealing with Pro DJ Link, and
  taking into account the configured latency (see [[set-latency]]).

  When the response comes, if we are configured to be the tempo
  master, nudge the Link timeline so that it had a beat at the same
  time. If a `beat-number` (ranging from 1 to 4) is supplied, move the
  timeline by more than a beat if necessary in order to get the Link
  session's bars aligned as well."
  ([time]
   (beat-at-time time nil))
  ([time beat-number]
   (let [adjusted-time (- time (.toMicros TimeUnit/MILLISECONDS (:latency @client)))]
     (swap! client assoc :beat [adjusted-time beat-number])
     (send-message (str "beat-at-time " adjusted-time " 4.0")))))

(defn start-transport
  "Tells Carabiner to start the Link session playing, for any
  participants using Start/Stop Sync. If `time` is supplied, it
  specifies when, on the Link microsecond timeline, playback should
  begin; the default is right now."
  ([]
   (start-transport (.toMicros TimeUnit/NANOSECONDS (System/nanoTime))))
  ([time]
   (send-message (str "start-playing " time))))

(defn stop-transport
  "Tells Carabiner to stop the Link session playing, for any
  participants using Start/Stop Sync. If `time` is supplied, it
  specifies when, on the Link microsecond timeline, playback should
  end; the default is right now."
  ([]
   (stop-transport (.toMicros TimeUnit/NANOSECONDS (System/nanoTime))))
  ([time]
   (send-message (str "stop-playing " time))))

(defn- align-pioneer-phase-to-ableton
  "Send a probe that will allow us to align the Virtual CDJ timeline to
  Ableton Link's."
  []
  (let [ableton-now (+ (.toMicros TimeUnit/NANOSECONDS (System/nanoTime))
                       (.toMicros TimeUnit/MILLISECONDS (:latency @client)))
        snapshot    (.getPlaybackPosition virtual-cdj)]
    (swap! client assoc :phase-probe [ableton-now snapshot])
    (send-message (str "phase-at-time " ableton-now " 4.0"))))

(defonce ^{:private true
           :doc "Responds to tempo changes and beat packets from the
  master player when we are controlling the Ableton Link tempo (in
  Passive or Full mode)."}
  ^MasterListener master-listener
  (reify MasterListener

    (masterChanged [_this _update])  ; Nothing we need to do here, we don't care which device is the master.

    (tempoChanged [_this tempo]
      (if (valid-tempo? tempo)
        (lock-tempo tempo)
        (unlock-tempo)))

    (newBeat [_this beat]
      (try
        (when (and (.isRunning virtual-cdj) (.isTempoMaster beat))
          (beat-at-time (.toMicros TimeUnit/NANOSECONDS (.getTimestamp beat))
                        (when (:bar @client) (.getBeatWithinBar beat))))
        (catch Exception e
          (timbre/error e "Problem responding to beat packet in beat-carabiner."))))))

(defn- tie-ableton-to-pioneer
  "Start forcing the Ableton Link to follow the tempo and beats (and
  maybe bars) of the Pioneer master player."
  []
  (.addMasterListener virtual-cdj master-listener)
  (.tempoChanged master-listener (.getMasterTempo virtual-cdj)))

(defn- free-ableton-from-pioneer
  "Stop forcing Ableton Link to follow the Pioneer master player."
  []
  (.removeMasterListener virtual-cdj master-listener)
  (cancel-adjustment)
  (unlock-tempo))

(defn- tie-pioneer-to-ableton
  "Start forcing the Pioneer tempo and beat grid to follow Ableton
  Link."
  []
  (free-ableton-from-pioneer)  ; When we are master, we don't follow anyone else.
  (align-pioneer-phase-to-ableton)
  (.setTempo virtual-cdj (:link-bpm @client))
  (.setPlaying virtual-cdj true)
  (.becomeTempoMaster virtual-cdj)
  (future  ; Realign the BPM in a millisecond or so, in case it gets changed by the outgoing master during handoff.
    (Thread/sleep 1)
    (send-message "status")))

(defn- free-pioneer-from-ableton
  "Stop forcing the Pioneer tempo and beat grid to follow Ableton Link."
  []
  (.setPlaying virtual-cdj false)
  ;; If we are also supposed to be synced the other direction, it is time to turn that back on.
  (when (and (#{:passive :full} (:sync-mode @client))
             (.isSynced virtual-cdj))
    (tie-ableton-to-pioneer)))

(defn sync-link
  "Controls whether the Link session is tied to the tempo of the DJ Link
  devices. Also reflects that in the sync state of
  the [`VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
  so it can be seen on the DJ Link network. Finally, if our Sync mode
  is `:passive` or `:full`, unless we are the tempo master, start
  tying the Ableton Link tempo to the Pioneer DJ Link tempo master.

  Has no effect if we are not in a compatible sync
  mode (see [[set-sync-mode]])."
  [sync?]
  (when (not= (.isSynced virtual-cdj) sync?)
    (.setSynced virtual-cdj sync?))
  (when (and (#{:passive :full} (:sync-mode @client))
             (not (.isTempoMaster virtual-cdj)))
    (if sync?
      (tie-ableton-to-pioneer)
      (free-ableton-from-pioneer))))

(defn link-master
  "Controls whether the Link session is tempo master for the DJ link
  devices. Has no effect if we are not in a compatible sync
  mode (see [[set-sync-mode]])."
  [master?]
  (if master?
    (when (= :full (:sync-mode @client))
      (tie-pioneer-to-ableton))
    (free-pioneer-from-ableton)))

(defn set-sync-mode
  "Validates that the desired mode is compatible with the current state,
  and if so, updates our state to put us in that mode and performs any
  necessary synchronization operations. Choices are:

  * `:off` No synchronization is attempted.

  * `:manual` External code will be calling `lock-tempo`
    and `unlock-tmepo` to manipulate the Ableton Link session.

  * `:passive` Ableton Link always follows the Pro DJ Link
    network, and we do not attempt to control other players on that
    network.

  * `:full` Bidirectional, determined by the Master and Sync states
    of players on the DJ Link network, including Beat
    Link's [`VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
    which stands in for the Ableton Link session."
  [new-mode]
  (cond
    (not (#{:off :manual :passive :full} new-mode))
    (throw (IllegalArgumentException. "new-mode must be one of :off, :maunal, :passive, or :full."))

    (and (not= new-mode :off) (not (active?)))
    (throw (IllegalStateException. "Cannot synchronize without an active Carabiner connection."))

    (and (not= new-mode :off) (not (.isRunning virtual-cdj)))
    (throw (IllegalStateException. "Cannot synchronize when VirtualCdj isn't running."))

    (and (= new-mode :full) (not (.isSendingStatus virtual-cdj)))
    (throw (IllegalStateException. "Cannot use full sync mode when VirtualCdj isn't sending status packets.")))

  (swap! client assoc :sync-mode new-mode)
  (if ({:passive :full} new-mode)
    (do
      (sync-link (.isSynced virtual-cdj))  ; This is now relevant, even if it wasn't before.
      (when (and (= :full new-mode) (.isTempoMaster virtual-cdj))
        (tie-pioneer-to-ableton)))
    (do
      (free-ableton-from-pioneer)
      (free-pioneer-from-ableton))))

(defn set-link-tempo
  "Sets the Link session tempo to the specified number of beats per
  minute, unless it is already close enough (within 0.005 beats per
  minute)."
  [tempo]
  (validate-tempo tempo)
  (ensure-active)
  (when (> (Math/abs ^Double (- tempo (:link-bpm @client))) 0.005)
    (send-message (str "bpm " tempo))))

(defonce ^{:private true
           :doc "A daemon thread that periodically aligns the Pioneer phase to the
  Ableton Link session when the sync mode requires it."}

  full-sync-daemon
  (Thread. (fn []
             (loop []
               (try
                 ;; If we are due to send a probe to align the Virtual CDJ timeline to Link's, do so.
                 (when (and (= :full (:sync-mode @client)) (.isTempoMaster virtual-cdj))
                   (align-pioneer-phase-to-ableton))
                 (Thread/sleep 200)
                 (catch Exception e
                   (timbre/error e "Problem aligning DJ Link phase to Ableton Link.")))
               (recur)))
           "Beat Carabiner Phase Alignment"))

(let [^Thread daemon full-sync-daemon]
  (when-not (.isAlive daemon)
    (.setPriority daemon Thread/MIN_PRIORITY)
    (.setDaemon daemon true)
    (.start daemon)))
