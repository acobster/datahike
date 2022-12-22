(ns tasks.cljdoc
  (:require [babashka.fs :as fs]
            [tasks.deploy :as deploy]
            [tasks.settings :refer [load-settings]]
            [tasks.version :refer [version-str]]
            [utils/shell :refer [docker]]))

(def tmp-dir "/tmp/cljdoc")
(def settings (load-settings))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn preview []
  (println "Creating temporary directory at" tmp-dir)
  (fs/delete-tree tmp-dir)
  (fs/create-dirs tmp-dir)

  (deploy/local)

  (println "---- cljdoc preview: ingesting datahike")
  (docker "run" "--rm"
          "--volume" "$PWD:/repo-to-import"
          "--volume" "$HOME/.m2:/root/.m2"
          "--volume" "/tmp/cljdoc:/app/data"
          "--entrypoint" "clojure" "cljdoc/cljdoc" "-A:cli" "ingest" "-p" (name (:lib settings)) "-v" (version-str)
          "--git" "/repo-to-import")

  (println "---- cljdoc preview: starting server on port 8000")
  (docker "run" "--rm" "-p" "8000:8000" "-v" "/tmp/cljdoc:/app/data"  "cljdoc/cljdoc"))
