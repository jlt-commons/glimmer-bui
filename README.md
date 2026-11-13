# glimmer-bui

The **BUI** backend for [glimmer](https://github.com/jolt-lang/glimmer), the
reactive Clojure UI framework running on
[jolt](https://github.com/jolt-lang/jolt) (Clojure on Chez Scheme, no JVM).

BUI is [Blender's UI as a standalone C library](https://github.com/ppmpreetham/BUI):
panels, number sliders, vector fields, checkboxes, color swatches — the widget
drawing and interaction ported from Blender's `interface_widgets.cc`, with
damage-driven rendering that costs nothing while idle.

With this backend, glimmer's hiccup renders into BUI kit nodes in a real
Blender-look native window:

```clojure
(ns myapp
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-bui.core]))            ; installs the BUI backend

(defonce count* (atom 0))

(defn counter []
  [:vbox {:padding 12}
   [:slider {:label "count" :min -100 :max 100 :value @count*}]
   [:hbox {}
    [:button {:text "- 1" :on-click #(swap! count* dec)}]
    [:button {:text "+ 1" :on-click #(swap! count* inc)}]
    [:button {:text "reset" :on-click #(reset! count* 0)}]]])

(defn -main [& _]
  (ui/run counter :title "counter" :width 320 :height 160))
```

Components, reactive state and reconciliation are documented in glimmer's
README. What follows is the BUI-specific part.

## Requirements

The BUI kit is not yet published as a system library; build it from source
anywhere and point `BUI_HOME` at the checkout (default `~/src/bui-src`):

```sh
export BUI_HOME=~/src/bui-src
git clone --depth 1 https://github.com/ppmpreetham/BUI.git "$BUI_HOME"
cmake -S "$BUI_HOME" -B "$BUI_HOME/build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUI_HOME/build" --parallel
jolt native    # links $BUI_HOME/build's static archives into lib/libbui.dylib
```

`jolt native` (which `jolt test`, `jolt smoke` and `jolt counter` run first)
links the kit, gpu and core archives into one shared library at `lib/` in this
project, and skips the link when that file is newer than the archives. The
`:jolt/native` entry in `deps.edn` loads `lib/libbui.dylib` (`lib/libbui.so` on
Linux) relative to this project, so it is found from consumer apps as well, and
falls back to a `libbui.dylib` / `libbui.so` on the loader's search path. The
macOS link uses the Cocoa/IOKit/CoreVideo/AVFoundation/CoreMedia frameworks;
on Linux it uses `--whole-archive` and expects the usual X11 dev packages.

**License note:** BUI is GPL-2.0-or-later; programs linking it are GPL too.

## Running

```sh
jolt test      # unit tests (kit nodes headless — no window needed)
jolt smoke     # reactivity smoke against the live window loop (needs a display)
jolt counter   # interactive counter demo (opens a window, blocks)
```

## Hiccup reference

Elements are `[:tag props? & children]`; props are an optional map, children
may be native elements, component invocations, strings or numbers (rendered as
labels).

**Containers:** `:vbox`/`:column` (vertical), `:hbox`/`:row` (horizontal),
`:frame` (Tk-style frame — children get the pack geometry manager by default,
or `:side`/`:fill`/`:expand`/`:padx0`… through `BK_geom_set`), `:panel`
(collapsible Blender panel), `:box`, `:tabs`, `:scroll`.

**Leaf widgets:** `:button` `:toggle` `:checkbox` `:radio` `:label`
`:separator` `:progress` `:entry`/`:text` `:search` `:number` `:slider`
`:dropdown` `:enum` `:swatch` `:meter` `:textarea` `:image` `:hotkey`
`:decorator`.

**Props** map straight onto the kit's string-keyed properties (see
`BUI_kit.h` and `kit_core.c`): `:text`, `:label`, `:value`, `:min`, `:max`,
`:description`, `:icon`, `:placeholder`, numeric flags (`:visible`
`:enabled` `:integer` `:bold` `:html` …), and container keys (`:padding`).
Dashes become underscores. Unknown keys are silently ignored by the kit.

Re-renders only send props whose value changed since the last render; the kit
relayouts on every numeric set, and re-sending `:value` would snap a slider the
user is dragging back to the render's value.

Layout: in a `:hbox`, `:weight` is a child's share of the spare width (0 keeps
its natural width); `:reqwidth`/`:reqheight` fix any node's natural size; in a
`:vbox`, `:stretch true` gives a child the spare height (the kit's `fill`, which
as a hiccup key belongs to the frame geometry manager); `:height-units` is the
kit's `height` (in widget units). Colors (`:bg`, `:color`, `:text-color`) take
`[r g b a]`. A `:canvas` takes `:canvas-items`, a vector of item maps keyed by
`:id` (`:type` `:rect` `:oval` `:line` `:polygon` `:text` `:image`, `:coords`,
`:fill`, `:outline`, `:width`, and for images `:rgba` (a byte-array or a native
pointer) with `:iw`/`:ih`/`:fit`); only changed items are re-sent, and ids that
disappear are deleted. `glimmer-bui.ffi/file-dialog` runs Blender's file browser.

**Events:** `:on-click`, `:on-change` (receives the node's numeric value),
`:on-submit` (text fields, receives the text), `:on-wheel` (the delta and the modifier bits: Shift 1, Ctrl 2, Alt 4; a horizontal scroll arrives as Shift + wheel),
`:on-key`/`:on-keyrelease` (the keysym, e.g. "space", and the modifier bits; nodes with `:events 1`, under the pointer when nothing has focus), `:on-activate`/`:on-rename` (the row index), and the raw pointer events
`:on-press`/`:on-release`/`:on-double`/`:on-motion`, which receive `x y`
relative to the node (set `:events 1` on the node to get them);
`:on-configure` receives the node's new `width height`. The kit funnels
everything through one `BKHost.emit` callback; handlers are registered in a
table keyed by node address and dispatched by kind.

Handlers close over reactive cells (not values), so the first render's closure
stays correct for the widget's life — exactly like glimmer-gtk.

## Architecture

Three namespaces, mirroring glimmer-gtk:

- **`glimmer-bui.ffi`** — thin `defcfn` bindings to `BK_*`. No logic.
- **`glimmer-bui.widget`** — hiccup to kit nodes: tag to node type, prop
  classification (number vs string vs geometry key), the event handler table,
  and container child management with the kit's ownership rules
  (`BK_node_free` frees recursively; removed subtrees are freed explicitly or
  they leak).
- **`glimmer-bui.core`** — the backend map handed to `glimmer.backend/register!`,
  the `BKHost` struct (emit wired through `foreign-callable`), the
  `BK_window_run` app loop with auto-quit/on-timer hooks for smoke tests, and
  `BK_window_wake` marshalling for off-thread re-renders.

## Live development

Under `jolt nrepl-server`, `ui/run` hops the window loop onto the process main
thread (Cocoa is main-thread only) and returns immediately, so the REPL session
stays live. Mutate a `defonce` reactive cell and the window repaints.

## License

MIT (see `LICENSE`) for this repository's code. The BUI kit it binds is
GPL-2.0-or-later.
