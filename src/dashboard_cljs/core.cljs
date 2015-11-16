(ns dashboard-cljs.core
  (:require [crate.core :as crate]
            [goog.net.XhrIo :as xhr]
            [goog.object]
            [cljsjs.moment]
            [cljsjs.pikaday.with-moment]
            [maplabel]
            ))

(def state (atom {:timeout-interval 5000
                  :orders (array)
                  :couriers (array)
                  :couriers-control
                  {:selected? true
                   :color "#8E44AD"}
                  :google-map nil
                  :from-date (-> (js/moment)
                                 (.subtract 30 "days")
                                 (.format "YYYY-MM-DD"))
                  :to-date   (.format (js/moment) "YYYY-MM-DD")
                  :status
                  {:unassigned {:color "#ff0000"
                                :selected? true}
                   :accepted   {:color "#808080"
                                :selected? true}
                   :enroute    {:color "#ffdd00"
                                :selected? true}
                   :servicing  {:color "#0000ff"
                                :selected? true}
                   :complete   {:color "#00ff00"
                                :selected? true}
                   :cancelled  {:color "#000000"
                                :selected? true}}
                  :cities
                  {"Los Angeles"
                   {:coords (js-obj "lat" 34.0714522
                                    "lng" -118.40362)}
                   "San Diego"
                   {:coords (js-obj "lat" 32.715786
                                    "lng" -117.158340)}}
                  }))

(defn send-xhr
  "Send a xhr to url using callback and HTTP method."
  [url callback method & [data headers timeout]]
  (.send goog.net.XhrIo url callback method data headers timeout))

(defn xhrio-wrapper
  "A callback for processing the xhrio response event. If
  response.target.isSuccess() is true, call f on the json response"
  [f response]
  (let [target (.-target response)]
    (if (.isSuccess target)
      (f (.getResponseJson target))
      (.log js/console
            (str "xhrio-wrapper error:" (aget target "lastError_"))))))

