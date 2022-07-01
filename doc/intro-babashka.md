# Writing steps in babashka

## Generate step

To generate documents, create a sequence of maps in the form `{:data {,,,} :uri "" :type "document"}`.At least one of :data or :uri must be present. Then call `srvc.bb/generate` to stream the sequence to the next review step.

This example generates 10 documents, from `{:i 1}` to `{:i 10}`:

```clojure
#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {co.insilica/bb-srvc {:mvn/version "0.5.0"}}})

(require '[srvc.bb :as sb])

(sb/generate
 (map (fn [i] {:data {:i i} :type "document"})
      (range 1 11)))
```

## Map step

`srvc.bb/map` takes a function of two arguments: The config, and a single event. It should return a sequence of events, or `nil` if the event is to be filtered out.

This example will look for a label called "even". If present, it creates a label-answer with a `true` or `false` answer depending on the value of `i`. Non-document events are passed along unchanged.

Numbers are processed as BigDecimals for consistency, so `i` is converted to a BigInteger before being checked with `even?`.

```clojure
#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {co.insilica/bb-srvc {:mvn/version "0.5.0"}}})

(require '[srvc.bb :as sb])

(sb/map
 (fn [{:keys [current_labels reviewer] :as config}
      {:keys [data hash type] :as event}]
   (let [label-hash (some (fn [{:keys [data hash]}]
                            (when (= "even" (:id data))
                              hash))
                          current_labels)]
     (if (or (not= "document" type) (not label-hash))
       [event]
       [event
        {:data {:answer (-> data :i .toBigIntegerExact even?)
                :document hash
                :label label-hash
                :reviewer reviewer
                :timestamp (sb/unix-time)}
         :type "label-answer"}]))))
```
