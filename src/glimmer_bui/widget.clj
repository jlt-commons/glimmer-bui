(ns glimmer-bui.widget
  "Hiccup -> BUI kit nodes. Where glimmer-gtk maps hiccup tags onto typed GTK
  constructors and setter calls, the kit's API is uniform — BK_node_new takes a
  type string, props are set by name through BK_set_num / BK_set_str — so most
  of this layer is classification: which keys are numbers, which are strings,
  which route to the geometry manager, and which are :on-* events.

  Events are the other way round from GTK: instead of per-widget signals, the
  kit emits everything through one BKHost.emit(data, node, kind, ...) callback
  per context. glimmer-bui.core installs that once; each node's handlers live
  in a table keyed by node address (an atom map), and the emit dispatcher looks
  them up by kind. Handlers close over reactive cells, exactly like glimmer-gtk.

  Ownership: the kit does NOT own nodes (no refcounting; BK_node_remove just
  unparks the child, BK_node_free frees recursively). The reconciler never
  revisits a removed widget, so remove-child!/replace-child! free the subtree
  here — otherwise every re-render leaks the discarded nodes.

  Layout: 'frame' children need a geometry manager (pack by default) or they
  are never shown (Tk semantics). column/row lay out children themselves."
  (:require [clojure.string :as st]
            [glimmer-bui.ffi :as bk]
            [jolt.ffi :as ffi]))

;; --- tag -> node type ---------------------------------------------------------
(def ^:private tags
  "Hiccup tag -> kit node type. column/row are the native linear containers;
  frame is Tk's free-form frame (children need a geometry manager)."
  {:vbox "column" :column "column"
   :hbox "row" :row "row"
   :frame "frame"
   :box "box"
   :panel "panel"
   :tabs "tabs" :sidetabs "sidetabs"
   :scroll "scroll"
   :label "label"
   :separator "separator"
   :progress "progress"
   :button "button"
   :toggle "toggle"
   :checkbox "checkbox"
   :radio "radio"
   :decorator "decorator"
   :entry "text" :text "text"
   :search "search"
   :hotkey "hotkey"
   :number "number"
   :slider "slider"
   :dropdown "dropdown"
   :enum "enum"
   :swatch "swatch"
   :popover "popover"
   :menubutton "menubutton"
   :menubar "menubar"
   :list "list"
   :tree "tree"
   :gridview "gridview"
   :canvas "canvas"
   :textarea "textarea"
   :image "image"
   :meter "meter"})

(defn node-type-for
  "The kit node type a hiccup tag renders as."
  [tag] (get tags tag (name tag)))

;; --- property classification ----------------------------------------------------
;; Numbers vs strings per kit_core.c's BK_set_num/BK_set_str. Everything not
;; listed here defaults to string; unknown keys return 0 from BK_set_* and the
;; kit silently ignores them.

;; Props whose name collides with a geometry key (:fill, :height) reach the node
;; under an alias; everything else uses its own name.
(def ^:private renamed-props
  "Alias -> kit key for node props whose real name is taken by geom-props:
  :stretch is a column child's `fill` (it takes the column's spare height),
  :height-units a node's `height` in widget units (canvas, lists, ramps)."
  {:stretch "fill" :height-units "height"})

(def ^:private num-props
  "Keys set through BK_set_num. flag props (visible, enabled, align, emboss,
  vertical, collapsed, bold...) are numbers: non-zero sets the flag."
  #{:value :set-value :default :min :max :precision
    :visible :enabled :align :emboss :vertical :collapsed :integer :live
    :alpha :square :directory :line :bold :icon-only :nopropagate :opaque
    :html :active-default :padding :step :rows :factor :noscrollbar
    ;; progress/meter
    :low :high :optimum
    ;; list/dropdown/enum selection
    :selected :multiple :hidden :sort :invert :mode :filter :selmode
    ;; raw event flags (canvas/scroll nodes: receive wheel/motion to the host)
    :events :viewevents
    ;; layout: a row child's share of the spare width (0 = natural width),
    ;; stack spacing, split factor, tabs page, grid columns, scroll offsets
    :weight :spacing :split :current :columns :tile :scroll :scroll-x :header
    :stretch :height-units :font-scale :cursor
    ;; tree/gridview
    :row-height})

