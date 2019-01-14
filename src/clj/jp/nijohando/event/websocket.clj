(ns jp.nijohando.event.websocket
  (:require [jp.nijohando.event :as ev]
            [jp.nijohando.event.protocols :as evp]
            [jp.nijohando.failable :as f]
            [clojure.core.async :as ca])
  (:import
   (java.nio ByteBuffer)
   (jp.nijohando.event.websocket WholeTextMessageHandler
                                 WholeBinaryMessageHandler
                                 PongMessageHandler)
   (java.net URI)
   (javax.websocket ContainerProvider
                    WebSocketContainer
                    Endpoint
                    Session
                    EndpointConfig
                    CloseReason
                    ClientEndpointConfig$Builder)
   (org.eclipse.jetty.websocket.jsr356 ClientContainer)))

(def websocket-container (delay(ContainerProvider/getWebSocketContainer)))

(defprotocol Client
  (-connect! [this url])
  (-disconnect! [this])
  (-on-open [this session])
  (-on-close [this session reason])
  (-on-error [this failure]))

(defn- new-endpoint
  [client]
  (proxy [Endpoint] []
    (onOpen [^Session session ^EndpointConfig config]
      (-on-open client session))
    (onClose [^Session session ^CloseReason close-reason]
      (let [reason {:code (.. close-reason getCloseCode getCode)
                    :reason (.. close-reason getReasonPhrase)}]
        (-on-close client session reason)))
    (onError [^Session session ^Throwable th]
      (-on-error client (f/wrap th ::error)))))

(defn connect!
  [client url]
  (-connect! client url))

(defn disconnect!
  [client]
  (-disconnect! client))

(defmulti ^:private send-message* (fn [remote type value] (keyword type)))
(defmethod send-message* :text [remote _ value]
  (.. remote (sendText value)))
(defmethod send-message* :binary [remote _ bytes]
  (.. remote (sendBinary (ByteBuffer/wrap bytes))))
(defmethod send-message* :ping [remote _ bytes]
  (.. remote (sendPing (when bytes
                         (ByteBuffer/wrap bytes)))))
(defmethod send-message* :default [_ type value]
  (-> (f/fail ::unknown-data-type)
      (assoc :type type :value value)))

(defn- send-message
  [session type value]
  (if (and session (.. session isOpen))
    (do
      (f/when-fail* [x (send-message* (.getBasicRemote session) type value)]
        (-> (f/wrap x ::send-failed)
            (assoc :type type
                   :value value))))
    (-> (f/fail ::closed)
        (f/wrap ::send-failed)
        (assoc :type type
               :value value))))

(defn client
  ([]
   (client nil))
  ([{:keys [buffer-size] :as opts}]
   (let [bus (ev/blocking-bus (or buffer-size 256))
         emitter (ca/chan)
         listener (ca/chan)
         error-event (fn [failure]
                       (ev/event "/error" failure))
         config (-> (ClientEndpointConfig$Builder/create)
                    (.build))
         state (ref :disconnected)
         current-session (agent nil)]
     (ev/emitize bus emitter)
     (ev/listen bus "/send/:type" listener)
     (ca/go-loop []
       (when-let [{:keys [value header]} (ca/<! listener)]
         (f/when-fail [x (send-message @current-session (get-in header [:route :path-params :type]) value)]
           (->> (error-event x)
                (ca/>! emitter)))
         (recur)))
     (reify
       Client
       (-connect! [this url]
         (let [endpoint (new-endpoint this)
               uri (URI/create url)]
          (dosync
            (when (= :disconnected @state)
              (ref-set state :connecting)
              (send-off current-session
                        (fn [_]
                          (f/if-succ* [x (.connectToServer @websocket-container endpoint config uri)]
                            (do
                              (dosync (ref-set state :connected))
                              x)
                            (do
                              (dosync (ref-set state :disconnected))
                              (ca/go
                                (->> (ev/event "/connect-failed" x)
                                     (ca/>! emitter)))
                              nil))))
              true))))
       (-disconnect! [this]
         (dosync
           (when (= :connected @state)
             (ref-set state :disconnecting)
             (send-off current-session
                       (fn [session]
                         (dosync (ref-set state :disconnected))
                         (when (and session (.isOpen session))
                           (f/when-fail* [x (.close session)]
                             (ca/go
                               (->> (ev/event "/disconnect-failed" x)
                                    (ca/>! emitter)))))
                         nil))
             true)))
       (-on-open [this session]
         (.addMessageHandler session
                             (reify
                               WholeTextMessageHandler
                               (onMessage [_ text]
                                 (->> (ev/event "/message/text" text)
                                      (ca/>!! emitter)))))
         (.addMessageHandler session
                             (reify
                               WholeBinaryMessageHandler
                               (onMessage [_ bytes]
                                 (->> (ev/event "/message/binary" bytes)
                                      (ca/>!! emitter)))))
         (.addMessageHandler session
                             (reify
                               PongMessageHandler
                               (onMessage [_ pongMessage]
                                 (->> (ev/event "/message/pong" (.. pongMessage getApplicationData array))
                                      (ca/>!! emitter)))))
         (ca/go
           (->> (ev/event "/connect")
                (ca/>! emitter))))
       (-on-close [_ session reason]
         (dosync (ref-set state :disconnected))
         (ca/go
           (->> (ev/event "/disconnect" reason)
                (ca/>! emitter))))
       (-on-error [_ failure]
         (ca/go
           (->> (error-event failure)
                (ca/>! emitter))))
       evp/Emittable
       (emitize [_ emitter-ch reply-ch]
         (ev/emitize bus emitter-ch reply-ch))
       evp/Listenable
       (listen [_ routes listener-ch]
         (ev/listen bus routes listener-ch))
       evp/Closable
       (close! [this]
         (ca/close! emitter)
         (ca/close! listener)
         (ev/close! bus))))))
