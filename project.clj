(defproject beat-carabiner "8.0.0-SNAPSHOT"
  :description "A minimal tempo bridge between Beat Link and Ableton Link."
  :url "https://github.com/Deep-Symmetry/beat-carabiner"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/Deep-Symmetry/beat-carabiner"}
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [org.deepsymmetry/beat-link "8.0.0-SNAPSHOT"]
                 [org.deepsymmetry/lib-carabiner "1.2.0"]
                 [org.deepsymmetry/electro "0.1.4"]
                 [com.taoensso/timbre "6.5.0"]
                 [com.fzakaria/slf4j-timbre "0.4.1"]]
  :manifest {"Name"                  ~#(str (clojure.string/replace (:group %) "." "/")
                                            "/" (:name %) "/")
             "Package"               ~#(str (:group %) "." (:name %))
             "Specification-Title"   ~#(:name %)
             "Specification-Version" ~#(:version %)}
  :target-path "target/%s"
  :profiles {:dev {:repl-options {:init-ns beat-carabiner.core
                                  :welcome (println "beat-carabiner loaded.")}
                   :jvm-opts     ["-XX:-OmitStackTraceInFastThrow"]}})
