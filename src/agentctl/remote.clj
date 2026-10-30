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

(defn- ps-inventory [host tools ids]
  (ps-wrap host
           (str "Say (\"H`t\"+(E $H))\n"
                "Say (\"R`t\"+(E \"$C\\skills\"))\n"
                (str/join (for [t tools]
                            (str "Say (\"T`t" t "`t\"+[int][bool](Get-Command " t
                                 " -ErrorAction SilentlyContinue))\n")))
                (str/join
                 (for [id ids]
                   (str "$id=" (ps-str id) "\n"
                        "$d=\"$C\\skills\\$id\"\n"
                        "$a=Attr $d\n"
                        "if($a -eq $null){Say (\"S`t$id`tA\")}\n"
                        "elseif(IsLink $a){Say (\"S`t$id`tL\")}\n"
                        "elseif(-not (IsDir $a)){Say (\"S`t$id`tF\")}\n"
                        "else{Say (\"S`t$id`tD\")\n"
                        "foreach($f in @(Get-ChildItem -LiteralPath $d -Recurse -File -Force)){\n"
                        "$r=$f.FullName.Substring($d.Length+1).Replace('\\','/')\n"
                        "Say (\"M`t$id`t\"+(E $r)+\"`t\"+(Sha $f.FullName))}}\n"))))))

(defn- ps-move-helpers []
  (str "function Mv($a,$b){if(IsDir (Attr $a)){[IO.Directory]::Move($a,$b)}else{[IO.File]::Move($a,$b)}}\n"
       "function Park($d,$b){if((Attr $b) -ne $null){throw \"backup exists: $b\"}\n"
       "[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($b));Mv $d $b}\n"))

(defn- ps-write-tree [host stamp id files]
  (ps-wrap host
           (str (ps-move-helpers)
                "function W($r,$b,$h){$p=[IO.Path]::Combine($s,$r.Replace('/','\\'))\n"
                "[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))\n"
                "[IO.File]::WriteAllBytes($p,[Convert]::FromBase64String($b))\n"
                "if((Sha $p) -ne $h){throw \"checksum mismatch: $r\"}}\n"
                "$id=" (ps-str id) "\n"
                "$d=\"$C\\skills\\$id\"\n"
                "$b=\"$H\\.config\\agentctl\\backups\\" stamp "\\_.codex_skills_$id\"\n"
                "$s=\"$H\\.config\\agentctl\\staging\\" stamp "-$id\"\n"
                "try{\n"
                (str/join (for [{:keys [rel bytes sha]} files]
                            (str "W " (ps-str rel) " '" (b64 bytes) "' '" sha "'\n")))
                ;; two names differing only in case collapse into one file here
                "if(@(Get-ChildItem -LiteralPath $s -Recurse -File -Force).Count -ne " (count files)
                "){throw 'file count differs after writing'}\n"
                "[void][IO.Directory]::CreateDirectory(\"$C\\skills\")\n"
                "$had=(Attr $d) -ne $null\n"
                "if($had){Park $d $b}\n"
                "try{Mv $s $d}catch{if($had){Mv $b $d};throw}\n"
                "}finally{Remove-Item -LiteralPath $s -Recurse -Force -ErrorAction SilentlyContinue}\n")))

(defn- ps-park [host stamp id]
  (ps-wrap host
           (str (ps-move-helpers)
                "$id=" (ps-str id) "\n"
                "$d=\"$C\\skills\\$id\"\n"
                "if((Attr $d) -ne $null){Park $d \"$H\\.config\\agentctl\\backups\\" stamp "\\_.codex_skills_$id\"}\n")))

;; ---------------------------------------------------------------- posix sh

(defn- sh-prelude [{:keys [home]}]
  (str "set -eu\n"
       (if home
         (str "H=" (sh-quote home) "\nC=\"$H/.codex\"\n")
         "H=$HOME\nC=${CODEX_HOME:-$H/.codex}\n")
       "sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum \"$1\"; else shasum -a 256 \"$1\"; fi | cut -d' ' -f1; }\n"
       "e() { printf %s \"$1\" | base64 | tr -d '\\n'; }\n"))

