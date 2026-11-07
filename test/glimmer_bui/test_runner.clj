(ns glimmer-bui.test-runner
  "Entry point for `jolt -M:test`. Requires each glimmer-bui test namespace and
  runs clojure.test against it. Prints a summary; exits non-zero if anything
  failed (so the :test task fails CI)."
  (:require [clojure.test :as t]))

;; Surface full causes on :error — the default report swallows the throwable.
(defmethod t/report :error [m]
  (t/with-test-out
    (t/inc-report-counter :error)
    (println "\nERROR in" (t/testing-vars-str m))
    (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (when-let [e (:actual m)]
      (if (instance? Throwable e)
        (do (println "  ->" (.getName (class e)) ":" (ex-message e))
            (when-let [d (ex-data e)] (prn d))
            (when-let [c (ex-cause e)]
              (println "  caused by:" (.getName (class c)) ":" (ex-message c))))
        (prn e)))))

(defn -main [& _]
  (let [namespaces '[glimmer-bui.ffi-test glimmer-bui.widget-test glimmer-bui.core-test]
        ;; A namespace that fails to load registers no tests, so run-tests alone
        ;; would report it as a clean run; count it as a failure.
        load-errors (count (for [ns namespaces
                                 :let [e (try (require ns :reload) nil
                                              (catch Exception e e))]
                                 :when e]
                             (println "ERROR requiring" ns ":" (ex-message e))))]
    (let [{:keys [fail error]} (apply t/run-tests (map find-ns namespaces))]
      (System/exit (if (or (pos? fail) (pos? error) (pos? load-errors)) 1 0)))))
