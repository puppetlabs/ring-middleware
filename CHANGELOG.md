# 2.0.1
* Updates tk-jetty-10 to 1.0.7 which includes a fix for ring handler's getRequestCharacterEncoding() function.

# 2.0.0

* This version updates testing in the repo to use trapperkeeper-webserver-jetty10. The repo
  is now intended to be used with Jetty 10.

# 1.3.0
* This version updates the `wrap-add-cache-headers` middleware so that
  it adds a `cache-control` header with the "no-store" directive instead of
  the "private, max-age=0, no-cache" directives.

# 1.2.0
* This version adds `wrap-params` middleware with custom implementation of the
  `params-request` function. This was copied from the puppetserver repo to this
  more-central location. It is documented in the [README](./README.md).

# 1.1.0
* This version adds two new middleware used in other
  puppetlabs projects, documented in the [README](./README.md):
  * `wrap-add-x-content-nosniff`
  * `wrap-add-csp`

# 1.0.1
* This is a bug fix release that ensure stacktraces are correctly printed
  to the log when handling otherwise uncaught exceptions.

# 1.0.0
#### Breaking Changes
* Moves from `{:type ... :message ...}` to `{:kind ... :msg ...}` for
  exceptions and error responses.
* Moves schemas and helpers previously defined in `core` namespace into new `utils` namespace.

# 0.3.1
* This is a bug-fix release for a regression in wrap-proxy.
* All middleware now have the `:always-validate` metadata
  set for schema validation.

# 0.3.0
* This version adds many middleware that are used in other
  puppetlabs projects.  These middleware are mostly for logging
  and error handling, and they are all documented in the
  [README](./README.md):
  * `wrap-request-logging`
  * `wrap-response-logging`
  * `wrap-service-unavailable`
  * `wrap-bad-request`
  * `wrap-data-errors`
  * `wrap-schema-errors`
  * `wrap-uncaught-errors`
* Additionally, this version fixes
  [an issue](https://tickets.puppetlabs.com/browse/TK-228) with the
  behavior of `wrap-proxy` and its handling of redirects.

# 0.2.1
* Add wrap-with-certificate-cn middleware that adds a `:ssl-client-cn` key
  to the request map if a `:ssl-client-cert` is present.
* Add wrap-with-x-frame-options-deny middleware that adds `X-Frame-Options: DENY`

# 0.2.0
* Modify behavior of regex support in the wrap-proxy function.
  Now, when a regex is given for the `proxied-path` argument,
  the entirety of the request uri's path will be appended onto
  the path of `remote-uri-base`.
* Add a new utility middleware, `wrap-add-cache-headers`,
  that adds `cache-control` headers to `GET` and `PUT`
  requests.

# 0.1.3
* Log proxied requests
* Allow `proxied-path` argument in `wrap-proxy` function to
  be a regular expression
* Bump http-client to v0.2.8

# 0.1.2
* Add support for redirect following on proxy requests
* Fix issue where Gzipped proxy responses were being truncated
