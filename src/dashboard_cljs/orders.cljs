(ns dashboard-cljs.orders
  (:require [cljs.core.async :refer [put!]]
            [cljsjs.moment]
            [clojure.string :as s]
            [reagent.core :as r]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [unix-epoch->hrf base-url
                                          cents->dollars]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            ))

(defn StaticTable
  "props contains:
  {
  :table-header  ; reagenet component to render the table header with
  :table-row     ; reagent component to render a row
  }
  data is the reagent atom to display with this table."
  [props data]
  (fn [props data]
    (let [table-data data
          sort-fn   (if (nil? (:sort-fn props))
                      (partial sort-by :id)
                      (:sort-fn props))
          ]
      [:table {:class "table table-bordered table-hover table-striped"}
       (:table-header props)
       [:tbody
        (map (fn [element]
               ^{:key (:id element)}
               [(:table-row props) element])
             data)]])))

(defn TableHeadSortable
  "props is:
  {
  :keyword        ; keyword associated with this field to sort by
  :sort-keyword   ; reagent atom keyword
  :sort-reversed? ; is the sort being reversed?
  }
  text is the text used in field"  
  [props text]
  (fn [props text]
    [:th
     {:class "fake-link"
      :on-click #(do
                   (reset! (:sort-keyword props) (:keyword props))
                   (swap! (:sort-reversed? props) not))}
     text
     (when (= @(:sort-keyword props)
              (:keyword props))
       [:i {:class (str "fa fa-fw "
                        (if @(:sort-reversed? props)
                          "fa-angle-down"
                          "fa-angle-up"))}])]))

(defn EditButton
  "Button for toggling editing? button"
  [editing?]
  (fn [editing?]
    [:button {:type "button"
              :class (str "btn btn-sm btn-default pull-right")
              :on-click #(swap! editing? not)
              }
     (if @editing?
       "Save"
       "Edit")]))

(defn order-row
  "A table row for an order in a table. current-order is the one currently being
  viewed"
  [current-order]
  (fn [order]
    [:tr {:class (when (= (:id order)
                          (:id @current-order))
                   "active")
          :on-click #(reset! current-order order)}
     ;; order status
     [:td (:status order)]
     ;; courier assigned
     [:td (:courier_name order)]
     ;; order placed
     [:td (unix-epoch->hrf
           (:target_time_start order))]
     ;; delivery time
     [:td (str (.diff (js/moment.unix (:target_time_end order))
                      (js/moment.unix (:target_time_start order))
                      "hours")
               " Hr")]
     ;; username
     [:td (:customer_name order)]
     ;; phone #
     [:td (:customer_phone_number order)]
     ;; email #
     [:td (:email order)]
     ;; stree address
     [:td
      [:i {:class "fa fa-circle"
           :style {:color (:zone-color @current-order)}}]
      (:address_street order)]]))


(defn order-table-header
  "props is:
  {
  :sort-keyword   ; reagent atom keyword used to sort table
  :sort-reversed? ; reagent atom boolean for determing if the sort is reversed
  }"
  [props]
  (fn [props]
    [:thead
     [:tr
      [TableHeadSortable
       (conj props {:keyword :status})
       "Status"]
      [TableHeadSortable
       (conj props {:keyword :courier_name})
       "Courier"
       ]
      [TableHeadSortable
       (conj props {:keyword :target_time_start})
       "Order Placed"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Delivery Time"]
      [TableHeadSortable
       (conj props {:keyword :customer_name})
       "Username"] 
      [TableHeadSortable
       (conj props {:keyword :customer_phone_number})
       "Phone #"] 
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Email"]
      [TableHeadSortable
       (conj props {:keyword :address_street})
       "Street Address"]]]))

(defn order-courier-select
  "Select for assigning a courier
  props is:
  {
  :select-courier ; reagent atom, id of currently selected courier
  :couriers        ; set of courier maps
  }"
  [props]
  (fn [{:keys [selected-courier couriers]} props]
    [:select
     {:value (if @selected-courier
               @selected-courier
               (:id (first couriers)))
      :on-change
      #(do (reset! selected-courier
                   (-> %
                       (aget "target")
                       (aget "value"))))}
     (map
      (fn [courier]
        ^{:key (:id courier)}
        [:option
         {:value (:id courier)}
         (:name courier)])
      couriers)]))

(defn ErrorComp
  [error-message]
  (fn [error-messsage]
    [:div {:class "alert alert-danger"
           :role "alert"}
     [:span {:class "sr-only"} "Error:"]
     error-message]))

