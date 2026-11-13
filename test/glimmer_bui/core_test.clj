(ns glimmer-bui.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer-bui.widget :as w]
            [glimmer-bui.core :as c]
            [glimmer-bui.ffi :as bk]))
;; Headless: no window host, no context render. The context/emit dispatch is
;; exercised with direct BK_context calls.

(deftest emit-dispatch
  (testing "an emit with a handler attached to the node invokes it"
    (let [result (atom nil)
          n (w/create! :button {:text "b" :on-click (fn [] (reset! result :clicked))})
          root (bk/node-new "column")
          _ (bk/node-add root n -1)
          ctx (bk/context-new root)]
      (c/test-emit ctx n 2 nil 0.0 0.0) ; BK_EV_CLICK
      (is (= :clicked @result))
      (bk/context-free ctx)
      (bk/node-free root)))) ; frees n recursively

(deftest emit-with-value
  (testing "on-change receives the node's current value"
    (let [result (atom nil)
          n (w/create! :slider {:min 0 :max 10 :value 2.5 :on-change (fn [v] (reset! result v))})
          root (bk/node-new "column")
          _ (bk/node-add root n -1)
          ctx (bk/context-new root)]
      (c/test-emit ctx n 1 nil 0.0 0.0) ; BK_EV_CHANGE
      (is (= 2.5 @result))
      (bk/context-free ctx)
      (bk/node-free root)))) ; frees n recursively

(deftest emit-wheel
  (testing "on-wheel receives the delta and the modifier keys"
    (let [result (atom nil)
          n (w/create! :canvas {:on-wheel (fn [d mods] (reset! result [d mods]))})
          root (bk/node-new "column")
          _ (bk/node-add root n -1)
          ctx (bk/context-new root)]
      (c/test-emit ctx n 15 nil -3.0 0.0) ; BK_EV_WHEEL, a = delta
      (is (= [-3.0 0] @result))
      ;; the kit reports a horizontal scroll as Shift + wheel ("mods button")
      (c/test-emit ctx n 15 "1 0" 1.0 0.0)
      (is (= [1.0 1] @result))
      (bk/context-free ctx)
      (bk/node-free root))))

(deftest emit-keys
  (testing "on-key and on-keyrelease receive the keysym and the modifier bits"
    (let [seen (atom [])
          n (w/create! :canvas {:events 1
                                :on-key (fn [k mods] (swap! seen conj [:down k mods]))
                                :on-keyrelease (fn [k mods] (swap! seen conj [:up k mods]))})
          root (bk/node-new "column")
          _ (bk/node-add root n -1)
          ctx (bk/context-new root)]
      (c/test-emit ctx n 14 " \tspace" 2.0 0.0) ; BK_EV_KEY: ref "text\tkeysym", a = mods
      (c/test-emit ctx n 20 "\tspace" 0.0 0.0)  ; BK_EV_KEYRELEASE: ref "\tkeysym"
      (is (= [[:down "space" 2] [:up "space" 0]] @seen))
      (bk/context-free ctx)
      (bk/node-free root))))

(defn- emit-on [props kind a b]
  (let [result (atom nil)
        n (w/create! :canvas (assoc props :events 1))
        root (bk/node-new "column")
        _ (bk/node-add root n -1)
        ctx (bk/context-new root)]
    (c/test-emit ctx n kind nil a b)
    (bk/context-free ctx)
    (bk/node-free root)
    result))

(deftest emit-pointer-positions
  (testing "raw pointer events pass the node-relative position (a, b)"
    (doseq [[k kind] [[:on-press 8] [:on-release 9] [:on-double 10] [:on-motion 11]]]
      (let [seen (atom nil)]
        @(emit-on {k (fn [x y] (reset! seen [k x y]))} kind 12.0 34.0)
        (is (= [k 12.0 34.0] @seen)))))
  (testing "configure passes the new width and height"
    (let [seen (atom nil)]
      (emit-on {:on-configure (fn [w h] (reset! seen [w h]))} 19 640.0 480.0)
      (is (= [640.0 480.0] @seen)))))

(deftest drain-isolates-failures
  (testing "a throwing re-render doesn't drop the work queued after it"
    (let [ran (atom [])]
      (swap! @#'c/work-queue into [#(swap! ran conj 1)
                                   #(throw (ex-info "boom" {}))
                                   #(swap! ran conj 3)])
      (binding [*err* (java.io.StringWriter.)]
        (#'c/drain-work!))
      (is (= [1 3] @ran)))))

(deftest key-bindings
  (testing "a bound combo calls its handler wherever the pointer is"
    (let [hits (atom [])
          root (bk/node-new "column")
          ctx (bk/context-new root)]
      (bk/context-set-host ctx (#'c/ensure-host!))
      (c/bind-key! "ctrl+e" #(swap! hits conj :export))
      (c/bind-key! "shift+ctrl+z" #(swap! hits conj :redo))
      (c/attach-key-bindings! ctx)
      (bk/key ctx "" "e" 2)             ; Ctrl (Command on macOS) + E
      (bk/key ctx "" "Z" 3)             ; Shift + Ctrl + Z
      (bk/key ctx "e" "e" 0)            ; plain E: not bound
      (is (= [:export :redo] @hits))
      (testing "rebinding a combo replaces its handler, without binding it twice"
        (c/bind-key! "ctrl+e" #(swap! hits conj :export2))
        (bk/key ctx "" "e" 2)
        (is (= [:export :redo :export2] @hits)))
      (testing "a combo bound after the context is attached works too"
        (c/bind-key! "ctrl+o" #(swap! hits conj :open))
        (bk/key ctx "" "o" 2)
        (is (= :open (peek @hits))))
      (testing "an unbound combo does nothing"
        (c/unbind-key! "ctrl+o")
        (bk/key ctx "" "o" 2)
        (is (= 4 (count @hits))))
      (c/unbind-key! "ctrl+e")
      (c/unbind-key! "shift+ctrl+z")
      (c/detach-key-bindings!)
      (bk/context-free ctx)
      (bk/node-free root))))
