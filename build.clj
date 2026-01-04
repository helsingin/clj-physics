(ns build
  (:require [clojure.string :as string]
            [clojure.tools.build.api :as b]))

(def lib 'net.clojars.helsingin/physics)
(def version (string/trim (slurp "version.txt")))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file
          :basis basis
          :lib lib
          :version version
          :pom-file "pom.xml"}))
