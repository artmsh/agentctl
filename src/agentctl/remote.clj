(ns agentctl.remote
  "Filesystem primitives on another machine, over SSH.

   The host may have nothing but OpenSSH and PowerShell, so every primitive is
   one generated script fed to the remote interpreter on stdin. Nothing is ever
   spliced into the ssh command line, because the login shell on the far side
   is not knowable: Windows OpenSSH's DefaultShell can be cmd, PowerShell or
   WSL's bash, and the same bootstrap has to survive all three. Scripts are pure
   ASCII — PowerShell 5.1 reads stdin in the OEM codepage — so paths and file
   contents travel base64-encoded.

   A primitive either ends its output with a final `OK` line or counts as
   failed. A host that answered half a question must not read as a clean one."
  (:require [agentctl.util :as u]
            [babashka.process :as p]
            [clojure.string :as str])
  (:import [java.util Base64]))

;; ---------------------------------------------------------------- encoding

(defn b64 [^bytes bs] (.encodeToString (Base64/getEncoder) bs))
(defn b64-str [^String s] (b64 (.getBytes s "UTF-8")))
(defn unb64-str [^String s] (String. (.decode (Base64/getDecoder) s) "UTF-8"))

(defn- sh-quote [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))

;; ---------------------------------------------------------------- transport

(defn windows? [host] (= :windows (:os host)))

(def ^:private ps-bootstrap
  "Reads the real script from stdin. `-Command -` would do without it, except
   that it executes stdin line by line and a multi-line block never finishes."
  "$ProgressPreference='SilentlyContinue'; Invoke-Expression ([Console]::In.ReadToEnd())")

(defn remote-command
  "What ssh asks the login shell to run. Base64 has no character bash, cmd or
   PowerShell treats as special, and `powershell.exe` — never `powershell` —
   is the one spelling WSL interop also resolves."
  [host]
  (if (windows? host)
    (str "powershell.exe -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand "
         (b64 (.getBytes ^String ps-bootstrap "UTF-16LE")))
    "sh -s"))

(defn- ssh-argv
  "`AGENTCTL_SSH_CMD` replaces ssh itself (tests run offline). It receives the
   destination and the remote command."
  [host]
  (let [cmd (remote-command host)]
    (if-let [stub (some-> (System/getenv "AGENTCTL_SSH_CMD") not-empty u/split-cmd)]
      (concat stub [(:ssh host) cmd])
      ["ssh" "-T" "-o" "BatchMode=yes" "-o" "ConnectTimeout=10"
       "-o" "ServerAliveInterval=15" "-o" "ServerAliveCountMax=4"
       "--" (:ssh host) cmd])))

(defn- fail [kind host msg]
  (throw (ex-info (str "host " (name (:id host)) " (" (:ssh host) ") " msg) {:remote kind})))

(defn- first-error [err]
  (or (some->> (str/split-lines (str err)) (map str/trim) (remove str/blank?) last) "no error output"))

(defn exec
  "Run `script` on the host; its output lines. Throws ex-info carrying
   `{:remote :unreachable}` when ssh itself gave up (its exit is 255), and
   `{:remote :failed}` when the script did not reach its final `OK`."
  [host script]
  (let [{:keys [exit out err]}
        (try
          @(p/process (ssh-argv host) {:in script :out :string :err :string})
          (catch Exception e {:exit 255 :err (.getMessage e)}))
        lines (mapv str/trim (str/split-lines (str out)))]
    (cond
      (= 255 exit) (fail :unreachable host (str "is unreachable: " (first-error err)))
      (not (zero? exit)) (fail :failed host (str "failed: " (first-error err)))
      (not= "OK" (last (remove str/blank? lines))) (fail :failed host "gave a truncated answer")
      :else lines)))

;; ---------------------------------------------------------------- roots

(def roots
  "Where a host keeps user-wide skills, each named by the key its ownership is
   recorded under: codex's own `$CODEX_HOME/skills`, and `~/.agents/skills`,
   which awl reads (and codex too, from 0.95.0 on)."
  [:codex :awl])

(defn- sh-root [root] (case root :codex "$C/skills" :awl "$H/.agents/skills"))
(defn- ps-root [root] (case root :codex "$C\\skills" :awl "$H\\.agents\\skills"))

