## 0.2.0
* Modify behavior of regex support in the wrap-proxy function.
  Now, when a regex is given for the `proxied-path` argument,
  the entirety of the request uri's path will be appended onto
  the path of `remote-uri-base`.
* Add a new utility middleware, `wrap-add-cache-headers`,
  that adds `cache-control` headers to `GET` and `PUT`
  requests.

## 0.1.3
* Log proxied requests
* Allow `proxied-path` argument in `wrap-proxy` function to
  be a regular expression
* Bump http-client to v0.2.8

## 0.1.2

* Add support for redirect following on proxy requests
* Fix issue where Gzipped proxy responses were being truncated