(defn get-obj-with-prop-val
  "Get the object in array by prop with val"
  [array prop val]
  (first (filter #(= val (aget % prop)) array)))

(defn swap-obj-prop!
  "Set obj's prop to the result of using f(prop)"
  [obj prop f]
  (let [prop-value (aget obj (str prop))]
    (aset obj (str prop) (f prop-value))))

(defn sync-obj-properties!
  "Sync all object properties of target-obj with sync-obj"
  [target-obj sync-obj]
  (goog.object/forEach
   sync-obj
   (fn [el key obj]
     (aset target-obj (str key) el))))

(defn unix-epoch->hrf
  "Convert a unix epoch (in seconds) to a human readable format"
  [unix-epoch]
  (-> (js/moment.unix unix-epoch)
      (.format "MM/DD hh:mm A")))

(defn cents->dollars
  "Converts an integer value of cents to dollar string"
  [cents]
  (str "$" (-> cents (/ 100) (.toFixed 2))))

;; the base url to use for server calls
(def base-server-url (-> (.getElementById js/document "base-url")
                         (.getAttribute "value")))

(defn create-circle
  "Create a circle shape on google-map with lat lng and color"
  [google-map lat lng color click-fn]
  (let [circle
        (js/google.maps.Circle.
         (js-obj "strokeColor" color
                 "strokeOpacity" 1.0
                 "strokeWeight" 1
                 "fillColor" color
                 "fillOpacity" 1
                 "map" google-map
                 "center" (js-obj "lat" lat "lng" lng)
                 "radius" 200))]
    (.addListener circle "click" click-fn)
    circle))

(defn add-circle-to-spatial-object!
  "Given an object with lat,lng properties, create a circle on gmap
  with color and optional click-fn. Add circle as obj property."
  ([obj gmap color]
   (add-circle-to-spatial-object! obj gmap color #()))
  ([obj gmap color click-fn]
   (aset obj "circle"
         (create-circle gmap
                        (aget obj "lat")
                        (aget obj "lng")
                        color
                        click-fn))))

;; see: http://goo.gl/SgBTn4 (stackoverflow)
(defn scale-by-zoom
  "Given a zoom, min-val and max-val return a value scaled by zoom"
  [zoom min-val max-val]
  (let [min-zoom 10
        max-zoom 21
        zoom-normalized (- zoom min-zoom)
        scale-factor (.pow js/Math 2 (- 21 zoom))
        new-radius   (cond (>= zoom max-zoom)
                           min-val
                           (<= zoom min-zoom )
                           max-val
                           (< min-zoom zoom max-zoom)
                           (* scale-factor 1128.497220 0.0027 (/ 1 10)))]
    new-radius))

(defn scale-spatial-object-circle-radius!
  "Given obj with a circle prop, scale its radius by zoom"
  [zoom obj]
  (.setRadius (aget obj "circle") (scale-by-zoom zoom 1 200)))

(defn dlat-label
  "Calculate the dLat by which the label should be moved based on the zoom so
  that it can be seen"
  [zoom]
  ;; This equation was determined by zooming and modifying
  ;; the lat value of the label by hand for reasonable behavior.
  ;; This resulted in (zoom,dLat) pairs. The equation below was fitted to those
  ;; points in gnuplot.
  (* -1 (.pow js/Math 10 (/ (* -1 zoom) 4.2))))

(defn set-obj-label-lat-lng!
  "Given an obj, set the lat, lng positions of the label"
  [lat lng obj]
  (let [label (aget obj "label")]
    (.set label "position"
          (js/google.maps.LatLng. lat lng))
    (.draw label)))

(defn set-obj-label-position!
  "Given state, set the obj's label position based on the map's zoom"
  [state obj]
  (let [circle (aget obj "circle")
        circle-center (aget circle "center")
        label (aget obj "label")
        circle-lat (.lat circle-center)
        circle-lng (.lng circle-center)
        zoom       (.getZoom (:google-map @state))
        dlat       (dlat-label zoom)]
    (set-obj-label-lat-lng! (+ circle-lat dlat) circle-lng obj)))

(defn ^:export get-map-info
  "Utility function for viewing map information in the browser"
  []
  (.log js/console (str "Map-Zoom:" (.getZoom (:google-map @state))
                        " "
                        "Map Center:" (.getCenter (:google-map @state))
                        )))

(defn create-label
  "Create a MapLabel object on google-map with lat, lng and text"
  [google-map lat lng text]
  (js/MapLabel.
   (js-obj "map" google-map
           "position" (js/google.maps.LatLng. (+ lat
                                                 (dlat-label
                                                  (.getZoom google-map)))
                                              lng)
           "text" text
           "fontSize" 12
           "align" "center")))

(defn add-label-to-spatial-object!
  "Given an object with lat,lng properties, create a label on gmap
  with text. Add label as obj property"
  [obj gmap text]
  (aset obj "label"
        (create-label gmap
                      (aget obj "lat")
                      (aget obj "lng")
                      text)))

(defn create-info-window
  "Create an InfoWindow at lat,lng with node as content"
  [lat lng node]
  (js/google.maps.InfoWindow.
   (js-obj
    "position" (js-obj "lat" lat "lng" lng)
    "content" (aget node "outerHTML"))))

(defn add-info-window-to-spatial-object!
  "Given an obj with lat,lng properties, create an info-window on gmap
  with node as content"
  [obj node]
  (aset obj "info-window"
        (create-info-window
         (aget obj "lat")
         (aget obj "lng")
         node)))

(defn open-obj-info-window
  "Given an obj with a info-window prop, open it"
  [state obj]
  (let [info-window (aget obj "info-window")]
    (.open info-window (:google-map @state))))

(defn create-info-window-tr
  [key value]
  (crate/html [:tr [:td {:class "info-window-td"} key] [:td value]]))

(defn create-info-window-table
  "Create a info window table node using array-map"
  [array-map]
  (crate/html [:table (map #(if (not (nil? (val %)))
                              (create-info-window-tr
                               (key %)
                               [:div {:class "info-window-value"} (val %)]))
                           array-map)]))

(defn create-order-info-window-node
  "Create an html node containing information about order"
  [order]
  (create-info-window-table
   (let [address-city (aget order "address_city")
         address-state (aget order "address_state")]
     (array-map
      ;; "Status" (aget order "status")
      "Courier" (aget order "courier_name")
      "Customer" (aget order "customer_name")
      "Phone" (aget order "customer_phone_number")
      "Address"  (crate/raw
                  (str
                   (aget order "address_street")
                   "</br>"
                   (if (and (not (nil? (seq address-city)))
                            (not (nil? (seq address-state))))
                     (str (aget order "address_city")
                          ","
                          (aget order "address_state")
                          " "))
                   (aget order "address_zip")))
      "Plate #" (aget order "license_plate")
      "Gallons" (aget order "gallons")
      "Octane"  (aget order "gas_type")
      "Total Price" (-> (aget order "total_price")
                        (cents->dollars))
      "Placed"  (-> (aget order "target_time_start")
                    (unix-epoch->hrf))
      "Deadline" (-> (aget order "target_time_end")
                     (unix-epoch->hrf))))))

(defn create-courier-info-window-node
  "Create an html node containing information about order"
  [courier]
  (create-info-window-table
   (array-map
    "Name" (aget courier "name")
    "Phone" (aget courier "phone_number")
    "Last Seen" (-> (aget courier "last_ping")
                    (unix-epoch->hrf))
    ;; "87 Octane" (aget courier "gallons_87")
    ;; "91 Octane" (aget courier "gallons_91")
    )))

(defn order-displayed?
  "Given the state, determine if an order should be displayed or not"
  [state order]
  (let [from-date (.parse js/Date (:from-date @state))
        order-date (aget order "timestamp_created")
        to-date (+ (.parse js/Date (:to-date @state))
                   (* 24 60 60 1000)) ; need to add 24 hours so day is included
        status  (aget order "status")
        status-selected? (get-in @state [:status (keyword status) :selected?])]
    (and
     ;; order is within the range of dates
     (<= from-date order-date to-date)
     status-selected?)))

(defn set-obj-prop-map!
  "Given an obj with prop, set its circle's map to map-value"
  [obj prop map-value]
  (if (not (nil? (aget obj prop)))
    (.setMap (aget obj prop) map-value)))

(defn display-selected-props!
  "Given the state, only display props of objs that satisfy pred on
  object in objs"
  [state objs prop pred]
  (mapv #(if (pred %)
           (set-obj-prop-map! % prop (:google-map @state))
           (set-obj-prop-map! % prop nil))
        objs))

;; could this be refactored to use "every-pred" ?
(defn courier-displayed?
  "Given the state, determine if a courier should be displayed or not"
  [state courier]
  (let [active?    (aget courier "active")
        on-duty?   (aget courier "on_duty")
        connected? (aget courier "connected")
        selected?  (get-in @state [:couriers-control :selected?])]
    (and connected? active? on-duty? selected?)))

(defn display-selected-couriers!
  "Given the state, display selected couriers"
  [state]
  (let [display-courier-props!
        #(display-selected-props!
          state (:couriers @state) % (partial courier-displayed? state))]
    (display-courier-props! "circle")
    (display-courier-props! "label")))

(defn indicate-courier-busy-status!
  "Given a courier, indicate whether or not they are busy"
  [obj]
  (let [busy? (aget obj "busy")
        circle (aget obj "circle")]
    (if busy?
      ;; set red stroke
      (.setOptions circle
                   (clj->js {:options {:strokeColor "#ff0000"}}))
      ;; set green stroke
      (.setOptions circle
                   (clj->js {:options {:strokeColor "#00ff00"}})))))

(defn retrieve-route
  "Access route with data and process xhr callback with f"
  [route data f & [timeout]]
  (let [url (str base-server-url route)
        data data
        header (clj->js {"Content-Type" "application/json"})]
    (send-xhr url f "POST" data header timeout)))

(defn sync-courier!
  "Given a courier, sync it with the one in state. If it does not already exist,
  add it to the orders in state"
  [state courier]
  (let [state-courier (get-obj-with-prop-val (:couriers @state) "id"
                                             (aget courier "id"))]
    (if (nil? state-courier)
      ;; process the courier and add it to couriers of state
      (do
        ;; add a circle to represent the courier
        (add-circle-to-spatial-object!
         courier (:google-map @state)
         (get-in @state [:couriers-control
                         :color])
         (fn [] (open-obj-info-window state courier)))
        ;; add a label to the courier
        (add-label-to-spatial-object!
         courier
         (:google-map @state)
         (aget courier "name"))
        ;; indicate courier status
        (indicate-courier-busy-status! courier)
        ;; add an info window to the courier
        ;; note: The info window must be added after all modifications
        ;; to the state courier have been carried out
        (add-info-window-to-spatial-object!
         courier
         (create-courier-info-window-node courier))
        ;; push the order
        (.push (:couriers @state) courier))
      ;; update the existing courier's properties
      (let [state-courier-circle (aget state-courier "circle")
            state-courier-label (aget state-courier "label")
            state-courier-info-window (aget state-courier "info-window")
            new-position  (js/google.maps.LatLng.
                           (aget courier "lat")
                           (aget courier "lng"))]
        ;; sync the object properties of state-courier with courier
        (sync-obj-properties! state-courier courier)
        ;; set the corresponding circle's center
        (.setCenter state-courier-circle
                    new-position)
        ;;set the corresponding label's position
        (set-obj-label-position! state state-courier)
        ;; indicate busy status
        (indicate-courier-busy-status! state-courier)
        ;; the content window has to be updated
        (.setContent state-courier-info-window
                     (-> state-courier
                         (create-courier-info-window-node)
                         (aget "outerHTML")))
        ;; move the info window position
        (.setPosition state-courier-info-window
                      new-position)))))

(defn retrieve-couriers!
  "Get the couriers from the server and sync them with the state atom"
  [state & [timeout]]
  (retrieve-route
   "couriers" {}
   (partial xhrio-wrapper
            #(let [couriers (aget % "couriers")]
               (if (not (nil? couriers))
                 (do
                   ;; update the couriers
                   (mapv (partial sync-courier! state) couriers)
                   ;; display the selected couriers
                   (display-selected-couriers! state)))))
   timeout))

(defn init-couriers!
  [state]
  (retrieve-couriers! state))

(defn sync-couriers!
  [state]
  (retrieve-couriers! state (:timeout-interval @state)))

(defn retrieve-orders
  "Retrieve the orders since date and apply f to them"
  [date f & [timeout]]
  (retrieve-route "orders-since-date" (js/JSON.stringify
                                       (clj->js {:date date})) f timeout))

(defn convert-order-timestamp!
  "Convert an order's timestamp_created to a js/Date"
  [order]
  (aset order "timestamp_created"
        (.parse js/Date
                (aget order "timestamp_created"))))

(defn sync-order!
  "Given an order, sync it with the one in state. If it does not already exist,
  add it to the orders in state"
  [state order]
  (let [state-order (get-obj-with-prop-val (:orders @state) "id"
                                           (aget order "id"))]
    (if (nil? state-order)
      ;; process the order and add it to orders of state
      (do
        ;; add a circle to represent the order
        (add-circle-to-spatial-object!
         order (:google-map @state)
         (get-in @state [:status
                         (keyword
                          (.-status order))
                         :color])
         (fn [] (open-obj-info-window state order)))
        (swap-obj-prop! order "timestamp_created" #(.parse js/Date %))
        ;; add a info window to order
        ;; Note: The info window must be added after all modifications
        ;; to the order have been carried out
        (add-info-window-to-spatial-object!
         order
         (create-order-info-window-node order))
        ;; push the order
        (.push (:orders @state) order))
      ;; update the existing orders properties
      (do
        ;; sync the object properties of state-order with order
        (sync-obj-properties! state-order order)
        (swap-obj-prop! state-order "timestamp_created" #(.parse js/Date %))
        ;; the content window has to be udpated
        (.setContent (aget state-order "info-window")
                     (-> state-order
                         (create-order-info-window-node)
                         (aget "outerHTML")))
        ;; change the color of circle to correspond to order status
        (let [status-color (get-in @state [:status
                                           (keyword (aget state-order "status"))
                                           :color])]
          (.setOptions (aget state-order "circle")
                       (clj->js {:options {:fillColor status-color
                                           :strokeColor status-color}})))))))

(defn init-orders!
  "Get the all orders from the server and store them in the state atom. This
  should only be called once initially."
  [state date]
  (do
    (retrieve-orders
     date ; note: an empty date will return ALL orders
     (partial
      xhrio-wrapper
      #(let [orders (aget % "orders")]
         (if (not (nil? orders))
           (do (mapv (partial sync-order! state) orders)
               ;; redraw the circles according to which ones are selected
               (display-selected-props! state (:orders @state) "circle"
                                        (partial order-displayed? state)))))))))

(defn sync-orders!
  "Retrieve today's orders and sync them with the state"
  [state]
  (retrieve-orders
   (.format (js/moment) "YYYY-MM-DD")
   (partial
    xhrio-wrapper
    #(let [orders (aget % "orders")]
       (if (not (nil? orders))
         (do (mapv (partial sync-order! state) orders)
             ;; redraw the circles according to which ones are selected
             (display-selected-props! state (:orders @state) "circle"
                                      (partial order-displayed? state))))))
   (:timeout-interval @state)))

(defn legend-symbol
  "A round div for use as a legend symbol"
  [fill-color & [stroke-color]]
  (crate/html [:div {:style (str "height: 10px;"
                                 " width: 10px;"
                                 " display: inline-block;"
                                 " float: right;"
                                 " border-radius: 10px;"
                                 " margin-top: 7px;"
                                 " margin-left: 5px;"
                                 " background-color: "
                                 fill-color
                                 "; "
                                 (if (not (nil? stroke-color))
                                   (str " border: 1px solid "
                                        stroke-color ";")))}]))

(defn order-status-checkbox
  "A checkbox for controlling an order of status"
  [state status]
  (let [checkbox (crate/html [:input {:type "checkbox"
                                      :name "orders"
                                      :value "orders"
                                      :class "orders-checkbox"
                                      :checked true}])
        control-text (crate/html
                      [:div {:class "setCenterText map-control-font"}
                       checkbox status
                       (legend-symbol (get-in @state [:status
                                                      (keyword status)
                                                      :color]))])]
    (.addEventListener checkbox "click" #(do (if (aget checkbox "checked")
                                               (swap! state assoc-in
                                                      [:status (keyword status)
                                                       :selected?] true)
                                               (swap! state assoc-in
                                                      [:status (keyword status)
                                                       :selected?] false))
                                             (display-selected-props!
                                              state
                                              (:orders @state)
                                              "circle"
                                              (partial order-displayed? state)
                                              )))
    control-text))

