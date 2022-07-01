#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {co.insilica/bb-srvc {:mvn/version "0.5.0"}}})

(require '[srvc.bb.review :as sbr])

(apply sbr/run-command *command-line-args*)

nil
