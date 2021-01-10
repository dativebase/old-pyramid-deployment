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
(def mysql-pyr-dumps-path
  (-> private-dir (path-join "old-mysqldumps-pyr") ensure-trailing-slash))
(def mysql-data-path (path-join private-dir "mysql-data"))
(def mysql-pyr-data-path (path-join private-dir "mysql-pyr-data"))
(def old-stores-path
  (-> private-dir (path-join "old-stores") ensure-trailing-slash))
(def old-stores-pyr-path
  (-> private-dir (path-join "old-stores-pyr") ensure-trailing-slash))
(def html-path (path-join src-dir "nginx" "var" "www" "html"))
(def certs-path (path-join private-dir "certs"))
(def nginx-conf-path (path-join src-dir "nginx" "etc" "nginx.conf"))
(def nginx-pyr-conf-path (path-join src-dir "nginx-pyr" "etc" "nginx.conf"))
(def nginx-sites-path (path-join src-dir "nginx" "etc" "old"))
(def nginx-pyr-sites-path (path-join src-dir "nginx-pyr" "etc" "old"))
(def old-source-path (path-join src-dir "old"))
(def old-pyr-source-path (path-join src-dir "old-pyramid"))

(def nginx-container-certs-path "/etc/letsencrypt")

(def mysql-image-name "opd-mysql")
(def mysql-image-version "1.0")
(def mysql-image-tag (str mysql-image-name ":" mysql-image-version))
(def mysql-container-name "opd-mysql")

(def mysql-pyr-image-name "opd-mysql-pyr")
(def mysql-pyr-image-version "1.0")
(def mysql-pyr-image-tag (str mysql-pyr-image-name ":" mysql-pyr-image-version))
(def mysql-pyr-container-name "opd-mysql-pyr")

(def nginx-image-name "opd-nginx")
(def nginx-image-version "1.0")
(def nginx-image-tag (str nginx-image-name ":" nginx-image-version))
(def nginx-container-name "opd-nginx")

(def nginx-pyr-image-name "opd-nginx-pyr")
(def nginx-pyr-image-version "1.0")
(def nginx-pyr-image-tag (str nginx-pyr-image-name ":" nginx-pyr-image-version))
(def nginx-pyr-container-name "opd-nginx-pyr")

(def old-image-name "opd-old")
(def old-image-version "1.0")
(def old-image-tag (str old-image-name ":" old-image-version))

(def old-pyr-image-name "opd-old-pyr")
(def old-pyr-image-version "1.0")
(def old-pyr-image-tag (str old-pyr-image-name ":" old-pyr-image-version))

(def net-name "opd")
(def config-path "CONFIG.edn")
(def secrets-path ".SECRETS.edn")

(defn get-mysql-image-tag [{:keys [old-type]}]
  (if (= :pyramid old-type) mysql-pyr-image-tag mysql-image-tag))

(defn get-mysql-container-name [{:keys [old-type]}]
  (if (= :pyramid old-type) mysql-pyr-container-name mysql-container-name))

(defn get-mysql-dumps-path [{:keys [old-type]}]
  (if (= :pyramid old-type) mysql-pyr-dumps-path mysql-dumps-path))

(defn get-mysql-data-path [{:keys [old-type]}]
  (if (= :pyramid old-type) mysql-pyr-data-path mysql-data-path))

(defn get-old-stores-path [{:keys [old-type]}]
  (if (= :pyramid old-type) old-stores-pyr-path old-stores-path))

(defn get-old-image-tag [{:keys [old-type]}]
  (if (= :pyramid old-type) old-pyr-image-tag old-image-tag))

(defn get-old-source-path [{:keys [old-type]}]
  (if (= :pyramid old-type) old-pyr-source-path old-source-path))

(defn get-nginx-image-tag [{:keys [old-type]}]
  (if (= :pyramid old-type) nginx-pyr-image-tag nginx-image-tag))

(defn get-nginx-container-name [{:keys [old-type]}]
  (if (= :pyramid old-type) nginx-pyr-container-name nginx-container-name))

(defn get-nginx-sites-path [{:keys [old-type]}]
  (if (= :pyramid old-type) nginx-pyr-sites-path nginx-sites-path))

(defn get-nginx-conf-path [{:keys [old-type]}]
  (if (= :pyramid old-type) nginx-pyr-conf-path nginx-conf-path))

(defn get-nginx-source-path [{:keys [old-type]}]
  (if (= :pyramid old-type) "src/nginx-pyr" "src/nginx"))

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

