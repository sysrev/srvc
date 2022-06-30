#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {co.insilica/bb-srvc {:mvn/version "0.4.0"}}})

(require '[srvc.bb :as sb])

(sb/map
 (fn [_config {:keys [hash] :as event}]
   (let [event (if hash event (sb/add-hash event))]
     [event])))