(defn- sh-inventory [host tools ids]
  (str (sh-prelude host)
       "printf 'H\\t%s\\n' \"$(e \"$H\")\"\n"
       "printf 'R\\t%s\\n' \"$(e \"$C/skills\")\"\n"
       (str/join (for [t tools]
                   (str "if command -v " t " >/dev/null 2>&1; then v=1; else v=0; fi; "
                        "printf 'T\\t" t "\\t%s\\n' \"$v\"\n")))
       (str/join
        (for [id ids]
          (str "id=" (sh-quote id) "\n"
               "d=\"$C/skills/$id\"\n"
               "if [ -L \"$d\" ]; then printf 'S\\t%s\\tL\\n' \"$id\"\n"
               "elif [ -d \"$d\" ]; then printf 'S\\t%s\\tD\\n' \"$id\"\n"
               "find -L \"$d\" -type f | while IFS= read -r f; do\n"
               "printf 'M\\t%s\\t%s\\t%s\\n' \"$id\" \"$(e \"${f#\"$d\"/}\")\" \"$(sha \"$f\")\"; done\n"
               "elif [ -e \"$d\" ]; then printf 'S\\t%s\\tF\\n' \"$id\"\n"
               "else printf 'S\\t%s\\tA\\n' \"$id\"; fi\n")))
       "echo OK\n"))

(defn- mime [^bytes bs]
  (.encodeToString (Base64/getMimeEncoder 76 (.getBytes "\n")) bs))

(defn- sh-park-fn []
  (str "park() { [ ! -e \"$2\" ] && [ ! -L \"$2\" ] || { echo \"backup exists: $2\" >&2; exit 1; }\n"
       "mkdir -p \"$(dirname \"$2\")\"; mv \"$1\" \"$2\"; }\n"))

(defn- sh-write-tree [host stamp id files]
  (str (sh-prelude host)
       (sh-park-fn)
       "id=" (sh-quote id) "\n"
       "d=\"$C/skills/$id\"\n"
       "b=\"$H/.config/agentctl/backups/" stamp "/_.codex_skills_$id\"\n"
       "s=\"$H/.config/agentctl/staging/" stamp "-$id\"\n"
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
       "mkdir -p \"$C/skills\"\n"
       "had=0; if [ -e \"$d\" ] || [ -L \"$d\" ]; then had=1; park \"$d\" \"$b\"; fi\n"
       "mv \"$s\" \"$d\" || { [ $had = 0 ] || mv \"$b\" \"$d\"; exit 1; }\n"
       "echo OK\n"))

(defn- sh-park [host stamp id]
  (str (sh-prelude host)
       (sh-park-fn)
       "id=" (sh-quote id) "\n"
       "d=\"$C/skills/$id\"\n"
       "if [ -e \"$d\" ] || [ -L \"$d\" ]; then park \"$d\" \"$H/.config/agentctl/backups/" stamp "/_.codex_skills_$id\"; fi\n"
       "echo OK\n"))

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
     (let [[tag a b c] (str/split line #"\t" -1)]
       (case tag
         "H" (assoc acc :home (unb64-str a))
         "R" (assoc acc :root (unb64-str a))
         "T" (cond-> acc (= "1" b) (update :tools conj (keyword a)))
         "S" (assoc-in acc [:entries a :state] (get state-of b :absent))
         "M" (assoc-in acc [:entries a :files (unb64-str b)] c)
         acc)))
   {:tools #{} :entries {}}
   lines))

(defn inventory
  "What is on the host: `{:home :root :tools #{…} :entries {id {:state :files}}}`.
   `:files` maps each file below a skill, `/`-separated, to the SHA-256 of its
   bytes. One round trip however many skills: PowerShell takes a second to start."
  [host tools ids]
  (parse-inventory
   (exec host ((if (windows? host) ps-inventory sh-inventory) host (map name tools) ids))))

(defn write-tree!
  "Make `<skills>/<id>` hold exactly `files` (`{:rel :bytes :sha :exec?}`).
   The tree is staged and checksummed first; what it replaces is moved into the
   host's own backup directory rather than deleted, and moved back if the swap
   fails. Moving also means no recursive delete ever walks into a junction."
  [host id files]
  (when (windows? host)
    (when-let [bad (first (remove (comp windows-safe-rel? :rel) files))]
      (throw (ex-info (str "file name cannot exist on Windows: " (:rel bad)) {:rel (:rel bad)}))))
  (exec host ((if (windows? host) ps-write-tree sh-write-tree) host @run-stamp id files)))

(defn park!
  "Remove `<skills>/<id>` by moving it into the host's backup directory."
  [host id]
  (exec host ((if (windows? host) ps-park sh-park) host @run-stamp id)))

(defn separator [host] (if (windows? host) "\\" "/"))

(defn backup-dir
  "This run's backup directory on the host, given the home it reported."
  [host home]
  (str/join (separator host) [home ".config" "agentctl" "backups" @run-stamp]))
