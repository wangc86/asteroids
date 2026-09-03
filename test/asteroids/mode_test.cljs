(ns asteroids.mode-test
  "The precedence rules for picking a control mode are the fiddly part, so they
   live in a pure namespace and are tested here rather than by clicking."
  (:require [cljs.test :refer [deftest is testing]]
            [asteroids.mode :as mode]))

(deftest parses-only-known-names
  (is (= :desktop (mode/parse "desktop")))
  (is (= :touch (mode/parse "touch")))
  (testing "anything else is as good as absent"
    (is (nil? (mode/parse "phone")))
    (is (nil? (mode/parse "")))
    (is (nil? (mode/parse nil)))
    (is (nil? (mode/parse "DESKTOP")) "no case folding, so a typo cannot half-work")))

(deftest round-trips-through-storage
  (doseq [m [:desktop :touch]]
    (is (= m (mode/parse (mode/->str m))))))

(deftest the-url-wins-over-a-remembered-choice
  (is (= :touch (mode/resolve-mode "touch" "desktop")))
  (is (= :desktop (mode/resolve-mode "desktop" "touch"))))

(deftest a-remembered-choice-is-used-when-the-url-says-nothing
  (is (= :touch (mode/resolve-mode nil "touch")))
  (is (= :desktop (mode/resolve-mode nil "desktop"))))

(deftest nothing-decided-means-ask
  (is (nil? (mode/resolve-mode nil nil)))
  (testing "a stale or hand-edited value must not wedge the game"
    (is (nil? (mode/resolve-mode nil "mobile")))
    (is (nil? (mode/resolve-mode "gamepad" nil)))
    (is (= :desktop (mode/resolve-mode "gamepad" "desktop"))
        "a bad url falls through to the remembered choice")))

(deftest the-pointer-suggests-but-does-not-decide
  (is (= :touch (mode/suggested true)))
  (is (= :desktop (mode/suggested false))))
