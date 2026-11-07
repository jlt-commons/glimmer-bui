(ns glimmer-bui.ffi
  "Raw C bindings for the BUI kit (https://github.com/ppmpreetham/BUI,
  kit/include/BUI_kit.h). A thin defcfn layer — no logic. The backend is built
  on top of these in glimmer-bui.widget / glimmer-bui.core; glimmer's own
  reconciler never sees them.

  Nodes, contexts and windows are opaque handles (plain machine addresses).
  Properties are set by string name through BK_set_num / BK_set_str and friends.

  The emit callback is installed once per context via BK_context_set_host with a
  BKHost struct built with ffi/layout; handlers reach it as jolt fns wrapped in
  foreign-callable (:collect-safe), because BUI invokes them from inside the
  blocking BK_window_run loop."
  (:require [jolt.ffi :as ffi]))

;; --- version -----------------------------------------------------------------
(ffi/defcfn version         "BK_version"         [] :int)
(ffi/defcfn version-string  "BK_version_string"  [] :string)

;; --- nodes --------------------------------------------------------------------
(ffi/defcfn node-new        "BK_node_new"        [:string] :pointer)
(ffi/defcfn node-free       "BK_node_free"       [:pointer] :void)
(ffi/defcfn node-type       "BK_node_type"       [:pointer] :string)
(ffi/defcfn node-add        "BK_node_add"        [:pointer :pointer :int] :int)
(ffi/defcfn node-remove     "BK_node_remove"     [:pointer :pointer] :void)
(ffi/defcfn node-child-count "BK_node_child_count" [:pointer] :int)
(ffi/defcfn node-child      "BK_node_child"      [:pointer :int] :pointer)
(ffi/defcfn node-parent     "BK_node_parent"     [:pointer] :pointer)
(ffi/defcfn node-rect       "BK_node_rect"       [:pointer :pointer] :void)
(ffi/defcfn node-natural    "BK_node_natural"    [:pointer :pointer] :void)
(ffi/defcfn node-invalidate "BK_node_invalidate" [:pointer] :void)
(ffi/defcfn node-relayout   "BK_node_relayout"   [:pointer] :void)
(ffi/defcfn node-set-ref    "BK_node_set_ref"    [:pointer :pointer] :void)
(ffi/defcfn node-ref        "BK_node_ref"        [:pointer] :pointer)

;; --- properties ----------------------------------------------------------------
(ffi/defcfn set-num         "BK_set_num"         [:pointer :string :double] :int)
(ffi/defcfn set-str         "BK_set_str"         [:pointer :string :string] :int)
(ffi/defcfn set-color       "BK_set_color"       [:pointer :string :pointer] :int)
(ffi/defcfn set-strs        "BK_set_strs"        [:pointer :string :pointer :int] :int)
(ffi/defcfn get-num         "BK_get_num"         [:pointer :string] :double)
(ffi/defcfn get-str         "BK_get_str"         [:pointer :string] :string)
(ffi/defcfn strs-count      "BK_strs_count"      [:pointer :string] :int)
(ffi/defcfn strs-get        "BK_strs_get"        [:pointer :string :int] :string)

;; --- images ---------------------------------------------------------------------
(ffi/defcfn node-set-image        "BK_node_set_image"        [:pointer :pointer :int :int] :void)
(ffi/defcfn node-set-image-source "BK_node_set_image_source" [:pointer :string :int] :void)
(ffi/defcfn node-set-image-frame  "BK_node_set_image_frame"  [:pointer :int] :void)

;; --- canvas (Tk Canvas / QPainter display list) -----------------------------------
(def ITEM-IMAGE 6)    ; BK_ITEM_IMAGE
(def FIT-FILL 0)      ; BK_FIT_FILL
(def FIT-CONTAIN 1)   ; BK_FIT_CONTAIN
(def FIT-COVER 2)     ; BK_FIT_COVER
(def FIT-NONE 3)      ; BK_FIT_NONE
(def FIT-SCALE-DOWN 4); BK_FIT_SCALE_DOWN

(ffi/defcfn canvas-set-item "BK_canvas_set_item" [:pointer :pointer] :void)
(ffi/defcfn canvas-add-item "BK_canvas_add_item" [:pointer :pointer] :void)
(ffi/defcfn canvas-delete    "BK_canvas_delete"    [:pointer :int] :void)
(ffi/defcfn canvas-count     "BK_canvas_count"     [:pointer] :int)

;; --- geometry -------------------------------------------------------------------
(def GEOM-NONE 0)
(def GEOM-PACK 1)
(def GEOM-GRID 2)
(def GEOM-PLACE 3)
(def ANCHOR-N 1)
(def ANCHOR-S 2)
(def ANCHOR-W 4)
(def ANCHOR-E 8)

(ffi/defcfn geom-set-manager "BK_geom_set_manager" [:pointer :int] :void)
(ffi/defcfn geom-manager     "BK_geom_manager"     [:pointer] :int)
(ffi/defcfn geom-set         "BK_geom_set"         [:pointer :string :double] :void)
(ffi/defcfn geom-get         "BK_geom_get"         [:pointer :string] :double)
(ffi/defcfn frame-grid-weight "BK_frame_grid_weight" [:pointer :int :int :double :int] :void)