(defn- parked-as
  "A backup's name prefix. Per root, because a run that moves a skill from one
   root to the other parks the same id from both under one stamp."
  [root]
  (case root :codex "_.codex_skills_" :awl "_.agents_skills_"))

;; A whole file a host keeps for a tool, read with the inventory and written
;; back in one piece: what agentctl edits in it is edited in a local copy.
(defn- sh-file [k] (case k :codex-config "$C/config.toml"))
(defn- ps-file [k] (case k :codex-config "$C\\config.toml"))
(defn- file-parked-as [k] (case k :codex-config "_.codex_config.toml"))

;; ---------------------------------------------------------------- powershell

(defn- ps-str [s] (str "(D '" (b64-str s) "')"))

(defn- ps-prelude
  "`$H` is the user's home and `$C` codex's, as codex itself resolves them. A
   declared `:home` pins both, so a scratch tree stays out of the real ones."
  [{:keys [home]}]
  (str "$ErrorActionPreference='Stop'\n"
       "function D($s){[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))}\n"
       "function E($s){[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s))}\n"
       "function Say($s){[Console]::Out.WriteLine($s)}\n"
       ;; GetAttributes does not follow a link, so a dangling one still exists
       "function Attr($p){try{[IO.File]::GetAttributes($p)}catch{$null}}\n"
       "function IsDir($a){($a -band [IO.FileAttributes]::Directory) -ne 0}\n"
       "function IsLink($a){($a -band [IO.FileAttributes]::ReparsePoint) -ne 0}\n"
       "$sha=[Security.Cryptography.SHA256]::Create()\n"
       "function Sha($p){$f=[IO.File]::OpenRead($p);try{[BitConverter]::ToString($sha.ComputeHash($f)).Replace('-','').ToLower()}finally{$f.Dispose()}}\n"
       (if home
         (str "$H=" (ps-str home) "\n$C=\"$H\\.codex\"\n")
         (str "$H=$env:USERPROFILE\n$C=if($env:CODEX_HOME){$env:CODEX_HOME}else{\"$H\\.codex\"}\n"))))

(defn- ps-wrap [{:as host} body]
  (str (ps-prelude host)
       "try{\n" body "\nSay 'OK'\nexit 0\n}catch{[Console]::Error.WriteLine($_.Exception.Message);exit 1}\n"))

(defn- ps-inventory [host tools ids files]
  (ps-wrap host
           (str "Say (\"H`t\"+(E $H))\n"
                (str/join (for [k files]
                            (str "$f=\"" (ps-file k) "\"\n"
                                 "$a=Attr $f\n"
                                 "if(($a -ne $null) -and (IsLink $a)){Say (\"F`t" (name k) "`t\"+(E $f)+\"`t@\")}\n"
                                 "elseif(Test-Path -LiteralPath $f -PathType Leaf){Say (\"F`t" (name k) "`t\"+(E $f)"
                                 "+\"`t\"+[Convert]::ToBase64String([IO.File]::ReadAllBytes($f)))}"
                                 "else{Say (\"F`t" (name k) "`t\"+(E $f)+\"`t-\")}\n")))
                (str/join (for [r roots]
                            (str "Say (\"R`t" (name r) "`t\"+(E \"" (ps-root r) "\"))\n")))
                (str/join (for [t tools]
                            (str "if(Get-Command " t " -ErrorAction SilentlyContinue){Say \"T`t" t "`t1\"\n"
                                 ;; under 'Stop', PowerShell 5.1 turns a native
                                 ;; command's stderr into a terminating error
                                 "try{$v=[string](& " t " --version 2>$null | Select-Object -First 1)}catch{$v=''}\n"
                                 "Say (\"V`t" t "`t\"+(E $v))}else{Say \"T`t" t "`t0\"}\n")))
                (str/join
                 (for [r roots id ids
                       :let [tag (str "`t" (name r) "`t$id`t")]]
                   (str "$id=" (ps-str id) "\n"
                        "$d=\"" (ps-root r) "\\$id\"\n"
                        "$a=Attr $d\n"
                        "if($a -eq $null){Say (\"S" tag "A\")}\n"
                        "elseif(IsLink $a){Say (\"S" tag "L\")}\n"
                        "elseif(-not (IsDir $a)){Say (\"S" tag "F\")}\n"
                        "else{Say (\"S" tag "D\")\n"
                        "foreach($f in @(Get-ChildItem -LiteralPath $d -Recurse -File -Force)){\n"
                        "$r=$f.FullName.Substring($d.Length+1).Replace('\\','/')\n"
                        "Say (\"M" tag "\"+(E $r)+\"`t\"+(Sha $f.FullName))}}\n"))))))

