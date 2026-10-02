(ns audit-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [dotfiles.audit :as audit]))

(defn put! [home path text]
  (let [path (audit/path-at home path)]
    (fs/create-dirs (fs/parent path))
    (spit path text)
    path))

(defn fake-probe [argv]
  (case (first argv)
    "emacs" {:exit 0 :out "GNU Emacs 31.1\n"}
    "herdr" {:exit 0 :out "herdr 0.9.1\n"}
    "emacsclient" {:exit 0 :out "(12345 \"31.1\")\n"}))

(defn context [home host]
  (let [spec (audit/load-spec)
        versions (audit/mise-versions (slurp (str (fs/path audit/skill-root "assets/configs/mise/config.toml"))))]
    {:home (str home) :host host :platform host :spec spec
     :which (fn [binary]
              (if-let [tool (first (filter #(= binary (:binary %)) (:mise-tools spec)))]
                (str (fs/path home ".local/share/mise/installs" (:directory tool) (get versions (:id tool)) "bin" binary))
                (str "/fixture/bin/" binary)))
     :probe fake-probe}))

(defn fixture! [home]
  (let [spec (audit/load-spec)
        versions (audit/mise-versions (slurp (str (fs/path audit/skill-root "assets/configs/mise/config.toml"))))]
    (doseq [file (:files spec)]
      (put! home (:target file) (slurp (str (fs/path audit/skill-root (:asset file))))))
    (doseq [tool (:mise-tools spec)]
      (put! home (str (fs/path ".local/share/mise/installs" (:directory tool) (get versions (:id tool)) "bin" (:binary tool)))
            "fixture binary; never execute\n"))
    (doseq [hook (:shell-hooks spec)] (put! home (:target hook) (str (:hook hook) "\n")))
    (put! home ".pi/agent/settings.json" (json/generate-string {:packages (mapv :source (:pi-packages spec))}))
    (let [root (str (fs/path home "reviewr-fixture"))
          binary (put! root "bin/herdr-reviewr" "fixture binary; never execute\n")]
      (fs/set-posix-file-permissions binary "rwx------")
      (put! home ".config/herdr/plugins.json"
            (json/generate-string [{:plugin_id "persiyanov.reviewr" :version "0.39.0" :enabled true
                                   :plugin_root root :source {:owner "persiyanov" :repo "herdr-reviewr"}}])))
    home))

(defn with-home [f]
  (let [home (fs/create-temp-dir {:prefix "my-pi-audit-test-"})]
    (try (f home) (finally (fs/delete-tree home)))))

(defn findings [report] (into {} (map (juxt :id identity) (:findings report))))
(defn status [report id] (:status (get (findings report) id)))
(defn snapshot [home]
  (into {} (for [p (fs/glob home "**" {:hidden true}) :when (fs/regular-file? p)]
             [(str p) (audit/digest (slurp (str p)))])))

(deftest complete-fixture-is-read-only
  (with-home
    (fn [home]
      (fixture! home)
      (let [before (snapshot home) r (audit/run-audit (context home :linux))]
        (is (zero? (get-in r [:summary :missing])))
        (is (zero? (get-in r [:summary :drifted])))
        (is (= 4 (get-in r [:summary :unknown])))
        (is (= :not-applicable (status r "integration.macos-overrides")))
        (is (= (count (:findings r)) (count (findings r))))
        (is (= before (snapshot home)))
        (is (= :compliant (status r "pi.global")))
        (is (= :compliant (status r "integration.emacs-daemon")))))))

(deftest configuration-drift-missing-and-limits
  (with-home
    (fn [home]
      (fixture! home)
      (put! home ".config/ghostty/config" "theme = Different\n")
      (fs/delete (fs/path home ".emacs.d/early-init.el"))
      (put! home ".emacs.d/init.el" (apply str (repeat 2097153 "x")))
      (let [r (audit/run-audit (context home :linux))]
        (is (= :drifted (status r "ghostty.config")))
        (is (= :missing (status r "emacs.early-init")))
        (is (= :unknown (status r "emacs.init")))
        (is (not (str/includes? (json/generate-string r) "theme = Different")))))))

(deftest global-instructions-are-machine-specific
  (with-home
    (fn [home]
      (fixture! home)
      (put! home ".pi/agent/AGENTS.md" "# Different machine\nProjects live under ~/work.\n")
      (let [r (audit/run-audit (context home :linux))]
        (is (= :compliant (status r "pi.global")))
        (is (str/includes? (:observed (get (findings r) "pi.global")) "may differ")))
      (put! home ".pi/agent/AGENTS.md" " \n")
      (is (= :missing (status (audit/run-audit (context home :linux)) "pi.global")))
      (fs/delete (fs/path home ".pi/agent/AGENTS.md"))
      (is (= :missing (status (audit/run-audit (context home :linux)) "pi.global"))))))

(deftest required-pi-packages-accept-pins-and-objects
  (with-home
    (fn [home]
      (fixture! home)
      (put! home ".pi/agent/settings.json"
            (json/generate-string {:packages ["npm:pi-mcp-adapter@1.2.3"
                                              {:source "npm:pi-web-access@2.0.0"}
                                              "npm:@juicesharp/rpiv-ask-user-question@3.0.0"]}))
      (let [r (audit/run-audit (context home :linux))]
        (doseq [id ["pi-mcp-adapter" "pi-web-access" "@juicesharp/rpiv-ask-user-question"]]
          (is (= :compliant (status r (str "pi.package." id))))))
      (put! home ".pi/agent/settings.json" "{not valid json}")
      (is (= :unknown (status (audit/run-audit (context home :linux)) "pi.package.pi-mcp-adapter"))))))

(deftest shell-hooks-ignore-comments-and-detect-exclusions
  (with-home
    (fn [home]
      (fixture! home)
      (put! home ".zshrc" "# . \"$HOME/.config/my-pi/shell/zshrc\"\n")
      (is (= :missing (status (audit/run-audit (context home :linux)) "shell.zsh-hook")))
      (put! home ".zshrc" ". \"$HOME/.config/my-pi/shell/zshrc\"\nwtc() { something; }\n")
      (is (= :drifted (status (audit/run-audit (context home :linux)) "shell.zsh-hook")))
      (put! home ".bashrc" ". \"$HOME/.config/my-pi/shell/bashrc\"\neval \"$(command wt config shell init bash)\"\n")
      (is (= :drifted (status (audit/run-audit (context home :linux)) "shell.bash-hook"))))))

(deftest profile-mismatch-and-files-only-never-probe
  (with-home
    (fn [home]
      (fixture! home)
      (let [calls (atom 0)
            ctx (assoc (context home :linux) :probe (fn [_] (swap! calls inc) (throw (ex-info "Unexpected probe" {}))))
            cross (audit/run-audit (assoc ctx :platform :macos))
            files (audit/run-audit (assoc ctx :files-only true))]
        (is (every? #(= :unknown (:status %)) (:findings cross)))
        (is (= :compliant (status files "ghostty.config")))
        (is (= :unknown (status files "version.emacs")))
        (is (= :unknown (status files "mise.node")))
        (is (zero? @calls))))))

(deftest macos-fixture-and-bounded-probe-failure
  (with-home
    (fn [home]
      (fixture! home)
      (let [r (audit/run-audit (context home :macos))]
        (is (= :compliant (status r "emacs.init")))
        (is (= :unknown (status r "integration.macos-overrides"))))
      (let [r (audit/run-audit (assoc (context home :linux) :probe (fn [_] {:exit -1 :out ""})))]
        (is (= :unknown (status r "version.emacs")))
        (is (= :unknown (status r "integration.emacs-daemon")))))))

(deftest reviewr-and-version-compatibility
  (with-home
    (fn [home]
      (fixture! home)
      (let [path (audit/path-at home ".config/herdr/plugins.json")
            entries (json/parse-string (slurp path) true)]
        (spit path (json/generate-string [(assoc (first entries) :enabled false)]))
        (is (= :drifted (status (audit/run-audit (context home :linux)) "plugin.reviewr"))))
      (let [r (audit/run-audit (assoc (context home :linux) :probe
                                    (fn [argv] (if (= "herdr" (first argv)) {:exit 0 :out "herdr 0.7.4"} (fake-probe argv)))))]
        (is (= :drifted (status r "version.herdr")))))))

(deftest mise-path-shadowing-is-not-compliant
  (with-home
    (fn [home]
      (fixture! home)
      (let [original (context home :linux)
            outside (put! home "unmanaged/node" "fixture binary; never execute\n")
            r (audit/run-audit (assoc original :which (fn [binary]
                                                        (if (= binary "node") outside ((:which original) binary)))))]
        (is (= :drifted (status r "mise.node")))))))

(deftest parser-normalization-and-safety
  (is (= "2.12.0" (get (audit/mise-versions (slurp (str (fs/path audit/skill-root "assets/configs/mise/config.toml")))) "leiningen")))
  (is (= {:home "/fixture" :files-only true :json true} (audit/parse-args ["--home" "/fixture" "--json"])))
  (is (thrown? Exception (audit/parse-args ["--platform" "windows"])))
  (is (thrown? Exception (audit/parse-args ["--home"])))
  (is (thrown? Exception (audit/probe ["sh" "-c" "anything"])))
  (is (= "~/repos/\n" (audit/normalize "/fixture/repos/\r\n" "/fixture" false)))
  (is (audit/version-at-least? "31.1" "31.0"))
  (is (not (audit/version-at-least? "0.7.4" "0.7.5"))))

(deftest package-resources-are-explicit
  (let [package (json/parse-string (slurp "package.json") true)
        skill (slurp (str (fs/path audit/skill-root "SKILL.md")))]
    (is (= ["./plugins/dotfiles/skill/SKILL.md"] (get-in package [:pi :skills])))
    (is (= ["./prompts/prompt.md"] (get-in package [:pi :prompts])))
    (is (str/includes? skill "disable-model-invocation: true"))
    (is (not (fs/exists? "prompts/plan.md")))
    (doseq [file ["common.sh" "zshrc" "bashrc"]]
      (is (not (re-find #"(?:\bwtc\s*\(\)|\bwt\s+config\s+shell\s+init)"
                       (slurp (str (fs/path audit/skill-root "assets/configs/shell" file)))))))))

(defn run-tests! [_]
  (let [{:keys [fail error]} (run-tests 'audit-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
