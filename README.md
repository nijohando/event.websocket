# event.websocket

[![Clojars Project](https://img.shields.io/clojars/v/jp.nijohando/event.websocket.svg)](https://clojars.org/jp.nijohando/event.websocket)
[![CircleCI](https://circleci.com/gh/nijohando/event.websocket.svg?style=shield)](https://circleci.com/gh/nijohando/event.websocket)

Experimental websocket client integrated with [nijohando/event](https://github.com/nijohando/event) bus.

## Installation

#### Ligningen / Boot

```clojure
[jp.nijohando/event.websocket "0.1.0"]
```

#### Clojure CLI / deps.edn

```clojure
jp.nijohando/event.websocket {:mvn/version "0.1.0"}
```

## Usage

```clojure
(require '[jp.nijohando.event :as ev]
         '[jp.nijohando.event.websocket :as ws]
         '[clojure.core.async :as ca])
```

#### Bus integration

This library provides only 3 functions that are `client`, `connect!` and `disconnect!`.


Function `client` creates an event bus that acts as a websocket client.

```clojure
(def bus (ws/client))
```

Function `connect!` connects the bus with the websocket server.

```clojure
(ws/connect! bus "wss://echo.websocket.org")
```

Function `disconnect!` disconnects the session from the websocket server.

```clojure
(ws/disconnect! bus)
```

All other operations are channel based operations with [nijohando/event](https://github.com/nijohando/event) API.


### Listening to websocket events

Various events related to websocket can be read from the listener channel.

```clojure
(def bus (ws/client))
(def listener (ca/chan))
(ev/listen bus "/*" listener)
(ca/go-loop []
  (when-some [{:keys [path value] :as event} (ca/<! listener)]
    (condp = path
      "/connect"           (prn "connected!")
      "/connect-failed"    (prn "connect-failed!")
      "/disconnect"        (prn "disconnected!")
      "/disconnect-failed" (prn "disconnect-failed!")
      "/message/text"      (prn "text message arrived!")
      "/message/binary"    (prn "binary message arrived!")
      "/message/pong"      (prn "pong message arrived!")
      "/error"             (prn "error!" value)
      (prn "other event " path))
    (recur)))
(ws/connect! bus "wss://echo.websocket.org")
;=> "connected!"
; "pong message arrived!"
```

### Sending messages 

Messages can be sent via the emitter channel.  

```clojure
(def bus (ws/client))
(def emitter (ca/chan))
(def listener (ca/chan))
(ev/emitize bus emitter)
(ev/listen bus ["/" ["connect"]
                    ["message/text"]
                    ["error"]] listener)
(ca/go-loop []
  (when-some [{:keys [path value] :as event} (ca/<! listener)]
    (condp = path
      "/connect"      (ca/>! emitter (ev/event "/send/text" "hello!"))
      "/message/text" (prn "echo message arrivded! " value)
      "/error"        (prn "error! " value))
    (recur)))
(ws/connect! bus "wss://echo.websocket.org")
;=> "echo message arrivded! " "hello!"
```

## License

© 2018 nijohando  

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.

