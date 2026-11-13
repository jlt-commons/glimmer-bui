(ns glimmer-bui.core
  "The BUI backend for glimmer. Requiring this namespace installs it, after
  which glimmer's portable reconciler (glimmer.core) renders hiccup into real
  BUI kit nodes:

    (ns myapp
      (:require [glimmer.ratom :refer [atom]]
                [glimmer.core :as ui]
                [glimmer-bui.core]))          ; installs the BUI backend

    (defn -main [& _] (ui/run my-app :title \"hello\"))

  What this namespace supplies to glimmer.backend is the toolkit half of the
  seam: element creation and prop application (glimmer-bui.widget), container
  child management (with BUI's ownership rules), the emit dispatcher, the app
  loop (BKWindow + BK_window_run), and the marshalling of off-thread work onto
  that loop via BK_window_wake.

  The kit calls one BKHost.emit per context; nodes carry their :on-* handlers
  in the BKNode ref slot (a jolt atom), which the dispatcher looks up by kind."
  (:require [clojure.string :as str]
            [glimmer.backend :as b]
            [glimmer-bui.ffi :as bk]
            [glimmer-bui.widget :as w]
            [jolt.ffi :as ffi]))

;; --- the host -----------------------------------------------------------------
;; One BKHost per process. The struct is written once at load; `emit` is the
;; only callback BUI exercises in the glimmer flow (a BKWindow installs the
;; window's own request/set_cursor/warp/clipboard/schedule services and keeps
;; an already-set emit), so only that slot is filled.

(def ^:private host-layout
  (ffi/layout [:struct [[:data :pointer]
                        [:request :pointer]
                        [:set-cursor :pointer]
                        [:warp :pointer]
                        [:clipboard-get :pointer]
                        [:clipboard-set :pointer]
                        [:schedule :pointer]
                        [:emit :pointer]]]))

(def ^:private positional-kinds
  "BK_EV kinds whose handler receives (a, b): raw pointer events (position
  relative to the node), CONFIGURE (new width, height), TOGGLE (column, value)
  and SCROLL (request kind, amount)."
  #{6 8 9 10 11 18 19})

(defn- ref-string [ref]
  (cond (string? ref) ref
        (and ref (not (ffi/null? ref))) (ffi/ptr->string ref)))

(defn- keysym
  "The keysym of a key event's ref (\"text\\tkeysym\")."
  [ref]
  (some-> (ref-string ref) (str/split #"\t" 2) second))

(defn- event-mods
  "The modifier bits (BK_SHIFT 1, BK_CTRL 2, BK_ALT 4) of a raw pointer
  event's ref, the kit's \"mods button\" string (a C string, or a Clojure
  string in tests); 0 without one."
  [ref]
  (or (some->> (ref-string ref) (re-find #"^-?\d+") parse-long) 0))

(defn emit-dispatch
  "The BKHost.emit body: look up the node's handlers (registered at create! in
  glimmer-bui.widget's handler table, keyed by node address) and invoke the
  matching kind. CHANGE (1) passes the node's current numeric value, SUBMIT (16)
  its text, WHEEL (15) the delta `a` and the modifier bits (the kit turns a
  horizontal scroll into Shift + wheel), KEY/KEYRELEASE (14/20) the keysym
  and the modifier bits `a`, ACTIVATE/RENAME (5/4) the index `a`,
  positional kinds (press/release/double/motion, configure, toggle, scroll)
  `a` and `b`; every other kind passes nothing."
  [node kind ref a b]
  (when-let [handlers (w/handlers-for node)]
    (when-let [f (get handlers kind)]
      (cond
        (= kind 1)  (f (bk/get-num node "value"))
        (= kind 16) (f (bk/get-str node "value"))
        (= kind 15) (f a (event-mods ref))
        (or (= kind 14) (= kind 20)) (f (keysym ref) (long a))
        (or (= kind 4) (= kind 5)) (f a)
        (contains? positional-kinds kind) (f a b)
        :else (f)))))

(def ^:private callables (atom #{}))

(defn- retain-callable! [cb] (swap! callables conj cb) cb)

(def ^:private host-ptr (atom nil))

(defn- ensure-host!
  "Build the BKHost struct (emit wired) once per process and return its address."
  []
  (when-not @host-ptr
    (let [cb (retain-callable!
               (ffi/foreign-callable
                 (fn [_data node kind ref a b]
                   (emit-dispatch node kind ref a b))
                 [:pointer :pointer :int :pointer :double :double]
                 :void :collect-safe))]
      (let [p (ffi/alloc (ffi/sizeof host-layout))]
        (ffi/write-field p host-layout [:emit] cb)
        (reset! host-ptr p))))
  @host-ptr)

;; --- schedule (off-thread marshalling) -----------------------------------------
(def ^:private work-queue (atom []))

(defn- drain-work!
  "Run everything queued by post-to-loop. Called on the loop's thread by the
  wake callback BK_window_wake_init registered. Each thunk runs on its own: one
  that throws is reported and the rest still run (a dropped re-render would
  leave its component's pending flag set, so it would never render again)."
  []
  (let [queue (atom nil)]
    (swap! work-queue (fn [q] (reset! queue q) []))
    (doseq [thunk @queue]
      (try (thunk)
           (catch Throwable e
             (binding [*out* *err*]
               (println "glimmer-bui: error in scheduled work:" (ex-message e))
               (when-let [d (ex-data e)] (prn d))))))))

(defn- post-to-loop
  "Backend :schedule. Queue work and wake the window loop."
  [work]
  (swap! work-queue conj work)
  (bk/window-wake))

;; --- the app loop ----------------------------------------------------------------
(def ^:private current-window (atom nil))

;; Frame count of the most recent run's window, set after its loop exits
;; (smoke tests assert on it).
(defonce last-frames (atom nil))

(defn- run*
  "Create the context and window, mount the root into it, show, and run the
  blocking message loop. backend/loop-running? is set for the duration.

  :auto-quit-ms arms a one-shot timer; when :on-timer (a fn of no args) is also
  given it runs first on the tick — a smoke test mutates a ratom there to prove
  the reactive path — and its numeric result re-arms the timer instead of
  quitting (nil/0 quits)."
  [opts mount-root!]
  (let [{:keys [title width height auto-quit-ms on-timer]
         :or {title "glimmer" width 400 height 300}} opts
        root (bk/node-new "column")
        ctx   (bk/context-new root)]
    (bk/context-set-host ctx (ensure-host!))
    (if (= 1 (bk/window-supported))
      (do
        (bk/window-wake-init (retain-callable!
                               ;; :collect-safe: BUI invokes this wake callback on
                               ;; the window-loop thread while it is parked in the
                               ;; :blocking BK_window_run. drain-work! runs the
                               ;; queued re-renders, which allocate heavily and
                               ;; trigger GC — without :collect-safe that collection
                               ;; fails ("cannot collect when multiple threads are
                               ;; active", the slider crash).
                               (ffi/foreign-callable (fn [] (drain-work!)) [] :void :collect-safe)))
        (let [win (bk/window-new ctx title width height)]
          (reset! current-window win)
          (mount-root! root :column)
          (bk/window-show win)
          (when auto-quit-ms
            ;; one-shot timer that runs :on-timer (if given) then quits, via
            ;; BKWindowCallbacks.on_timer — the same path the kit's own
            ;; window_smoke.c uses. A numeric result from :on-timer re-arms.
            (let [cb-layout (ffi/layout [:struct [[:data :pointer]
                                                  [:on-timer :pointer]
                                                  [:on-close :pointer]
                                                  [:on-frame :pointer]]])
                  cb-ptr    (ffi/alloc (ffi/sizeof cb-layout))
                  on-timer* (retain-callable!
                              (ffi/foreign-callable
                                (fn [_data _win _id]
                                  (let [again (when on-timer (on-timer))]
                                    (if (number? again)
                                      (bk/window-timer _win (int again))
                                      (bk/window-quit))))
                                [:pointer :pointer :int] :void :collect-safe))]
              (ffi/write-field cb-ptr cb-layout [:on-timer] on-timer*)
              (bk/window-set-callbacks win cb-ptr)
              (bk/window-timer win auto-quit-ms)))
          (try
            (reset! b/loop-running? true)
            (bk/window-run)
            (finally
              (reset! b/loop-running? false)
              (reset! last-frames (bk/window-frames win))
              (reset! current-window nil)))
          (bk/window-free win)))
      ;; No window host (headless CI): mount the root anyway so a render can
      ;; be driven manually with BK_render into a raster.
      (do (mount-root! root :column)
          (reset! b/loop-running? true)
          (reset! b/loop-running? false)))))

(defn- run!
  "Backend entry point for glimmer.core/run.

  On macOS the Cocoa window host is main-thread only. Under `jolt nrepl-server`
  the primordial thread parks in jolt.host/park-until-interrupt (a main-thread
  pump); this hops the boot onto it ASYNCHRONOUSLY via
  jolt.host/call-on-main-thread-async and returns right away, so the nREPL eval
  that started the app completes and the session stays live for reactive edits
  (swap! a ratom to re-render). Under `jolt run` there is no pump, so it runs
  inline and blocks until the app quits."
  [opts mount-root!]
  (let [start (fn [] (run* opts mount-root!))]
    (if-let [hop (resolve 'jolt.host/call-on-main-thread-async)]
      (hop start)
      (start))))

;; --- the backend -------------------------------------------------------------------
(def backend
  "The BUI backend map handed to glimmer.backend/register!. See that namespace
  for the contract each key satisfies."
  {:name           :bui
   :create!        w/create!
   :apply-props!   w/apply-props!
   :append-child!  w/append-child!
   :remove-child!  w/remove-child!
   :replace-child! w/replace-child!
   :reorder-child! w/reorder-child!
   :dispose!       w/dispose!
   :text->element  (fn [s] [:label {:text s}])
   :schedule       post-to-loop
   :run            run!})

(defn install!
  "Make BUI the backend glimmer renders with. Called on load, so requiring this
  namespace is enough; exposed for code that wants to be explicit."
  []
  (b/register! backend)
  nil)

(defonce ^:private _installed (do (install!) true))

;; --- test hook ---------------------------------------------------------------------
(defn test-emit
  "Invoke the emit dispatcher directly (headless tests: no window loop)."
  [ctx node kind ref a b]
  (emit-dispatch node kind ref a b))