(defn orders-status-control
  "A control for viewing orders on the map for courier managers"
  [state]
  (crate/html [:div
               [:div
                {:class "setCenterUI"
                 :title "Select order status"}
                (map #(order-status-checkbox state %)
                     '("unassigned"
                       "accepted"
                       "enroute"
                       "servicing"
                       "complete"
                       "cancelled"))]]))

(defn orders-date-control
  "A control for picking the orders range of dates"
  [state]
  (let [date-picker-input #(crate/html [:input {:type "text"
                                                :name "orders-date"
                                                :class "date-picker"
                                                :value %}])
        date-picker #(js/Pikaday.
                      (js-obj "field" %1
                              "format" "YYYY-MM-DD"
                              "onSelect"
                              %2))
        from-input (date-picker-input (:from-date @state))
        from-date-picker (date-picker from-input
                                      #(do
                                         (swap! state assoc :from-date
                                                from-input.value)
                                         (display-selected-props!
                                          state (:orders @state)
                                          "circle"
                                          (partial order-displayed? state))))
        to-input (date-picker-input (:to-date @state))
        to-date-picker (date-picker
                        to-input #(do
                                    (swap! state assoc :to-date to-input.value)
                                    (display-selected-props!
                                     state (:orders @state)
                                     "circle"
                                     (partial order-displayed? state))))]
    (crate/html [:div
                 [:div {:class "setCenterUI"
                        :title "Click to change dates"}
                  [:div {:class "setCenterText map-control-font"}
                   "Orders"
                   [:br]
                   "From: "
                   from-input
                   [:br]
                   "To:   " to-input]]])))

