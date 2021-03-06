(ns replete.core
  (:require-macros [cljs.env.macros :refer [ensure with-compiler-env]]
                   [cljs.analyzer.macros :refer [no-warn]])
  (:require [cljs.js :as cljs]
            [cljs.pprint :refer [pprint]]
            [cljs.tagged-literals :as tags]
            [cljs.tools.reader :as r]
            [cljs.tools.reader.reader-types :refer [string-push-back-reader]]
            [cljs.analyzer :as ana]
            [cljs.compiler :as c]
            [cljs.env :as env]
            [cljs.repl :as repl]
            [clojure.string :as s]
            [cljs.stacktrace :as st]
            [cljs.source-map :as sm]
            [tailrecursion.cljson :refer [cljson->clj]]
            [parinfer.indent-mode :as indent-mode]
            [parinfer.paren-mode :as paren-mode]))

(def DEBUG false)

(defonce st (cljs/empty-state))

(defn- known-namespaces
  []
  (keys (:cljs.analyzer/namespaces @st)))

(defn ^:export setup-cljs-user []
  (js/eval "goog.provide('cljs.user')")
  (js/eval "goog.require('cljs.core')"))

(def app-env (atom nil))

(defn map-keys [f m]
  (reduce-kv (fn [r k v] (assoc r (f k) v)) {} m))

(defn ^:export init-app-env [app-env]
  (reset! replete.core/app-env (map-keys keyword (cljs.core/js->clj app-env))))

(defn user-interface-idiom-ipad?
  "Returns true iff the interface idiom is iPad."
  []
  (= "iPad" (:user-interface-idiom @app-env)))

