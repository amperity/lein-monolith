(ns broken-load-for-test)


(throw (ex-info "deliberately fails to load" {}))
