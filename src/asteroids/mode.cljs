(ns asteroids.mode
  "Which control scheme to run, and how that gets decided.

   Pure, like asteroids.game: the caller reads the URL, storage and the
   browser's own guess, and passes them in. That keeps the precedence rules —
   which are the fiddly part — testable under node.")

(def ^:const storage-key "asteroids-mode")

(defn parse
  "Accept only the two names we know; anything else is as good as absent, so a
   stale or hand-edited value can never wedge the game into a broken mode."
  [s]
  (case s
    "desktop" :desktop
    "touch"   :touch
    nil))

(defn ->str [mode]
  (case mode
    :desktop "desktop"
    :touch   "touch"
    nil))

(defn resolve-mode
  "The URL wins over a remembered choice, so a link can force a mode — handy for
   testing and for sending someone straight into one. nil means nothing has been
   decided yet, which is the caller's cue to ask."
  [url-param saved]
  (or (parse url-param) (parse saved)))

(defn suggested
  "What to highlight in the chooser. A coarse pointer means a finger."
  [coarse-pointer?]
  (if coarse-pointer? :touch :desktop))
