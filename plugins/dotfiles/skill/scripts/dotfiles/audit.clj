(ns dotfiles.audit
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(def skill-root (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))

(defn platform []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond (str/includes? os "linux") :linux
          (str/includes? os "mac") :macos
          :else :unsupported)))

(defn load-spec []
  (edn/read-string (slurp (str (fs/path skill-root "setup.edn")))))

(defn path-at [home path]
  (str (if (fs/absolute? path) (fs/path path) (fs/path home path))))

(defn read-text [path limit]
  (when-not (and (fs/regular-file? path) (fs/readable? path))
    (throw (ex-info "Not a readable regular file" {:path path})))
  (when (> (fs/size path) limit)
    (throw (ex-info "File exceeds audit size limit" {:path path})))
  (slurp path))

(defn normalize [text home ignore-comments]
  (let [portable (-> text (str/replace "\r\n" "\n")
                     (str/replace #"[ \t]+(?=\n|$)" "")
                     (str/replace (str (str/replace (str home) #"/+$" "") "/") "~/"))]
    (if ignore-comments
      (->> (str/split-lines portable)
           (remove #(or (str/blank? %) (str/starts-with? (str/triml %) "#")))
           (str/join "\n"))
      portable)))

(defn digest [text]
  (apply str (map #(format "%02x" (bit-and 0xff %))
                  (.digest (MessageDigest/getInstance "SHA-256")
                           (.getBytes (str text) "UTF-8")))))

(defn finding [requirement status desired observed evidence]
  {:id (:id requirement) :status status :desired desired :observed observed
   :evidence evidence :priority (or (:priority requirement) 2)
   :remediation (:remediation requirement)})

(defn guarded [ctx requirement desired check]
  (cond
    (and (:platforms requirement) (not (some #{(:platform ctx)} (:platforms requirement))))
    (finding requirement :not-applicable desired "Not required by this profile" [])
    (not (some #{(:platform ctx)} (:platforms (:spec ctx))))
    (finding requirement :unknown desired "Unsupported platform" [])
    (not= (:host ctx) (:platform ctx))
    (finding requirement :unknown desired "Requested profile differs from actual host; not inspected" [])
    :else
    (try (check)
         (catch Exception _
           (finding requirement :unknown desired "Could not inspect safely (unreadable, oversized or invalid data)" [])))))

(defn check-file [ctx requirement]
  (let [target (path-at (:home ctx) (:target requirement))
        asset (str (fs/path skill-root (:asset requirement)))
        desired (if (= :presence (:comparison requirement))
                  "A non-empty global AGENTS.md; machine-specific content is allowed"
                  (str "Match bundled " (:asset requirement)))]
    (guarded ctx requirement desired
      #(cond
         (not (fs/exists? target)) (finding requirement :missing desired "File absent" [target])
         (= :presence (:comparison requirement))
         (let [text (read-text target (:file-limit-bytes (:spec ctx)))]
           (finding requirement (if (str/blank? text) :missing :compliant) desired
                    (if (str/blank? text) "File is empty"
                        "Present. Global instructions may differ between machines; content equality is not required.")
                    [target]))
         :else
         (let [expected (normalize (read-text asset (:file-limit-bytes (:spec ctx))) (:home ctx) (:ignore-comments requirement))
               actual (normalize (read-text target (:file-limit-bytes (:spec ctx))) (:home ctx) (:ignore-comments requirement))]
           (finding requirement (if (= expected actual) :compliant :drifted) desired
                    (if (= expected actual) "Matches baseline" "Differs from baseline; review and merge")
                    [target (str "expected sha256:" (digest expected)) (str "observed sha256:" (digest actual))]))))))

;; Parse only this package's deliberately restricted, flat [tools] declarations.
;; Live machine config is compared as data, not parsed/executed by mise.
(defn mise-versions [text]
  (let [section (second (re-find #"(?ms)^\[tools\]\s*\n(.*?)(?=^\[|\z)" text))]
    (when-not section (throw (ex-info "Missing canonical [tools] section" {})))
    (into {}
          (for [line (str/split-lines section)
                :when (not (or (str/blank? line) (str/starts-with? (str/triml line) "#")))]
            (let [[_ quoted plain value] (re-matches #"\s*(?:\"([^\"]+)\"|([\w-]+))\s*=\s*(.*?)\s*" line)
                  version (or (second (re-find #"^\"([^\"]+)\"" (or value "")))
                              (second (re-find #"\bversion\s*=\s*\"([^\"]+)\"" (or value ""))))]
              (when-not (and (or quoted plain) version)
                (throw (ex-info "Unsupported canonical mise declaration" {})))
              [(or quoted plain) version])))))

(defn check-mise-tool [ctx requirement versions]
  (let [version (get versions (:id requirement))
        requirement (assoc requirement :id (str "mise." (:id requirement)) :priority 1
                           :remediation (str "After reviewing the complete mise configuration, install "
                                             (:id requirement) " with its declared " version
                                             " policy using mise install. Verify the executable in a fresh shell."))
        installed (str (fs/path (:mise-data ctx) "installs" (:directory requirement) version))
        desired (str "mise-managed " (:binary requirement) " with policy " version)]
    (guarded ctx requirement desired
      #(let [binary (when-not (:files-only ctx) ((:which ctx) (:binary requirement)))
             shim (and binary (str/includes? (str binary) "/mise/shims/"))
             managed (and binary (fs/exists? binary) (fs/directory? installed)
                          (.startsWith (fs/real-path binary) (fs/real-path installed)))]
         (finding requirement
                  (cond (not (fs/directory? installed)) :missing
                        (:files-only ctx) :unknown
                        (not binary) :missing
                        shim :unknown
                        (not managed) :drifted
                        :else :compliant)
                  desired
                  (cond (not (fs/directory? installed)) "Declared version/alias install directory absent"
                        (:files-only ctx) "Install directory exists; PATH not inspected"
                        (not binary) "Install directory exists but executable is not on PATH"
                        shim "Executable uses a mise shim; effective resolution was not executed"
                        (not managed) "PATH executable resolves outside the declared mise installation; review active version"
                        :else "PATH executable resolves inside the declared mise install; upstream freshness not checked")
                  (cond-> [installed] binary (conj (str binary))))))))

(defn check-binary [ctx requirement]
  (guarded ctx requirement (str (:binary requirement) " available on PATH")
    #(if (:files-only ctx)
       (finding requirement :unknown (str (:binary requirement) " available on PATH") "PATH not inspected" [])
       (let [binary ((:which ctx) (:binary requirement))]
         (finding requirement (if binary :compliant :missing) (str (:binary requirement) " available on PATH")
                  (if binary "Executable found; not executed" "Executable absent from PATH")
                  (if binary [(str binary)] []))))))

(defn check-application [ctx requirement]
  (let [desired (str (:id requirement) " installed")]
    (guarded ctx requirement desired
      #(let [binary (when-not (:files-only ctx) ((:which ctx) (:binary requirement)))
             paths (map (partial path-at (:home ctx)) (get-in requirement [:paths (:platform ctx)]))
             found (filter fs/exists? paths)]
         (finding requirement (if (or binary (seq found)) :compliant :unknown) desired
                  (if (or binary (seq found)) "Known app path or launcher exists; launch not tested"
                      "Not found at known paths; custom/native install layouts need manual inspection")
                  (vec (concat (when binary [(str binary)]) (if (seq found) found paths))))))))

(defn active-lines [text]
  (remove #(or (str/blank? %) (str/starts-with? (str/triml %) "#")) (str/split-lines text)))

(defn check-shell-hook [ctx requirement]
  (let [target (path-at (:home ctx) (:target requirement))
        desired (str "Source managed fragment once: " (:hook requirement) "; no wtc/wt integration")]
    (guarded ctx requirement desired
      #(if-not (fs/exists? target)
         (finding requirement :missing desired "Startup file absent" [target])
         (let [lines (active-lines (read-text target (:file-limit-bytes (:spec ctx))))
               count-hooks (count (filter (fn [line] (str/includes? line (:hook requirement))) lines))
               banned (some (fn [line] (re-find #"(?:\bwtc\s*\(\)|\bwt\s+config\s+shell\s+init)" line)) lines)]
           (finding requirement (cond banned :drifted (zero? count-hooks) :missing (> count-hooks 1) :drifted :else :compliant)
                    desired
                    (cond banned "Excluded wtc/wt integration remains in startup file"
                          (zero? count-hooks) "Canonical source hook absent"
                          (> count-hooks 1) "Duplicate source hooks"
                          :else "Source hook present; effective interactive behavior remains a manual check")
                    [target]))))))

(defn npm-name [source]
  (when (string? source)
    (second (re-matches #"npm:((?:@[^/]+/)?[^@/]+)(?:@[^/]+)?" source))))

(defn check-pi-package [ctx requirement]
  (let [target (path-at (:home ctx) ".pi/agent/settings.json")
        requirement (assoc requirement :id (str "pi.package." (npm-name (:source requirement)))
                           :remediation (str "Install with pi install " (:source requirement)
                                             "; run pi list and /reload. Configure MCP endpoints separately without publishing secrets."))
        desired (str "Personal Pi package " (:source requirement))]
    (guarded ctx requirement desired
      #(if-not (fs/exists? target)
         (finding requirement :missing desired "Pi personal settings absent" [target])
         (let [settings (json/parse-string (read-text target (:file-limit-bytes (:spec ctx))) true)
               entries (:packages settings)
               matches (filter (fn [entry] (= (npm-name (:source requirement))
                                              (npm-name (if (map? entry) (:source entry) entry)))) entries)]
           (when-not (or (nil? entries) (sequential? entries)) (throw (ex-info "Invalid packages array" {})))
           (finding requirement (if (seq matches) :compliant :missing) desired
                    (if (seq matches) "Personal package declaration present; runtime load/configuration must be verified with pi list and /reload"
                        "Personal package declaration absent")
                    [target (:source requirement)]))))))

(defn check-reviewr [ctx]
  (let [requirement (:reviewr (:spec ctx))
        target (path-at (:home ctx) ".config/herdr/plugins.json")
        desired (str (:plugin-id requirement) " enabled from " (:source requirement) ", with an executable binary")]
    (guarded ctx requirement desired
      #(if-not (fs/exists? target)
         (finding requirement :missing desired "Plugin registry absent" [target])
         (let [entries (json/parse-string (read-text target (:file-limit-bytes (:spec ctx))) true)
               _ (when-not (sequential? entries) (throw (ex-info "Invalid plugin registry" {})))
               entry (first (filter (fn [entry] (= (:plugin-id requirement) (:plugin_id entry))) entries))
               correct-source (= (:source requirement) (str (get-in entry [:source :owner]) "/" (get-in entry [:source :repo])))
               binary (when (:plugin_root entry) (str (fs/path (:plugin_root entry) "bin" "herdr-reviewr")))
               status (cond (nil? entry) :missing
                            (not (true? (:enabled entry))) :drifted
                            (not correct-source) :drifted
                            (not (and binary (fs/executable? binary))) :missing
                            :else :compliant)]
           (finding requirement status desired
                    (cond (nil? entry) "reviewr not registered"
                          (not (true? (:enabled entry))) "reviewr is disabled"
                          (not correct-source) "Plugin source differs from approved GitHub repository"
                          (= status :missing) "Plugin registered but executable absent"
                          :else (str "Enabled registration and binary present; registered version " (:version entry)
                                     ". Live actions not invoked."))
                    (cond-> [target] binary (conj binary))))))))

;; Fixed, non-mutating probes only. Never execute configuration text or invoke a shell.
(def allowed-probes
  #{["emacs" "--version"] ["herdr" "--version"]
    ["emacsclient" "--alternate-editor=false" "--eval" "(list (emacs-pid) emacs-version)"]})

(defn probe [argv]
  (when-not (contains? allowed-probes argv)
    (throw (ex-info "Probe is not allowlisted" {:argv argv})))
  (try
    (let [child (process/process argv {:out :string :err :discard})]
      (if (.waitFor (:proc child) 5 TimeUnit/SECONDS)
        (select-keys @child [:exit :out])
        (do (process/destroy-tree child) {:exit -1 :out ""})))
    (catch Exception _ {:exit -1 :out ""})))

(defn version-at-least? [actual minimum]
  (let [numbers #(mapv parse-long (str/split % #"\."))
        pad #(vec (take 3 (concat (numbers %) (repeat 0))))]
    (not (neg? (compare (pad actual) (pad minimum))))))

(defn check-version [ctx id binary minimum]
  (let [requirement {:id id :priority 1 :remediation (str "Install " binary " >= " minimum " using platform guidance; preserve the baseline configuration.")}
        desired (str binary " >= " minimum)]
    (guarded ctx requirement desired
      #(cond
         (:files-only ctx) (finding requirement :unknown desired "Executable probe skipped" [])
         (not ((:which ctx) binary)) (finding requirement :missing desired "Executable absent from PATH" [])
         :else (let [{:keys [exit out]} ((:probe ctx) [binary "--version"])
                     version (second (re-find #"(\d+\.\d+(?:\.\d+)?)" (or out "")))]
                 (finding requirement (cond (not= 0 exit) :unknown (nil? version) :unknown
                                            (version-at-least? version minimum) :compliant :else :drifted)
                          desired (if (and (= 0 exit) version) (str "Observed version " version) "Version probe failed or unrecognized")
                          [(str binary " --version")]))))))

(defn check-emacs-daemon [ctx]
  (let [requirement {:id "integration.emacs-daemon" :priority 3
                     :remediation "After config review, approve daemon startup: systemd user service or existing compositor startup on Linux, launchd on macOS. Avoid duplicate daemons. Verify emacsclient connectivity afterward."}
        desired "A running Emacs 31+ daemon accepts client connections"]
    (guarded ctx requirement desired
      #(cond
         (:files-only ctx) (finding requirement :unknown desired "Client probe skipped" [])
         (not ((:which ctx) "emacsclient")) (finding requirement :missing desired "Client absent" [])
         :else (let [{:keys [exit out]} ((:probe ctx) ["emacsclient" "--alternate-editor=false" "--eval" "(list (emacs-pid) emacs-version)"])
                     version (second (re-find #"\"(\d+\.\d+(?:\.\d+)?)\"" (or out "")))]
                 (finding requirement (if (and (= 0 exit) version)
                                        (if (version-at-least? version "31.0") :compliant :drifted) :unknown)
                          desired (if (and (= 0 exit) version) (str "Existing server answered; Emacs " version)
                                      "No confirmed reachable server; not started by the audit")
                          ["emacsclient --alternate-editor=false --eval (list (emacs-pid) emacs-version)"]))))))

(defn run-audit [{:keys [home host spec] :as options}]
  (let [home (str (fs/absolutize (or home (System/getProperty "user.home"))))
        actual-home (= home (str (fs/absolutize (System/getProperty "user.home"))))
        host (or host (platform))
        spec (or spec (load-spec))
        ctx (merge {:which fs/which :probe probe :files-only false} options
                   {:home home :host host :platform (or (:platform options) host) :spec spec
                    :mise-data (or (:mise-data options)
                                   (when actual-home (System/getenv "MISE_DATA_DIR"))
                                   (path-at home ".local/share/mise"))})
        versions (mise-versions (slurp (str (fs/path skill-root "assets/configs/mise/config.toml"))))]
    (when-not (= (set (keys versions)) (set (map :id (:mise-tools spec))))
      (throw (ex-info "Canonical mise config and tool inventory disagree" {})))
    (let [findings (vec (concat
                         (map #(check-file ctx %) (:files spec))
                         (map #(check-mise-tool ctx % versions) (:mise-tools spec))
                         (map #(check-binary ctx %) (:binaries spec))
                         (map #(check-application ctx %) (:applications spec))
                         (map #(check-shell-hook ctx %) (:shell-hooks spec))
                         (map #(check-pi-package ctx %) (:pi-packages spec))
                         [(check-reviewr ctx)
                          (check-version ctx "version.emacs" "emacs" "31.0")
                          (check-version ctx "version.herdr" "herdr" (:minimum-herdr (:reviewr spec)))
                          (check-emacs-daemon ctx)]
                         (map #(guarded ctx % (:desired %) (fn [] (finding % :unknown (:desired %) "Requires explicit manual verification" [])))
                              (:manual spec))))]
      {:schema-version 1 :host host :profile (:platform ctx) :home home
       :summary (merge (zipmap [:compliant :missing :drifted :unknown :not-applicable] (repeat 0))
                       (frequencies (map :status findings)))
       :findings findings :limitations (:limitations spec)})))

(defn markdown [report]
  (let [cell #(-> (str %) (str/replace "|" "\\|") (str/replace #"[\r\n]+" " "))]
    (str "# Computer setup audit\n\nHost: " (name (:host report)) "; profile: " (name (:profile report)) "\n\n"
         "| Requirement | Status | Desired | Observed | Evidence |\n|---|---|---|---|---|\n"
         (str/join "\n" (for [f (:findings report)]
                           (str "| " (str/join " | " (map cell [(:id f) (name (:status f)) (:desired f) (:observed f)
                                                                   (str/join "; " (:evidence f))])) " |")))
         "\n\n## Limitations\n" (str/join "\n" (map #(str "- " %) (:limitations report))) "\n")))

(def usage "Usage: bb audit.clj [--json] [--platform linux|macos] [--home PATH] [--files-only]\n--home implies --files-only. Reports go to stdout only; findings are not an exit-code failure.")

(defn parse-args [args]
  (loop [args args options {}]
    (if-let [arg (first args)]
      (case arg
        "--json" (recur (next args) (assoc options :json true))
        "--files-only" (recur (next args) (assoc options :files-only true))
        "--help" (assoc options :help true)
        "--platform" (let [value (second args)]
                       (when-not (#{"linux" "macos"} value) (throw (ex-info "Expected linux or macos" {})))
                       (recur (nnext args) (assoc options :platform (keyword value))))
        "--home" (let [value (second args)]
                   (when (or (str/blank? value) (str/starts-with? value "--")) (throw (ex-info "Expected a home path" {})))
                   (recur (nnext args) (assoc options :home value :files-only true)))
        (throw (ex-info "Unknown audit argument" {})))
      options)))

(defn -main [& args]
  (try
    (let [options (parse-args args)]
      (if (:help options) (println usage)
          (let [report (run-audit options)]
            (println (if (:json options) (json/generate-string report {:pretty true}) (markdown report))))))
    (catch Exception e
      (binding [*out* *err*] (println (str "Audit error: " (.getMessage e))) (println usage))
      (System/exit 2))))
