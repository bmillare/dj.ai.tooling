(ns dj.ai.tooling.markdown
  "Server-side Markdown -> HTML for node bodies in the progress builder.

  Slimmed from dj.monitor.be.web.markdown (the reference implementation).
  Bodies are short, often LLM-generated notes, so this keeps the two safety
  halves and drops the document machinery (mermaid islands, relative-link
  resolution, link rewriting — a node body has no document root):

  1. `ESCAPE_HTML` neutralises raw HTML in the source, so a body cannot
     inject markup into the page around it.
  2. The `LinkResolver` is the URL gate, and it is ours, not flexmark's:
     flexmark drops lowercase `javascript:` but renders `JaVaScRiPt:...`
     and `data:text/html;...` straight into an href. `safe-url?` reproduces
     markdown-it's `validateLink`, hardened to strip control characters and
     Unicode spaces before the scheme test.

  A render failure is a VALUE, not an exception: `render` returns
  `{:html ... :error ...}` with the escaped source in `pre.raw-fallback`,
  so a body is never blank and never partly rendered."
  (:require [clojure.string :as str]
            [dj.web.html :as html])
  (:import [com.vladsch.flexmark.ext.autolink AutolinkExtension]
           [com.vladsch.flexmark.ext.gfm.strikethrough StrikethroughExtension]
           [com.vladsch.flexmark.ext.tables TablesExtension]
           [com.vladsch.flexmark.html HtmlRenderer LinkResolver LinkResolverFactory]
           [com.vladsch.flexmark.html.renderer LinkResolverBasicContext LinkStatus]
           [com.vladsch.flexmark.parser Parser]
           [com.vladsch.flexmark.util.data MutableDataSet]))

(def ^:private bad-scheme
  "markdown-it's `BAD_PROTO_RE`, anchored at the start of the trimmed,
  lowercased URL."
  #"^(vbscript|javascript|file|data):")

(def ^:private good-data
  "markdown-it's `GOOD_DATA_RE`: the one `data:` family allowed through,
  because an inline image is a legitimate thing to put in a note."
  #"^data:image/(gif|png|jpeg|webp);")

(def ^:private control-chars
  "ASCII control characters and every Unicode space, stripped BEFORE the
  scheme test — browsers drop them when resolving a scheme, so
  `java\\u0000script:` would otherwise slip past. No legitimate scheme
  contains one."
  #"[\p{Cntrl}\p{Zs}​-‏  ﻿]")

(defn safe-url?
  "markdown-it's `validateLink`, hardened per `control-chars`."
  [url]
  (let [s (-> (str url) str/trim (str/replace control-chars "") str/lower-case)]
    (if (re-find bad-scheme s)
      (boolean (re-find good-data s))
      true)))

(def blocked-url
  "What a rejected link's href/src becomes: an inert same-document fragment
  that exists nowhere and says why."
  "#blocked-link")

(defn- link-resolver-factory []
  (reify LinkResolverFactory
    (getAfterDependents [_] nil)
    (getBeforeDependents [_] nil)
    (affectsGlobalScope [_] false)
    (^LinkResolver apply [_ ^LinkResolverBasicContext _context]
      (reify LinkResolver
        (resolveLink [_ _node _context link]
          (let [url (.getUrl link)]
            (-> link
                (.withStatus LinkStatus/VALID)
                (.withUrl (if (safe-url? url) url blocked-url)))))))))

(defn- options ^MutableDataSet []
  (doto (MutableDataSet.)
    (.set Parser/EXTENSIONS [(TablesExtension/create)
                             (StrikethroughExtension/create)
                             (AutolinkExtension/create)])
    ;; raw HTML in the source is escaped, never passed through — half of the
    ;; safety story; the LinkResolver gate is the other half.
    (.set HtmlRenderer/ESCAPE_HTML true)))

(def ^:private parser
  (.build (Parser/builder (options))))

(def ^:private ^HtmlRenderer renderer
  (-> (HtmlRenderer/builder (options))
      (.linkResolverFactory (link-resolver-factory))
      (.build)))

(defn- fallback-html
  "The documented failure shape: escaped source, never a blank body."
  [text]
  (html/html [:pre.raw-fallback (str text)]))

(defn render
  "Markdown `text` -> `{:html <string> :error <message-or-nil>}`. An
  exception is caught and reported, not thrown: a body that fails to parse
  must still be readable."
  [text]
  (try
    {:html (.render renderer (.parse parser (str text)))}
    (catch Throwable t
      {:html (fallback-html text)
       :error (str (.getName (class t)) ": " (.getMessage t))})))