;; --- context ---------------------------------------------------------------------
(ffi/defcfn context-new     "BK_context_new"     [:pointer] :pointer)
(ffi/defcfn context-free    "BK_context_free"    [:pointer] :void)
(ffi/defcfn context-set-host "BK_context_set_host" [:pointer :pointer] :void)
(ffi/defcfn context-root    "BK_context_root"    [:pointer] :pointer)
(ffi/defcfn render          "BK_render"          [:pointer :pointer :int :int :int] :int)
(ffi/defcfn needs-render    "BK_needs_render"    [:pointer] :int)

;; --- input (returned non-zero when something changed) ------------------------------
(ffi/defcfn press           "BK_press"           [:pointer :int :int :int] :int)
(ffi/defcfn release         "BK_release"         [:pointer :int :int :int] :int)
(ffi/defcfn motion          "BK_motion"          [:pointer :int :int :int] :int)
(ffi/defcfn wheel           "BK_wheel"           [:pointer :int :int :int :int] :int)
(ffi/defcfn scroll          "BK_scroll"          [:pointer :int :int :float :float :int] :int)
(ffi/defcfn key             "BK_key"             [:pointer :string :string :int] :int)
(ffi/defcfn context-menu    "BK_context_menu"    [:pointer :int :int :int] :int)
(ffi/defcfn timer           "BK_timer"           [:pointer :int] :void)
(ffi/defcfn close-popups    "BK_close_popups"    [:pointer] :void)
(ffi/defcfn set-pointer     "BK_set_pointer"     [:pointer :int :int] :void)

;; --- look / theme -------------------------------------------------------------------
(ffi/defcfn set-scale       "BK_set_scale"       [:float] :void)
(ffi/defcfn scale           "BK_scale"           [] :float)
(ffi/defcfn unit            "BK_unit"            [] :int)
(ffi/defcfn set-font        "BK_set_font"        [:string] :void)
(ffi/defcfn set-theme       "BK_set_theme"       [:string] :int)
(ffi/defcfn theme-name      "BK_theme_name"      [] :string)
(ffi/defcfn theme-color     "BK_theme_color"     [:string :pointer] :int)
(ffi/defcfn set-animations  "BK_set_animations"  [:int] :void)
(ffi/defcfn animating       "BK_animating"       [:pointer] :int)

;; --- undo ---------------------------------------------------------------------------
(ffi/defcfn undo            "BK_undo"            [:pointer] :int)
(ffi/defcfn redo            "BK_redo"            [:pointer] :int)

;; --- windows (Cocoa on macOS; main-thread only) --------------------------------------
(ffi/defcfn window-supported "BK_window_supported" [] :int)
(ffi/defcfn window-new      "BK_window_new"      [:pointer :string :int :int] :pointer)
(ffi/defcfn window-free     "BK_window_free"     [:pointer] :void)
(ffi/defcfn window-set-callbacks "BK_window_set_callbacks" [:pointer :pointer] :void)
(ffi/defcfn window-show     "BK_window_show"     [:pointer] :void)
(ffi/defcfn window-close    "BK_window_close"    [:pointer] :void)
(ffi/defcfn window-is-open  "BK_window_is_open"  [:pointer] :int)
(ffi/defcfn window-set-title "BK_window_set_title" [:pointer :string] :void)
(ffi/defcfn window-set-size "BK_window_set_size" [:pointer :int :int] :void)
(ffi/defcfn window-get-size "BK_window_get_size" [:pointer :pointer] :void)
(ffi/defcfn window-raster   "BK_window_raster"   [:pointer] :pointer)
(ffi/defcfn window-frames   "BK_window_frames"   [:pointer] :int)
(ffi/defcfn window-render-now "BK_window_render_now" [:pointer] :void)
(ffi/defcfn window-timer    "BK_window_timer"    [:pointer :int] :int)
(ffi/defcfn window-request-frame "BK_window_request_frame" [:pointer :int] :void)
(ffi/defcfn window-cancel-timer "BK_window_cancel_timer" [:pointer :int] :void)
(ffi/defcfn window-wm       "BK_window_wm"       [:pointer :string :double :double] :void)
(ffi/defcfn window-wm-get   "BK_window_wm_get"   [:pointer :string :pointer :pointer] :int)
(ffi/defcfn window-run      "BK_window_run"      [] :void :blocking)
(ffi/defcfn window-pump     "BK_window_pump"     [] :int)
(ffi/defcfn window-quit     "BK_window_quit"     [] :void)
(ffi/defcfn window-wake-init "BK_window_wake_init" [:pointer] :int)
(ffi/defcfn window-wake     "BK_window_wake"     [] :void)
(ffi/defcfn window-set-stop-check "BK_window_set_stop_check" [:pointer] :void)
(ffi/defcfn window-set-keep-alive "BK_window_set_keep_alive" [:int] :void)

;; --- helpers ---------------------------------------------------------------------
(defn null? [p] (ffi/null? p))