(defn- ps-move-helpers []
  (str "function Mv($a,$b){if(IsDir (Attr $a)){[IO.Directory]::Move($a,$b)}else{[IO.File]::Move($a,$b)}}\n"
       "function Park($d,$b){if((Attr $b) -ne $null){throw \"backup exists: $b\"}\n"
       "[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($b));Mv $d $b}\n"))

(defn- ps-write-tree [host stamp root id files]
  (ps-wrap host
           (str (ps-move-helpers)
                "function W($r,$b,$h){$p=[IO.Path]::Combine($s,$r.Replace('/','\\'))\n"
                "[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))\n"
                "[IO.File]::WriteAllBytes($p,[Convert]::FromBase64String($b))\n"
                "if((Sha $p) -ne $h){throw \"checksum mismatch: $r\"}}\n"
                "$id=" (ps-str id) "\n"
                "$d=\"" (ps-root root) "\\$id\"\n"
                "$b=\"$H\\.config\\agentctl\\backups\\" stamp "\\" (parked-as root) "$id\"\n"
                "$s=\"$H\\.config\\agentctl\\staging\\" stamp "-" (name root) "-$id\"\n"
                "try{\n"
                (str/join (for [{:keys [rel bytes sha]} files]
                            (str "W " (ps-str rel) " '" (b64 bytes) "' '" sha "'\n")))
                ;; two names differing only in case collapse into one file here
                "if(@(Get-ChildItem -LiteralPath $s -Recurse -File -Force).Count -ne " (count files)
                "){throw 'file count differs after writing'}\n"
                "[void][IO.Directory]::CreateDirectory(\"" (ps-root root) "\")\n"
                "$had=(Attr $d) -ne $null\n"
                "if($had){Park $d $b}\n"
                "try{Mv $s $d}catch{if($had){Mv $b $d};throw}\n"
                "}finally{Remove-Item -LiteralPath $s -Recurse -Force -ErrorAction SilentlyContinue}\n")))

(defn- ps-park [host stamp root id]
  (ps-wrap host
           (str (ps-move-helpers)
                "$id=" (ps-str id) "\n"
                "$d=\"" (ps-root root) "\\$id\"\n"
                "if((Attr $d) -ne $null){Park $d \"$H\\.config\\agentctl\\backups\\" stamp "\\" (parked-as root) "$id\"}\n")))

;; ---------------------------------------------------------------- posix sh

(defn- sh-prelude [{:keys [home]}]
  (str "set -eu\n"
       (if home
         (str "H=" (sh-quote home) "\nC=\"$H/.codex\"\n")
         "H=$HOME\nC=${CODEX_HOME:-$H/.codex}\n")
       "sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum \"$1\"; else shasum -a 256 \"$1\"; fi | cut -d' ' -f1; }\n"
       "e() { printf %s \"$1\" | base64 | tr -d '\\n'; }\n"))

