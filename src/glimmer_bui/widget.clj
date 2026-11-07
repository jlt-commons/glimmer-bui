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

(def ^:private num-props
  "Keys set through BK_set_num. flag props (visible, enabled, align, emboss,
  vertical, collapsed, bold...) are numbers: non-zero sets the flag."
  #{:value :set-value :default :min :max :precision
    :visible :enabled :align :emboss :vertical :collapsed :integer :live
    :alpha :square :directory :line :bold :icon-only :nopropagate :opaque
    :html :active-default :padding :step :rows :factor
    ;; progress/meter
    :low :high :optimum
    ;; list/dropdown/enum selection
    :selected :multiple :hidden :sort :invert :mode :filter
    ;; raw event flags (canvas/scroll nodes: receive wheel/motion to the host)
    :events :viewevents
    ;; tree/gridview
    :row-height})

(def ^:private geom-props
  "Keys routed to BK_geom_set (children of frames)."
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

(defn- set-canvas-image!
  "Set a canvas node's image item from {:id :width :height :rgba :coords [x0 y0 x1 y1]
   :fit}. BK_canvas_set_item copies coords + rgba, so the arena is freed after."
  [node {:keys [id width height rgba coords fit]}]
  (with-open [a (ffi/confined-arena)]
    (let [[x0 y0 x1 y1] coords
          cptr (ffi/alloc a :float 4)
          _ (ffi/write-array cptr :float (float-array [x0 y0 x1 y1]))
          rptr (ffi/alloc a (* width height 4))
          _ (ffi/write-array rptr rgba)
          item (ffi/alloc a canvas-item-layout)]
      (ffi/write-field item canvas-item-layout [:id] (int (or id 0)))
      (ffi/write-field item canvas-item-layout [:type] bk/ITEM-IMAGE)
      (ffi/write-field item canvas-item-layout [:coords] cptr)
      (ffi/write-field item canvas-item-layout [:ncoords] 4)
      (ffi/write-field item canvas-item-layout [:rgba] rptr)
      (ffi/write-field item canvas-item-layout [:iw] (int width))
      (ffi/write-field item canvas-item-layout [:ih] (int height))
      (ffi/write-field item canvas-item-layout [:fit] (int (or fit bk/FIT-CONTAIN)))
      (bk/canvas-set-item node item))))

(defn- set-prop!
  "Set one prop on `node`. Numbers go to BK_set_num, strings to BK_set_str,
  geometry to BK_geom_set. A keyword value for a string prop is stringified.
  Special props: :items (vector of strings, BK_set_strs), :src (image path),
  :image (raw RGBA map), :canvas-image (canvas image item)."
  [node k v]
  (let [k' (st/replace (name k) "-" "_")]
    (cond
      (= k :items) (set-items! node v)
      (= k :src)   (bk/node-set-image-source node (str v) 1)  ; BK_FIT_CONTAIN
      (= k :image) (set-image! node v)
      (= k :canvas-image) (set-canvas-image! node v)
      (contains? geom-props k) (bk/geom-set node k' (double v))
      (contains? num-props k)  (bk/set-num node k' (double (cond (true? v) 1 (false? v) 0 :else v)))
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

(defn- attach-handlers!
  "Register the node's :on-* handlers in the handler table."
  [node props]
  (let [handlers (into {} (keep (fn [[k v]]
                                  (when-let [kind (get @events k)]
                                    [kind v])))
                      props)]
    (when (seq handlers)
      (swap! handler-table assoc node handlers))))

(defn- event-key? [k] (contains? @events k))

;; --- create / patch ----------------------------------------------------------------
(defn create!
  "Construct a fresh kit node for `tag`, apply props, register event handlers.
  Children are NOT added here — the reconciler appends them."
  [tag props]
  (let [node (bk/node-new (node-type-for tag))]
    (doseq [[k v] props :when (and (some? v) (not (event-key? k)))]
      (set-prop! node k v))
    (attach-handlers! node props)
    node))

(defn apply-props!
  "Re-apply props to an existing node (re-render). Skips :on-* (handlers stay
  wired from mount) and nil values."
  [tag node props]
  (doseq [[k v] props :when (and (some? v) (not (event-key? k)))]
    (set-prop! node k v)))

(defn dispose!
  "Free a node the tree no longer references."
  [node] (free-tree! node))

;; --- ownership -----------------------------------------------------------------
(defn free-tree!
  "Free `node` and its whole subtree, dropping handler-table entries.
  BK_node_free itself frees ref-less children recursively, so only the
  bookkeeping walks the tree; the one C call does the freeing."
  [node]
  (let [walk (fn walk [n]
               (swap! handler-table dissoc n)
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

(defn append-child!
  [parent-tag parent child]
  (auto-manage! parent-tag child)
  (bk/node-add parent child -1))

(defn remove-child!
  "Remove `child` from `parent` and free it — the reconciler never reuses a
  removed widget, so its subtree is dead."
  [_parent-tag parent child]
  (bk/node-remove parent child)
  (free-tree! child))

(defn replace-child!
  [_parent-tag parent old new]
  (auto-manage! _parent-tag new)
  (bk/node-remove parent old)
  (free-tree! old)
  (bk/node-add parent new -1))

(defn reorder-child!
  "Move `child` to sit immediately after `sibling` (nil = first). BK_node_add
  on an already-parented child reparents it in place at `index`, so one call
  with the target index does the move."
  [_parent-tag parent child sibling]
  (let [n     (bk/node-child-count parent)
        index (fn [node] (some (fn [i] (when (= node (bk/node-child parent i)) i))
                               (range n)))
        target (if sibling
                 (let [si (index sibling)
                       ci (index child)]
                   (if (and si ci (< ci si)) si (inc (or si 0))))
                 0)]
    (bk/node-add parent child (or target 0))))
