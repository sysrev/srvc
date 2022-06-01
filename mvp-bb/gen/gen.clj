#!/usr/bin/env bb

(doseq [i (range 1 101)]
  (println (str "{\"i\":" i "}")))