(defn- sh-inventory [host tools ids files]
  (str (sh-prelude host)
       "printf 'H\\t%s\\n' \"$(e \"$H\")\"\n"
       (str/join (for [k files]
                   (str "f=\"" (sh-file k) "\"\n"
                        "if [ -L \"$f\" ]; then printf 'F\\t" (name k) "\\t%s\\t@\\n' \"$(e \"$f\")\"\n"
                        "elif [ -f \"$f\" ]; then printf 'F\\t" (name k) "\\t%s\\t%s\\n' \"$(e \"$f\")\" \"$(base64 < \"$f\" | tr -d '\\n')\"\n"
                        "else printf 'F\\t" (name k) "\\t%s\\t-\\n' \"$(e \"$f\")\"; fi\n")))
       (str/join (for [r roots]
                   (str "printf 'R\\t" (name r) "\\t%s\\n' \"$(e \"" (sh-root r) "\")\"\n")))
       (str/join (for [t tools]
                   (str "if command -v " t " >/dev/null 2>&1; then printf 'T\\t" t "\\t1\\n'\n"
                        ;; stdin is the rest of this script: a CLI reading it
                        ;; would eat it. One that fails, or has not answered in
                        ;; 5s, has no version. Not `timeout`: stock macOS has
                        ;; none. Output goes to a file, so a child the CLI
                        ;; spawned cannot hold a pipe open after the kill.
                        "o=$(mktemp); " t " --version </dev/null >\"$o\" 2>/dev/null & p=$!; i=0\n"
                        "while kill -0 \"$p\" 2>/dev/null && [ \"$i\" -lt 50 ]; do sleep 0.1; i=$((i+1)); done\n"
                        "if kill -0 \"$p\" 2>/dev/null; then kill -9 \"$p\" 2>/dev/null || true; v=\n"
                        "elif wait \"$p\"; then v=$(head -n 1 \"$o\"); else v=; fi\n"
                        "rm -f \"$o\"\n"
                        "printf 'V\\t" t "\\t%s\\n' \"$(e \"$v\")\"\n"
                        "else printf 'T\\t" t "\\t0\\n'; fi\n")))
       (str/join
        (for [r roots id ids
              :let [tag (str "\\t" (name r) "\\t%s\\t")]]
          (str "id=" (sh-quote id) "\n"
               "d=\"" (sh-root r) "/$id\"\n"
               "if [ -L \"$d\" ]; then printf 'S" tag "L\\n' \"$id\"\n"
               "elif [ -d \"$d\" ]; then printf 'S" tag "D\\n' \"$id\"\n"
               "find -L \"$d\" -type f | while IFS= read -r f; do\n"
               "printf 'M" tag "%s\\t%s\\n' \"$id\" \"$(e \"${f#\"$d\"/}\")\" \"$(sha \"$f\")\"; done\n"
               "elif [ -e \"$d\" ]; then printf 'S" tag "F\\n' \"$id\"\n"
               "else printf 'S" tag "A\\n' \"$id\"; fi\n")))
       "echo OK\n"))

(defn- mime [^bytes bs]
  (.encodeToString (Base64/getMimeEncoder 76 (.getBytes "\n")) bs))

(defn- sh-park-fn []
  (str "park() { [ ! -e \"$2\" ] && [ ! -L \"$2\" ] || { echo \"backup exists: $2\" >&2; exit 1; }\n"
       "mkdir -p \"$(dirname \"$2\")\"; mv \"$1\" \"$2\"; }\n"))

(defn- sh-write-tree [host stamp root id files]
  (str (sh-prelude host)
       (sh-park-fn)
       "id=" (sh-quote id) "\n"
       "d=\"" (sh-root root) "/$id\"\n"
       "b=\"$H/.config/agentctl/backups/" stamp "/" (parked-as root) "$id\"\n"
       "s=\"$H/.config/agentctl/staging/" stamp "-" (name root) "-$id\"\n"
       "trap 'rm -rf \"$s\"' EXIT\n"
       (str/join
        (for [{:keys [rel bytes sha exec?]} files]
          (let [q (sh-quote rel)]
            (str "p=\"$s\"/" q "\n"
                 "mkdir -p \"$(dirname \"$p\")\"\n"
                 "base64 -d > \"$p\" <<'AGENTCTL_EOF'\n" (mime bytes) "\nAGENTCTL_EOF\n"
                 "[ \"$(sha \"$p\")\" = " (sh-quote sha) " ] || { echo 'checksum mismatch: '" q " >&2; exit 1; }\n"
                 (when exec? "chmod +x \"$p\"\n")))))
       "[ \"$(find \"$s\" -type f | wc -l | tr -d ' ')\" = " (count files)
       " ] || { echo 'file count differs after writing' >&2; exit 1; }\n"
       "mkdir -p \"" (sh-root root) "\"\n"
       "had=0; if [ -e \"$d\" ] || [ -L \"$d\" ]; then had=1; park \"$d\" \"$b\"; fi\n"
       "mv \"$s\" \"$d\" || { [ $had = 0 ] || mv \"$b\" \"$d\"; exit 1; }\n"
       "echo OK\n"))