(defn load-config [env]
  (let [whole (-> config-path load-edn)
        {:keys [configuration] :as config} (-> whole :environments env)]
    (assoc config :configuration (get-in whole [:configurations configuration]))))

(defn load-context
  [env]
  {:secrets (load-edn secrets-path)
   :config (load-config env)
   :env env
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
    ((if (= 0 exit) just nothing)
     (do
       (when (not= 0 exit) (pprint/pprint o))
       (update ctx :history update-hist o cmd descr)))))

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

(defn create-opd-network [ctx]
  (printf "Creating network %s ...\n" net-name)
  (just-then
   (run-shell-cmd ctx ["docker" "network" "create" net-name]
                  (str "Create user-defined bridge network " net-name))
   (fn [ctx] (printf "%s\nCreated network %s.\n"
                     (get-history-summary ctx) net-name))
   (fn [ctx] (printf "%s\nERROR. Failed to create network %s.\n"
                     (get-history-summary ctx) net-name))))

(defn create-opd-network-idempotent [ctx]
  (if (get-docker-network-by-name net-name)
    (println "Network" net-name "already exists")
    (create-opd-network ctx)))

(defn get-docker-images-subcommand [_] (pprint/pprint (get-docker-images)))

(defn get-docker-processes-subcommand [_] (pprint/pprint (get-docker-processes)))

(defn get-docker-process-subcommand [_ ps-name]
  (pprint/pprint (get-docker-process-by-name ps-name)))

(defn get-mysql-docker-path [{:keys [old-type]}]
  (if (= :pyramid old-type) "src/mysql-pyr" "src/mysql"))

(defn build-mysql
  "Build the MySQL Docker image"
  [ctx]
  (let [image-tag (get-mysql-image-tag ctx)
        docker-path (get-mysql-docker-path ctx)]
    (println "docker" "build" "-t" image-tag docker-path)
    (run-shell-cmd ctx ["docker" "build" "-t" image-tag docker-path]
                   (format "Build OPD MySQL Docker image %s" image-tag))))

(defn run-mysql
  "Run the mysql:1.0 image as a container named mysql in detached mode."
  [{{mysql-root-password :mysql-root-password} :secrets :as ctx}]
  (let [container-name (get-mysql-container-name ctx)
        dumps-path (get-mysql-dumps-path ctx)
        data-path (get-mysql-data-path ctx)
        image-tag (get-mysql-image-tag ctx)]
    (run-shell-cmd
     ctx
     ["docker" "run"
      "-d"
      "--network" net-name
      "--name" container-name
      "-e" (str "MYSQL_ROOT_PASSWORD=" mysql-root-password)
      "-v" (str dumps-path ":/dumps")
      "-v" (str data-path ":/var/lib/mysql")
      image-tag]
     (format "Create OPD MySQL Docker container %s" container-name))))

(defn mysql-already-running [ctx]
  (just (update ctx :history
                (fn [old-hist]
                  (conj old-hist
                        {:summary
                         (format "Succeeded: MySQL Docker process %s is already running."
                                 (get-mysql-container-name ctx))})))))

(defn run-mysql-idempotent [ctx]
  (let [container-name (get-mysql-container-name ctx)]
    (if (get-docker-process-by-name container-name)
      (mysql-already-running ctx)
      (run-mysql ctx))))

(defn extract-port [old-stores-path old]
  (->> (path-join old-stores-path old "production.ini")
       slurp
       str/split-lines
       (filter (fn [line] (= "port = " (->> line (take 7) (apply str)))))
       first
       (drop 7)
       (apply str)
       Integer/parseInt))

(defn get-old-container-name [{:keys [old-type]} old-name]
  (if (= :pyramid old-type)
    (str "opd-old-pyr-" old-name)
    (str "opd-old-" old-name)))

(defn extract-olds-metadata
  [ctx]
  (let [old-stores-path (get-old-stores-path ctx)]
    (->> old-stores-path
         (shell/sh "ls")
         :out
         str/trim
         str/split-lines
         (map (fn [old] {:db-name old
                         :store-path (path-join old-stores-path old)
                         :dump-name (str old ".dump")
                         :container-name (get-old-container-name ctx old)
                         :port (extract-port old-stores-path old)})))))

(defn extract-olds-metadata-subcommand [ctx]
  (let [olds-metadata (extract-olds-metadata ctx)]
    (pprint/pprint olds-metadata)
    olds-metadata))

(defn extract-olds-pyr-metadata-subcommand [ctx]
  (extract-olds-metadata-subcommand (assoc ctx :old-type :pyramid)))

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
                        (get-mysql-container-name ctx) mysql-root-password)
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
                (format (get-mysql-container-name ctx) mysql-user mysql-password)
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