(defn repl-read-string [line]
  (r/read-string {:read-cond :allow :features #{:cljs}} line))

(defn ^:export is-readable? [line]
  (binding [r/*data-readers* tags/*cljs-data-readers*]
    (with-compiler-env st
      (try
        (repl-read-string line)
        true
        (catch :default _
          false)))))

(defn calc-x-line [text pos line]
  (let [x (s/index-of text "\n")]
    (if (or (nil? x)
          (< pos (inc x)))
      {:cursor-x    pos
       :cursor-line line}
      (recur (subs text (inc x)) (- pos (inc x)) (inc line)))))

(defn first-non-space-pos-after [text pos]
  (if (= " " (subs text pos (inc pos)))
    (recur text (inc pos))
    pos))

(defn ^:export format [text pos enter-pressed?]
  (let [formatted-text (:text ((if enter-pressed?
                                 paren-mode/format-text
                                 indent-mode/format-text)
                                text (calc-x-line text pos 0)))
        formatted-pos (if enter-pressed?
                        (first-non-space-pos-after formatted-text pos)
                        pos)]
    #js [formatted-text formatted-pos]))

(def current-ns (atom 'cljs.user))

(defn ns-form? [form]
  (and (seq? form) (= 'ns (first form))))

(def repl-specials '#{in-ns require require-macros doc})

(defn repl-special? [form]
  (and (seq? form) (repl-specials (first form))))

(def repl-special-doc-map
  '{in-ns          {:arglists ([name])
                    :doc      "Sets *cljs-ns* to the namespace named by the symbol, creating it if needed."}
    require        {:arglists ([& args])
                    :doc      "Loads libs, skipping any that are already loaded."}
    require-macros {:arglists ([& args])
                    :doc      "Similar to the require REPL special function but
                    only for macros."}
    doc            {:arglists ([name])
                    :doc      "Prints documentation for a var or special form given its name"}})

(defn- repl-special-doc [name-symbol]
  (assoc (repl-special-doc-map name-symbol)
    :name name-symbol
    :repl-special-function true))

(defn reflow [text]
  (and text
    (-> text
      (s/replace #" \n  " "")
      (s/replace #"\n  " " "))))

;; Copied from cljs.analyzer.api (which hasn't yet been converted to cljc)
(defn resolve
  "Given an analysis environment resolve a var. Analogous to
   clojure.core/resolve"
  [env sym]
  {:pre [(map? env) (symbol? sym)]}
  (try
    (ana/resolve-var env sym
      (ana/confirm-var-exists-throw))
    (catch :default _
      (ana/resolve-macro-var env sym))))

(defn extension->lang [extension]
  (if (= ".js" extension)
    :js
    :clj))

(defn load-and-callback! [path extension cb]
  (when-let [source (js/REPLETE_LOAD (str path extension))]
    (cb {:lang   (extension->lang extension)
         :source source})
    :loaded))

(defn load [{:keys [name macros path] :as full} cb]
  #_(prn full)
  (loop [extensions (if macros
                      [".clj" ".cljc"]
                      [".cljs" ".cljc" ".js"])]
    (if extensions
      (when-not (load-and-callback! path (first extensions) cb)
        (recur (next extensions)))
      (cb nil))))

(defn require [macros-ns? sym reload]
  (cljs.js/require
    {:*compiler*     st
     :*data-readers* tags/*cljs-data-readers*
     :*load-fn*      load
     :*eval-fn*      cljs/js-eval}
    sym
    reload
    {:macros-ns macros-ns?
     :verbose   (:verbose @app-env)}
    (fn [res]
      #_(println "require result:" res))))

(defn require-destructure [macros-ns? args]
  (let [[[_ sym] reload] args]
    (require macros-ns? sym reload)))

(defn load-core-source-maps! []
  (when-not (get (:source-maps @st) 'cljs.core)
    (swap! st update-in [:source-maps] merge {'cljs.core
                                              (sm/decode
                                                (cljson->clj
                                                  (js/REPLETE_LOAD "cljs/core.js.map")))})))

(defn unmunge-core-fn [munged-name]
  (s/replace munged-name #"^cljs\$core\$" "cljs.core/"))

(defn mapped-stacktrace-str
  "Given a vector representing the canonicalized JavaScript stacktrace and a map
  of library names to decoded source maps, print the ClojureScript stacktrace .
  See mapped-stacktrace."
  ([stacktrace sms]
   (mapped-stacktrace-str stacktrace sms nil))
  ([stacktrace sms opts]
   (with-out-str
     (doseq [{:keys [function file line column]}
             (st/mapped-stacktrace stacktrace sms opts)]
       (println
         (str (when function (str (unmunge-core-fn function) " "))
           "(" file (when line (str ":" line))
           (when column (str ":" column)) ")"))))))

(defn print-error
  ([error]
   (print-error error true))
  ([error include-stacktrace?]
   (let [cause (.-cause error)]
     (println (.-message cause))
     (when include-stacktrace?
       (load-core-source-maps!)
       (let [canonical-stacktrace (st/parse-stacktrace
                                    {}
                                    (.-stack cause)
                                    {:ua-product :safari}
                                    {:output-dir "file://(/goog/..)?"})]
         (println
           (mapped-stacktrace-str
             canonical-stacktrace
             (or (:source-maps @st) {})
             nil)))))))

(defn get-var
  [env sym]
  (let [var (with-compiler-env st (resolve env sym))
        var (or var
              (if-let [macro-var (with-compiler-env st
                                   (resolve env (symbol "cljs.core$macros" (name sym))))]
                (update (assoc macro-var :ns 'cljs.core)
                  :name #(symbol "cljs.core" (name %)))))]
    (if (= (namespace (:name var)) (str (:ns var)))
      (update var :name #(symbol (name %)))
      var)))

(defn- make-base-eval-opts
  []
  {:ns      @current-ns
   :context :expr
   :eval    cljs/js-eval})

(defn- process-in-ns
  [argument]
  (cljs/eval
    st
    argument
    (make-base-eval-opts)
    (fn [result]
      (if (and (map? result) (:error result))
        (print-error (:error result) false)
        (let [ns-name result]
          (if-not (symbol? ns-name)
            (println "Argument to in-ns must be a symbol.")
            (if (some (partial = ns-name) (known-namespaces))
              (reset! current-ns ns-name)
              (let [ns-form `(~'ns ~ns-name)]
                (cljs/eval
                  st
                  ns-form
                  (make-base-eval-opts)
                  (fn [{e :error}]
                    (if e
                      (print-error e false)
                      (reset! current-ns ns-name))))))))))))

(defn ^:export read-eval-print
  ([source]
   (read-eval-print source true))
  ([source expression?]
   (binding [ana/*cljs-ns* @current-ns
             *ns* (create-ns @current-ns)
             r/*data-readers* tags/*cljs-data-readers*]
     (let [expression-form (and expression? (repl-read-string source))]
       (if (repl-special? expression-form)
         (let [env (assoc (ana/empty-env) :context :expr
                                          :ns {:name @current-ns})
               argument (second expression-form)]
           (case (first expression-form)
             in-ns (process-in-ns argument)
             require (require-destructure false (rest expression-form))
             require-macros (require-destructure true (rest expression-form))
             doc (if (repl-specials argument)
                   (repl/print-doc (repl-special-doc argument))
                   (repl/print-doc
                     (let [sym argument
                           var (get-var env sym)]
                       (update var
                         :doc (if (user-interface-idiom-ipad?)
                                identity
                                reflow))))))
           (prn nil))
         (try
           (cljs/eval-str
             st
             source
             (if expression? source "File")
             (merge
               {:ns         @current-ns
                :load       load
                :eval       cljs/js-eval
                :source-map false
                :verbose    (:verbose @app-env)}
               (when expression?
                 {:context       :expr
                  :def-emits-var true}))
             (fn [{:keys [ns value error] :as ret}]
               (if expression?
                 (if-not error
                   (do
                     (prn value)
                     (when-not
                       (or ('#{*1 *2 *3 *e} expression-form)
                         (ns-form? expression-form))
                       (set! *3 *2)
                       (set! *2 *1)
                       (set! *1 value))
                     (reset! current-ns ns)
                     nil)
                   (do
                     (set! *e error))))
               (when error
                 (print-error error))))
           (catch :default e
             (print-error e))))))))
