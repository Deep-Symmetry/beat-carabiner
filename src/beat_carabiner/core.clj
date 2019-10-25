(ns beat-carabiner.core
  "The main entry point for the beat-carabiner daemon. Handles any
  command-line arguments, then establishes and interacts with
  connections to any Pioneer Pro DJ Link and Ableton Link sessions
  that can be found."
  (:require [clojure.tools.cli :as cli]
            [beat-carabiner.carabiner :as carabiner]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncementListener BeatFinder
                                      VirtualCdj MasterListener DeviceUpdateListener])
  (:gen-class))

(defn- create-appenders
  "Create a set of appenders which rotate the log file at the
  specified path."
  [path]
  {:rotor (rotor/rotor-appender {:path path
                                 :max-size 100000
                                 :backlog 5})})

(defonce ^{:private true
           :doc "If the user has requested logging to a log directory,
  this will be set to an appropriate set of appenders. Defaults to`,
  logging to stdout."}
  appenders (atom {:println (timbre/println-appender {:stream :auto})}))

(def device-finder
  "Holds the singleton instance of the Device Finder for convenience."
  (DeviceFinder/getInstance))

(def virtual-cdj
  "Holds the singleton instance of the Virtual CDJ for convenience."
  (VirtualCdj/getInstance))

(def beat-finder
  "Holds the singleton instance of the Beat Finder for convenience."
  (BeatFinder/getInstance))

(defn output-fn
  "Log format (fn [data]) -> string output fn.
  You can modify default options with `(partial output-fn
  <opts-map>)`. This is based on timbre's default, but removes the
  hostname and stack trace fonts."
  ([data] (output-fn nil data))
  ([{:keys [no-stacktrace?] :as opts} data]
   (let [{:keys [level ?err_ vargs_ msg_ ?ns-str hostname_
                 timestamp_ ?line]} data]
     (str
             @timestamp_       " "
       (clojure.string/upper-case (name level))  " "
       "[" (or ?ns-str "?") ":" (or ?line "?") "] - "
       (force msg_)
       (when-not no-stacktrace?
         (when-let [err (force ?err_)]
           (str "\n" (timbre/stacktrace err (assoc opts :stacktrace-fonts {})))))))))

(defn- init-logging-internal
  "Performs the actual initialization of the logging environment,
  protected by the delay below to insure it happens only once."
  []
  (timbre/set-config!
   {:level :info  ; #{:trace :debug :info :warn :error :fatal :report}
    :enabled? true

    ;; Control log filtering by namespaces/patterns. Useful for turning off
    ;; logging in noisy libraries, etc.:
    :ns-whitelist  [] #_["my-app.foo-ns"]
    :ns-blacklist  [] #_["taoensso.*"]

    :middleware [] ; (fns [data]) -> ?data, applied left->right

    :timestamp-opts {:pattern "yyyy-MMM-dd HH:mm:ss"
                     :locale :jvm-default
                     :timezone (java.util.TimeZone/getDefault)}

    :output-fn output-fn ; (fn [data]) -> string
    })

  ;; Install the desired log appenders, if they have been configured
  (when-let [custom-appenders @appenders]
    (timbre/merge-config!
     {:appenders custom-appenders})))

(defonce ^{:private true
           :doc "Used to ensure log initialization takes place exactly once."}
  initialized (delay (init-logging-internal)))

(defn init-logging
  "Set up the logging environment for Afterglow. Called by main when invoked
  as a jar, and by the examples namespace when brought up in a REPL for exploration,
  and by extensions such as afterglow-max which host Afterglow in Cycling '74's Max."
  ([] ;; Resolve the delay, causing initialization to happen if it has not yet.
   @initialized)
  ([appenders-map] ;; Override the default appenders, then initialize as above.
   (reset! appenders appenders-map)
   (init-logging)))

(defn- println-err
  "Prints objects to stderr followed by a newline."
  [& more]
  (binding [*out* *err*]
    (apply println more)))

(def ^:private log-file-error
  "Holds the validation failure message if the log file argument was
  not acceptable."
  (atom nil))

(defn- bad-log-arg
  "Records a validation failure message for the log file argument, so
  a more specific diagnosis can be given to the user. Returns false to
  make it easy to invoke from the validation function, to indicate
  that validation failed after recording the reason."
  [& messages]
  (reset! log-file-error (clojure.string/join " " messages))
  false)

(defn- valid-log-file?
  "Check whether a string identifies a file that can be used for logging."
  [path]
  (let [f (clojure.java.io/file path)
        dir (or (.getParentFile f) (.. f (getAbsoluteFile) (getParentFile)))]
    (if (.exists f)
      (cond  ; The file exists, so make sure it is writable and a plain file
        (not (.canWrite f)) (bad-log-arg "Cannot write to log file")
        (.isDirectory f) (bad-log-arg "Requested log file is actually a directory")
        ;; Requested existing file looks fine, make sure we can roll over
        :else (or (.canWrite dir)
                  (bad-log-arg "Cannot create rollover log files in directory" (.getPath dir))))
      ;; The requested file does not exist, make sure we can create it
      (if (.exists dir)
        (and (or (.isDirectory dir)
                 (bad-log-arg "Log directory is not a directory:" (.getPath dir)))
             (or (.canWrite dir) ; The parent directory exists, make sure we can write to it
                 (bad-log-arg "Cannot create log file in directory" (.getPath dir))))
        (or (.mkdirs dir) ; The parent directory doesn't exist, make sure we can create it
          (bad-log-arg "Cannot create log directory" (.getPath dir)))))))

(def cli-options
  "The command-line options supported by Afterglow."
  [["-b" "--beat-align" "Sync Link session to beats only, not bars"]
   ["-c" "--carabiner-port PORT" "Port number of Carabiner daemon"
    :default 17000
    :parse-fn #(Long/parseLong %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-l" "--latency MS" "How many milliseconds are we behind the CDJs"
    :default 20
    :parse-fn #(Long/parseLong %)
    :validate [#(<= 0 % 500) "Must be a number between 0 and 500 inclusive"]]
   ["-L" "--log-file PATH" "Log to a rotated file instead of stdout"
    :validate [valid-log-file? @log-file-error]]
   ["-h" "--help" "Display help information and exit"]])

(defn usage
  "Print message explaining command-line invocation options."
  [options-summary]
  (clojure.string/join
   \newline
   [(str "beat-carabiner, a simple bridge bewteen Pioneer and Ableton Link.")
    (str "Usage: beat-carabiner [options]")
    ""
    "Options:"
    options-summary
    ""
    "Please see https://github.com/brunchboy/beat-carabiner for more information."]))

(defn error-msg
  "Format an error message related to command-line invocation."
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn exit
  "Terminate execution with a message to the command-line user."
  [status msg]
  (if (zero? status)
    (println msg)
    (println-err msg))
  (System/exit status))

(defn -main
  "The entry point when invoked as a jar from the command line. Parse
  options, and start daemon operation."
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (let [errors (concat errors (map #(str "Unknown argument: " %) arguments))]

      ;; Handle help and error conditions.
      (cond
        (:help options) (exit 0 (usage summary))
        (seq errors)    (exit 1 (str (error-msg errors) "\n\n" (usage summary)))))

    ;; Set up the logging environment
    (when-let [log-file (:log-file options)]
      (reset! appenders (create-appenders log-file)))
    (init-logging)

    (timbre/info (if (:beat-align options)
                   "Will align Ableton Link session at the level of individual beats, ignoring bars."
                   "Will align Ableton Link session to bars and beats."))

    ;; Start the daemons that do everything!
    (let [bar-align (not (:beat-align options))]
      ((.addMasterListener virtual-cdj)   ; First set up to respond to master tempo changes and beats.
       (reify MasterListener
         (masterChanged [_ update]
           #_(timbre/info "Master Changed!" update)
           (when (nil? update)           ; If there's no longer a tempo master,
             (carabiner/unlock-tempo)))  ; free the Ableton Link session tempo.
         (tempoChanged [_ tempo]  ; Master tempo has changed, lock the Ableton Link session to it, unless out of range.
           (if (carabiner/valid-tempo? tempo)
             (carabiner/lock-tempo tempo)
             (carabiner/unlock-tempo)))
         (newBeat [_ beat]  ; The master player has reported a beat, so align to it as needed.
           #_(timbre/info "Beat!" beat)
           (carabiner/beat-at-time (long (/ (.getTimestamp beat) 1000)) (when bar-align (.getBeatWithinBar beat)))))))

    (timbre/info "Waiting for Pro DJ Link devices...")
    (.start device-finder)  ; Start watching for any Pro DJ Link devices.
    ((.addDeviceAnnouncementListener device-finder)  ; And set up to respond when they arrive and leave.
     (reify DeviceAnnouncementListener
       (deviceFound [_ announcement]
         (timbre/info "Pro DJ Link Device Found:" announcement)
         (future  ; We have seen a device, so we can start up the Virtual CDJ if it's not running.
           (if (.start virtual-cdj)
             (timbre/info "Virtual CDJ running as Player" (.getDeviceNumber virtual-cdj))
             (timbre/warn "Virtual CDJ failed to start."))))
       (deviceLost [_ announcement]
         (timbre/info "Pro DJ Link Device Lost:" announcement)
         (when (empty? (.currentDevices device-finder))
           (timbre/info "Shutting down Virtual CDJ.")  ; We have lost the last device, so shut down for now.
           (.stop virtual-cdj)
           (carabiner/unlock-tempo)))))

    (.start beat-finder)  ; Also start watching for beats, so the beat-alignment handler will get called.

    ;; Enter an infinite loop attempting to connect to the Carabiner daemon.
    (loop [port    (:carabiner-port options)
           latency (:latency options)]
      (timbre/info "Trying to connect to Carabiner daemon on port" port "with latency" latency)
      (carabiner/connect port latency)
      (timbre/warn "Not connected to Carabiner. Waiting ten seconds to try again.")
      (Thread/sleep 10000)
      (recur port latency))))
