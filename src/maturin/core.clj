(ns maturin.core
  "Next, I think we want to transform this into a Middleware for Quil: https://github.com/quil/quil/wiki/Middleware"
  (:refer-clojure :exclude [partial zero? + - * / ref compare])
  (:require [sicmutils.env :refer :all]
            [sicmutils.numerical.ode :as ode]
            [sicmutils.structure :as struct]
            [quil.core :as q]
            [quil.middleware :as m])
  (:import (org.apache.commons.math3.ode.nonstiff GraggBulirschStoerIntegrator)))

;; This all only runs in Clojure mode, for the simple reason that when I did the
;; exploration, the Clojurescript port of sicmutils didn't exist!

(defn timestamp
  "TODO update this to a cljc compatible catch."
  []
  (try (/ (q/millis) 1000.0)
       (catch Exception _ 0)))

;; The goal was to make an API that shared information between the following two
;; functions, AND a draw routine.

(defn setup-fn
  "I THINK this can stay the same for any of the Lagrangians."
  [initial-state]
  (fn []
    (q/frame-rate 60)
    ;; Set color mode to HSB (HSV) instead of default RGB.
    (q/color-mode :hsb)
    {:state initial-state
     :time (timestamp)
     :color 0
     :tick 0
     :navigation-2d {:zoom 4}
     }))

(defn Lagrangian-updater
  "Returns an update function that uses the supplied Lagrangian to tick forward."
  [L initial-state]
  (let [state-derivative (Lagrangian->state-derivative L)
        {:keys [integrator equations dimension] :as m}
        (ode/integration-opts (constantly state-derivative)
                              []
                              initial-state
                              {:epsilon 1e-6
                               :compile? true})
        buffer (double-array dimension)]
    (fn [{:keys [state time color tick] :as m}]
      (let [s (double-array (flatten state))
            t2 (timestamp)]
        ;; Every update step uses `integrator` to push forward from `time` to
        ;; `t2`, the current timestamp. This is probably NOT the best way! The
        ;; integrator can run much faster than realtime, and it's most efficient
        ;; when we let it do that, and let it internally hit a callback at
        ;; specified intervals. But that is not how the functional API works for
        ;; Quil.
        ;;
        ;; Should we instead let it run forward in a different thread at larger
        ;; steps and feed some data structure? Or just... not worry about it??
        (.integrate ^GraggBulirschStoerIntegrator integrator
                    equations time s t2 buffer)

        ;; Every tick of the integator gives us these new fields.
        (merge m {:color (mod (inc color) 255)
                  :state (struct/unflatten buffer state)
                  :time t2
                  :tick (inc tick)})))))

;; # API Attempt

(defn init
  "Generates an initial state dictionary."
  [L]
  {:lagrangian L

   ;; These are the coordinate transforms I was mentioning. We eventually need
   ;; to compose them with L, but we need them for the drawing code too!
   :transforms []})

(defn transform
  "Takes a state dictionary and stores the coordinate transformation, AND composes
  it with the Lagrangian."
  [m f]
  (if-not (map? m)
    (transform (init m) f)
    ;; `F->C` turns a function that JUST transforms coordinates into a new
    ;; function that transforms the whole local tuple.
    (let [xform (F->C f)]
      (update-in m [:transforms] conj xform))))

(defn build
  "Returns the final keys for the sketch."
  [m initial-state]
  (if-not (map? m)
    (build (init m) initial-state)
    (let [{:keys [lagrangian transforms] :as m} m
          ;; this is a hack! compose should be able to handle empty sequences.
          ;; I'll fix this.
          xform (if-let [xforms (seq transforms)]
                  (apply compose (reverse transforms))
                  identity)
          L (compose lagrangian xform)]
      {:setup (setup-fn initial-state)
       :update (Lagrangian-updater L initial-state)
       :xform (compose coordinate xform)})))

;; Next, let's do the double pendulum. This is going to require some manual work
;; to get the coordinate changes working.

(defn L-rectangular
  "Accepts:

  - a down tuple of the masses of each particle
  - a potential function of all coordinates

  Returns a function of local tuple -> the Lagrangian for N cartesian
  coordinates."
  [masses U]
  (fn [[_ q qdot]]
    ;; because `masses` is a down tuple, and `(mapv square qdot)` is an up, this
    ;; triggers a contraction!
    (- (* 1/2 masses (mapv square qdot))
       (U q))))

