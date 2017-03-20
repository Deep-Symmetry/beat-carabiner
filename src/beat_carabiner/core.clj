(ns beat-carabiner.core
  (:require [clojure.tools.cli :as cli]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncementListener])
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

    :output-fn timbre/default-output-fn ; (fn [data]) -> string
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

    (timbre/info "Looking for Carabiner on port" (:carabiner-port options))

    ;; Start the daemons that do everything!
    (DeviceFinder/start)
    (DeviceFinder/addDeviceAnnouncementListener
     (reify DeviceAnnouncementListener
       (deviceFound [_ announcement]
         (timbre/info "Device Found:" announcement))
       (deviceLost [_ announcement]
         (timbre/info "Device Lost:" announcement))))

    ;; TODO: Whenever we have at least one Pioneer device, start up the virtual CDJ. Shut it down again when we
    ;;       lose the last one.

    ;; TODO: Try to open a connection to the Carabiner daemon; if we fail, sleep for a while, and try again. Same
    ;;       if it ever closes on us.

    ;; TODO: When we have both a Virtual CDJ and a Carabiner daemon, tie the tempo of the Carabiner session to the
    ;;       master player on the Pioneer network. See carabiner.clj in beat-link-trigger. Also respond to beat
    ;;       packets from the master player, aligning at the beat or bar level as we are configured.
    (timbre/info "Startup complete.")))
