(ns agentctl.adapters.claude-sources
  "How Claude Code reads a remote marketplace source string, so agentctl writes
   and compares the `extraKnownMarketplaces` entry `claude plugin marketplace
   add` itself writes. Pure: no files, no network."
  (:require [clojure.string :as str]))

(defn- host [url]
  (try (some-> (java.net.URI. url) .getHost str/lower-case) (catch Exception _ nil)))

(defn- path-segments [url]
  (let [i (str/index-of url "/" (+ 3 (or (str/index-of url "://") 0)))]
    (if i (subs url i) "")))

(defn remote
  "`{:source \"git\" :url …}` or `{:source \"url\" :url …}` for a remote
   (non-local) URI, as the CLI parses it:
     - an scp-style address, or a URL ending `.git` or holding `/_git/`, is
       git as written;
     - `https://github.com/owner/repo` and `https://gitlab.com/group/project`
       are git, with `.git` appended;
     - any other http(s) URL is a hosted marketplace.json (`url`)."
  [uri]
  (let [uri (str/trim uri)]
    (cond
      (not (re-find #"(?i)^https?://" uri)) {:source "git" :url uri}
      (or (str/ends-with? uri ".git") (str/includes? uri "/_git/")) {:source "git" :url uri}
      :else
      (let [h (host uri)
            trimmed (str/replace uri #"(?:[/?#]|%2[Ff]|%3[Ff]|%23)+$" "")
            path (path-segments trimmed)
            segs (remove str/blank? (str/split path #"/"))]
        (cond
          (and (= "github.com" h) (re-find #"^/[^/]+/[^/]+" path))
          {:source "git" :url (str trimmed ".git")}

          (and (= "gitlab.com" h) (>= (count segs) 2) (not= "api" (first segs)) (not-any? #{"-"} segs)
               (= path (str "/" (str/join "/" segs))))
          {:source "git" :url (str trimmed ".git")}

          :else {:source "url" :url uri})))))

(defn same-url?
  "Two spellings of one git remote: with and without a trailing `.git`."
  [a b]
  (let [canon #(some-> % (str/replace #"/+$" "") (str/replace #"\.git$" ""))]
    (= (canon a) (canon b))))

(defn same-source?
  "One marketplace source, whatever spelling of its URL either side uses.
   Other keys (`:ref`, `:path`, `:repo`) must agree as written."
  [a b]
  (and (= (:source a) (:source b))
       (= (dissoc a :url) (dissoc b :url))
       (same-url? (:url a) (:url b))))