(defn couriers-control
  "A checkbox for controlling whether or not couriers are shown
  on the map"
  [state]
  (let [checkbox (crate/html [:input {:type "checkbox"
                                      :name "couriers"
                                      :value "couriers"
                                      :class "couriers-checkbox"
                                      :checked true}])
        control-text (crate/html
                      [:div {:class "setCenterText map-control-font"}
                       checkbox "Couriers"
                       [:br]
                       "Busy"
                       (legend-symbol (get-in @state
                                              [:couriers-control :color])
                                      "#ff0000")
                       [:br]
                       "Not Busy"
                       (legend-symbol (get-in @state
                                              [:couriers-control :color])
                                      "#00ff00")
                       ])]
    (.addEventListener
     checkbox "click" #(do (if (aget checkbox "checked")
                             (swap! state assoc-in
                                    [:couriers-control :selected?] true)
                             (swap! state assoc-in
                                    [:couriers-control :selected?] false))
                           (display-selected-props!
                            state
                            (:couriers @state)
                            "circle"
                            (partial courier-displayed? state)
                            )))
    (crate/html [:div [:div {:class "setCenterUI" :title "Select couriers"}
                       control-text]])))

(defn city-button
  "Create a button for selecting city-name"
  [state city-name]
  (let [city-button (crate/html [:div {:class "map-control-font cities"}
                                 city-name])]
    (.addEventListener
     city-button
     "click"
     #(do (.log js/console (str "I clicked "
                                city-name))
          (.setCenter (:google-map @state)
                      (get-in @state [:cities city-name :coords]))))
    city-button))