(def ^:private color-props
  "Keys set through BK_set_color from an [r g b a] vector (0..1)."
  #{:color :bg :text-color})

(def ^:private geom-props
  "Keys routed to BK_geom_set. Pack/grid/place keys only matter for children of
  a frame; :reqwidth/:reqheight override any node's natural size."
  #{:side :fill :expand :anchor
    :padx0 :padx1 :pady0 :pady1 :ipadx :ipady
    :row :column :rowspan :columnspan :sticky
    :x :y :relx :rely :width :height :relwidth :relheight
    :reqwidth :reqheight})

(defn- set-items!
  "Set a dropdown/enum/list's items from a vector of strings (BK_set_strs)."
  [node items]
  (when (seq items)
    (with-open [a (ffi/confined-arena)]
      (let [n (count items)
            ptrs (mapv #(ffi/string->ptr a (str %)) items)
            arr (ffi/alloc a (* 8 n))]
        (dotimes [i n]
          (ffi/write arr :pointer (nth ptrs i) (* 8 i)))
        (bk/set-strs node "items" arr n)))))

(defn- set-color!
  [node k' rgba]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 4 4))]
      (ffi/write-array p :float (float-array (map float rgba)))
      (bk/set-color node k' p))))

(defn- set-image!
  "Set an image node's pixels from {:width w :height h :rgba byte-array} (RGBA,
   w*h*4 bytes). BK_node_set_image copies the buffer, so we free ours after."
  [node {:keys [width height rgba]}]
  (let [n (* width height 4)]
    (when (and width height rgba (= n (alength rgba)))
      (let [p (ffi/alloc n)]
        (ffi/write-array p rgba)
        (bk/node-set-image node p width height)
        (ffi/free p)))))

(def ^:private canvas-item-layout
  "Layout of the C BKCanvasItem struct (BUI_kit.h). Field order must match."
  (ffi/layout [:struct [[:id :int32] [:type :int32] [:coords :pointer] [:ncoords :int32]
                        [:fill [:array :float 4]] [:outline [:array :float 4]]
                        [:width :float] [:radius :float] [:size :float]
                        [:start :float] [:extent :float]
                        [:text :pointer] [:anchor :int32]
                        [:rgba :pointer] [:iw :int32] [:ih :int32]
                        [:hidden :int32] [:text-flags :int32] [:clip-on :int32]
                        [:clip [:array :float 4]]
                        [:family :pointer] [:imbuf :pointer] [:src :pointer]
                        [:fit :int32] [:frame :int32] [:video :pointer]]]))

(def ^:private item-types
  "BK_ITEM_* by keyword."
  {:rect 0 :oval 1 :line 2 :polygon 3 :text 4 :arc 5 :image 6 :roundrect 7})

(defn- write-rgba!
  "An item's pixels: a native pointer (a number) is passed as is; a byte-array
  is copied into the arena. BK_canvas_set_item copies them either way."
  [a rgba]
  (if (number? rgba)
    rgba
    (let [p (ffi/alloc a (alength rgba))]
      (ffi/write-array p rgba)
      p)))

(defn- set-canvas-item!
  "Insert or replace one canvas item (by :id) from a map:
    {:id :type (:rect :oval :line :polygon :text :arc :image :roundrect)
     :coords [x0 y0 ...] :fill [r g b a] :outline [r g b a] :width :radius
     :size :text :anchor :hidden
     ;; images: :rgba (byte-array or native pointer) :iw :ih :fit}
  BK_canvas_set_item copies coords, text and pixels, so the arena is freed after."
  [node {:keys [id type coords fill outline width radius size text anchor
                rgba iw ih fit hidden]}]
  (with-open [a (ffi/confined-arena)]
    (let [item (ffi/alloc a canvas-item-layout)
          n (count coords)
          cptr (ffi/alloc a (* 4 (max 1 n)))
          w! (fn [k v] (ffi/write-field item canvas-item-layout [k] v))]
      (ffi/write-array cptr :float (float-array (map float coords)))
      (w! :id (int (or id 0)))
      (w! :type (int (get item-types type (or type 0))))
      (w! :coords cptr)
      (w! :ncoords (int n))
      (dotimes [i 4]
        (ffi/write-field item canvas-item-layout [:fill i] (float (nth fill i 0)))
        (ffi/write-field item canvas-item-layout [:outline i] (float (nth outline i 0))))
      (w! :width (float (or width 1)))
      (w! :radius (float (or radius 0)))
      (w! :size (float (or size 0)))
      (when text (w! :text (ffi/string->ptr a (str text))))
      (w! :anchor (int (or anchor 0)))
      (w! :hidden (int (if hidden 1 0)))
      (when rgba
        (w! :rgba (write-rgba! a rgba))
        (w! :iw (int iw))
        (w! :ih (int ih))
        (w! :fit (int (or fit bk/FIT-FILL))))
      (bk/canvas-set-item node item))))

(defn- set-canvas-image!
  "Set a canvas node's image item from {:id :width :height :rgba :coords [x0 y0 x1 y1]
   :fit}. :rgba is a byte-array or a native pointer."
  [node {:keys [id width height rgba coords fit]}]
  (set-canvas-item! node {:id id :type :image :coords coords :rgba rgba
                          :iw width :ih height :fit (or fit bk/FIT-CONTAIN)}))

(defn- set-canvas-items!
  "Reconcile a canvas's display list with `items` (each with a unique :id):
  changed or new items are set, ids no longer present are deleted. `old` is the
  previously applied vector (nil on create)."
  [node old items]
  (let [before (into {} (map (juxt :id identity)) old)
        now    (into {} (map (juxt :id identity)) items)]
    (doseq [id (keys before) :when (not (contains? now id))]
      (bk/canvas-delete node (int id)))
    (doseq [it items :when (not= it (get before (:id it)))]
      (set-canvas-item! node it))))

(defn- flag->num [v] (double (cond (true? v) 1 (false? v) 0 (nil? v) 0 :else v)))

(defn- set-prop!
  "Set one prop on `node`. Numbers go to BK_set_num, strings to BK_set_str,
  colors to BK_set_color, geometry to BK_geom_set. A keyword value for a
  string prop is stringified. Special props: :items (vector of strings,
  BK_set_strs), :src (image path), :image (raw RGBA map), :canvas-image (one
  canvas image item), :canvas-items (the canvas display list, by :id). `old`
  is the value applied before (nil when none)."
  [node k v old]
  (let [k' (get renamed-props k (st/replace (name k) "-" "_"))]
    (cond
      (= k :items) (set-items! node v)
      (= k :src)   (bk/node-set-image-source node (str v) 1)  ; BK_FIT_CONTAIN
      (= k :image) (set-image! node v)
      (= k :canvas-image) (set-canvas-image! node v)
      (= k :canvas-items) (set-canvas-items! node old v)
      (contains? color-props k) (set-color! node k' v)
      (contains? geom-props k) (bk/geom-set node k' (double v))
      (contains? num-props k)  (bk/set-num node k' (flag->num v))
      :else                    (bk/set-str node k' (if (keyword? v) (name v) (str v))))))

;; --- events ----------------------------------------------------------------------
;; BK_EV_* kinds; the handler table maps node address -> {kind fn}. core's emit
;; dispatcher reads it. Kinds from BUI_kit.h's enum:
;; 1 CHANGE, 2 CLICK, 3 CALL, 4 RENAME, 5 ACTIVATE, 6 TOGGLE, 7 BROWSE,
;; 8 PRESS, 9 RELEASE, 10 DOUBLE, 11 MOTION, 12 ENTER, 13 LEAVE, 14 KEY,
;; 15 WHEEL, 16 SUBMIT, 17 VIEW, 18 SCROLL, 19 CONFIGURE, 20 KEYRELEASE.
(def event-kinds
  {:on-change  1
   :on-click   2
   :on-call    3
   :on-rename  4
   :on-activate 5
   :on-toggle  6
   :on-browse  7
   :on-press     8
   :on-release   9
   :on-double    10
   :on-motion    11
   :on-enter     12
   :on-leave     13
   :on-key       14
   :on-wheel     15
   :on-submit    16
   :on-view      17
   :on-scroll    18
   :on-configure 19
   :on-keyrelease 20})

(def events (atom event-kinds))

(declare free-tree!)

(defn register-event!
  "Map an :on-* key to a BK_EV_* kind integer."
  [event kind] (swap! events assoc event kind) nil)

(def ^:private handler-table
  "node address -> {BK_EV kind -> handler fn}. Keyed by node address because
  the kit hands back only the node pointer in emit; cleared in free-tree!."
  (atom {}))

(defn handlers-for
  "The event handler map registered for `node` (nil when none)."
  [node] (get @handler-table node))

;; node address -> the props last applied to it (minus handlers and nils), so a
;; re-render sets only what changed. Re-setting an unchanged prop is not free:
;; every numeric setter relayouts the tree, and re-setting :value snaps a
;; widget the user is dragging back to the render's value (the jitter).
(def ^:private prop-cache (atom {}))

(defn applied-props
  "The props last applied to `node` (nil once it is freed)."
  [node] (get @prop-cache node))

(defn- attach-handlers!
  "Register (or replace) the node's :on-* handlers in the handler table. Called
  on every render, so a node the reconciler reuses for another item dispatches
  to the current closure, not the one from its first render."
  [node props]
  (let [handlers (into {} (keep (fn [[k v]]
                                  (when-let [kind (get @events k)]
                                    [kind v])))
                      props)]
    (if (seq handlers)
      (swap! handler-table assoc node handlers)
      (swap! handler-table dissoc node))))

(defn- event-key? [k] (contains? @events k))

(defn- settable [props]
  (into {} (remove (fn [[k v]] (or (nil? v) (event-key? k)))) props))

(defn- apply-changed!
  "Set each prop of `props` whose value differs from `old`, then remember them."
  [node old props]
  (let [new (settable props)]
    (doseq [[k v] new :let [was (get old k)] :when (not= v was)]
      (set-prop! node k v was))
    (swap! prop-cache assoc node new)))

;; --- create / patch ----------------------------------------------------------------
(defn create!
  "Construct a fresh kit node for `tag`, apply props, register event handlers.
  Children are NOT added here — the reconciler appends them."
  [tag props]
  (let [node (bk/node-new (node-type-for tag))]
    (apply-changed! node nil props)
    (attach-handlers! node props)
    node))

(defn apply-props!
  "Re-apply props to an existing node (re-render). Only props whose value
  changed since the last render reach the kit; nil values are skipped; :on-*
  handlers are re-registered."
  [tag node props]
  (apply-changed! node (applied-props node) props)
  (attach-handlers! node props))

(defn dispose!
  "Free a node the tree no longer references."
  [node] (free-tree! node))

;; --- ownership -----------------------------------------------------------------
(defn free-tree!
  "Free `node` and its whole subtree, dropping handler-table and prop-cache
  entries. BK_node_free itself frees ref-less children recursively, so only the
  bookkeeping walks the tree; the one C call does the freeing."
  [node]
  (let [walk (fn walk [n]
               (swap! handler-table dissoc n)
               (swap! prop-cache dissoc n)
               (doseq [i (range (bk/node-child-count n))]
                 (walk (bk/node-child n i))))]
    (walk node)
    (bk/node-free node)))

;; --- container child management --------------------------------------------------------
;; frame children need a geometry manager (default: pack) or they never show.
(defn- auto-manage!
  [parent-tag child]
  (when (and (= :frame parent-tag) (= 0 (bk/geom-manager child)))
    (bk/geom-set-manager child bk/GEOM-PACK)))

(defn- container
  "Where `child` lives inside `parent`. A panel owns a body node (its first
  child) that holds its content; anything added to the panel itself is a
  header widget, so children go into the body unless they are :header."
  [parent-tag parent child]
  (if (and (= :panel parent-tag) (not (:header (applied-props child))))
    (bk/node-child parent 0)
    parent))

(defn append-child!
  [parent-tag parent child]
  (auto-manage! parent-tag child)
  (bk/node-add (container parent-tag parent child) child -1))

(defn remove-child!
  "Remove `child` from `parent` and free it — the reconciler never reuses a
  removed widget, so its subtree is dead."
  [parent-tag parent child]
  (bk/node-remove (container parent-tag parent child) child)
  (free-tree! child))

(defn replace-child!
  [parent-tag parent old new]
  (auto-manage! parent-tag new)
  (let [c (container parent-tag parent old)
        index (some (fn [i] (when (= old (bk/node-child c i)) i)) (range (bk/node-child-count c)))]
    (bk/node-remove c old)
    (free-tree! old)
    (bk/node-add (container parent-tag parent new) new (if (= c (container parent-tag parent new)) (or index -1) -1))))

(defn reorder-child!
  "Move `child` to sit immediately after `sibling` (nil = first). BK_node_add
  on an already-parented child reparents it in place at `index`, so one call
  with the target index does the move."
  [parent-tag parent child sibling]
  (let [parent (container parent-tag parent child)
        n     (bk/node-child-count parent)
        index (fn [node] (some (fn [i] (when (= node (bk/node-child parent i)) i))
                               (range n)))
        target (if sibling
                 (let [si (index sibling)
                       ci (index child)]
                   (if (and si ci (< ci si)) si (inc (or si 0))))
                 0)]
    (bk/node-add parent child (or target 0))))
