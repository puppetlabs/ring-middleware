# ring-middleware

This project is adapted from tailrecursion's
[ring-proxy](https://github.com/tailrecursion/ring-proxy) middleware, and is
meant for use with the [Trapperkeeper Jetty9 Webservice](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9).

## Usage


To use `ring-middleware`, add this project as a dependency in your leiningen project file:

[![Clojars Project](http://clojars.org/puppetlabs/ring-middleware/latest-version.svg)](https://clojars.org/puppetlabs/ring-middleware)

## wrap-proxy

This project provides a `wrap-proxy` function with the following signature:

```clj
(wrap-proxy [handler proxied-path remote-uri-base & [http-opts]])
```

This function returns a ring handler that, when given a URL with a certain prefix, proxies the request
to a remote URL specified by the `remote-uri-base` argument.

The arguments are as follows:

* `handler`: A ring-handler that will be used if the provided url does not begin with the proxied-path prefix
* `proxied-path`: The URL prefix of all requests that are to be proxied. This can be either a string or a
   regular expression pattern.
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

### Proxy Redirect Support

By default, all proxy requests using `wrap-proxy` will follow any redirects, including on POST and PUT
requests. To allow redirects but restrict their use on POST and PUT requests, set the `:force-redirects`
option to `false` in the `http-opts` map. To disable redirect following on proxy requests, set the
`:follow-redirects` option to `false` in the `http-opts` map. Please not that if proxy redirect following
is disabled, you may have to disable it on the client making the proxy request as well if the location returned
by the redirect is relative.

### SSL Support

`wrap-proxy` supports SSL. To add SSL support, you can set SSL options in the `http-opts` map as you would in
a request made with [clj-http-client](https://github.com/puppetlabs/clj-http-client). Simply set the
`:ssl-cert`, `:ssl-key`, and `:ssl-ca-cert` options in the `http-opts` map to be paths to your .pem files.

## wrap-add-cache-headers

A utility middleware with the following signature:

```clj
(wrap-add-cache-headers [handler])
```

This utility function returns a ring handler that will add `cache-control` headers ("private, max-age=0, no-cache") to `GET` and `PUT` if they are handled by the handler.
