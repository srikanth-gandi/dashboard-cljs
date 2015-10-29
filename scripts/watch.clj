(require '[cljs.build.api :as b])

(b/watch "src"
         {:main 'dashboard-cljs.core
          :output-to "out/dashboard_cljs.js"
          :output-dir "out"
          :verbose true
          :foreign-libs [{:file "resources/js/pikaday.js"
                          :provides ["pikaday"]}
                         {:file "resources/js/moment.js"
                          :provides ["moment"]}]})