(defn- sh-park [host stamp root id]
  (str (sh-prelude host)
       (sh-park-fn)
       "id=" (sh-quote id) "\n"
       "d=\"" (sh-root root) "/$id\"\n"
       "if [ -e \"$d\" ] || [ -L \"$d\" ]; then park \"$d\" \"$H/.config/agentctl/backups/" stamp "/" (parked-as root) "$id\"; fi\n"
       "echo OK\n"))

;; ---------------------------------------------------------------- whole files

(declare run-stamp)

(defn sha256 [^bytes bs]
  (->> (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)
       (map #(format "%02x" %))
       (apply str)))

(defn- sh-write-file [host stamp k ^bytes bs expected]
  (str (sh-prelude host)
       "f=\"" (sh-file k) "\"\n"
       "b=\"$H/.config/agentctl/backups/" stamp "/" (file-parked-as k) "\"\n"
       ;; staged beside the file: the closing `mv` is then a rename on one
       ;; filesystem, so a crash leaves the old file or the new, never half
       "s=\"$(dirname \"$f\")/.$(basename \"$f\").agentctl-" stamp "\"\n"
       "trap 'rm -f \"$s\"' EXIT\n"
       "[ ! -L \"$f\" ] || { echo \"is a symlink now, not written through: $f\" >&2; exit 1; }\n"
       (if expected
         (str "[ -f \"$f\" ] && [ \"$(sha \"$f\")\" = " (sh-quote expected) " ] || "
              "{ echo \"changed on the host since it was read: $f\" >&2; exit 1; }\n")
         "[ ! -e \"$f\" ] && [ ! -L \"$f\" ] || { echo \"appeared on the host since it was read: $f\" >&2; exit 1; }\n")
       "mkdir -p \"$(dirname \"$f\")\"\n"
       ;; the staged file takes the mode of the one it replaces (a 0600
       ;; config.toml can hold tokens): `mv` puts this inode in its place
       "if [ -f \"$f\" ]; then cp -p \"$f\" \"$s\"; fi\n"
       "base64 -d > \"$s\" <<'AGENTCTL_EOF'\n" (mime bs) "\nAGENTCTL_EOF\n"
       "[ \"$(sha \"$s\")\" = " (sh-quote (sha256 bs)) " ] || { echo 'checksum mismatch' >&2; exit 1; }\n"
       ;; the first copy a run backs up is the one it found
       "if [ -f \"$f\" ] && [ ! -e \"$b\" ]; then mkdir -p \"$(dirname \"$b\")\"; cp -p \"$f\" \"$b\"; fi\n"
       "mv \"$s\" \"$f\"\n"
       "echo OK\n"))

(defn- ps-write-file [host stamp k ^bytes bs expected]
  (ps-wrap host
           (str "$f=\"" (ps-file k) "\"\n"
                "$b=\"$H\\.config\\agentctl\\backups\\" stamp "\\" (file-parked-as k) "\"\n"
                "$s=[IO.Path]::Combine([IO.Path]::GetDirectoryName($f),'.'+[IO.Path]::GetFileName($f)+'.agentctl-" stamp "')\n"
                "$a=Attr $f\n"
                "if(($a -ne $null) -and (IsLink $a)){throw \"is a symlink now, not written through: $f\"}\n"
                (if expected
                  (str "if(-not (Test-Path -LiteralPath $f -PathType Leaf) -or (Sha $f) -ne '" expected "'){"
                       "throw \"changed on the host since it was read: $f\"}\n")
                  "if((Attr $f) -ne $null){throw \"appeared on the host since it was read: $f\"}\n")
                "[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($f))\n"
                "try{\n"
                "[IO.File]::WriteAllBytes($s,[Convert]::FromBase64String('" (b64 bs) "'))\n"
                "if((Sha $s) -ne '" (sha256 bs) "'){throw 'checksum mismatch'}\n"
                "if(((Attr $f) -ne $null) -and ((Attr $b) -eq $null)){"
                "[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($b));[IO.File]::Copy($f,$b)}\n"
                ;; Replace and Move are renames in one directory; Copy is not
                "if((Attr $f) -ne $null){[IO.File]::Replace($s,$f,$null)}else{[IO.File]::Move($s,$f)}\n"
                "}finally{Remove-Item -LiteralPath $s -Force -ErrorAction SilentlyContinue}\n")))

(defn write-file!
  "Replace the host's file `k` (`:codex-config`) with `bs`, provided it still
   holds what was read: `expected` is that SHA-256, or nil for no file. Staged
   and checksummed first; what it replaces is copied into the host's backups."
  [host k bs expected]
  (exec host ((if (windows? host) ps-write-file sh-write-file) host @run-stamp k bs expected)))

;; ---------------------------------------------------------------- primitives

(defn windows-safe-rel?
  "Can this `/`-separated file name exist under a Windows directory, and stay
   there? `\\` and `:` are ordinary characters in a POSIX name, but in Windows they
   turn a pack's `..\\..\\x.bat` or `C:x.bat` into a write outside the skill."
  [rel]
  (every? (fn [seg]
            (and (not (re-find #"[\\/:*?\"<>|\x00-\x1f]" seg))
                 (not (re-find #"[. ]$" seg))
                 (not (re-matches #"(?i)(con|prn|aux|nul|com\d|lpt\d)(\..*)?" seg))))
          (str/split rel #"/" -1)))

(def ^:private run-stamp
  "One per process, as for local backups: a run's backups sit together."
  (delay (u/timestamp)))

(def ^:private state-of {"A" :absent "L" :link "F" :file "D" :dir})

(defn parse-inventory
  "Lines of an inventory answer as data. Anything else the login shell chose
   to print along the way — a motd, an rc file — is not part of the protocol."
  [lines]
  (reduce
   (fn [acc line]
     (let [[tag a b c d] (str/split line #"\t" -1)]
       (case tag
         "H" (assoc acc :home (unb64-str a))
         "R" (assoc-in acc [:roots (keyword a)] (unb64-str b))
         "T" (cond-> acc (= "1" b) (update :tools conj (keyword a)))
         ;; an empty answer loses its tab separator to the line trim
         "V" (assoc-in acc [:versions (keyword a)] (str/trim (unb64-str (str b))))
         "S" (assoc-in acc [:entries (keyword a) b :state] (get state-of c :absent))
         "M" (assoc-in acc [:entries (keyword a) b :files (unb64-str c)] d)
         ;; `-` is no file; an empty one loses its field to the line trim
         ;; `@` is a symlink, whose content is not read: it is not written through
         "F" (assoc-in acc [:host-files (keyword a)]
                       (cond-> {:path (unb64-str b)
                                :bytes (when-not (#{"-" "@"} c) (.decode (Base64/getDecoder) (str c)))}
                         (= "@" c) (assoc :link? true)))
         acc)))
   {:tools #{} :roots {} :versions {} :entries {} :host-files {}}
   lines))

(defn inventory
  "What is on the host: `{:home :roots {root dir} :tools #{…} :versions {tool
   line} :entries {root {id {:state :files}}}}`, every id looked up in every
   root. `:files` maps each file below a skill, `/`-separated, to the SHA-256 of
   its bytes; `:versions` holds the first line of each present tool's
   `--version`. `files` names whole files to read as well (`:host-files {k
   {:path :bytes :link?}}`, `:bytes` nil when there is none or it is a symlink). One round trip however
   many skills: PowerShell takes a second to start."
  ([host tools ids] (inventory host tools ids nil))
  ([host tools ids files]
   (parse-inventory
    (exec host ((if (windows? host) ps-inventory sh-inventory) host (map name tools) ids files)))))

(defn write-tree!
  "Make `<root>/<id>` hold exactly `files` (`{:rel :bytes :sha :exec?}`).
   The tree is staged and checksummed first; what it replaces is moved into the
   host's own backup directory rather than deleted, and moved back if the swap
   fails. Moving also means no recursive delete ever walks into a junction."
  [host root id files]
  (when (windows? host)
    (when-let [bad (first (remove (comp windows-safe-rel? :rel) files))]
      (throw (ex-info (str "file name cannot exist on Windows: " (:rel bad)) {:rel (:rel bad)}))))
  (exec host ((if (windows? host) ps-write-tree sh-write-tree) host @run-stamp root id files)))

(defn park!
  "Remove `<root>/<id>` by moving it into the host's backup directory."
  [host root id]
  (exec host ((if (windows? host) ps-park sh-park) host @run-stamp root id)))

(defn separator [host] (if (windows? host) "\\" "/"))

(defn backup-dir
  "This run's backup directory on the host, given the home it reported."
  [host home]
  (str/join (separator host) [home ".config" "agentctl" "backups" @run-stamp]))
