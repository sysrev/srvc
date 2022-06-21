#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {co.insilica/bb-srvc {:mvn/version "0.1.0"}}})

(require '[srvc.bb :as sb])

(sb/generate
 (map (fn [i] {:data {:i i} :type "document"})
      (range 1 11)))
