(ns aps.parts.frontend.state.toolbar-test
  (:require
   [aps.parts.frontend.state.toolbar :as toolbar]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest relationship-create-attrs-test
  (testing "the selected type is threaded into the new Relationship"
    (let [db {:map {:id "map-1"}
              :ui  {:relationship-type :protects}}]
      (is (= {:map_id    "map-1"
              :type      "protects"
              :source_id "p1"
              :target_id "p2"}
             (toolbar/relationship-create-attrs
              db {:source_id "p1" :target_id "p2"})))))

  (testing "defaults to unknown when no type was ever selected"
    (is (= "unknown"
           (:type (toolbar/relationship-create-attrs
                   {:map {:id "map-1"}}
                   {:source_id "p1" :target_id "p2"}))))))

(deftest tool-mode-after-create-test
  (testing "one-shot: placing a Part springs back to Select (ADR-0015)"
    (is (= :select (toolbar/tool-mode-after-create :add-exile false))))

  (testing "shift-click keeps the tool armed for batch adds"
    (is (= :add-exile (toolbar/tool-mode-after-create :add-exile true))))

  (testing "Connect is one-shot the same way: back to Select, Shift batches"
    (is (= :select (toolbar/tool-mode-after-create :connect false)))
    (is (= :connect (toolbar/tool-mode-after-create :connect true)))))

(deftest default-tool-test
  (testing "Select is the default tool"
    (is (= :select toolbar/default-tool))))

(deftest shortcut-tool-test
  (testing "H selects the Hand tool, V the Select tool, case-insensitive"
    (is (= :hand (toolbar/shortcut-tool "h")))
    (is (= :hand (toolbar/shortcut-tool "H")))
    (is (= :select (toolbar/shortcut-tool "v")))
    (is (= :select (toolbar/shortcut-tool "V"))))

  (testing "C arms the Connect tool"
    (is (= :connect (toolbar/shortcut-tool "c")))
    (is (= :connect (toolbar/shortcut-tool "C"))))

  (testing "Escape returns to Select — the disarm-everything key"
    (is (= :select (toolbar/shortcut-tool "Escape"))))

  (testing "other keys map to no tool"
    (is (nil? (toolbar/shortcut-tool "x")))
    (is (nil? (toolbar/shortcut-tool " ")))))

(deftest part-chord-test
  (testing "P opens the Part chord, case-insensitive"
    (is (true? (toolbar/part-chord-key? "p")))
    (is (true? (toolbar/part-chord-key? "P")))
    (is (false? (toolbar/part-chord-key? "u"))))

  (testing "the second key picks the Part type, case-insensitive"
    (is (= :add-unknown (toolbar/chord-tool "u")))
    (is (= :add-exile (toolbar/chord-tool "E")))
    (is (= :add-firefighter (toolbar/chord-tool "f")))
    (is (= :add-manager (toolbar/chord-tool "M"))))

  (testing "an unmatched second key cancels — nil, so the caller routes
            the key as if pressed alone"
    (is (nil? (toolbar/chord-tool "v")))
    (is (nil? (toolbar/chord-tool "Escape")))
    (is (nil? (toolbar/chord-tool "p")))))

(deftest chord-step-test
  (testing "P arms the chord; the next matching key picks the tool"
    (is (= {:arm? true} (toolbar/chord-step false "p")))
    (is (= {:tool :add-exile} (toolbar/chord-step true "e"))))

  (testing "an unmatched second key returns {} — consumed, and the caller
            routes the key as if pressed alone"
    (is (= {} (toolbar/chord-step true "v")))
    (is (= {} (toolbar/chord-step true "t")))
    (is (= {} (toolbar/chord-step true "Escape"))))

  (testing "a repeated P re-arms — holding P down must not flip the chord
            off on every other auto-repeat"
    (is (= {:arm? true} (toolbar/chord-step true "p"))))

  (testing "chord keys mean nothing without the prefix"
    (is (= {} (toolbar/chord-step false "e")))))

(deftest modifier-key?-test
  (testing "pure modifier keydowns don't advance the chord — Shift+E must
            still complete a pending chord"
    (is (true? (toolbar/modifier-key? "Shift")))
    (is (true? (toolbar/modifier-key? "Meta")))
    (is (true? (toolbar/modifier-key? "Control")))
    (is (true? (toolbar/modifier-key? "Alt")))
    (is (false? (toolbar/modifier-key? "p")))))

(deftest select-tool-test
  (testing "an explicit choice sets the tool"
    (is (= {:tool-mode :hand}
           (toolbar/select-tool {:tool-mode :select} :hand))))
  (testing "an explicit choice cancels a spring-loaded hold —
            releasing the key must not undo what was just picked"
    (is (= {:tool-mode :hand}
           (toolbar/select-tool {:tool-mode          :hand
                                 :spring-return-tool :select} :hand)))))

(deftest choose-relationship-type-test
  (testing "picking an ink arms the Connect tool with it — choosing a
            type and not drawing with it is a dead end"
    (is (= {:relationship-type :protects :tool-mode :connect}
           (toolbar/choose-relationship-type {:tool-mode :select} :protects))))
  (testing "already connecting: the type just switches"
    (is (= {:relationship-type :activates :tool-mode :connect}
           (toolbar/choose-relationship-type {:tool-mode         :connect
                                              :relationship-type :protects}
                                             :activates)))))

(deftest spring-tool-test
  (testing "holding Space holds the Hand tool"
    (is (= :hand (toolbar/spring-tool " "))))
  (testing "other keys hold nothing"
    (is (nil? (toolbar/spring-tool "h")))
    (is (nil? (toolbar/spring-tool "Escape")))))

(deftest spring-hold-test
  (testing "a hold switches to the held tool, remembering where to return"
    (is (= {:tool-mode :hand :spring-return-tool :select}
           (toolbar/spring-hold {:tool-mode :select} :hand))))
  (testing "an armed creation tool is remembered too"
    (is (= {:tool-mode :hand :spring-return-tool :add-exile}
           (toolbar/spring-hold {:tool-mode :add-exile} :hand))))
  (testing "no tool-mode set yet — the default is remembered"
    (is (= {:tool-mode :hand :spring-return-tool :select}
           (toolbar/spring-hold {} :hand))))
  (testing "holding while already holding (key auto-repeat) is a no-op"
    (let [held (toolbar/spring-hold {:tool-mode :select} :hand)]
      (is (= held (toolbar/spring-hold held :hand))))))

(deftest spring-release-test
  (testing "release returns to the remembered tool and clears the hold"
    (is (= {:tool-mode :select}
           (toolbar/spring-release {:tool-mode          :hand
                                    :spring-return-tool :select}))))
  (testing "release without a hold is a no-op"
    (is (= {:tool-mode :select}
           (toolbar/spring-release {:tool-mode :select})))))

(deftest tool-interaction-test
  (testing "Select: drag-empty marquees and does NOT pan (ADR-0015);
            middle-mouse-drag stays as a pan accelerator"
    (let [props (toolbar/tool-interaction :select)]
      (is (= [1] (:pan-on-drag props))
          "left-drag must not pan — only middle-mouse (button 1) pans")
      (is (true? (:selection-on-drag props)))
      (is (true? (:nodes-draggable props)))
      (is (true? (:elements-selectable props)))
      (is (true? (:nodes-connectable props)))))

  (testing "Hand: drag pans; nothing is selectable, draggable, or connectable"
    (let [props (toolbar/tool-interaction :hand)]
      (is (true? (:pan-on-drag props)))
      (is (false? (:selection-on-drag props)))
      (is (false? (:nodes-draggable props)))
      (is (false? (:elements-selectable props)))
      (is (false? (:nodes-connectable props)))))

  (testing "Connect: a Part's body means endpoint, not move or select;
            the ring drag stays available; drag-empty neither pans nor marquees"
    (let [props (toolbar/tool-interaction :connect)]
      (is (= [1] (:pan-on-drag props)))
      (is (false? (:selection-on-drag props)))
      (is (false? (:nodes-draggable props)))
      (is (false? (:elements-selectable props)))
      (is (true? (:nodes-connectable props)))))

  (testing "an armed one-shot Part tool interacts like Select — and the
            values are identity-stable, so ReactFlow's memoized renderer
            isn't busted by fresh props every render"
    (is (identical? (toolbar/tool-interaction :select)
                    (toolbar/tool-interaction :add-exile)))
    (is (identical? (toolbar/tool-interaction :hand)
                    (toolbar/tool-interaction :hand))))

  (testing "read-only (no active Session, or 073.03's viewing-the-past):
            selection and pan stay — reading is the point — but nothing
            can move, connect, or marquee-less tools mutate"
    (let [props (toolbar/tool-interaction :select false)]
      (is (= [1] (:pan-on-drag props)))
      (is (true? (:selection-on-drag props)) "marquee still selects")
      (is (true? (:elements-selectable props)) "click-to-read stays")
      (is (false? (:nodes-draggable props)))
      (is (false? (:nodes-connectable props)))))

  (testing "read-only Hand is just Hand — it never mutated anything"
    (is (identical? (toolbar/tool-interaction :hand)
                    (toolbar/tool-interaction :hand false))))

  (testing "read-only Connect falls back to the read-only interaction —
            an armed Connect must not become a drag source"
    (is (false? (:nodes-connectable (toolbar/tool-interaction :connect false)))))

  (testing "read-only values are identity-stable too"
    (is (identical? (toolbar/tool-interaction :select false)
                    (toolbar/tool-interaction :add-exile false))))

  (testing "editable? true is the existing per-tool behaviour"
    (is (identical? (toolbar/tool-interaction :select)
                    (toolbar/tool-interaction :select true)))))

(deftest tool-interaction-touch-test
  (testing "touch-primary Select: one-finger drag on empty canvas pans —
            xyflow can't pair marquee with pinch-zoom on touch, so the
            marquee is desktop-only until the long-press gesture lands.
            Parts still drag and tap-select as usual"
    (let [props (toolbar/tool-interaction :select true true)]
      (is (true? (:pan-on-drag props)))
      (is (false? (:selection-on-drag props)))
      (is (true? (:nodes-draggable props)) "dragging a Part still moves it")
      (is (true? (:elements-selectable props)) "tap still selects")
      (is (true? (:nodes-connectable props)))
      (is (true? (:long-press-marquee props))
          "the long-press marquee gesture is gated on this key")))

  (testing "touch Connect: a Part's body is still the drag source;
            empty-canvas drag pans instead of dead-ending; no marquee —
            a drag from empty canvas must never grow a selection while
            the tool means connect"
    (let [props (toolbar/tool-interaction :connect true true)]
      (is (true? (:pan-on-drag props)))
      (is (false? (:selection-on-drag props)))
      (is (true? (:nodes-connectable props)))
      (is (not (:long-press-marquee props)))))

  (testing "touch read-only: tap-to-read stays, drag pans, and long-press
            marquee selects for reading"
    (let [props (toolbar/tool-interaction :select false true)]
      (is (true? (:pan-on-drag props)))
      (is (false? (:selection-on-drag props)))
      (is (true? (:elements-selectable props)))
      (is (true? (:long-press-marquee props)))))

  (testing "Hand never marquees — dragging only ever pans"
    (is (not (:long-press-marquee (toolbar/tool-interaction :hand true true)))))

  (testing "Hand is Hand on every input device"
    (is (identical? (toolbar/tool-interaction :hand true false)
                    (toolbar/tool-interaction :hand true true))))

  (testing "touch values are identity-stable — ReactFlow memoization"
    (is (identical? (toolbar/tool-interaction :select true true)
                    (toolbar/tool-interaction :add-exile true true))))

  (testing "the 2-arity defaults from the environment: in this Node test
            runtime there is no matchMedia, so it must equal desktop"
    (is (identical? (toolbar/tool-interaction :select true)
                    (toolbar/tool-interaction :select true false)))))

(deftest phone-interaction-test
  (testing "the phone canvas is view-only (TASK-105): drag pans, pinch
            zooms, and nothing can be selected, moved, or connected —
            unlike read-only-interaction, which keeps selection because
            a desktop-sized screen has a sidebar to read notes in"
    (is (true? (:pan-on-drag toolbar/phone-interaction)))
    (is (false? (:selection-on-drag toolbar/phone-interaction)))
    (is (false? (:nodes-draggable toolbar/phone-interaction)))
    (is (false? (:elements-selectable toolbar/phone-interaction)))
    (is (false? (:nodes-connectable toolbar/phone-interaction))))

  (testing "no :long-press-marquee grant — the touch marquee handlers
            self-gate on that key, so its absence disables the gesture"
    (is (not (contains? toolbar/phone-interaction :long-press-marquee)))))

(deftest touch-drops-hand-tool-test
  (testing "touch-primary devices offer NO persistent-tool buttons: no
            Hand (one-finger drag already pans in every tool), and with
            Hand gone Select is the only persistent mode — a button for
            a mode you can never leave controls nothing. Armed creation
            tools disarm by tapping again (ADR-0015 amendment)"
    (is (= [:select :hand] (toolbar/persistent-tools false)))
    (is (= [] (toolbar/persistent-tools true))))

  (testing "the H shortcut is inert on touch — a Smart-Keyboard iPad
            must not arm a tool that has no palette button to leave;
            the other shortcuts still work"
    (is (= :hand (toolbar/shortcut-tool "h" false)))
    (is (nil? (toolbar/shortcut-tool "h" true)))
    (is (= :select (toolbar/shortcut-tool "v" true)))
    (is (= :connect (toolbar/shortcut-tool "c" true)))
    (is (= :select (toolbar/shortcut-tool "Escape" true))))

  (testing "Space spring-loads Hand only where Hand exists"
    (is (= :hand (toolbar/spring-tool " " false)))
    (is (nil? (toolbar/spring-tool " " true))))

  (testing "Connect drag threshold gets fingertip-jitter headroom on
            touch — an honest tap wanders ~10px and must not start a
            phantom connection drag"
    (is (= 8 (toolbar/connection-drag-threshold false)))
    (is (= 16 (toolbar/connection-drag-threshold true)))))

(deftest marquee-hold-test
  (testing "pointer down begins a hold at the touch point, bound to the
            pointer that started it"
    (is (= {:phase :holding :origin {:x 10 :y 20} :pointer-id 7}
           (toolbar/marquee-hold-begin {:x 10 :y 20} 7))))

  (testing "small movement (finger tremor) keeps the hold alive"
    (let [held (toolbar/marquee-hold-begin {:x 10 :y 20} 7)]
      (is (= held (toolbar/marquee-hold-move held {:x 14 :y 22})))))

  (testing "movement past the slop radius cancels — the drag is a pan"
    (let [held (toolbar/marquee-hold-begin {:x 10 :y 20} 7)]
      (is (nil? (toolbar/marquee-hold-move held {:x 40 :y 20})))))

  (testing "the timer arms a still hold into an active marquee anchored
            at the origin, still bound to its pointer"
    (is (= {:phase      :active
            :origin     {:x 10 :y 20}
            :current    {:x 10 :y 20}
            :pointer-id 7}
           (-> (toolbar/marquee-hold-begin {:x 10 :y 20} 7)
               (toolbar/marquee-hold-arm)))))

  (testing "arming is a no-op on anything but a live hold"
    (is (nil? (toolbar/marquee-hold-arm nil)))
    (is (nil? (-> (toolbar/marquee-hold-begin {:x 0 :y 0} 7)
                  (toolbar/marquee-hold-move {:x 99 :y 99})
                  (toolbar/marquee-hold-arm)))))

  (testing "once active, movement drags the marquee corner — no more slop"
    (is (= {:x 300 :y 400}
           (-> (toolbar/marquee-hold-begin {:x 10 :y 20} 7)
               (toolbar/marquee-hold-arm)
               (toolbar/marquee-hold-move {:x 300 :y 400})
               :current))))

  (testing "the gesture belongs to one pointer: only events from the
            pointer that began the hold may drive or end it — a second
            finger landing mid-gesture must not yank the rect or commit
            the selection"
    (let [held (toolbar/marquee-hold-begin {:x 10 :y 20} 7)]
      (is (true? (toolbar/marquee-hold-owns? held 7)))
      (is (false? (toolbar/marquee-hold-owns? held 8)))
      (is (false? (toolbar/marquee-hold-owns? nil 7)))))

  (testing "the rect spans origin and current whichever way the drag ran;
            nil until the hold arms"
    (is (= {:x 10 :y 20 :width 40 :height 30}
           (-> (toolbar/marquee-hold-begin {:x 50 :y 50} 7)
               (toolbar/marquee-hold-arm)
               (toolbar/marquee-hold-move {:x 10 :y 20})
               (toolbar/marquee-hold-rect))))
    (is (nil? (toolbar/marquee-hold-rect
               (toolbar/marquee-hold-begin {:x 0 :y 0} 7))))
    (is (nil? (toolbar/marquee-hold-rect nil))))

  (testing "dragging? — has the armed marquee demonstrably been dragged
            (drives the armed-ring fade): false at arm, false within the
            slop, true past it, false for un-armed states"
    (let [armed (-> (toolbar/marquee-hold-begin {:x 50 :y 50} 7)
                    (toolbar/marquee-hold-arm))]
      (is (false? (toolbar/marquee-hold-dragging? armed)))
      (is (false? (toolbar/marquee-hold-dragging?
                   (toolbar/marquee-hold-move armed {:x 55 :y 55}))))
      (is (true? (toolbar/marquee-hold-dragging?
                  (toolbar/marquee-hold-move armed {:x 100 :y 50}))))
      (is (false? (toolbar/marquee-hold-dragging?
                   (toolbar/marquee-hold-begin {:x 0 :y 0} 7))))
      (is (false? (toolbar/marquee-hold-dragging? nil)))

      (is (true? (toolbar/marquee-hold-dragging?
                  (-> armed
                      (toolbar/marquee-hold-move {:x 100 :y 50})
                      (toolbar/marquee-hold-move {:x 52 :y 51}))))
          "latched: dragging the corner back near the origin must not
           un-drag — the ring stays faded for the rest of the gesture"))))

(deftest marquee-buffer-add-test
  (testing "Part selects accumulate — the latest selected? per id wins;
            the gesture's origin rides along untouched"
    (is (= {:origin {:x 1 :y 2} :parts {"p1" false "p2" true}}
           (-> {:origin {:x 1 :y 2} :parts {}}
               (toolbar/marquee-buffer-add
                {:intent :part-selected :id "p1" :selected? true})
               (toolbar/marquee-buffer-add
                {:intent :part-selected :id "p2" :selected? true})
               (toolbar/marquee-buffer-add
                {:intent :part-selected :id "p1" :selected? false})))))

  (testing "relationship selects are swallowed (buffer unchanged) — the
            marquee owns them but ReactFlow's connected-edge rule
            over-selects; the rect's own edge hits commit at gesture end"
    (is (= {:parts {}}
           (toolbar/marquee-buffer-add
            {:parts {}}
            {:intent :relationship-selected :id "r1" :selected? true}))))

  (testing "any other intent returns nil — not the marquee's, pass it through"
    (is (nil? (toolbar/marquee-buffer-add
               {:parts {}}
               {:intent :part-moved :id "p1" :position {:x 1 :y 2}})))))

(deftest marquee-preview-ids-test
  (testing "the rendered selection is the committed one with the gesture's
            adds and removes applied on top"
    (is (= #{"p1" "p3"}
           (toolbar/marquee-preview-ids ["p1" "p2"] {"p3" true "p2" false}))))

  (testing "an empty overlay leaves the committed selection as-is"
    (is (= #{"p1"} (toolbar/marquee-preview-ids ["p1"] {})))))

(deftest resize-armed?-test
  (testing "resize arms only for a single selection in Select — each node
            then shows handles iff it is the selected one"
    (is (true? (toolbar/resize-armed? :select 1 true))))

  (testing "not for multi-selections — marquee selections are for move/delete"
    (is (false? (toolbar/resize-armed? :select 2 true)))
    (is (false? (toolbar/resize-armed? :select 0 true))))

  (testing "not outside the Select tool"
    (is (false? (toolbar/resize-armed? :hand 1 true)))
    (is (false? (toolbar/resize-armed? :add-exile 1 true))))

  (testing "never on a read-only canvas"
    (is (false? (toolbar/resize-armed? :select 1 false)))))
