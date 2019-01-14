(defproject jp.nijohando/event.websocket "0.1.2"
  :description "Experimental websocket client integrated with nijohando/event bus."
  :url "https://github.com/nijohando/event.websocket"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.0"]
                [javax.websocket/javax.websocket-client-api "1.0"]
                [org.eclipse.jetty.websocket/javax-websocket-client-impl "9.4.14.v20181114"]
                [jp.nijohando/failable "0.4.0"]
                [jp.nijohando/deferable "0.2.1"]
                [jp.nijohando/event "0.1.5"]]
  :source-paths ["src/clj"]
  :test-paths ["test"]
  :java-source-paths ["src/java"]
  :prep-tasks [["with-profile" "-dev" "javac"] "compile"]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.3.0-alpha4"]
                                  [http-kit "2.4.0-alpha2"]
                                  [jp.nijohando/ext.async "0.1.0"]]}}
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]])
