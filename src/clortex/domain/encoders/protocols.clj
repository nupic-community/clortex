(ns clortex.domain.encoders.protocols)

(defprotocol CLAEncoder
  "Encodes values into bit representations"
  (encoders [this] "returns the bit encoder functions")
  (encode-all [this value] "returns a verbose data structure for an encoding of value")
  (encode [this value] "returns a set of on-bits encoding value"))
