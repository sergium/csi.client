(ns csi.core
  (:require
    [cljs.core.async :as async]
    [shodan.console :as log]
    [chord.client :as chord]
    [csi.etf.core :as etf]
    [cljs.core.async.impl.protocols :as p])

  (:require-macros
    [cljs.core.async.macros :refer [go alt! go-loop]]))

(defprotocol IErlangMBox
  (close! [_self])
  (send! [_ pid message])
  (call* [_ fn-def params])
  (self  [_]))


(defn- fn-def->meta-module-fn [fn-def]
  (let [[m f] (if (vector? fn-def)
                [(first fn-def) (second fn-def)]
                [nil fn-def])]
    [(or m :undefined) (namespace f) (name f)]))


(defn erlang-mbox* [socket {:keys [self] :as params}]
  (log/debug (str "creating mbox with params: " params))

  (let [messages (async/chan) replies (async/chan) replies-mult (async/mult replies) correlation (atom 0)]
    (go-loop []
      (when-let [message (:message (<! socket))]
        (let [[type body] (etf/decode message)]
          (>! (case type :message messages :reply replies) body)
          (recur)))
      (log/debug "web socket closed")
      (async/close! messages)
      (async/close! replies))

    (reify
      p/ReadPort
        (take! [_ handler]
          (p/take! messages handler))

      IErlangMBox
        (self [_]
          self)

        (close! [_]
          (async/close! socket))

        (call* [_ fn-def params]
          (go
            (let [replies (async/chan) 
                  correlation (swap! correlation inc)
                  [meta-data module function] (fn-def->meta-module-fn fn-def)]
              (assert function "invalid function")

              (async/tap replies-mult replies)
              (>! socket
                (etf/encode [:call [correlation meta-data] [(keyword (or module :erlang)) (keyword function)] (apply list params)]))

              (loop []
                (when-let [reply (<! replies)]
                  (let [[rcorrelatin return] reply]
                    (if (= correlation rcorrelatin) ; TODO: timeout handling
                      (do
                        (async/untap replies-mult replies) return)
                      (recur))))))))

        (send! [_ pid message]
          (let [encoded (etf/encode [:send pid message])]
            (go
              (>! socket encoded)))))))

(defn mbox [url]
  (go
    (if-let [socket (:ws-channel (<! (chord/ws-ch url {:format :str})))]
      (loop []
        (when-let [message (:message (<! socket))]
          (let [[type body] (etf/decode message)]
            (if (= type :setup)
              (erlang-mbox* socket body)
              (recur))))))))

(defn list-to-string [l]
  (apply str (map #(.fromCharCode js/String %) l)))

(defn string-to-list [s]
  (apply list
    (reduce
      (fn [acc ix]
        (conj acc
          (.charCodeAt s ix))) [] (range (count s)))))
