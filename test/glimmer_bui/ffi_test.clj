(ns glimmer-bui.ffi-test
  "Tests for the raw BK_* bindings. Needs the BUI kit dylib (built by
  `bin/build-bui`); every call here is headless — no window, no display."
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer-bui.ffi :as bk]))

(deftest version
  (testing "the kit reports its version"
    (is (= "0.4.1" (bk/version-string)))
    (is (= 401 (bk/version)))))

(deftest nodes
  (testing "node lifecycle"
    (let [n (bk/node-new "label")]
      (is (not (bk/null? n)))
      (is (= "label" (bk/node-type n)))
      (bk/node-free n))))

(deftest props
  (testing "set and read back string/number properties"
    (let [n (bk/node-new "button")]
      (is (= 1 (bk/set-str n "text" "Apply")))
      (is (= "Apply" (bk/get-str n "text")))
      (is (= 1 (bk/set-num n "value" 2.5)))
      (is (= 2.5 (bk/get-num n "value")))
      (is (= 0 (bk/set-str n "no-such-key" "x"))) ; unknown key -> 0
      (bk/node-free n))))

(deftest tree
  (testing "add/remove/children"
    (let [root (bk/node-new "frame")
          a    (bk/node-new "button")
          b    (bk/node-new "label")]
      (is (= 1 (bk/node-add root a -1)))
      (is (= 1 (bk/node-add root b -1)))
      (is (= 2 (bk/node-child-count root)))
      (is (= a (bk/node-child root 0)))
      (is (= b (bk/node-child root 1)))
      (is (= root (bk/node-parent a)))
      (bk/node-remove root b)
      (is (= 1 (bk/node-child-count root)))
      (bk/node-free root)
      (bk/node-free b))))
