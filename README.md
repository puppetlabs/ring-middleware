# ring-middleware

[![Build Status](https://travis-ci.org/puppetlabs/ring-middleware.png?branch=master)](https://travis-ci.org/puppetlabs/ring-middleware)

This project was originally adapted from tailrecursion's
[ring-proxy](https://github.com/tailrecursion/ring-proxy) middleware, and is
meant for use with the [Trapperkeeper Jetty9 Webservice](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9).  It also contains common ring middleware between Puppet projects and helpers to be used with the middleware.

## Usage


To use `ring-middleware`, add this project as a dependency in your leiningen project file:

[![Clojars Project](http://clojars.org/puppetlabs/ring-middleware/latest-version.svg)](https://clojars.org/puppetlabs/ring-middleware)

## Schemas

  * `ResponseType` -- one of the two supported response types (`:json`, `:plain`) returned by many middleware.
  * `RingRequest` -- a map containing at least a `:uri`, optionally a valid certificate, and any number of keyword-Any pairs.
  * `RingResponse` -- a map with at least `:status`, `:headers`, and `:body` keys.


## Non-Middleware Helpers
### json-response
```clj
(json-response status body)
```
Creates a basic ring response with `:status` of `status` and a `:body` of `body` serialized to json.

### plain-response
```clj
(plain-response status body)
```
Creates a basic ring response with `:status` of `status` and a `:body` of `body` set to UTF-8 plain text.


### throw-bad-request!
```clj
(throw-bad-request! "Error Message")
```
Throws a :bad-request type slingshot error with the supplied message.
See `wrap-bad-request` for middleware designed to compliment this function,
also `bad-request?` for a function to help implement your own error handling.

### throw-service-unavailable!
```clj
(throw-service-unavailable! "Error Message")
```
Throws a :service-unavailable type slingshot error with the supplied message.
See `wrap-service-unavailable` for middleware designed to compliment this function,
also `service-unavailable?` for a function to help implement your own error handling.

### throw-data-invalid!
```clj
(throw-data-invalid! "Error Message")
```
Throws a :data-invalid type slingshot error with the supplied message.
See `wrap-data-errors` for middleware designed to compliment this function,
also `data-invalid?` for a function to help implement your own error handling.


### bad-request?
```clj
(try+ (handler request)
  (catch bad-request? e
    (...handle a bad request...)))
```
Determines if the supplied slingshot error map is for a bad request.

### service-unavailable?
```clj
(try+ (handler request)
  (catch service-unavailable? e
    (...handle service unavailability...)))
```
Determines if the supplied slingshot error map is for the service being unavailable.

### data-invalid?
```clj
(try+ (handler request)
  (catch data-invalid? e
    (...handle invalid data...)))
```
Determines if the supplied slingshot error map is for invalid data.

### schema-error?
```clj
(try+ (handler request)
  (catch schema-error? e
    (...handle schema error...)))
```
Determines if the supplied slingshot error map is for a schema error.



## Middleware
### wrap-request-logging
```clj
(wrap-request-logging handler)
```
Logs the `:request-method` and `:uri` at debug level, the full request at trace.  At the trace level, attempts to remove sensitive auth information and replace client certificate with the client's common name.

### wrap-response-logging
```clj
(wrap-response-logging handler)
```
Logs the response at the trace log level.

### wrap-proxy
```clj
(wrap-proxy handler proxied-path remote-uri-base & [http-opts])
```

This function returns a ring handler that, when given a URL with a certain prefix, proxies the request
to a remote URL specified by the `remote-uri-base` argument.

The arguments are as follows:

* `handler`: A ring-handler that will be used if the provided url does not begin with the proxied-path prefix
* `proxied-path`: The URL prefix of all requests that are to be proxied. This can be either a string or a
   regular expression pattern. Note that, when this is a regular expression, the entire request URI
   will be appended to `remote-uri-base` when the URI is being rewritten, whereas if this argument
   is a string, the `proxied-path` will not be included.
* `remote-uri-base`: The base URL that you want to proxy requests with the `proxied-path` prefix to
* `http-opts`: An optional list of options for an http client. This is used by the handler returned by
  `wrap-proxy` when it makes a proxied request to a remote URI. For a list of available options, please
  see the options defined for [clj-http-client](https://github.com/puppetlabs/clj-http-client).

For example, the following:

```clj
(wrap-proxy handler "/hello-world" "http://localhost:9000/hello")
```
would return a ring handler that proxies all requests with URL prefix "/hello-world" to
`http://localhost:9000/hello`.

The following:

```clj
(wrap-proxy handler #"^/hello-world" "http://localhost:9000/hello")
```
would return a ring handler that proxies all requests with a URL path matching the regex
`#^/hello-world"` to `http://localhost:9000/hello/[url-path]`.

#### Proxy Redirect Support

By default, all proxy requests using `wrap-proxy` will follow any redirects, including on POST and PUT
requests. To allow redirects but restrict their use on POST and PUT requests, set the `:force-redirects`
option to `false` in the `http-opts` map. To disable redirect following on proxy requests, set the
`:follow-redirects` option to `false` in the `http-opts` map. Please not that if proxy redirect following
is disabled, you may have to disable it on the client making the proxy request as well if the location returned
by the redirect is relative.

#### SSL Support

`wrap-proxy` supports SSL. To add SSL support, you can set SSL options in the `http-opts` map as you would in
a request made with [clj-http-client](https://github.com/puppetlabs/clj-http-client). Simply set the
`:ssl-cert`, `:ssl-key`, and `:ssl-ca-cert` options in the `http-opts` map to be paths to your .pem files.

### wrap-with-certificate-cn

This middleware adds a `:ssl-client-cn` key to the request map if a
`:ssl-client-cert` is present. If no client certificate is present,
  the key's value is set to nil. This makes for easier certificate
whitelisting (using the cert whitelisting function from pl/kitchensink)

### wrap-add-cache-headers

A utility middleware with the following signature:

```clj
(wrap-add-cache-headers handler)
```

This middleware adds `cache-control` headers ("private, max-age=0, no-cache") to `GET` and `PUT` requests if they are handled by the handler.

### wrap-add-x-frame-options-deny

A utility middleware with the following signature:

```clj
(wrap-add-x-frame-options-deny handler)
```

This middleware adds `X-Frame-Options: DENY` headers to requests if they are handled by the handler.

### wrap-data-errors
```clj
(wrap-data-errors handler)
```
Always returns a status code of 400 to the client and logs the error message at the "error" log level.
Catches and processes any exceptions thrown via `slingshot/throw+` with a `:type` of one of:
  * `:request-data-invalid`
  * `:user-data-invalid`
  * `:data-invalid`
  * `:service-status-version-not-found`

Returns a basic ring response map with the `:body` set to the JSON serialized representation of the exception thrown wrapped in a map and accessible by the "error" key.

Example return body:
```json
{
  "error": {
    "type": "user-data-invalid",
    "message": "Error Message From Thrower"
  }
}
```

Returns valid [`ResponseType`](#schemas)s, eg:
```clj
(wrap-data-errors handler :plain)
```

### wrap-bad-request
```clj
(wrap-bad-request handler)
```
Always returns a status code of 400 to the client and logs the error message at the "error" log level.
Catches and processes any exceptions thrown via `slingshot/throw+` with a `:type` of one of:
  * `:bad-request`

Returns a basic ring response map with the `:body` set to the JSON serialized representation of the exception thrown wrapped in a map and accessible by the "error" key.

Example return body:
```json
{
  "error": {
    "type": "bad-request",
    "message": "Error Message From Thrower"
  }
}
```

Returns valid [`ResponseType`](#schemas)s, eg:
```clj
(wrap-bad-request handler :plain)
```

### wrap-schema-errors
```clj
(wrap-schema-errors handler)
```
Always returns a status code of 500 to the client and logs an message containing the schema error, expected value, and exception type at the "error" log level.

Returns a basic ring response map with the `:body` as the JSON serialized representation of helpful exception information wrapped in a map and accessible by the "error" key.  Always returns an error type of "application-error".

Example return body:
```json
{
  "error": {
    "type": "application-error",
    "message": "Something unexpected happened: {:error ... :value ... :type :schema.core/error}"
  }
}
```

Returns valid [`ResponseType`](#schemas)s, eg:
```clj
(wrap-schema-errors handler :plain)
```

### wrap-uncaught-errors
```clj
(wrap-uncaught-errors handler)
```
Always returns a status code of 500 to the client and logs a message with the serialized Exception at the "error" log level.

Returns a basic ring response map with the `:body` set as the JSON serialized representation of helpful exception information wrapped in a map and accessible by the "error" key.  Always returns an error type of "application-error".

Example return body:
```json
{
  "error": {
    "type": "application-error",
    "message": "Internal Server Error: <serialized Exception>"
  }
}
```

Returns valid [`ResponseType`](#schemas)s, eg:
```clj
(wrap-uncaught-errors handler :plain)
```

## Support

Please log tickets and issues at our [Trapperkeeper Issue Tracker](https://tickets.puppetlabs.com/browse/TK).
In addition there is a #trapperkeeper channel on Freenode.

Maintainers: Justin Stoller <justin@puppet.com>, Kevin Corcoran <kevin.corcoran@puppet.com>, Matthaus Owens <haus@puppet.com>
