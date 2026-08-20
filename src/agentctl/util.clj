(ns agentctl.util
  "Filesystem, serialization and process helpers shared by all adapters."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------- paths

(def home
  "Home directory all tool paths hang off. AGENTCTL_HOME lets tests and
   sandboxed runs point the whole tool at a scratch tree."
  (or (System/getenv "AGENTCTL_HOME")
      (System/getenv "HOME")
      (System/getProperty "user.home")))

(defn expand
  "Expand ~ and $VAR / ${VAR} inside a path-ish string."
  [s]
  (when s
    (-> (str s)
        (str/replace #"^~(?=/|$)" home)
        (str/replace #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}"
                     (fn [[whole v]] (or (System/getenv v) whole)))
        (str/replace #"\$([A-Za-z_][A-Za-z0-9_]*)"
                     (fn [[whole v]] (or (System/getenv v) whole))))))

(defn abs-path ^String [s] (str (fs/absolutize (fs/path (expand s)))))

(defn tilde
  "Render an absolute path back with ~ for display."
  [s]
  (let [s (str s)]
    (if (str/starts-with? s home) (str "~" (subs s (count home))) s)))

(defn real-path
  "Canonical path, resolving symlinks. On macOS /var and /tmp are themselves
   symlinks, so comparing a link target to a plain absolute path never matches
   unless both sides go through this."
  [p]
  (let [p (abs-path p)]
    (try (str (fs/real-path p)) (catch Exception _ p))))

(defn exists? [p] (and p (fs/exists? (fs/path (expand (str p))) {:nofollow-links true})))

;; ---------------------------------------------------------------- hashing

(defn sha256 [^String s]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (->> (.digest d (.getBytes s "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn path-sha256 [p]
  (when (exists? p)
    (let [p (fs/path (expand (str p)))]
      (if (fs/directory? p)
        (sha256 (->> (fs/glob p "**")
                     (sort)
                     (map (fn [f] (str (fs/relativize p f) ":"
                                       (when (fs/regular-file? f) (sha256 (slurp (fs/file f)))))))
                     (str/join "\n")))
        (sha256 (slurp (fs/file p)))))))

;; ---------------------------------------------------------------- io

(defn slurp-safe [p]
  (try (when (exists? p) (slurp (fs/file (expand (str p))))) (catch Exception _ nil)))

(defn read-json [p]
  (try (some-> (slurp-safe p) (json/parse-string true)) (catch Exception _ nil)))

(defn read-yaml [p]
  (try (some-> (slurp-safe p) (yaml/parse-string {:keywordize true}))
       (catch Exception _ nil)))

(defn read-edn [p]
  (try (some-> (slurp-safe p) (edn/read-string)) (catch Exception _ nil)))

(defn write-json! [p data]
  (let [f (fs/file (expand (str p)))]
    (fs/create-dirs (fs/parent f))
    (spit f (str (json/generate-string data {:pretty true}) "\n"))))

(defn write-yaml! [p data]
  (let [f (fs/file (expand (str p)))]
    (fs/create-dirs (fs/parent f))
    (spit f (yaml/generate-string data :dumper-options {:flow-style :block}))))

(defn write-text! [p ^String text]
  (let [f (fs/file (expand (str p)))]
    (fs/create-dirs (fs/parent f))
    (spit f text)))

(defn timestamp []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))

(def ^:dynamic *backup-root* (str home "/.config/agentctl/backups"))

(def ^:private run-stamp
  "One timestamp per process: several ops touch the same file in a single run."
  (delay (timestamp)))

(defn backup-dir [] (str *backup-root* "/" @run-stamp))

(defn backup!
  "Copy `p` into this run's backup directory. Returns the backup path, or nil
   when there is nothing to back up.

   The first copy of a given file wins: later ops in the same run would
   otherwise overwrite the pre-run state with a half-converged one, which is
   exactly the state the backup exists to undo."
  [p]
  (when (exists? p)
    (let [src (fs/path (expand (str p)))
          dest (fs/path (backup-dir) (str/replace (tilde (str src)) #"[/~]" "_"))]
      (if (fs/exists? dest {:nofollow-links true})
        (str dest)
        (do (fs/create-dirs (fs/parent dest))
            (if (fs/directory? src)
              (fs/copy-tree src dest)
              (fs/copy src dest))
            (str dest))))))

;; ---------------------------------------------------------------- process

(defn sh
  "Run a command. Returns {:exit :out :err}. Never throws."
  [& args]
  (let [args (remove nil? (flatten args))]
    (try
      (let [{:keys [exit out err]} @(p/process args {:out :string :err :string})]
        {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))})
      (catch Exception e
        {:exit 127 :out "" :err (.getMessage e)}))))

(defn sh-env
  "Like `sh` but with extra environment variables layered on top."
  [env & args]
  (let [args (remove nil? (flatten args))]
    (try
      (let [{:keys [exit out err]}
            @(p/process args {:out :string :err :string
                              :extra-env (into {} (map (fn [[k v]]
                                                         [(if (keyword? k) (name k) (str k)) (str v)]))
                                               env)})]
        {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))})
      (catch Exception e
        {:exit 127 :out "" :err (.getMessage e)}))))

(defn which [cmd]
  (when-not (str/blank? (str cmd))
    (let [{:keys [exit out]} (sh "sh" "-c" (str "command -v " cmd))]
      (when (and (zero? exit) (not (str/blank? out))) (str/trim out)))))

;; ---------------------------------------------------------------- maps

(defn deep-merge [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (some? b) b
    :else a))

(defn dissoc-in [m path]
  (if (= 1 (count path))
    (dissoc m (first path))
    (update-in m (butlast path) dissoc (last path))))

(defn prune-nils [m]
  (into {} (remove (fn [[_ v]] (nil? v)) m)))

(defn kw->str [k] (if (keyword? k) (name k) (str k)))

(defn id->str
  "Like `kw->str` but keeps the namespace: `:example/slack` is a different
   resource from a user-wide `:slack`, and collapsing them in the ownership
   manifest would let a prune delete the wrong one."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn key-str
  "Full string of a map key. Unlike `name`, keeps slashes: a JSON object keyed
   by an absolute path parses to (keyword \"/a/b\"), whose `name` is \"a/b\"."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn norm
  "Normalize a value for comparison: keywords -> strings, vectors <-> seqs."
  [v]
  (cond
    (keyword? v) (name v)
    (map? v) (into (sorted-map) (map (fn [[k x]] [(kw->str k) (norm x)])) v)
    (sequential? v) (mapv norm v)
    :else v))

(defn split-cmd
  "Split a shell-ish command line into tokens, honouring single and double
   quotes. `:cmd \"a 'b c' d\"` is the terse spelling of :command + :args, so a
   quoted argument has to survive as one token."
  [s]
  (when-let [s (some-> s str not-empty)]
    (loop [cs (seq s) cur (StringBuilder.) started? false quote nil out []]
      (if-let [c (first cs)]
        (cond
          quote (if (= c quote)
                  (recur (next cs) cur true nil out)
                  (recur (next cs) (.append cur c) true quote out))
          (or (= c \") (= c \')) (recur (next cs) cur true c out)
          (Character/isWhitespace ^char c)
          (if started?
            (recur (next cs) (StringBuilder.) false nil (conj out (str cur)))
            (recur (next cs) cur false nil out))
          :else (recur (next cs) (.append cur c) true nil out))
        (cond-> out started? (conj (str cur)))))))