(defn assign-courier
  [editing? order selected-courier couriers error]
  (retrieve-url
   (str base-url "assign-order")
   "POST"
   (js/JSON.stringify (clj->js {:order_id (:id @order)
                                :courier_id @selected-courier}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys true)]
                (when (:success response)
                  (let [order-status (if (= (:status @order)
                                            "unassigned")
                                       "accepted"
                                       (:status @order))

                        updated-order  (assoc
                                        @order
                                        :courier_id @selected-courier
                                        :courier_name
                                        (:name
                                         (first (filter (fn [courier]
                                                          (= @selected-courier
                                                             (:id courier)))
                                                        couriers)))
                                        :status order-status)]
                    (reset! order updated-order)
                    (reset! editing? false)
                    (reset! error "")
                    (put! datastore/modify-data-chan
                          {:topic "orders"
                           :data
                           #{updated-order}})))
                (when (not (:success response))
                  (reset! error (:message response))
                  ))))))

(defn order-courier-comp
  "Component for the courier filed of an order panel
  props is:
  {
  :editing?         ; ratom, is the field currently being edited?
  :assigned-courier ; id of the courier who is currently assigned to the order
  :couriers         ; set of courier maps
  :order            ; ratom, currently selected order
  }
  "
  [props]
  (let [error-message (r/atom "")]
    (fn [{:keys [editing? assigned-courier couriers order]}
         props]
      (let [selected-courier (r/atom assigned-courier)]
        [:h5 [:span {:class "info-window-label"} "Courier: "]
         ;; courier assigned (if any)
         (when (not @editing?)
           [:span (str (:courier_name @order) " ")])
         ;; assign courier button
         (when (not @editing?)
           [:button {:type "button"
                     :class "btn btn-xs btn-default"
                     :on-click #(reset! editing? true)}
            (if (nil? (:courier_name @order))
              "Assign Courier"
              "Reassign Courier"
              )])
         ;; courier select
         (when @editing?
           [order-courier-select {:selected-courier
                                  selected-courier
                                  :couriers couriers}])
         ;; save assignment
         " "
         (when (and @editing?)
           [:button {:type "button"
                     :class "btn btn-xs btn-default"
                     :on-click
                     #(assign-courier editing? order selected-courier couriers
                                      error-message)
                     }
            "Save assignment"
            ])
         (when (not (s/blank? @error-message))
           [ErrorComp (str "Courier could not be assigned! Reason: "
                           @error-message
                           "\n"
                           "Try saving the assignment again"
                           )])
         ]))))


(defn update-status
  [order status error-message]
  (retrieve-url
   (str base-url "update-status")
   "POST"
   (js/JSON.stringify (clj->js {:order_id (:id @order)}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys true)
                    status->next-status {"unassigned"  "assigned"
                                         "assigned"    "accepted"
                                         "accepted"    "enroute"
                                         "enroute"     "servicing"
                                         "servicing"   "complete"
                                         "complete"    nil
                                         "cancelled"   nil}]
                (when (:success response)
                  (let [updated-order
                        (assoc
                         @order
                         :status (status->next-status status))]
                    (reset! order updated-order)
                    (reset! error-message "")
                    (put! datastore/modify-data-chan
                          {:topic "orders"
                           :data #{updated-order}})))
                (when (not (:success response))
                  (reset! error-message (:message response))))))))

(defn cancel-order
  [order error-message]
  (retrieve-url
   (str base-url "cancel-order")
   "POST"
   (js/JSON.stringify (clj->js {:user_id (:user_id @order)
                                :order_id (:id @order)}))
   (partial xhrio-wrapper
            (fn [r]
              (let [response (js->clj r :keywordize-keys true)]
                (when (:success response)
                  (let [updated-order
                        (assoc
                         @order
                         :status "cancelled")]
                    (reset! order updated-order)
                    (reset! error-message "")
                    (put! datastore/modify-data-chan
                          {:topic "orders"
                           :data #{updated-order}})))
                (when (not (:success response))
                  (reset! error-message (:message response))
                  ))))))

(defn status-comp
  "Component for the status field of an order
  props is:
  {
  :editing?        ; ratom, is the field currently being edited?
  :status          ; string, the status of the order
  :order           ; ratom, currently selected order
  }"
  [props]
  (let [error-message (r/atom "")]
    (fn [{:keys [editing? status order]}
         props]
      [:h5 [:span {:class "info-window-label"} "Status: "]
       (str status " ")
       ;; advance order button
       (when-not (contains? #{"complete" "cancelled" "unassigned"}
                            status)
         [:button {:type "button"
                   :class "btn btn-xs btn-default"
                   :on-click #(update-status order status error-message)}
          ({"accepted" "Start Route"
            "enroute" "Begin Servicing"
            "servicing" "Complete Order"}
           status)])
       " "
       ;; cancel button
       (when
           (and (not @editing?)
                (not (contains? #{"complete" "cancelled"}
                                status)))
         [:button {:type "button"
                   :class "btn btn-xs btn-default btn-danger"
                   :on-click #(cancel-order order error-message)}
          "Cancel Order"])
       (when (not (s/blank? @error-message))
         [ErrorComp (str "Order status could be not be changed! Reason: "
                         @error-message)])
       ])))

(defn order-panel
  "Display detailed and editable fields for an order"
  [current-order]
  (fn [current-order]
    (let [editing-assignment? (r/atom false)
          editing-status?     (r/atom false)
          couriers
          ;; filter out the couriers to only those assigned
          ;; to the zone
          (sort-by :name (filter #(contains? (set (:zones %))
                                             (:zone @current-order))
                                 @datastore/couriers))
          assigned-courier (if (not (nil? (:courier_name @current-order)))
                             ;; there is a courier currently assigned
                             (:id (first (filter #(= (:courier_name
                                                      @current-order)
                                                     (:name % )) couriers)))
                             ;; no courier, assign the first one
                             (:id (first couriers)))
          order-status (:status @current-order)
          ]
      [:div {:class "panel-body"}
       [:h3 "Order Details"]
       [:div
        ;; order price
        [:h5 [:span {:class "info-window-label"} "Total Price: "]
         (cents->dollars (:total_price @current-order))
         " "
         ;; declined payment?
         (if (and (= (:status @current-order)
                     "complete")
                  (not= 0 (:total_price @current-order))
                  (or (s/blank? (:stripe_charge_id @current-order))
                      (not (:paid @current-order))))
           [:span {:class "text-danger"} "Payment declined!"])]
        ;; coupon code
        (when (not (s/blank? (:coupon_code @current-order)))
          [:h5 [:span {:class "info-window-label"} "Coupon: "]
           (:coupon_code @current-order)])
        ;; gallons and type
        [:h5 [:span {:class "info-window-label"} "Gallons: "]
         (str (:gallons @current-order) " (" (:gas_type @current-order)
              " Octane)")]
        ;; time order was placed
        [:h5 [:span {:class "info-window-label"} "Order Placed: "]
         (unix-epoch->hrf (:target_time_start @current-order))]
        ;; delivery time
        [:h5 [:span {:class "info-window-label"} "Delivery Time: "]
         (str (.diff (js/moment.unix (:target_time_end @current-order))
                     (js/moment.unix (:target_time_start @current-order))
                     "hours")
              " Hr")]
        ;; special instructions field
        (when (not (s/blank? (:special_instructions @current-order)))
          [:h5 [:span {:class "info-window-label"} "Special Instructions: "]
           (:special_instructions @current-order)])
        ;;  name
        [:h5 [:span {:class "info-window-label"} "Customer: "]
         (:customer_name @current-order)]
        ;;  phone number
        [:h5 [:span {:class "info-window-label"} "Phone: "]
         (:customer_phone_number @current-order)]
        ;;  email
        [:h5 [:span {:class "info-window-label"} "Email: "]
         (:email @current-order)]
        ;; rating
        (let [number-rating (:number_rating @current-order)]
          (when number-rating
            [:h5 [:span {:class "info-window-label"} "Rating: "]
             (for [x (range number-rating)]
               ^{:key x} [:i {:class "fa fa-star fa-lg"}])
             (for [x (range (- 5 number-rating))]
               ^{:key x} [:i {:class "fa fa-star-o fa-lg"}])
             ]))
        ;; review
        (when (not (s/blank? (:text_rating @current-order)))
          [:h5 [:span {:class "info-window-label"} "Review: "]
           (:text_rating @current-order)])
        ;; delivery address
        [:h5
         [:span {:class "info-window-label"} "Address: "]
         [:i {:class "fa fa-circle"
              :style {:color (:zone-color @current-order)}}]
         " "
         (:address_street @current-order)]
        ;; vehicle description
        [:h5
         [:span {:class "info-window-label"} "Vehicle: "]
         (str (:color (:vehicle @current-order))
              " "
              (:make (:vehicle @current-order))
              " "
              (:model (:vehicle @current-order)))]
        ;; license plate
        [:h5 {:class "info-window-label"} "License Plate: "
         (:license_plate @current-order)]
        ;; ETAs
        (when (:etas @current-order)
          [:h5
           [:span {:class "info-window-label"} "ETAs: "]
           (map (fn [eta]
                  ^{:key (:name eta)}
                  [:p (str (:name eta) " - ")
                   [:strong {:class (when (:busy eta)
                                      "text-danger")}
                    (:minutes eta)]])
                (sort-by :minutes (:etas @current-order)))])
        ;; assigned courier display and editing
        [order-courier-comp {:editing? editing-assignment?
                             :assigned-courier assigned-courier
                             :couriers couriers
                             :order current-order}]
        ;; status and editing
        [status-comp {:editing? editing-status?
                      :status order-status
                      :order current-order}]]])))

(defn orders-filter
  [selected-filter]
  ;;(let [selected (r/atom "show-all")])
  (fn [selected-filter]
    [:div {:class "btn-group"
           :role "group"
           :aria-label "filter group"}
     [:button {:type "button"
               :class
               (str "btn btn-default "
                    (when (= @selected-filter
                             "show-all")
                      "active"))
               :on-click #(reset! selected-filter "show-all")}
      "Show All"]
     [:button {:type "button"
               :class
               (str "btn btn-default "
                    (when (= @selected-filter
                             "declined")
                      "active"))
               :on-click #(reset! selected-filter "declined")}
      "Declined Payments"]]))

(defn refresh-button
  []
  (let [saving? (r/atom false)]
    (fn []
      [:button
       {:type "button"
        :class "btn btn-default"
        :on-click
        #(when (not @saving?)
           (reset! saving? true)
           (retrieve-url
            (str base-url "orders-since-date")
            "POST"
            (js/JSON.stringify
             (clj->js
              ;; just retrieve the last 20 days worth of orders
              {:date (-> (js/moment)
                         (.subtract 30 "days")
                         (.format "YYYY-MM-DD"))}))
            (partial xhrio-wrapper
                     (fn [response]
                       (let [orders (js->clj response
                                             :keywordize-keys true)]
                         (when (> (count orders)
                                  0)
                           ;; update the orders atom
                           (put! datastore/modify-data-chan
                                 {:topic "orders"
                                  :data orders})
                           ;; update the most recent order atom
                           (reset! datastore/most-recent-order
                                   (last (sort-by
                                          :target_time_start orders)))
                           ;; update the most last-acknowledged-order atom
                           (reset! datastore/last-acknowledged-order
                                   @datastore/most-recent-order)
                           (reset! saving? false)))))
            ))}
       [:i {:class (str "fa fa-lg fa-refresh "
                        (when @saving?
                          "fa-pulse"))}]])))

(defn new-orders-button
  []
  (fn []
    (let [new-orders (- (count @datastore/orders)
                        (count (filter
                                #(<= (:target_time_start %)
                                     (:target_time_start
                                      @datastore/last-acknowledged-order))
                                @datastore/orders)))]
      [:button {:type "button"
                :class "btn btn-default"
                :on-click
                #(reset! datastore/last-acknowledged-order
                         @datastore/most-recent-order)}
       (str "View " new-orders " New Orders")])))

(defn orders-panel
  "Display a table of selectable orders with an indivdual order panel
  for the selected order"
  [orders]
  (let [current-order (r/atom nil)
        sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        selected-filter (r/atom "show-all")]
    (fn [orders]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            filter-fn (cond (= @selected-filter
                               "declined")
                            (fn [order]
                              (and (not (:paid order))
                                   (= (:status order) "complete")
                                   (> (:total_price order))))
                            :else (fn [order] true))
            displayed-orders (filter #(<= (:target_time_start %)
                                          (:target_time_start
                                           @datastore/last-acknowledged-order))
                                     orders)
            sorted-orders (->> displayed-orders
                               sort-fn
                               (filter filter-fn))]
        (when (nil? @current-order)
          (reset! current-order (first sorted-orders)))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [order-panel current-order]
          [:h3 "Orders"]
          [:div {:class "btn-toolbar"
                 :role "toolbar"
                 :aria-label "Toolbar with button groups"}
           [orders-filter selected-filter]
           [:div {:class "btn-group"
                  :role "group"
                  :aria-label "refresh group"}
            (when (not (= @datastore/most-recent-order
                          @datastore/last-acknowledged-order))
              [new-orders-button])
            [refresh-button]]]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [order-table-header {:sort-keyword sort-keyword
                                               :sort-reversed? sort-reversed?}]
            :table-row (order-row current-order)}
           sorted-orders]]]))))