(defn city-control
  "A control for selecting which City to view"
  [state]
  (let [cities      (keys (:cities @state))]
    (crate/html [:div [:div {:class "setCenterUI" :title "cities"}
                       (crate/html
                        [:div (map (partial city-button state) cities)])]])))

(defn add-control!
  "Add control to g-map using position"
  [g-map control position]
  (.push  (aget g-map "controls" position)
          control))


(defn continous-update
  "Call f continously every n seconds"
  [f n]
  (js/setTimeout #(do (f)
                      (continous-update f n))
                 n))

(defn ^:export init-map-orders
  "Initialize the map for viewing all orders"
  []
  (do
    (swap! state
           assoc
           :google-map
           (js/google.maps.Map.
            (.getElementById js/document "map")
            (js-obj "center"
                    (js-obj "lat" 34.0714522 "lng" -118.40362)
                    "zoom" 12) ))
    ;; add listener for when the map is zoomed
    (.addListener (:google-map @state) "zoom_changed"
                  #(mapv (partial scale-spatial-object-circle-radius!
                                  (.getZoom (:google-map @state)))
                         (:orders @state)))
    ;; add controls
    (add-control! (:google-map @state) (crate/html
                                        [:div
                                         (orders-date-control state)
                                         (orders-status-control state)
                                         ])
                  js/google.maps.ControlPosition.LEFT_TOP)
    ;; initialize the orders
    (init-orders! state "")
    ;; poll the server and update orders
    (continous-update #(do (sync-orders! state))
                      (* 10 60 1000))))

