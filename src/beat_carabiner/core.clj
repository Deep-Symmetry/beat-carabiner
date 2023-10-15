(ns beat-carabiner.core
  "The main entry point for the beat-carabiner library. Simple scenarios
  can just call [[connect]] followed by [[set-sync-mode]], but you
  will likely want to explore the rest of the API."
  (:require [taoensso.timbre :as timbre]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.net Socket InetSocketAddress]
           [java.util.concurrent TimeUnit]
           [org.deepsymmetry.beatlink DeviceFinder BeatFinder VirtualCdj MasterListener]
           [org.deepsymmetry.libcarabiner Runner]
           [org.deepsymmetry.electro Metronome Snapshot]))

(def device-finder
  "Holds the singleton instance of the Beat
  Link [`DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
  for convenience."
  (DeviceFinder/getInstance))

(def virtual-cdj
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
  "The amount by which the start of a beat can be off without
  triggering an adjustment. This can't be larger than the normal beat
  packet jitter without causing spurious readjustments."
  0.0166)

(def connect-timeout
  "How long the connection attempt to the Carabiner daemon can take
  before we give up on being able to reach it."
  5000)

(def read-timeout
  "How long reads from the Carabiner daemon should block so we can
  periodically check if we have been instructed to close the
  connection."
  2000)

(defn state
  "Returns the current state of the Carabiner connection as a map whose
  keys include:

  `:port`, the port on which the Carabiner daemon is listening.

  `:latency`, the estimated latency in milliseconds between an
  actual beat played by a CDJ and when we receive the packet.

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
  `:target-bpm` key holding that tempo."
  []
  (select-keys @client [:port :latency :sync-mode :bar :running :link-bpm :link-peers :target-bpm]))

(defn active?
  "Checks whether there is currently an active connection to a
  Carabiner daemon."
  []
  (:running @client))

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
  played on a CDJ and when we receive the packet."
  [latency]
    (when-not (<= 0 latency 1000)
    (throw (IllegalArgumentException. "latency must be in range 0-1000.")))
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
      (when (> (Math/abs ^Double (- link-bpm target-bpm)) ^Double bpm-tolerance)
        (send-message (str "bpm " target-bpm)))
      (when (and (.isTempoMaster ^VirtualCdj virtual-cdj) (pos? link-bpm))
        (.setTempo ^VirtualCdj virtual-cdj link-bpm)))))

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

(defn- handle-beat-at-time
  "Processes a beat probe response from Carabiner."
  [info]
  (let [^Double beat       (:beat info)
        raw-beat           (Math/round beat)
        ^Double beat-skew  (mod beat 1.0)
        [time beat-number] (:beat @client)
        candidate-beat     (if (and beat-number (= time (:when info)))
                             (let [bar-skew   (- (dec beat-number) (mod raw-beat 4))
                                   adjustment (if (<= bar-skew -2) (+ bar-skew 4) bar-skew)]
                               (+ raw-beat adjustment))
                             raw-beat)
        target-beat        (if (neg? candidate-beat) (+ candidate-beat 4) candidate-beat)]
    (when (or (> (Math/abs beat-skew) skew-tolerance)
              (not= target-beat raw-beat))
      (timbre/info "Realigning to beat" target-beat "by" beat-skew)
      (send-message (str "force-beat-at-time " target-beat " " (:when info) " 4.0")))))

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
          (let [beat-phase (.getBeatPhase (.getPlaybackPosition ^VirtualCdj virtual-cdj))
                beat-delta (if align-to-bar (* phase-delta 4.0) phase-delta)
                beat-delta (if (pos? beat-delta) (+ beat-delta 0.1) beat-delta)]  ; Account for sending lag.
            (when (or (zero? (Math/floor (+ beat-phase beat-delta)))  ; Staying in same beat, we are fine.
                      (> (Math/abs beat-delta) 0.2))  ; We are moving more than 1/5 of a beat, so do it anyway.
              (timbre/info "Adjusting Pioneer timeline, delta-ms:" ms-delta)
              (.adjustPlaybackPosition ^VirtualCdj virtual-cdj ms-delta)))))
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

(def carabiner-runner
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
      (.stop ^Runner carabiner-runner))))

(defn- response-handler
  "A loop that reads messages from Carabiner as long as it is supposed
  to be running, and takes appropriate action."
  [^Socket socket running]
  (let [unexpected? (atom false)]  ; Tracks whether Carabiner unexpectedly closed the connection from its end.
    (try
      (let [buffer      (byte-array 1024)
            input       (.getInputStream socket)]
        (while (and (= running (:running @client)) (not (.isClosed socket)))
          (try
            (let [n (.read input buffer)]
              (if (and (pos? n) (= running (:running @client)))  ; We got data, and were not shut down while reading
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
                (do  ; We read zero, meaning the other side closed, or we have been instructed to terminate.
                  (.close socket)
                  (reset! unexpected? (= running (:running @client))))))
            (catch java.net.SocketTimeoutException _
              (timbre/debug "Read from Carabiner timed out, checking if we should exit loop."))
            (catch Throwable t
              (timbre/error t "Problem reading from Carabiner.")))))
      (timbre/info "Ending read loop from Carabiner.")
      (swap! client (fn [oldval]
                      (if (= running (:running oldval))
                        (do  ; We are causing the ending.
                          (shutdown-embedded-carabiner oldval)
                          (dissoc oldval :running :embedded :socket :link-bpm :link-peers))
                        oldval)))  ; Someone else caused the ending, so leave client alone; may be new connection.
      (.close socket)  ; Either way, close the socket we had been using to communicate, and update the window state.
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
  the embedded Carabiner process if we started it."
  []
  (swap! client (fn [oldval]
                  (shutdown-embedded-carabiner oldval)
                  (dissoc oldval :running :embedded :socket :link-bpm :link-peers))))

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
                           (if (.canRunCarabiner ^Runner carabiner-runner)
                             (do
                               (.setPort ^Runner carabiner-runner (:port oldval))
                               (.start ^Runner carabiner-runner)
                               (connect-internal (assoc oldval :embedded true)))
                             (throw e))))
                       (catch Exception e
                         (timbre/warn e "Unable to connect to Carabiner")
                         (when failure-fn
                           (try
                             (failure-fn
                              "Unable to connect to Carabiner; make sure it is running on the specified port.")
                             (catch Throwable t
                               (timbre/error t "Problem running failure-fn"))))
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
    (throw (IllegalArgumentException. "Tempo must be between 20 and 999 BPM"))))

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
        snapshot    (.getPlaybackPosition ^VirtualCdj virtual-cdj)]
    (swap! client assoc :phase-probe [ableton-now snapshot])
    (send-message (str "phase-at-time " ableton-now " 4.0"))))

(defonce ^{:private true
           :doc "Responds to tempo changes and beat packets from the
  master player when we are controlling the Ableton Link tempo (in
  Passive or Full mode)."}
  master-listener
  (reify MasterListener

    (masterChanged [_this _update])  ; Nothing we need to do here, we don't care which device is the master.

    (tempoChanged [_this tempo]
      (if (valid-tempo? tempo)
        (lock-tempo tempo)
        (unlock-tempo)))

    (newBeat [_this beat]
      (try
        (when (and (.isRunning ^VirtualCdj virtual-cdj) (.isTempoMaster beat))
          (beat-at-time (.toMicros TimeUnit/NANOSECONDS (.getTimestamp beat))
                        (when (:bar @client) (.getBeatWithinBar beat))))
        (catch Exception e
          (timbre/error e "Problem responding to beat packet in beat-carabiner."))))))

(defn- tie-ableton-to-pioneer
  "Start forcing the Ableton Link to follow the tempo and beats (and
  maybe bars) of the Pioneer master player."
  []
  (.addMasterListener ^VirtualCdj virtual-cdj master-listener)
  (.tempoChanged ^MasterListener master-listener (.getMasterTempo ^VirtualCdj virtual-cdj)))

(defn- free-ableton-from-pioneer
  "Stop forcing Ableton Link to follow the Pioneer master player."
  []
  (.removeMasterListener ^VirtualCdj virtual-cdj master-listener)
  (unlock-tempo))

(defn- tie-pioneer-to-ableton
  "Start forcing the Pioneer tempo and beat grid to follow Ableton
  Link."
  []
  (free-ableton-from-pioneer)  ; When we are master, we don't follow anyone else.
  (align-pioneer-phase-to-ableton)
  (.setTempo ^VirtualCdj virtual-cdj (:link-bpm @client))
  (.becomeTempoMaster ^VirtualCdj virtual-cdj)
  (.setPlaying ^VirtualCdj virtual-cdj true)
  (future  ; Realign the BPM in a millisecond or so, in case it gets changed by the outgoing master during handoff.
    (Thread/sleep 1)
    (send-message "status")))

(defn- free-pioneer-from-ableton
  "Stop forcing the Pioneer tempo and beat grid to follow Ableton Link."
  []
  (.setPlaying ^VirtualCdj virtual-cdj false)
  ;; If we are also supposed to be synced the other direction, it is time to turn that back on.
  (when (and (#{:passive :full} (:sync-mode @client))
             (.isSynced ^VirtualCdj virtual-cdj))
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
  (when (not= (.isSynced ^VirtualCdj virtual-cdj) sync?)
    (.setSynced ^VirtualCdj virtual-cdj sync?))
  (when (and (#{:passive :full} (:sync-mode @client))
             (not (.isTempoMaster ^VirtualCdj virtual-cdj)))
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

    (and (not= new-mode :off) (not (.isRunning ^VirtualCdj virtual-cdj)))
    (throw (IllegalStateException. "Cannot synchronize when VirtualCdj isn't running."))

    (and (= new-mode :full) (not (.isSendingStatus ^VirtualCdj virtual-cdj)))
    (throw (IllegalStateException. "Cannot use full sync mode when VirtualCdj isn't sending status packets.")))

  (swap! client assoc :sync-mode new-mode)
  (if ({:passive :full} new-mode)
    (do
      (sync-link (.isSynced ^VirtualCdj virtual-cdj))  ; This is now relevant, even if it wasn't before.
      (when (and (= :full new-mode) (.isTempoMaster ^VirtualCdj virtual-cdj))
        (tie-pioneer-to-ableton)))
    (do
      (free-ableton-from-pioneer)
      (free-pioneer-from-ableton))))

(defn set-link-tempo
  "Sets the Link session tempo to the specified number of beats per
  minute, unless it is already close enough (within 0.005 beats per
  minute)."
  [tempo]
  (when-not (<= 20.0 tempo 999.0)
    (throw (IllegalArgumentException. "tempo must be in range 20.0-999.0.")))
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
                 (when (and (= :full (:sync-mode @client)) (.isTempoMaster ^VirtualCdj virtual-cdj))
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
