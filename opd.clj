;; OPD is a Clojure Babashka script for performing tasks related to orchestrating
;; a containerized deployment of the OLD Pyramid REST API.

(require '[clojure.java.shell :as shell])

(def docker-ps
  ["docker-compose" "ps"])

(defn list-home
  []
  (->> (apply shell/sh ["ls" "/home/rancher"])
       :out)
  )

(defn clone-old-pyramid
  []
  )


(list-home)