(defn ^:export init-map-couriers
  "Initialize the map for manager couriers"
  []
  (do
    (swap! state
           assoc
           :google-map
           (js/google.maps.Map.
            (.getElementById js/document "map")
            (js-obj "center"
                    ;;(js-obj "lat" 34.0714522 "lng" -118.40362)
                    (get-in @state [:cities "Los Angeles" :coords])
                    "zoom" 12)))
    ;; listener for map zoom
    (.addListener (:google-map @state) "zoom_changed"
                  #(do
                     (mapv (partial scale-spatial-object-circle-radius!
                                    (.getZoom (:google-map @state)))
                           (:orders @state))
                     (mapv (partial scale-spatial-object-circle-radius!
                                    (.getZoom (:google-map @state)))
                           (:couriers @state))
                     (mapv (partial set-obj-label-position! state)
                           (:couriers @state))))
    ;; add controls
    (add-control! (:google-map @state) (crate/html
                                        [:div
                                         (orders-status-control state)
                                         (crate/html
                                          [:div (couriers-control state)])
                                         (city-control state state)
                                         ])
                  js/google.maps.ControlPosition.LEFT_TOP)
    ;; initialize the orders
    (init-orders! state (.format (js/moment) "YYYY-MM-DD"))
    ;; initialize the couriers
    (init-couriers! state)
    ;; poll the server and update the orders and couriers
    (continous-update #(do (sync-couriers! state)
                           (sync-orders! state))
                      (:timeout-interval @state))))