(defn bootstrap-dbs-pyr [ctx]
  (bootstrap-dbs (assoc ctx :old-type :pyramid)))

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

(defn up-build-mysql-pyr-subcommand [ctx]
  (up-build-mysql-subcommand (assoc ctx :old-type :pyramid)))

(defn build-nginx
  "Build the Nginx Docker image"
  [ctx]
  (run-shell-cmd ctx ["docker" "build" "-t" (get-nginx-image-tag ctx)
                      (get-nginx-source-path ctx)]
                 (format "Build OPD Nginx Docker image %s" (get-nginx-image-tag ctx))))

(defn run-nginx
  "Run the nginx:1.0 image as a container named nginx in detached mode, exposing
  ports 80 and 443 to the world."
  [{{:keys [host-http-port host-https-port]} :config :as ctx}]
  (let [cmd ["docker" "run"
             "-d" "--rm"
             "--network" net-name
             "-p" (str host-http-port ":80")
             "-p" (str host-https-port ":443")
             "--name" (get-nginx-container-name ctx)
             "-v" (str html-path ":/var/www/html")
             "-v" (str certs-path ":" nginx-container-certs-path)
             "-v" (str (get-nginx-conf-path ctx) ":/etc/nginx/nginx.conf")
             "-v" (str (get-nginx-sites-path ctx) ":/etc/nginx/sites-enabled/old")
             (get-nginx-image-tag ctx)]]
    (run-shell-cmd
     ctx
     cmd
     (format "Create OPD Nginx Docker container %s" (get-nginx-container-name ctx)))))

(defn nginx-already-running [ctx]
  (just (update ctx :history
                (fn [old-hist]
                  (conj old-hist
                        {:summary
                         (format "Succeeded: Nginx Docker process %s is already running."
                                 (get-nginx-container-name ctx))})))))

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
  [old-type {:keys [db-name container-name port]}]
  (let [proxy (if (= :pyramid old-type)
                (format "http://%s:8000/%s/" container-name db-name)
                (format "http://%s:%s/" container-name port))]
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
       "        proxy_pass              %s;"
       "    }"])
     db-name db-name db-name proxy)))

(defn get-olds-nginx-location-blocks [old-type olds]
  (->> olds
       (map (partial get-old-nginx-location-blocks old-type))
       (str/join "\n\n")))

(defn get-container-certs-dir-path [domain]
  (path-join nginx-container-certs-path "live" domain))

(defn get-ssl-cert-path [domain]
  (path-join (get-container-certs-dir-path domain) "fullchain.pem"))

(defn get-ssl-cert-key-path [domain]
  (path-join (get-container-certs-dir-path domain) "privkey.pem"))

(defn get-server-port-443-nginx-block
  [{{:keys [domain]} :config old-type :old-type} olds]
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
   (get-olds-nginx-location-blocks old-type olds)))

(defn get-server-port-80-nginx-block
  [{{:keys [domain]} :config old-type :old-type} olds]
  (format
   (str/join
    "\n"
    ["server {"
     "    listen 80;"
     "    server_name %s;"
     ""
     "%s"
     "}"])
   domain
   (get-olds-nginx-location-blocks old-type olds)))

(def nginx-config-file-warning
  (str/join
   "\n"
   ["# WARNING: This file is auto-generated by the OLD Pyramid Deployment script"
    "# opd.clj. You should not edit it by hand. It is generated by inspecting a"
    "# set of OLD instance-specific directories expected to be present under"
    "# old-pyramid-deployment/private/old-stores/. It can be generated by"
    "# Babashka by running ``bb opd.clj construct-nginx-config``."]))

(defn http-nginx-config [ctx olds]
  (str/join
   "\n\n"
   [nginx-config-file-warning
    (get-server-port-80-nginx-block ctx olds)]))

(defn https-nginx-config [ctx olds]
  (str/join
   "\n\n"
   [nginx-config-file-warning
    server-port-80-nginx-block
    (get-server-port-443-nginx-block ctx olds)]))

(defn construct-nginx-config-content
  [ctx olds]
  (if (= :local (:env ctx))
    (http-nginx-config ctx olds)
    (https-nginx-config ctx olds)))

(defn construct-nginx-sites-config-file
  "Inspect the OLDs and construct an appropriate Nginx config file to serve those
  OLDs."
  [ctx]
  (construct-nginx-config-content ctx (extract-olds-metadata ctx)))

(defn construct-nginx-sites-config-file-subcommand [ctx]
  (println (construct-nginx-sites-config-file ctx)))

