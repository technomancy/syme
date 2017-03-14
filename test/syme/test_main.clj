(ns syme.test-main
  (:require [clojure.test :refer :all]
            [syme.web :refer [app]]))

(deftest test-app
  (is (= 200 (:status (app {:uri "/" :request-method :get})))))
