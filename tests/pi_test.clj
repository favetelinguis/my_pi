(ns pi-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn assert! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn command! [environment directory argv]
  (let [result @(process/process argv {:env environment :dir (str directory) :out :string :err :string})]
    (assert! (zero? (:exit result)) (str "Command failed: " argv "\n" (:err result)))
    result))

(defn read-until! [queue predicate]
  (loop [records [] remaining 300]
    (assert! (pos? remaining) "RPC record budget exceeded")
    (let [record (.poll queue 20 TimeUnit/SECONDS)]
      (assert! record (str "Timed out waiting for Pi RPC; records: " (mapv #(select-keys % [:type :id :command :success :method]) records)))
      (assert! (not (:reader-error record)) (str "RPC reader failed: " (:reader-error record)))
      (if (predicate record) (conj records record)
          (recur (conj records record) (dec remaining))))))

(defn rpc! [writer queue command]
  (.write writer (str (json/generate-string command) "\n"))
  (.flush writer)
  (let [records (read-until! queue #(and (= "response" (:type %)) (= (:id command) (:id %))))
        response (last records)]
    (assert! (:success response) (str "RPC command failed: " response))
    {:response response :records records}))

(defn smoke! [environment directory cli requests]
  (let [child (process/process [cli "--mode" "rpc" "--no-session" "--no-tools" "--no-context-files" "--offline"]
                               {:env environment :dir (str directory)})
        queue (LinkedBlockingQueue.)
        writer (io/writer (:in child))
        errors (future (slurp (:err child)))
        reader (future
                 (try
                   (with-open [r (io/reader (:out child))]
                     (loop []
                       (when-let [line (.readLine r)]
                         (.put queue (json/parse-string line true))
                         (recur))))
                   (catch Exception e (.put queue {:reader-error (.getMessage e)}))))]
    (try
      (let [commands (get-in (rpc! writer queue {:id "commands" :type "get_commands"}) [:response :data :commands])
            extension-path (get-in (first (filter #(= "dotfiles" (:name %)) commands)) [:sourceInfo :path])
            _ (assert! (and extension-path (str/ends-with? extension-path "/plugins/dotfiles/extension.ts"))
                       "Unexpected dotfiles extension path")
            root (str/replace extension-path #"/plugins/dotfiles/extension\.ts$" "")
            package-commands (filter #(str/starts-with? (get-in % [:sourceInfo :path] "") (str root "/")) commands)
            by-name (into {} (map (juxt :name identity) package-commands))]
        (assert! (= "extension" (:source (get by-name "dotfiles"))) "Missing /dotfiles extension")
        (assert! (= "prompt" (:source (get by-name "prompt"))) "Missing /prompt template")
        (assert! (= "skill" (:source (get by-name "skill:dotfiles"))) "Missing explicit skill command")
        (assert! (not (contains? by-name "plan")) "Unexpected /plan resource")
        (assert! (= #{"dotfiles" "prompt" "skill:dotfiles"} (set (keys by-name)))
                 (str "Unexpected resources: role prompts/Herdr skill must stay assets: " (keys by-name))))
      (let [messages (get-in (rpc! writer queue {:id "initial" :type "get_messages"}) [:response :data :messages])]
        (assert! (empty? messages) "Startup must not inject dotfiles instructions or run an audit"))
      (doseq [[id message] [["help" "/dotfiles help"] ["invalid" "/dotfiles unsupported"]]]
        (let [records (:records (rpc! writer queue {:id id :type "prompt" :message message}))]
          (assert! (some #(= "extension_ui_request" (:type %)) records) "Expected extension help/validation notification")))
      (let [messages (get-in (rpc! writer queue {:id "after-help" :type "get_messages"}) [:response :data :messages])]
        (assert! (empty? messages) "Help/invalid command must not invoke a model"))
      ;; A loopback-only fake provider captures the actual transmitted context.
      ;; No credentials or external provider endpoint are inherited.
      (doseq [[id message] [["ordinary" "An ordinary test conversation."] ["invoke" "/dotfiles check"]]]
        (let [result (rpc! writer queue {:id id :type "prompt" :message message})]
          (when-not (some #(= "agent_settled" (:type %)) (:records result))
            (read-until! queue #(= "agent_settled" (:type %))))))
      (assert! (= 2 (count @requests)) "Expected ordinary and explicit loopback model requests")
      (assert! (not (str/includes? (json/generate-string (first @requests)) "dotfiles"))
               "Ordinary conversation must not advertise or load the dotfiles skill")
      (let [messages (get-in (rpc! writer queue {:id "explicit" :type "get_messages"}) [:response :data :messages])
            text (json/generate-string messages)]
        (assert! (str/includes? text "Dotfiles and computer setup") "Explicit /dotfiles did not load skill instructions")
        (assert! (str/includes? text "audit findings only") "Check arguments were not forwarded")
        (assert! (not (str/includes? text "Cannot load the bundled")) "Installed skill path resolution failed"))
      (finally
        (.close writer)
        (when-not (.waitFor (:proc child) 10 TimeUnit/SECONDS) (process/destroy-tree child))
        (deref reader 5000 nil)
        (let [stderr (deref errors 5000 "")]
          (assert! (not (re-find #"(?i)(failed to load extension|extension errors|syntaxerror)" stderr))
                   (str "Extension startup diagnostics: " stderr)))))))

(defn fake-model-server [requests]
  (server/run-server
    (fn [request]
      (swap! requests conj (json/parse-string (slurp (:body request)) true))
      {:status 200 :headers {"Content-Type" "text/event-stream"}
       :body (str "data: " (json/generate-string
                              {:id "fixture-response" :object "chat.completion.chunk" :created 1 :model "fixture"
                               :choices [{:index 0 :delta {:role "assistant" :content "fixture response"} :finish_reason nil}]})
                  "\n\ndata: " (json/generate-string
                                   {:id "fixture-response" :object "chat.completion.chunk" :created 1 :model "fixture"
                                    :choices [{:index 0 :delta {} :finish_reason "stop"}]
                                    :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})
                  "\n\ndata: [DONE]\n\n")})
    {:ip "127.0.0.1" :port 0 :legacy-return-value? false}))

(defn run-test! [_]
  (let [root (str (fs/normalize (fs/absolutize ".")))
        source (or (System/getenv "MY_PI_TEST_SOURCE") root)
        cli (str (or (fs/which "pi") (throw (ex-info "pi is required for smoke tests" {}))))
        temp (fs/create-temp-dir {:prefix "my-pi-rpc-test-"})
        agent (str (fs/path temp "agent"))
        ;; Deliberately do not inherit any API keys, real HOME config or sessions.
        environment {"PATH" (System/getenv "PATH") "HOME" (str temp)
                     "PI_CODING_AGENT_DIR" agent "PI_OFFLINE" "1" "NO_COLOR" "1"}
        requests (atom [])
        model-server (fake-model-server requests)]
    (try
      (fs/create-dirs agent)
      (spit (str (fs/path agent "models.json"))
            (json/generate-string {:providers {"fixture" {:baseUrl (str "http://127.0.0.1:" (server/server-port model-server) "/v1")
                                                          :api "openai-completions" :apiKey "fixture-not-a-real-key"
                                                          :models [{:id "fixture" :reasoning false :contextWindow 200000 :maxTokens 1000}]}}}))
      (spit (str (fs/path agent "settings.json"))
            (json/generate-string {:defaultProvider "fixture" :defaultModel "fixture" :cacheWarming "off"}))
      (command! environment temp [cli "install" source])
      (let [settings (json/parse-string (slurp (str (fs/path agent "settings.json"))) true)]
        (assert! (if (str/starts-with? source "git:") (= [source] (:packages settings))
                     (= [root] (mapv #(str (fs/normalize (fs/path agent %))) (:packages settings))))
                 "pi install did not register the package"))
      (smoke! environment temp cli requests)
      (command! environment temp [cli "remove" source])
      (let [settings (json/parse-string (slurp (str (fs/path agent "settings.json"))) true)]
        (assert! (empty? (:packages settings)) "pi remove did not remove the declaration"))
      (println "PASS: isolated Pi install/remove, resource discovery, lazy loading, help and explicit invocation")
      (finally (server/server-stop! model-server) (fs/delete-tree temp)))))
