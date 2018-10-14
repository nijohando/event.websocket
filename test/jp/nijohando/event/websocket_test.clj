(ns jp.nijohando.event.websocket-test
  (:require [clojure.test :as t :refer [run-tests is are deftest testing use-fixtures]]
            [clojure.core.async :as ca]
            [jp.nijohando.ext.async :as xa]
            [jp.nijohando.event :as ev]
            [jp.nijohando.event.websocket :as ws]
            [jp.nijohando.deferable :as d]
            [jp.nijohando.failable :as f]
            [org.httpkit.server :as httpkit]))

(def ^:private ws-url (atom nil))

(defmacro with-echo-server
  [op-sym & body]
  `(d/do*
     (let [channels# (atom [])
           handler# (fn [request#]
                      (httpkit/with-channel request# channel#
                        (swap! channels# conj channel#)
                        (httpkit/on-close channel# (fn [status#]))
                        (httpkit/on-receive channel# (fn [data#]
                                                       (httpkit/send! channel# data#)))))
           stop# (httpkit/run-server handler# {:port 0})
           ~op-sym (fn [op-code#]
                     (condp = op-code#
                       :stop-server (stop#)
                       :disconnect-all-clients (do
                                                 (doseq[c# @channels#]
                                                   (httpkit/close c#))
                                                 (reset! channels# []))))
           _# (d/defer (f/do* (stop#)))
           port# (:local-port (meta stop#))]
       (reset! ws-url (str "ws://localhost:" port#))
       ~@body)))

(deftest connect-and-disconnect
  (testing "'connect' event must be emitted when connected to the server"
    (with-echo-server op
     (d/do*
       (let [bus (ws/client)
             _ (d/defer (ev/close! bus))
             listener (ca/chan)
             _ (d/defer (ca/close! listener))
             _ (d/defer (ws/disconnect! bus))]
         (ev/listen bus "/*" listener)
         (ws/connect! bus @ws-url)
         (let [x (xa/<!! listener :timeout 1000)]
           (is (f/succ? x))
           (is (= "/connect" (:path x))))))))
  (testing "'connect-failed' event must be emitted when failed to connect to the server"
    (with-echo-server op
      (d/do*
        (let [bus (ws/client)
              _ (d/defer (ev/close! bus))
              listener (ca/chan)
              _ (d/defer (ca/close! listener))
              _ (d/defer (ws/disconnect! bus))]
          (ev/listen bus "/*" listener)
          (ws/connect! bus "ws://nil.nijohando.jp") ;; nonexistent host for connection failed
          (let [x (xa/<!! listener :timeout 3000)]
            (is (f/succ? x))
            (is (= "/error" (:path x))))))))
  (testing "Just one 'connect' event must be emitted even if connecting multiple times"
    (with-echo-server op
      (d/do*
        (let [bus (ws/client)
              _ (d/defer (ev/close! bus))
              listener (ca/chan)
              _ (d/defer (ca/close! listener))
              _ (d/defer (ws/disconnect! bus))]
          (ev/listen bus "/*" listener)
          (dotimes [n 5]
            (ws/connect! bus @ws-url))
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/connect" (:path x))))
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/fail? x))
            (is (= ::xa/timeout @x)))))))
  (testing "'disconnect' event must be emitted when disconnected from myself"
    (with-echo-server op
      (d/do*
        (let [bus (ws/client)
              _ (d/defer (ev/close! bus))
              listener (ca/chan)
              _ (d/defer (ca/close! listener))]
          (ev/listen bus "/*" listener)
          (ws/connect! bus @ws-url)
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/connect" (:path x))))
          (ws/disconnect! bus)
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/disconnect" (:path x)))
            (is (= {:code 1000 :reason ""} (:value x))))))))
  (testing "Just one `disconnect` event must be emitted even if disconnecting multiple times"
    (with-echo-server op
      (d/do*
       (let [bus (ws/client)
             _ (d/defer (ev/close! bus))
             listener (ca/chan)
             _ (d/defer (ca/close! listener))]
         (ev/listen bus "/*" listener)
         (ws/connect! bus @ws-url)
         (let [x (xa/<!! listener :timeout 1000)]
           (is (f/succ? x))
           (is (= "/connect" (:path x))))
         (dotimes [n 5]
           (ws/disconnect! bus))
         (let [x (xa/<!! listener :timeout 1000)]
           (is (f/succ? x))
           (is (= "/disconnect" (:path x)))
           (is (= {:code 1000 :reason ""} (:value x))))
         (let [x (xa/<!! listener :timeout 1000)]
           (is (f/fail? x))
           (is (= ::xa/timeout @x)))))))
  (testing "'disconnect' event must be emitted when disconnected from the server"
    (with-echo-server op
      (d/do*
        (let [bus (ws/client)
              _ (d/defer (ev/close! bus))
              listener (ca/chan 10)
              _ (d/defer (ca/close! listener))]
          (ev/listen bus "/*" listener)
          (ws/connect! bus @ws-url)
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/connect" (:path x))))
          (op :disconnect-all-clients)
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/disconnect" (:path x)))
            (is (= {:code 1000 :reason ""} (:value x)))))))))

(deftest send-and-receive
  (testing "text message (string) can be sent and received"
    (with-echo-server op
      (d/do*
        (let [bus (ws/client)
              _ (d/defer (ev/close! bus))
              emitter (ca/chan)
              listener (ca/chan 10)
              _ (d/defer (ca/close! listener))]
          (ev/emitize bus emitter)
          (ev/listen bus ["/"
                          ["connect"]
                          ["message/text"]
                          ["error"]] listener)
          (ws/connect! bus @ws-url)
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/connect" (:path x))))
          (dotimes [n 10]
            (is (f/succ? (xa/>!! emitter (ev/event "/send/text" (str "hello" n)) timeout 1000))))
          (dotimes [n 10]
            (let [x (xa/<!! listener :timeout 1000)]
              (is (f/succ? x))
              (is (= "/message/text" (:path x)))
              (is (= (str "hello" n) (:value x)))))))))
  (testing "binary message can be sent and received"
    (with-echo-server op
      (d/do*
        (let [bus (ws/client)
              _ (d/defer (ev/close! bus))
              emitter (ca/chan)
              listener (ca/chan 10)
              _ (d/defer (ca/close! listener))]
          (ev/emitize bus emitter)
          (ev/listen bus ["/"
                          ["connect"]
                          ["message/binary"]
                          ["error"]] listener)
          (ws/connect! bus @ws-url)
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/connect" (:path x))))
          (dotimes [n 10]
            (xa/>!! emitter (ev/event "/send/binary" (.getBytes (str "hello" n )))))
          (dotimes [n 10]
            (let [x (xa/<!! listener :timeout 1000)]
              (is (f/succ? x))
              (is (= "/message/binary" (:path x)))
              (is (= (str "hello" n) (String. (:value x))))))))))

  (testing "ping message can be sent and pong received"
    (with-echo-server op
      (d/do*
        (let [bus (ws/client)
              _ (d/defer (ev/close! bus))
              emitter (ca/chan)
              listener (ca/chan)
              _ (d/defer (ca/close! listener))]
          (ev/emitize bus emitter)
          (ev/listen bus ["/"
                          ["connect"]
                          ["message/pong"]
                          ["error"]] listener)
          (ws/connect! bus @ws-url)
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/connect" (:path x))))
          (xa/>!! emitter (ev/event "/send/ping" (.getBytes "test")))
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/message/pong" (:path x)))
            (is (= "test" (String. (:value x)))))))))

  (testing "ping message without application data can be sent and pong received"
    (with-echo-server op
      (d/do*
        (let [bus (ws/client)
              _ (d/defer (ev/close! bus))
              emitter (ca/chan)
              listener (ca/chan)
              _ (d/defer (ca/close! listener))]
          (ev/emitize bus emitter)
          (ev/listen bus ["/"
                          ["connect"]
                          ["message/pong"]
                          ["error"]] listener)
          (ws/connect! bus @ws-url)
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/connect" (:path x))))
          (xa/>!! emitter (ev/event "/send/ping"))
          (let [x (xa/<!! listener :timeout 1000)]
            (is (f/succ? x))
            (is (= "/message/pong" (:path x)))
            (is (= "" (String. (:value x)))))))))
  (testing "Error event must occurs if a message is sent when websocket is closed"
    (d/do*
      (let [bus (ws/client)
            _ (d/defer (ev/close! bus))
            emitter (ca/chan)
            listener (ca/chan)
            _ (d/defer (ca/close! listener))]
        (ev/emitize bus emitter)
        (ev/listen bus ["/error"] listener)
        (xa/>!! emitter (ev/event "/send/ping"))
        (let [{:keys [path value] :as x} (xa/<!! listener :timeout 1000)]
          (is (f/succ? x))
          (is (= "/error" path))
          (is (f/fail? value))
          (is (= ::ws/send-failed @value))
          (is (= ::ws/closed @(f/cause value))))))))
