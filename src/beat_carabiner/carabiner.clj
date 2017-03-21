(ns beat-carabiner.carabiner
  "Maintains the connection with the local Carabiner daemon to
  participate in an Ableton Link session. Based on the version in
  beat-link-trigger, but simpler because it has no need to be
  asynchronous, nor to support graceful shutdown, because as a
  single-purpose daemon ourselves, we will run until terminated."
  (:require [taoensso.timbre :as timbre])
  (:import [java.net Socket InetSocketAddress]))

(defonce ^{:private true
           :doc "When connected, holds the socket that can be used to
  send messages to Carabiner, the configured latency value, and values
  which track the peer count and tempo reported by the Ableton Link
  session and the target tempo we are trying to maintain (when
  applicable)."}
  client (atom {}))

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

(defn- send-message
  "Sends a message to the active Carabiner daemon, if we are
  connected."
  ([message]
   (when-let [socket (:socket @client)]
     (send-message socket message)))
  ([socket message]
   (.write (.getOutputStream socket) (.getBytes (str message) "UTF-8"))))

(defn- check-tempo
  "If we are supposed to lock the Link tempo, make sure the Link
  tempo is close enough to our target value, and adjust it if needed."
  []
  (let [state @client]
    (when (and (some? (:target-bpm state))
               (> (Math/abs (- (:link-bpm state 0.0) (:target-bpm state))) bpm-tolerance))
      (send-message (str "bpm " (:target-bpm state))))))

(defn- handle-status
  "Processes a status update from Carabiner."
  [status]
  (let [bpm (double (:bpm status))
        peers (int (:peers status))]
    (swap! client assoc :link-bpm bpm :link-peers peers))
  (check-tempo))

(defn- handle-beat-at-time
  "Processes a beat probe response from Carabiner."
  [socket info]
  (let [raw-beat (Math/round (:beat info))
        beat-skew (mod (:beat info) 1.0)
        [time beat-number] (:beat @client)
        candidate-beat (if (and beat-number (= time (:when info)))
                         (let [bar-skew (- (dec beat-number) (mod raw-beat 4))
                               adjustment (if (<= bar-skew -2) (+ bar-skew 4) bar-skew)]
                           (+ raw-beat adjustment))
                         raw-beat)
        target-beat (if (neg? candidate-beat) (+ candidate-beat 4) candidate-beat)]
    (when (or (> (Math/abs beat-skew) skew-tolerance)
              (not= target-beat raw-beat))
      (timbre/info "Realigning Ableton Link to beat" target-beat "by" beat-skew)
      (send-message socket (str "force-beat-at-time " target-beat " " (:when info) " 4.0")))))

(defn- response-handler
  "A loop that reads messages from Carabiner as long as it has a
  connection, and takes appropriate action."
  [socket]
  (timbre/info "Connected to Carabiner.")
  (try
    (let [buffer (byte-array 1024)
          input (.getInputStream socket)]
      (while (not (.isClosed socket))
        (try
          (let [n (.read input buffer)]
            (if (pos? n)  ; We got data
              (let [message (String. buffer 0 n "UTF-8")
                    reader (java.io.PushbackReader. (clojure.java.io/reader (.getBytes message "UTF-8")))
                    cmd (clojure.edn/read reader)]
                (timbre/debug "Received:" message)
                (case cmd
                  status (handle-status (clojure.edn/read reader))
                  beat-at-time (handle-beat-at-time socket (clojure.edn/read reader))
                  (timbre/error "Unrecognized message from Carabiner:" message)))
              (do  ; We read zero, means the other side closed; force our loop to terminate.
                (timbre/warn "Carabiner unexpectedly closed our connection; is it still running?")
                (.close socket))))
          (catch java.net.SocketTimeoutException e
            (timbre/info "Read from Carabiner timed out, did not expect this to happen."))
          (catch Exception e
            (timbre/error e "Problem reading from Carabiner.")))))
    (catch Exception e
      (timbre/error e "Problem managing Carabiner read loop."))
    (finally
      (.close socket)  ; In case we got here through an exception
      (swap! client dissoc :socket :link-bpm :link-peers)
      (timbre/info "Ending read loop from Carabiner."))))

(defn connect
  "Try to establish a connection to Carabiner, and run a loop to
  process any responses from it. Will return only upon failure, or if
  the connection has closed. Sets up a background thread to reject the
  connection if we have not received an initial status report from the
  Carabiner daemon within a second of opening it."
  [port latency]
  (try
    (let [socket (Socket.)]
      (try
        (.connect socket (InetSocketAddress. "127.0.0.1" port) connect-timeout)
        (swap! client assoc :socket socket :latency latency)
        (future
          (Thread/sleep 1000)
          (when-not (:link-bpm @client)
            (timbre/warn "Did not receive inital status packet from Carabiner daemon; disconnecting.")
            (.close socket)))
        (catch Exception e
          (timbre/warn e "Unable to connect to Carabiner.")))
      (when (.isConnected socket) (response-handler socket)))
    (catch Exception e
      (timbre/warn e "Problem running Carabiner response handler loop."))))

(defn valid-tempo?
  "Checks whether a tempo request is a reasonable number of beats per
  minute. Link supports the range 20 to 999 BPM. If you want something
  outside that range, pick the closest multiple or fraction; for
  example for 15 BPM, propose 30 BPM."
  [bpm]
  (< 20.0 bpm 999.0))

(defn- validate-tempo
  "Makes sure a tempo request is a reasonable number of beats per
  minute. Coerces it to a double value if it is in the legal Link
  range, otherwise throws an exception."
  [bpm]
  (if (valid-tempo? bpm)
    (double bpm)
    (throw (IllegalArgumentException. "Tempo must be between 20 and 999 BPM"))))

(defn lock-tempo
  "Starts holding the tempo of the Link session to the specified
  number of beats per minute."
  [bpm]
  (timbre/info "Locking Ableton Link tempo to" bpm)
  (swap! client assoc :target-bpm (validate-tempo bpm))
  (check-tempo))

(defn unlock-tempo
  "Allow the tempo of the Link session to be controlled by other
  participants."
  []
  (timbre/info "Unlocking Ableton Link tempo.")
  (swap! client dissoc :target-bpm))

(defn beat-at-time
  "Find out what beat falls at the specified time in the Link
  timeline, assuming 4 beats per bar since we are dealing with Pro DJ
  Link, and taking into account the configured latency. When the
  response comes, nudge the Link timeline so that it had a beat at the
  same time. If a beat-number (ranging from 1 to the quantum) is
  supplied, move the timeline by more than a beat if necessary in
  order to get the Link session's bars aligned as well."
  ([time]
   (beat-at-time time nil))
  ([time beat-number]
   (let [adjusted-time (- time (* (:latency @client) 1000))]
     (swap! client assoc :beat [adjusted-time beat-number])
     (send-message (str "beat-at-time " adjusted-time " 4.0")))))
