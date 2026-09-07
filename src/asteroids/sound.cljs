(ns asteroids.sound
  "Web Audio side effects. Every sound is synthesised in code — the original's
   noises were analogue circuitry, so there is nothing to sample.

   asteroids.game decides *when* a sound happens by appending keywords to
   :events; this namespace decides what it sounds like. Nothing here is ever
   called from game.

   Browsers refuse to start an AudioContext without a user gesture, so init!
   must be called from a real key press, not on page load.")

;; One map of everything, defonce so hot reload does not build a second context.
(defonce audio (atom nil))
(defonce muted? (atom false))

(defn- noise-buffer
  "Two seconds of white noise, generated once and reused for every bang and for
   the thruster."
  [ctx]
  (let [len  (* 2 (.-sampleRate ctx))
        buf  (.createBuffer ctx 1 len (.-sampleRate ctx))
        data (.getChannelData buf 0)]
    (dotimes [i len]
      (aset data i (- (* 2 (js/Math.random)) 1)))
    buf))

(defn- looping-noise
  "A noise source that runs forever with its gain at zero, so switching it on is
   just a gain ramp. Starting and stopping sources instead would mean juggling
   their lifetimes every frame."
  [ctx dest freq]
  (let [src    (.createBufferSource ctx)
        filt   (.createBiquadFilter ctx)
        gain   (.createGain ctx)]
    (set! (.-buffer src) (:noise @audio))
    (set! (.-loop src) true)
    (set! (.-type filt) "lowpass")
    (set! (.-value (.-frequency filt)) freq)
    (set! (.-value (.-gain gain)) 0.0)
    (.connect src filt)
    (.connect filt gain)
    (.connect gain dest)
    (.start src)
    gain))

(defn- saucer-voice
  "A square wave with a slow wobble on its pitch: the saucer's warble."
  [ctx dest]
  (let [osc  (.createOscillator ctx)
        lfo  (.createOscillator ctx)
        dept (.createGain ctx)
        gain (.createGain ctx)]
    (set! (.-type osc) "square")
    (set! (.-value (.-frequency osc)) 120)
    (set! (.-type lfo) "sine")
    (set! (.-value (.-frequency lfo)) 9)
    (set! (.-value (.-gain dept)) 32)
    (.connect lfo dept)
    (.connect dept (.-frequency osc))         ; the LFO modulates the pitch
    (set! (.-value (.-gain gain)) 0.0)
    (.connect osc gain)
    (.connect gain dest)
    (.start osc)
    (.start lfo)
    {:osc osc :gain gain}))

(defn init!
  "Create the context, or resume it if the browser suspended it. Safe to call on
   every key press; only the first one does any work."
  []
  (when (nil? @audio)
    (when-let [Ctx (or js/window.AudioContext js/window.webkitAudioContext)]
      (let [ctx    (Ctx.)
            master (.createGain ctx)]
        (set! (.-value (.-gain master)) 0.32)
        (.connect master (.-destination ctx))
        (reset! audio {:ctx ctx :master master :noise (noise-buffer ctx)})
        (swap! audio assoc
               :thruster (looping-noise ctx master 700)
               :saucer   (saucer-voice ctx master)))))
  (when-let [ctx (:ctx @audio)]
    (when (= "suspended" (.-state ctx))
      (.resume ctx))))

(defn- now [] (.-currentTime (:ctx @audio)))

(defn- tone!
  "A single oscillator sweeping from freq to freq-end, with an exponential decay
   envelope. Everything short and pitched in this game is one of these."
  [type freq freq-end dur peak]
  (let [{:keys [ctx master]} @audio
        t    (now)
        osc  (.createOscillator ctx)
        gain (.createGain ctx)]
    (set! (.-type osc) type)
    (.setValueAtTime (.-frequency osc) freq t)
    (.exponentialRampToValueAtTime (.-frequency osc) freq-end (+ t dur))
    (.setValueAtTime (.-gain gain) peak t)
    (.exponentialRampToValueAtTime (.-gain gain) 0.0001 (+ t dur))
    (.connect osc gain)
    (.connect gain master)
    (.start osc t)
    (.stop osc (+ t dur 0.02))))

(defn- bang!
  "Filtered noise with a fast decay: the explosions. Bigger rocks get a lower
   filter and a longer tail."
  [cutoff dur peak]
  (let [{:keys [ctx master noise]} @audio
        t    (now)
        src  (.createBufferSource ctx)
        filt (.createBiquadFilter ctx)
        gain (.createGain ctx)]
    (set! (.-buffer src) noise)
    (set! (.-type filt) "lowpass")
    (.setValueAtTime (.-frequency filt) cutoff t)
    (.exponentialRampToValueAtTime (.-frequency filt) (max 80 (* cutoff 0.25)) (+ t dur))
    (.setValueAtTime (.-gain gain) peak t)
    (.exponentialRampToValueAtTime (.-gain gain) 0.0001 (+ t dur))
    (.connect src filt)
    (.connect filt gain)
    (.connect gain master)
    (.start src t)
    (.stop src (+ t dur 0.02))))

(defn play!
  "Handle one event from game's :events. Unknown keywords are ignored, so game
   can emit something new before this namespace knows about it."
  [event]
  (when (and @audio (not @muted?))
    (case event
      :fire        (tone! "square" 880 220 0.12 0.16)
      :ufo-fire    (tone! "sawtooth" 520 160 0.16 0.13)
      :bang-large  (bang! 900 0.55 0.55)
      :bang-medium (bang! 1400 0.40 0.45)
      :bang-small  (bang! 2000 0.28 0.35)
      :bang-ufo    (bang! 1100 0.50 0.55)
      :ship-explode (bang! 700 0.90 0.65)
      :extra-life  (tone! "triangle" 660 1320 0.35 0.22)
      ;; The heartbeat: two thumps a whole tone apart, exactly as low as the
      ;; original's.
      :beat-a      (tone! "triangle" 70 55 0.16 0.5)
      :beat-b      (tone! "triangle" 62 48 0.16 0.5)
      nil)))

(defn- ramp-gain! [gain target]
  (let [t (now)]
    (.cancelScheduledValues gain t)
    (.setValueAtTime gain (.-value gain) t)
    (.linearRampToValueAtTime gain target (+ t 0.08))))

(defn thruster!
  "Continuous while the player holds thrust, so it is driven by state rather
   than by an event."
  [on?]
  (when @audio
    (ramp-gain! (.-gain (:thruster @audio))
                (if (and on? (not @muted?)) 0.14 0.0))))

(defn saucer!
  "size is :large, :small, or nil when no saucer is on screen. The small one
   sits a fifth higher, the way the original distinguishes them."
  [size]
  (when @audio
    (let [{:keys [osc gain]} (:saucer @audio)]
      (when size
        (.setValueAtTime (.-frequency osc) (if (= :small size) 190 115) (now)))
      (ramp-gain! (.-gain gain) (if (and size (not @muted?)) 0.09 0.0)))))

(defn set-muted!
  "Returns the new state, so the caller can put the button in step with it."
  [on?]
  (reset! muted? (boolean on?))
  (when @muted?
    ;; The continuous voices are gain ramps rather than events, so they have to
    ;; be told; the one-shots simply stop being played.
    (thruster! false)
    (saucer! nil))
  @muted?)

(defn toggle-mute! []
  (set-muted! (not @muted?)))
