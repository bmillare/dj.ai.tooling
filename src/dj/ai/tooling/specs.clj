(ns dj.ai.tooling.specs
  "clojure.spec descriptions of the library's data contracts.

  Runtime validation in `dj.ai.tooling.edit` and `dj.ai.tooling.observe` is
  independent of these specs; they document the shapes as data and support
  instrumentation and generation. Maps are open: required keys are specified,
  unknown keys are permitted."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::relative-path (s/and string? (complement str/blank?)))

;; Patch — one ordered exact-search edit. An empty :search creates :path.
(s/def ::path ::relative-path)
(s/def ::search string?)
(s/def ::replace string?)
(s/def ::patch (s/keys :req-un [::path ::search ::replace]))
(s/def ::patches (s/coll-of ::patch))

;; Content validation rule — ordered; the first :matches? hit selects the
;; rule, whose :validators then run in order over a file's final content.
(s/def ::matches? ifn?)
(s/def ::validators (s/coll-of ifn? :kind vector?))
(s/def ::validation-rule (s/keys :req-un [::matches? ::validators]))
(s/def ::content-validation-rules
  (s/coll-of ::validation-rule :kind vector?))

;; Selector — an addressable source to observe.
(s/def ::scheme #{:file})
(s/def ::selector (s/keys :req-un [::scheme ::path]))
(s/def ::selectors (s/coll-of ::selector))

;; Snapshot — an immutable capture keeping source identity with content.
(s/def ::source ::selector)
(s/def ::content string?)
(s/def ::snapshot (s/keys :req-un [::source ::content]))
(s/def ::snapshots (s/coll-of ::snapshot))

;; Changeset — ordered changes plus the basis they were computed from. The
;; basis is keyed by path; :changes carries the first-touched order.
(s/def ::existed? boolean?)
(s/def ::before (s/nilable string?))
(s/def ::after string?)
(s/def ::basis (s/map-of ::path (s/keys :req-un [::existed? ::before])))
(s/def ::changes (s/coll-of (s/keys :req-un [::path ::after]) :kind vector?))

;; Results — every operation returns a map tagged by :status.
(s/def ::status #{:ready :rejected :snapshotted :committed})
(s/def ::type keyword?)
(s/def ::error (s/keys :req-un [::type]))
(s/def ::errors (s/coll-of ::error :kind vector? :min-count 1))
(s/def ::result
  (s/keys :req-un [::status]
          :opt-un [::basis ::changes ::errors ::snapshots]))
