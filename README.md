## ring-middleware

This project is adapted from tailrecursion's
[ring-proxy](https://github.com/tailrecursion/ring-proxy) middleware, and is
meant for use with the [Trapperkeeper Jetty9 Webservice](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9).

### Usage

To use `ring-middleware`, include the following dependency in your project.clj file:

```clj
[puppetlabs/ring-middleware "0.1.2"]
```

This project provides a single function, `wrap-proxy`, with the following signature:

```clj
(wrap-proxy [handler proxied-path remote-uri-base & [http-opts])
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
#^/hello-world" to `http://localhost:9000/hello/[url-path]`.

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