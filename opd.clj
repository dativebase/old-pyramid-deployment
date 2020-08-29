;; OPD is a Clojure Babashka script for performing tasks related to orchestrating
;; a containerized deployment of the OLD Pyramid REST API.

(require '[clojure.java.shell :as shell]
         '[clojure.pprint :as pprint])

(defn get-dirname [path]
  (->> (str/split path #"/") butlast (str/join "/")))

(defn remove-all-trailing-slashes [path]
  (loop [path path]
    (if (= \/ (last path))
      (recur (->> path butlast (apply str)))
      path)))

(defn ensure-trailing-slash [path]
  (-> path
      remove-all-trailing-slashes
      (str "/")))

(defn path-join [& parts] (str/join "/" (map remove-all-trailing-slashes parts)))

(def script-path *file*)
(def script-dir (get-dirname script-path))
(def private-dir (path-join script-dir "private"))
(def src-dir (path-join script-dir "src"))
(def mysql-dumps-path
  (-> private-dir (path-join "old-mysqldumps") ensure-trailing-slash))
(def mysql-data-path (path-join private-dir "mysql-data"))
(def old-stores-path
  (-> private-dir (path-join "old-stores") ensure-trailing-slash))
(def html-path (path-join src-dir "nginx" "var" "www" "html"))
(def certs-path (path-join private-dir "certs"))
(def nginx-conf-path (path-join src-dir "nginx" "etc" "nginx.conf"))
(def nginx-sites-path (path-join src-dir "nginx" "etc" "old"))

(def nginx-container-certs-path "/etc/letsencrypt")

(def mysql-image-name "opd-mysql")
(def mysql-image-version "1.0")
(def mysql-image-tag (str mysql-image-name ":" mysql-image-version))
(def mysql-container-name "opd-mysql")

(def nginx-image-name "opd-nginx")
(def nginx-image-version "1.0")
(def nginx-image-tag (str nginx-image-name ":" nginx-image-version))
(def nginx-container-name "opd-nginx")

(def old-image-name "opd-old")
(def old-image-version "1.0")
(def old-image-tag (str old-image-name ":" old-image-version))

(def net-name "opd")
(def config-path "CONFIG.edn")
(def secrets-path ".SECRETS.edn")

(defn load-edn
  "Load edn from an io/reader source (filename or io/resource)."
  [source]
  (try
    (with-open [r (io/reader source)]
      (edn/read (java.io.PushbackReader. r)))
    (catch java.io.IOException e
      (printf "Couldn't open '%s': %s\n" source (.getMessage e)))
    (catch RuntimeException e
      (printf "Error parsing edn file '%s': %s\n" source (.getMessage e)))))

(defn load-context
  [env]
  {:secrets (load-edn secrets-path)
   :config (-> config-path load-edn :environments env)
   :history []})

(def docker-ps
  ["docker-compose" "ps"])

(defn print-output [{:keys [exit out err] :as o}]
  (printf "exit code %s\n" exit)
  (when (seq out) (println out))
  (when (seq err) (println "stderr:") (println err))
  o)

(defn pp-str [x] (with-out-str (pprint/pprint x)))

(defn just [x] [x nil])

(defn nothing [error] [nil error])

(defn just-then
  "Given a maybe (`[just nothing]`) and a just function that returns a value given
  the `just` as input, return that value, or a nothing (`[nil error]`) if
  `error` is truthy."
  ([maybe just-fn] (just-then maybe just-fn identity))
  ([[val error] just-fn nothing-fn]
   (if error
     (nothing (nothing-fn error))
     (just (just-fn val)))))

(defn bind
  "Call f on val if err is nil, otherwise return [nil err]
  See https://adambard.com/blog/acceptable-error-handling-in-clojure/."
  [f [val err]]
  (if (nil? err)
    (f val)
    [nil err]))

(defmacro err->>
  "Thread-last val through all fns, each wrapped in bind.
  See https://adambard.com/blog/acceptable-error-handling-in-clojure/."
  [val & fns]
  (let [fns (for [f fns] `(bind ~f))]
    `(->> [~val nil]
          ~@fns)))

(defn update-hist [old-hist {:keys [exit] :as o} cmd descr]
  (conj old-hist
        (merge o {:cmd cmd
                  :summary (if (= 0 exit)
                             (format "Succeeded: %s." descr)
                             (format "Failed: %s." descr))})))

(defn run-shell-cmd [ctx cmd descr]
  (let [{:keys [exit] :as o} (apply shell/sh cmd)]
    ((if (= 0 exit) just nothing) (update ctx :history update-hist o cmd descr))))

(defn- get-history-summary [ctx]
  (->> ctx
       :history
       (map :summary)
       (str/join "\n- ")
       (str "- ")))

(defn- get-docker-tabular-as-seq-str [cmd]
  (->> cmd
       (apply shell/sh)
       :out
       str/split-lines
       (map (fn [line] (str/split line #"( ){2,}")))))

(defn- get-docker-images-lines []
  (get-docker-tabular-as-seq-str ["docker" "images"]))

(defn- get-docker-processes-lines []
  (get-docker-tabular-as-seq-str ["docker" "ps"]))

(defn- get-docker-tabular-as-seq-map [cmd]
  (let [[header & rows] (get-docker-tabular-as-seq-str cmd)
        header (map (comp keyword #(str/replace % #" " "-") str/lower-case) header)]
    (->> rows
         (map (fn [row] (->> (interleave header row)
                             (apply hash-map)))))))

(defn get-docker-images [] (get-docker-tabular-as-seq-map ["docker" "images"]))

(defn get-docker-processes [] (get-docker-tabular-as-seq-map ["docker" "ps"]))

(defn get-docker-networks []
  (get-docker-tabular-as-seq-map ["docker" "network" "ls"]))

(defn get-docker-networks-subcommand [_] (get-docker-networks))

(defn get-docker-process-by-name [ps-name]
  (->> (get-docker-tabular-as-seq-map ["docker" "ps"])
       (filter (fn [{:keys [names]}] (= names ps-name)))
       first))

(defn get-docker-network-by-name [net-name]
  (->> (get-docker-networks)
       (filter (fn [{:keys [name]}] (= name net-name)))
       first))

(defn create-odp-network [ctx]
  (printf "Creating network %s ...\n" net-name)
  (just-then
   (run-shell-cmd ctx ["docker" "network" "create" net-name]
                  (str "Create user-defined bridge network " net-name))
   (fn [ctx] (printf "%s\nCreated network %s.\n"
                     (get-history-summary ctx) net-name))
   (fn [ctx] (printf "%s\nERROR. Failed to create network %s.\n"
                     (get-history-summary ctx) net-name))))

(defn create-odp-network-idempotent [ctx]
  (if (get-docker-network-by-name net-name)
    (println "Network" net-name "already exists")
    (create-odp-network ctx)))

(defn get-docker-images-subcommand [_] (pprint/pprint (get-docker-images)))

(defn get-docker-processes-subcommand [_] (pprint/pprint (get-docker-processes)))

(defn get-docker-process-subcommand [_ ps-name]
  (pprint/pprint (get-docker-process-by-name ps-name)))

(defn build-mysql
  "Build the MySQL Docker image"
  [ctx]
  (run-shell-cmd ctx ["docker" "build" "-t" mysql-image-tag "src/mysql"]
                 (format "Build OPD MySQL Docker image %s" mysql-image-name)))

(defn run-mysql
  "Run the mysql:1.0 image as a container named mysql in detached mode."
  [{{mysql-root-password :mysql-root-password} :secrets :as ctx}]
  (run-shell-cmd
   ctx
   ["docker" "run"
    "-d"
    "--network" net-name
    "--name" mysql-container-name
    "-e" (str "MYSQL_ROOT_PASSWORD=" mysql-root-password)
    "-v" (str mysql-dumps-path ":/dumps")
    "-v" (str mysql-data-path ":/var/lib/mysql")
    mysql-image-tag]
   (format "Create OPD MySQL Docker container %s" mysql-container-name)))

(defn mysql-already-running [ctx]
  (just (update ctx :history
                (fn [old-hist]
                  (conj old-hist
                        {:summary
                         (format "Succeeded: MySQL Docker process %s is already running."
                                 mysql-container-name)})))))

(defn run-mysql-idempotent [ctx]
  (if (get-docker-process-by-name mysql-container-name)
    (mysql-already-running ctx)
    (run-mysql ctx)))

(defn extract-port [old-stores-path old]
  (->> (path-join old-stores-path old "production.ini")
       slurp
       str/split-lines
       (filter (fn [line] (= "port = " (->> line (take 7) (apply str)))))
       first
       (drop 7)
       (apply str)
       Integer/parseInt))

(defn extract-olds-metadata
  [_]
  (->> old-stores-path
       (shell/sh "ls")
       :out
       str/trim
       str/split-lines
       (map (fn [old] {:db-name old
                       :store-path (path-join old-stores-path old)
                       :dump-name (str old ".dump")
                       :container-name (str "opd-old-" old)
                       :port (extract-port old-stores-path old)}))))

(defn extract-olds-metadata-subcommand [ctx]
  (let [olds-metadata (extract-olds-metadata ctx)]
    (pprint/pprint olds-metadata)
    olds-metadata))

(defn bootstrap-db
  "Drop an existing MySLQ db db-name and recreate it so that the OLD user encoded
  in the SECRETS config file can use it. WARNING: deletes an existing db; only
  use this if you know what you are doing."
  [{{:keys [mysql-root-password mysql-user mysql-password]} :secrets :as ctx}
   db-name]
  (let [mysql-cmds
        [(format "DROP DATABASE IF EXISTS %s;" db-name)
         (format (str "CREATE DATABASE %s DEFAULT CHARACTER SET utf8 DEFAULT"
                      " COLLATE utf8_bin;") db-name)
         (format "GRANT ALL ON %s.* TO '%s'@'%%' IDENTIFIED BY '%s';"
                 db-name mysql-user mysql-password)]
        raw-cmd (format "docker exec %s mysql -u root -p%s -e"
                        mysql-container-name mysql-root-password)
        cmd (vec (concat (str/split raw-cmd #"\s+")
                    [(str (str/join " " mysql-cmds) )]))]
    (run-shell-cmd ctx cmd (format "Create database %s" db-name))))

(defn bootstrap-db-subcommand [ctx db-name]
  (printf "Dropping and recreating MySQL db %s ...\n" db-name)
  (just-then
   (bootstrap-db ctx db-name)
   (fn [ctx] (printf "%s\nDropped and recreated %s.\n"
                     (get-history-summary ctx) db-name))
   (fn [ctx] (printf "%s\nERROR. Failed to drop and recreate db %s.\n"
                     (get-history-summary ctx) db-name))))

(defn load-dump-to-db
  [{{:keys [mysql-user mysql-password]} :secrets :as ctx} db-name dump-name]
  (let [cmd (-> "docker exec %s mysql -u %s -p%s -e"
                (format mysql-container-name mysql-user mysql-password)
                (str/split #"\s+")
                (concat [(format "use %s; source /dumps/%s;"
                                 db-name dump-name)]))]
    (run-shell-cmd ctx cmd
                   (format "Load dump file %s to database %s" dump-name db-name))))

(defn load-dump-to-db-subcommand [ctx db-name dump-name]
  (printf "Loading MySLQ dump file %s to db %s ...\n" dump-name db-name)
  (just-then
   (load-dump-to-db ctx db-name dump-name)
   (fn [ctx] (printf "%s\nLoaded dump %s to db %s.\n"
                     (get-history-summary ctx) dump-name db-name) ctx)
   (fn [ctx] (printf "%s\nERROR. Failed to load dump %s to db %s.\n"
                     (get-history-summary ctx) dump-name db-name) ctx)))

(defn bootstrap-dump
  "Create MySQL database db-name and load into it dump file dump-name."
  [ctx db-name dump-name]
  (let [db-bootstrapper (fn [ctx] (bootstrap-db ctx db-name))
        dump-loader (fn [ctx] (load-dump-to-db ctx db-name dump-name))]
    (err->> ctx
            db-bootstrapper
            dump-loader)))

(defn bootstrap-dbs [ctx]
  (loop [[{:keys [db-name dump-name]} & cet] (extract-olds-metadata ctx)]
    (let [[_ _] (bootstrap-dump ctx db-name dump-name)]
      (if cet (recur cet) (just ctx)))))

(defn bootstrap-dump-subcommand
  ([ctx db-name dump-name] (bootstrap-dump-subcommand ctx db-name dump-name false))
  ([ctx db-name dump-name ret-ctx?]
  (printf "Creating db %s and loading dump file %s into it ...\n"
          db-name dump-name)
  (just-then
   (bootstrap-dump ctx db-name dump-name)
   (fn [ctx]
     (printf "%s\nCreated %s and loaded %s.\n" (get-history-summary ctx) db-name
             dump-name)
     (when ret-ctx? ctx))
   (fn [ctx]
     (printf "%s\nERROR. Failed to create %s and load %s.\n%s"
             (get-history-summary ctx) db-name dump-name (pp-str ctx))
     (when ret-ctx? ctx)))))

(defn up-build-mysql
  [ctx]
  (err->> ctx
          build-mysql
          run-mysql-idempotent))

(defn up-build-mysql-subcommand
  [ctx]
  (println "Building a MySQL image and bringing up a container based on it ...")
  (just-then
   (up-build-mysql ctx)
   (fn [ctx] (printf "%s\nRan and built the MySQL image.\n"
                     (get-history-summary ctx)))
   (fn [ctx] (printf "%s\nERROR. Failed to run and build the MySQL image.\n"
                     (get-history-summary ctx)))))

(defn build-nginx
  "Build the Nginx Docker image"
  [ctx]
  (run-shell-cmd ctx ["docker" "build" "-t" nginx-image-tag "src/nginx"]
                 (format "Build OPD Nginx Docker image %s" nginx-image-name)))

(defn run-nginx
  "Run the nginx:1.0 image as a container named nginx in detached mode, exposing
  ports 80 and 443 to the world."
  [{{:keys [host-http-port host-https-port]} :config :as ctx}]
  (let [cmd ["docker" "run"
             "-d" "--rm"
             "--network" net-name
             "-p" (str host-http-port ":80")
             "-p" (str host-https-port ":443")
             "--name" nginx-container-name
             "-v" (str html-path ":/var/www/html")
             "-v" (str certs-path ":" nginx-container-certs-path)
             "-v" (str nginx-conf-path ":/etc/nginx/nginx.conf")
             "-v" (str nginx-sites-path ":/etc/nginx/sites-enabled/old")
             nginx-image-tag]]
    (run-shell-cmd
     ctx
     cmd
     (format "Create OPD Nginx Docker container %s" nginx-container-name))))

(defn nginx-already-running [ctx]
  (just (update ctx :history
                (fn [old-hist]
                  (conj old-hist
                        {:summary
                         (format "Succeeded: Nginx Docker process %s is already running."
                                 nginx-container-name)})))))

(def server-port-80-nginx-block
  (str/join
   "\n"
   ["server {"
    "    listen 80;"
    "    server_name do.onlinelinguisticdatabase.org;"
    ""
    "    location / {"
    "        return 301 https://$server_name$request_uri;"
    "    }"
    "}"]))

(defn get-old-nginx-location-blocks
  [{:keys [db-name container-name port]}]
  (format
   (str/join
    "\n"
    ["    location = /%s {"
     "        return 302 /%s/;"
     "    }"
     "    location /%s/ {"
     "        proxy_set_header        Host $http_host;"
     "        proxy_set_header        X-Real-IP $remote_addr;"
     "        proxy_set_header        X-Forwarded-For $proxy_add_x_forwarded_for;"
     "        proxy_set_header        X-Forwarded-Proto $scheme;"
     "        client_max_body_size    1000m;"
     "        client_body_buffer_size 128k;"
     "        proxy_connect_timeout   60s;"
     "        proxy_send_timeout      90s;"
     "        proxy_read_timeout      90s;"
     "        proxy_buffering         off;"
     "        proxy_temp_file_write_size 64k;"
     "        proxy_redirect          off;"
     "        proxy_pass              http://%s:%s/;"
     "    }"])
   db-name db-name db-name container-name port))

(defn get-olds-nginx-location-blocks [olds]
  (->> olds
       (map get-old-nginx-location-blocks)
       (str/join "\n\n")))

(defn get-container-certs-dir-path [domain]
  (path-join nginx-container-certs-path "live" domain))

(defn get-ssl-cert-path [domain]
  (path-join (get-container-certs-dir-path domain) "fullchain.pem"))

(defn get-ssl-cert-key-path [domain]
  (path-join (get-container-certs-dir-path domain) "privkey.pem"))

(defn get-server-port-443-nginx-block
  [{{:keys [domain]} :config} olds]
  (format
   (str/join
    "\n"
    ["server {"
     "    listen 443 ssl;"
     "    server_name %s;"
     "    ssl_certificate %s;"
     "    ssl_certificate_key %s;"
     ""
     "%s"
     "}"])
   domain
   (get-ssl-cert-path domain)
   (get-ssl-cert-key-path domain)
   (get-olds-nginx-location-blocks olds)))

(def nginx-config-file-warning
  (str/join
   "\n"
   ["# WARNING: This file is auto-generated by the OLD Pyramid Deployment script"
    "# opd.clj. You should not edit it by hand. It is generated by inspecting a"
    "# set of OLD instance-specific directories expected to be present under"
    "# old-pyramid-deployment/private/old-stores/. It can be generated by"
    "# Babashka by running ``bb opd.clj construct-nginx-config``."]))

(defn construct-nginx-config-content
  [ctx olds]
  (str/join
   "\n\n"
   [nginx-config-file-warning
    server-port-80-nginx-block
    (get-server-port-443-nginx-block ctx olds)]))

(defn construct-nginx-sites-config-file
  "Inspect the OLDs and construct an appropriate Nginx config file to serve those
  OLDs."
  [ctx]
  (let [olds (extract-olds-metadata ctx)
        config-content (construct-nginx-config-content ctx olds)]
    config-content))

(defn construct-nginx-sites-config-file-subcommand [ctx]
  (println (construct-nginx-sites-config-file ctx)))

(defn write-nginx-sites-config-file [ctx]
  (spit nginx-sites-path (construct-nginx-sites-config-file ctx))
  (just ctx))

(defn write-nginx-sites-config-file-subcommand [ctx]
  (printf "Writing Nginx config file at %s ...\n" nginx-sites-path)
  (spit nginx-sites-path (construct-nginx-sites-config-file ctx))
  (printf "Wrote Nginx config file to %s.\n" nginx-sites-path))

(defn run-nginx-idempotent [ctx]
  (if (get-docker-process-by-name nginx-container-name)
    (nginx-already-running ctx)
    (err->> ctx
            write-nginx-sites-config-file
            run-nginx)))

(defn up-build-nginx ctx
  [ctx]
  (err->> ctx
          build-nginx
          run-nginx-idempotent))

(defn up-build-nginx-subcommand
  [ctx]
  (println "Building an Nginx image and bringing up a container based on it ...")
  (just-then
   (up-build-nginx ctx)
   (fn [ctx] (printf "%s\nRan and built the Nginx image.\n"
                     (get-history-summary ctx)))
   (fn [ctx] (printf "%s\nERROR. Failed to run and build the Nginx image.\n"
                     (get-history-summary ctx)))))

(defn build-old
  "Build the OLD Docker image"
  [ctx]
  (println "Building the OLD Docker image ...")
  (just-then
   (run-shell-cmd ctx ["docker" "build" "-t" old-image-tag "src/old"]
                  (format "Build OPD OLD Docker image %s" old-image-tag))
   (fn [ctx] (printf "%s\nBuilt the OLD image.\n" (get-history-summary ctx)) ctx)
   (fn [ctx] (printf "%s\nERROR. Failed to build the OLD image.\n"
                     (get-history-summary ctx)) ctx)))

(defn run-old
  "Bring up an OLD Pyramid container in detached mode, exposing its unique port."
  [ctx {:keys [container-name store-path]}]
  (run-shell-cmd
   ctx
   ["docker" "run"
    "-d"
    "--network" net-name
    "--name" container-name
    "-v" (str store-path ":/app")
    old-image-tag
    "paster" "serve"
    "--pid-file=/app/old.pid"
    "--log-file=/app/paster.log"
    "/app/production.ini"]
   (format "Create OPD OLD Docker container %s" container-name)))

(defn container-already-exists [ctx {:keys [container-name]}]
  (just (update ctx :history
                (fn [old-hist]
                  (conj old-hist
                        {:summary
                         (format "Succeeded: OLD Docker process %s is already running."
                                 container-name)})))))

(defn run-old-idempotent [ctx {:keys [container-name] :as old}]
  (if (get-docker-process-by-name container-name)
    (container-already-exists ctx old)
    (run-old ctx old)))

(defn up-olds
  [ctx]
  (loop [[old & cet] (extract-olds-metadata ctx)]
    (let [[_ _] (run-old-idempotent ctx old)]
      (if cet (recur cet) (just ctx)))))

(defn bootstrap-dumps [ctx]
  (let [olds (extract-olds-metadata ctx)]
    (printf "Bootstrapping %s OLDs from their dump files.\n" (count olds))
    (loop [[{:keys [db-name dump-name]} & cet] olds]
      (println) (flush)
      (let [[_ err] (bootstrap-dump-subcommand ctx db-name dump-name true)]
        (cond
          err (printf "\nFailed to bootstrap %s OLDs from their dump files.\n"
                      (count olds))
          cet (recur cet)
          :else (printf
                 "\nSucceeded in bootstrapping %s OLDs from their dump files.\n"
                 (count olds)))))))

(defn show-config [ctx] (pprint/pprint ctx))

(defn get-old-config-paths [_]
  (->> old-stores-path
       (shell/sh "ls")
       :out
       str/trim
       str/split-lines
       (map (fn [old]
              [(keyword old) {:path (path-join old-stores-path old "production.ini")}]))
       (into {})))

(defn line-fixer [ctx old line]
  (cond
    (str/starts-with? line "host =")
    "host = 0.0.0.0"
    (str/starts-with? line "sqlalchemy.url =")
    (format "sqlalchemy.url = mysql://%s:%s@%s:3306/%s?charset=utf8"
            (get-in ctx [:secrets :mysql-user])
            (get-in ctx [:secrets :mysql-password])
            mysql-container-name
            (name old))
    :else line))

(defn construct-old-config-repair [old path ctx]
  (->> path
       slurp
       str/split-lines
       (map (partial line-fixer ctx old))
       (str/join "\n")))

(defn construct-old-config-repairs [configs ctx]
  (->> configs
       (map (fn [[old {:keys [path] :as meta}]]
              [old (assoc meta :repair (construct-old-config-repair old path ctx))]))
       (into {})))

(defn write-repairs [configs]
  (doseq [{:keys [path repair]} (vals configs)]
    (spit path repair)))

(defn repair-old-configs
  "Repair each production.ini config of each OLD under old-stores-path so that
  its host value is 0.0.0.0 and its sqlalchemy.url value is
  mysql://<MYSQL_USER>:<MYSQL_PASSWORD>@<MYSQL_CONTAINER>:3306/<OLD_DB_NAME>?charset=utf8."
  [ctx]
  (-> (get-old-config-paths ctx)
      (construct-old-config-repairs ctx)
      write-repairs))

(defn down-all-subcommand [ctx]
  (println "Bringing down all OPD Docker processes.")
  (let [old-cmds
        (->> (extract-olds-metadata ctx)
             (mapcat (fn [{:keys [container-name]}]
                       [[(format "stop the %s container" container-name)
                         ["docker" "stop" container-name]]
                        [(format "remove the %s container" container-name)
                         ["docker" "rm" container-name]]])))
        cmds (concat
              [["stop the Nginx container" ["docker" "stop" nginx-container-name]]
               ["remove the Nginx container" ["docker" "rm" nginx-container-name]]]
              old-cmds
              [["stop the MySQL container" ["docker" "stop" mysql-container-name]]
               ["remove the MySQL container" ["docker" "rm" mysql-container-name]]])]
    (doseq [[descr cmd] cmds]
      (printf "Attempting %s ...\n" descr)
      (flush)
      (just-then
       (run-shell-cmd ctx cmd descr)
       (fn [ctx] (printf "%s\nSucceded: %s.\n" (get-history-summary ctx) descr))
       (fn [ctx] (printf "%s\nFailed: %s.\n" (get-history-summary ctx) descr))))))

(defn deploy-new [ctx]
  (println "Creating network") (flush)
  (create-odp-network-idempotent ctx)
  (println "Running the MySQL container") (flush)
  (up-build-mysql-subcommand ctx)
  (println "Creating the MySQL databases for the OLDs") (flush)
  (bootstrap-dbs ctx)
  (println "Building the OLD web service image") (flush)
  (build-old ctx)
  (println "Repairing the OLD configs") (flush)
  (repair-old-configs ctx)
  (println "Running the OLD containers") (flush)
  (up-olds ctx)
  (println "Running the Nginx container") (flush)
  (up-build-nginx-subcommand ctx))

(def registry
  {:bootstrap-db bootstrap-db-subcommand
   :bootstrap-dbs bootstrap-dbs
   :bootstrap-dump bootstrap-dump-subcommand
   :bootstrap-dumps bootstrap-dumps
   :build-old build-old
   :config show-config
   :create-network create-odp-network-idempotent
   :extract-olds-metadata extract-olds-metadata-subcommand
   :get-docker-images get-docker-images-subcommand
   :get-docker-networks get-docker-networks-subcommand
   :get-docker-process get-docker-process-subcommand
   :get-docker-processes get-docker-processes-subcommand
   :load-dump load-dump-to-db-subcommand
   :up-build-mysql up-build-mysql-subcommand
   :up-build-nginx up-build-nginx-subcommand
   :up-olds up-olds
   :repair-old-configs repair-old-configs
   ;; :up-build-old up-build-old-subcommand
   :construct-nginx-config construct-nginx-sites-config-file-subcommand
   :write-nginx-config write-nginx-sites-config-file-subcommand
   :down-all down-all-subcommand
   :deploy-new deploy-new
   })

(defn main []
  (let [[subcommand environment & args] *command-line-args*
        subcommand-fn (some-> subcommand keyword registry)
        environment (or (keyword environment) :local)]
    (if subcommand-fn
      (and (apply (partial subcommand-fn (load-context environment)) args) nil)
      (printf (format "No such command %s." subcommand)))))

;; Steps for a new deploy:
;; bb opd.clj deploy-new local
;; or:
;; 1. bb opd.clj create-network
;; 2. bb opd.clj up-build-mysql local
;; 3. bb opd.clj bootstrap-dbs local
;; 4. bb opd.clj build-old local
;; 5. bb opd.clj repair-old-configs local
;; 6. bb opd.clj up-olds local
;; 7. bb opd.clj up-build-nginx local

(main)