(defn write-nginx-sites-config-file [ctx]
  (spit (get-nginx-sites-path ctx) (construct-nginx-sites-config-file ctx))
  (just ctx))

(defn write-nginx-sites-config-file-subcommand [ctx]
  (printf "Writing Nginx config file at %s ...\n" nginx-sites-path)
  (spit nginx-sites-path (construct-nginx-sites-config-file ctx))
  (printf "Wrote Nginx config file to %s.\n" nginx-sites-path))

(defn run-nginx-idempotent [ctx]
  (if (get-docker-process-by-name (get-nginx-container-name ctx))
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

(defn up-build-nginx-subcommand-pyr [ctx]
  (up-build-nginx-subcommand (assoc ctx :old-type :pyramid)))

(defn build-old
  "Build the OLD Docker image"
  [ctx]
  (println "Building the OLD Docker image ...")
  (just-then
   (run-shell-cmd
    ctx
    ["docker" "build" "-t" (get-old-image-tag ctx) (get-old-source-path ctx)]
    (format "Build OPD OLD Docker image %s" (get-old-image-tag ctx)))
   (fn [ctx] (printf "%s\nBuilt the OLD image.\n" (get-history-summary ctx)) ctx)
   (fn [ctx] (printf "%s\nERROR. Failed to build the OLD image.\n"
                     (get-history-summary ctx)) ctx)))

(defn build-old-specify
  [{{{{{o-name :name version :version o-type :type} :old-server} :images}
     :configuration} :config :as ctx}]
  (println "Building the OLD Server Docker image ...")
  (let [cmd
        ["docker" "build" "-t"
         (format "%s:%s" o-name version)
         (get-old-source-path {:old-type o-type})]]
    (println (str/join " " cmd))
    #_(just-then
     (run-shell-cmd ctx cmd
                    (format "Build OLD Server Docker image %s\n\n%s\n\n"
                            (get-old-image-tag ctx) (str/join " " cmd)))
     (fn [ctx] (printf "%s\nBuilt the OLD Server Docker image.\n" (get-history-summary ctx)) ctx)
     (fn [ctx] (printf "%s\nERROR. Failed to build the OLD Server Docker image.\n"
                       (get-history-summary ctx)) ctx))


    (pprint/pprint cmd)))

(defn build-old-pyr
  "Build the OLD Pyramid Docker image"
  [ctx]
  (build-old (assoc ctx :old-type :pyramid)))

(defn run-old-pyl
  "Bring up an OLD Pylons container in detached mode, exposing its unique port."
  [ctx {:keys [container-name store-path]}]
  (run-shell-cmd
   ctx
   ["docker" "run"
    "-d"
    "--network" net-name
    "--name" container-name
    "-v" (str store-path ":/app")
    (get-old-image-tag ctx)
    "paster" "serve"
    "--pid-file=/app/old.pid"
    "--log-file=/app/paster.log"
    "/app/production.ini"]
   (format "Create OPD OLD Pylons Docker container %s" container-name)))

(defn run-old-pyr
  "Bring up an OLD Pyramid container in detached mode, exposing its unique port."
  [ctx {:keys [container-name store-path]}]
  (run-shell-cmd
   ctx
   ["docker" "run"
    "-d"
    "--network" net-name
    "--name" container-name
    "-v" (str store-path ":/app")
    (get-old-image-tag ctx)
    "/venv/bin/pserve" "config.ini", "http_port=8000", "http_host=0.0.0.0"]
   (format "Create OPD OLD Pyramid Docker container %s" container-name)))

(defn run-old [{:keys [old-type] :as ctx} old]
  (if (= :pyramid old-type)
    (run-old-pyr ctx old)
    (run-old-pyl ctx old)))

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

(defn up-olds-pyr [ctx] (up-olds (assoc ctx :old-type :pyramid)))

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

(defn get-old-config-paths [ctx]
  (->> (get-old-stores-path ctx)
       (shell/sh "ls")
       :out
       str/trim
       str/split-lines
       (map (fn [old]
              [(keyword old) {:path (path-join (get-old-stores-path ctx)
                                               old "production.ini")}]))
       (into {})))

(defn line-fixer [ctx old line]
  (cond
    (str/starts-with? line "host =")
    "host = 0.0.0.0"
    (str/starts-with? line "sqlalchemy.url =")
    (format "sqlalchemy.url = mysql://%s:%s@%s:3306/%s?charset=utf8"
            (get-in ctx [:secrets :mysql-user])
            (get-in ctx [:secrets :mysql-password])
            (get-mysql-container-name ctx)
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

(defn repair-old-pyr-configs [ctx]
  (repair-old-configs (assoc ctx :old-type :pyramid)))

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

(defn deploy-new-pylons
  "Deploy a new Pylons OLD system. This can be called from the command-line via:

      $ bb opd.clj deploy-new-pylons <ENV>

  where <ENV> is local or dev, which determines which config in CONFIG.edn is
  used. This is equivalent to running the following commands in sequence:

      $ bb opd.clj create-network <ENV>
      $ bb opd.clj up-build-mysql <ENV>
      $ bb opd.clj bootstrap-dbs <ENV>
      $ bb opd.clj build-old <ENV>
      $ bb opd.clj repair-old-configs <ENV>
      $ bb opd.clj up-olds <ENV>
      $ bb opd.clj up-build-nginx <ENV>
  "
  [ctx]
  (println "Creating a new Pylons OLD Deployment") (flush)
  (println "Creating network") (flush)
  (create-opd-network-idempotent ctx)
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

(defn deploy-new-pyramid
  "Deploy a new Pyramid OLD system. This can be called from the command-line via:

      $ bb opd.clj deploy-new-pyramid <ENV>

  where <ENV> is local or dev, which determines which config in CONFIG.edn is
  used. This is equivalent to running the following commands in sequence:

      $ bb opd.clj create-network <ENV>
      $ bb opd.clj up-build-mysql-pyr <ENV>
      $ bb opd.clj bootstrap-dbs-pyr <ENV>
      $ bb opd.clj build-old-pyr <ENV>
      $ bb opd.clj repair-old-pyr-configs <ENV>
      $ bb opd.clj up-olds-pyr <ENV>
      $ bb opd.clj up-build-nginx-pyr <ENV>
  "
  [ctx]
  (println "Creating a new Pyramid OLD Deployment") (flush)
  (println "Creating network") (flush)
  (create-opd-network-idempotent ctx)
  (println "Running the MySQL container") (flush)
  (up-build-mysql-pyr-subcommand ctx)
  (println "Creating the MySQL databases for the OLDs") (flush)
  (bootstrap-dbs-pyr ctx)
  (println "Building the OLD web service image") (flush)
  (build-old-pyr ctx)
  (println "Repairing the OLD configs") (flush)
  (repair-old-pyr-configs ctx)
  (println "Running the OLD containers") (flush)
  (up-olds-pyr ctx)
  (println "Running the Nginx container") (flush)
  (up-build-nginx-subcommand-pyr ctx))

(def registry
  {:bootstrap-db bootstrap-db-subcommand
   :bootstrap-dbs bootstrap-dbs
   :bootstrap-dbs-pyr bootstrap-dbs-pyr
   :bootstrap-dump bootstrap-dump-subcommand
   :bootstrap-dumps bootstrap-dumps
   :build-old build-old
   :build-old-pyr build-old-pyr
   :build-old-specify build-old-specify
   :config show-config
   :create-network create-opd-network-idempotent
   :extract-olds-metadata extract-olds-metadata-subcommand
   :extract-olds-pyr-metadata extract-olds-pyr-metadata-subcommand
   :get-docker-images get-docker-images-subcommand
   :get-docker-networks get-docker-networks-subcommand
   :get-docker-process get-docker-process-subcommand
   :get-docker-processes get-docker-processes-subcommand
   :load-dump load-dump-to-db-subcommand
   :up-build-mysql up-build-mysql-subcommand
   :up-build-mysql-pyr up-build-mysql-pyr-subcommand
   :up-build-nginx up-build-nginx-subcommand
   :up-build-nginx-pyr up-build-nginx-subcommand-pyr
   :up-olds up-olds
   :up-olds-pyr up-olds-pyr
   :repair-old-configs repair-old-configs
   :repair-old-pyr-configs repair-old-pyr-configs
   ;; :up-build-old up-build-old-subcommand
   :construct-nginx-config construct-nginx-sites-config-file-subcommand
   :write-nginx-config write-nginx-sites-config-file-subcommand
   :down-all down-all-subcommand
   :deploy-new-pylons deploy-new-pylons
   :deploy-new-pyramid deploy-new-pyramid
   })

(defn main []
  (let [[subcommand environment & args] *command-line-args*
        subcommand-fn (some-> subcommand keyword registry)
        environment (or (keyword environment) :local)]
    (if subcommand-fn
      (and (apply (partial subcommand-fn (load-context environment)) args) nil)
      (printf (format "No such command %s." subcommand)))))

(main)
