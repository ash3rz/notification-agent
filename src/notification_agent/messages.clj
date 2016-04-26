(ns notification-agent.messages
  (:use [notification-agent.config]
        [notification-agent.time]
        [slingshot.slingshot :only [throw+]])
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as client]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [notification-agent.amqp :as amqp]
            [notification-agent.db :as db]
            [notification-agent.scheduler :as scheduler])
  (:import [java.io IOException]))

(defn- fix-timestamp
  "Some timestamps are stored in the default timestamp format used by
   JavaScript.  The DE needs all timestamps to be represented as milliseconds
   since the epoch.  This function fixes timestamps that are in the wrong
   format."
  [timestamp]
  (let [ts (str timestamp)]
    (if (re-matches #"^\d*$" ts) ts (str (timestamp->millis ts)))))

(defn- opt-update-in
  "Updates a value in a map if that value exists."
  [m ks f & args]
  (let [value (get-in m ks)]
    (if (nil? value) m (apply update-in m ks f args))))

(defn reformat-message
  "Converts a message from the format stored in the notification database to the
   format that the DE expects."
  [uuid state & {:keys [seen deleted] :or {seen false deleted false}}]
  (-> state
      (assoc-in [:message :id] uuid)
      (opt-update-in [:message :timestamp] fix-timestamp)
      (opt-update-in [:payload :startdate] fix-timestamp)
      (opt-update-in [:payload :enddate] fix-timestamp)
      (dissoc :email_request)
      (assoc :seen seen :deleted deleted)
      (assoc :type (string/replace (or (:type state) "") #"_" " "))))

(defn- send-email-request
  "Sends an e-mail request to the iPlant e-mail service."
  [notification-uuid {:keys [template to] :as request}]
  (log/debug "sending an e-mail request:" request)
  (let [json-request (cheshire/encode request)]
    (client/post (email-url)
                 {:body         json-request
                  :content-type :json})
    (db/record-email-request notification-uuid template to json-request)))

(defn- persist-msg
  "Persists a message in the notification database."
  [{type :type username :user {subject :text created-date :timestamp} :message :as msg}]
  (log/debug "saving a message in the notification database:" msg)
  (db/insert-notification
   (or type "analysis") username subject created-date (cheshire/encode msg)))

(defn persist-and-send-msg
  "Persists a message in the notification database and sends it to any receivers
   and returns the state object."
  [{:keys [user] :as msg}]
  (let [uuid          (persist-msg msg)
        email-request (:email_request msg)]
    (log/debug "UUID of persisted message:" uuid)
    (when-not (nil? email-request)
      (.start (Thread. #(send-email-request uuid email-request))))
    (amqp/publish-msg user (reformat-message uuid msg))))

(defn- optional-insert-system-args
  [msg]
  (->> [[:activation_date (str (timestamp->millis (:activation_date msg)))]
        [:dismissible     (:dismissible msg)]
        [:logins_disabled (:logins_disabled msg)]]
       (remove (fn [[_ v]] (nil? v)))
       (flatten)))

(defn persist-system-msg
  "Persists a system notification in the database."
  [msg]
  (let [type                (:type msg)
        ddate               (str (timestamp->millis (:deactivation_date msg)))
        message             (:message msg)
        insert-system-notif (partial db/insert-system-notification type ddate message)
        sys-args            (optional-insert-system-args msg)
        sys-notification    (apply insert-system-notif sys-args)]
    (scheduler/schedule-system-message sys-notification)))

(defn list-system-msgs
  [active-only type limit offset]
  {:system-messages (db/list-system-notifications active-only type limit offset)
   :total           (db/count-system-notifications active-only type)})

(defn get-system-msg
  [uuid]
  {:system-notification (db/get-system-notification-by-uuid uuid)})

(defn update-system-msg
  [uuid update-map]
  (let [system-msg (db/update-system-notification uuid update-map)]
    (db/delete-system-notification-acks uuid)
    (scheduler/reschedule-system-message system-msg)
    {:system-notification system-msg}))

(defn delete-system-msg
  [uuid]
  (if-let [msg (db/get-system-notification-by-uuid uuid)]
    (do (db/delete-system-notification uuid)
        {:system-notification msg})
    (throw+ {:type          :clojure-commons.exception/not-found
             :system_msg_id uuid})))

(defn get-system-msg-types
  []
  {:types (db/get-system-notification-types)})