(defn U-uniform-gravity
  "Accepts:

  - a down tuple of each particle's mass
  - g, the gravitational constant
  - optionally, the cartesian coordinate index of the vertical component. You'd
    need to update this if you went into 3d.

  Returns a function of the generalized coordinates to a uniform vertical
  gravitational potential."
  ([masses g]
   (U-uniform-gravity masses g 1))
  ([masses g vertical-coordinate]
   (let [vertical #(nth % vertical-coordinate)]
     (fn [q]
       (* g masses (mapv vertical q))))))

;; Think of this next function like a coordinate transform that's targeted at a
;; specific spot in the `q` structure. I want to specify a sequence of all of
;; the angles, and I need to provide a transform from that angle to `x, y`
;; coordinates.
;;
;; but what is the angle? It's the angle of the new pendulum off of vertical,
;; but with its zero point offset to the XY coordinate of the pendulum bob it's
;; getting attached to!
;;
;; So you query the `attachment`'s XY, then use that to transform your index.

(defn attach-pendulum
  "Replaces an angle at the ith index with a pendulum. The supplied attachment
  can be either a:

  - map of the form {:coordinate idx}, in which case the pendulum will attach
    there
  - A 2-tuple, representing a 2d attachment point

  If the attachment index doesn't exist, attaches to the origin."
  [l idx attachment]
  (fn [[_ q]]
    ;; We shouldn't need any of this `structure->vector` and back code anymore.
    (let [v (structure->vector q)
          [x y] (if (vector? attachment)
                  attachment
                  (get v (:coordinate attachment) [0 0]))
          update (fn [angle]
                   (up (+ x (* l (sin angle)))
                       (- y (* l (cos angle)))))]
      (vector->up
       (update-in v [idx] update)))))

(defn double-pendulum->rect
  "Convert to rectangular coordinates from the angles. This is a simpler, explicit
  version of a transformation that attaches a double pendulum."
  [l1 l2]
  (fn [[_ [theta phi]]]
    (let [x1 (* l1 (sin theta))
          y1 (* -1 l1 (cos theta))]
      (up (up x1 y1)
          (up (+ x1 (* l2 (sin phi)))
              (-  y1 (* l2 (cos phi))))))))

;; `reduce` means that each angle is transformed to XY before the next angle
;; needs it!

(defn L-chain
  "Lagrangian for a chain of pendulums under the influence of a uniform
  gravitational pull in the -y direction."
  [m lengths g]
  (let [U (U-uniform-gravity m g)]
    (reduce transform
            (L-rectangular m U)
            (map-indexed
             (fn [i l]
               (attach-pendulum l i (if (zero? i)
                                      [0 0]
                                      {:coordinate (dec i)})))
             lengths))))

;; Drawing API

(defn attach
  "Attach two points with a line."
  [[x1 y1] [x2 y2]]
  (q/line x1 (- y1) x2 (- y2)))

(defn bob
  "Generate a bob at that point."
  [[x y]]
  (q/ellipse x (- y) 2 2))

;; WARNING! This is hardcoded to THREE now. I didn't make it general enough to
;; actually respond to the shape of the `state` it gets passed.

(defn draw-chain [convert]
  (fn [{:keys [state color]}]
    ;; Clear the sketch by filling it with light-grey color.
    (q/background 100)

    ;; Set a fill color to use for subsequent shapes.
    (q/fill color 255 255)

    ;; Calculate x and y coordinates of the circle.
    (let [[b1 b2 b3] (convert state)]
      ;; Move origin point to the center of the sketch.
      (q/with-translation [(/ (q/width) 2)
                           (/ (q/height) 2)]
        (attach [0 0] b1)
        (attach b1 b2)
        (attach b2 b3)
        (bob b1)
        (bob b2)
        (bob b3)))))

(comment
  ;; Woohoo, works!! You'll find that the simplification gets SUPER slow beyond
  ;; three pendulums. I think I know why that is happening and I bet we can make
  ;; it all smoking fast... but bear with the simplifier for now!

  (let [g 98
        m (down 1 1 1)
        lengths [4 10 12]
        L (L-chain m lengths g)
        initial-state (up 0
                          (up pi (/ pi 2) (/ pi 2))
                          (up 0 0 0))
        built (build L initial-state)]
    (q/defsketch triple-pendulum
      :title "Triple pendulum"
      :size [500 500]
      :setup (:setup built)
      :update (:update built)
      :draw (draw-chain (:xform built))
      :features [:keep-on-top]
      :middleware [m/fun-mode m/navigation-2d])))

;; ## Remaining intro stuff

(defn L-particle
  "Single particle to start, under some potential."
  [m g]
  (fn [[_ [_ y] qdot]]
    (- (* 1/2 m (square qdot))
       (* m g y))))

;; We have to re-make the drawing functions for each new animation, since I
;; didn't make that generic. And it sort of can't be... BUT the updater code can
;; be shared!

(defn draw-particle [convert]
  (fn [{:keys [state color]}]
    ;; Clear the sketch by filling it with light-grey color.
    (q/background 100)

    ;; Set a fill color to use for subsequent shapes.
    (q/fill color 255 255)
    ;; Calculate x and y coordinates of the circle.
    (let [[x y] (convert state)]
      ;; Move origin point to the center of the sketch.
      (q/with-translation [(/ (q/width) 2)
                           (/ (q/height) 2)]
        ;; Draw the circle.
        (q/ellipse x (- y) 5 5)))))

(comment
  (let [m 1
        g 9.8
        L (L-particle m g)
        initial-state (up 0 (up 5 5) (up 4 10))
        built (build L initial-state)]
    (q/defsketch uniform-particle
      :title "Particle in uniform gravity"
      :size [500 500]
      ;; setup function called only once, during sketch initialization.
      :setup (:setup built)
      :update (:update built)
      :draw (draw-particle (:xform built))
      :features [:keep-on-top]
      :middleware [m/fun-mode m/navigation-2d])))

;; # Particle on an Ellipse

(defn L-free-3d [m]
  (fn [[_ _ qdot]]
    (* 1/2 m (square qdot))))

(defn elliptical->rect [a b c]
  (fn [[_ [θ φ] _]]
    (up (* a (sin θ) (cos φ))
        (* b (sin θ) (sin φ))
        (* c (cos θ)))))

(defn draw-ellipse [convert]
  (fn [{:keys [state color]}]
    ;; Clear the sketch by filling it with light-grey color.
    (q/background 100)

    ;; Set a fill color to use for subsequent shapes.

    ;; Calculate x and y coordinates of the circle.
    (let [[x y z] (convert state)]
      ;; Move origin point to the center of the sketch.
      (q/with-translation [(/ (q/width) 2)
                           (/ (q/height) 2)]

        (q/fill 0 255 255 50)
        (q/sphere 3)
        ;; Draw the circle.
        (q/fill color 255 255)
        (q/with-translation [x y z]
          (q/ellipse 0 0 1 1))))))

(comment
  (let [m 1
        initial-state (up 0 (up 1 1) (up 1 5))
        L (transform (L-free-3d m)
                     (elliptical->rect 3 3 8))
        built (build L initial-state)]
    (q/defsketch triaxial-particle
      :title "Particle on an ellipse"
      :size [500 500]
      ;; setup function called only once, during sketch initialization.
      :setup (:setup built)
      :update (:update built)
      :draw (draw-ellipse (:xform built))
      :features [:keep-on-top]
      :renderer :p3d
      :middleware [m/fun-mode m/navigation-3d])))


;; # Harmonic Oscillator

(defn L-harmonic
  "Lagrangian for a harmonic oscillator"
  [m k]
  (fn [[_ q qdot]]
    (- (* 1/2 m (square qdot))
       (* 1/2 k (square q)))))

(comment
  (let [m 1
        k 9.8
        L (L-harmonic m k)
        initial-state (up 0 (up 5 5) (up 4 10))
        built (build (init L) initial-state)]
    (q/defsketch harmonic-oscillator-sketch
      :title "Harmonic oscillator"
      :size [500 500]
      :setup (:setup built)
      :update (:update built)
      :draw (draw-particle (:xform built))
      :features [:keep-on-top]
      :middleware [m/fun-mode m/navigation-2d])))

;; # Driven Pendulum

(defn driven-pendulum->rect
  "Convert to rectangular coordinates from a single angle."
  [l yfn]
  (fn [[t [theta]]]
    (up (* l (sin theta))
        (- (yfn t)
           (* l (cos theta))))))

(defn draw-driven [convert support-fn]
  (fn [{:keys [state color time tick] :as m}]
    ;; Clear the sketch by filling it with light-grey color.
    (q/background 100)

    ;; Set a fill color to use for subsequent shapes.
    (q/fill color 255 255)

    ;; Calculate x and y coordinates of the circle.
    (let [[x y] (convert state)
          [xₛ yₛ] (support-fn (state->t state))]
      ;; Move origin point to the center of the sketch.
      (q/with-translation [(/ (q/width) 2)
                           (/ (q/height) 2)]
        (q/line xₛ (- yₛ) x (- y))
        (q/ellipse x (- y) 2 2)))))

(comment
  (let [m 1
        l 6
        g 9.8
        yfn (fn [t]
              (* 10 (cos (* t 5))))
        L (-> (L-particle m g)
              init
              (transform (driven-pendulum->rect l yfn)))
        initial-state (up 0
                          (up (/ pi 4))
                          (up 0))
        built (build L initial-state)]
    (q/defsketch driven-pendulum
      :title "Driven pendulum"
      :size [500 500]
      ;; setup function called only once, during sketch initialization.
      :setup (:setup built)
      :update (:update built)
      :draw (draw-driven (:xform built) (fn [t] [0 (yfn t)]))
      :features [:keep-on-top]
      :middleware [m/fun-mode m/navigation-3d])))
