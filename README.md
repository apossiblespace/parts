<p>
  <img src="https://raw.github.com/apossiblespace/parts/main/resources/public/images/parts-logo-horizontal.svg" alt="Parts Logo"/>
</p>

-----

[![Clojure CI](https://github.com/apossiblespace/parts/actions/workflows/all-tests.yaml/badge.svg)](https://github.com/apossiblespace/parts/actions/workflows/all-tests.yaml)
[![License GPL 3][badge-license]](http://www.gnu.org/licenses/gpl-3.0.txt)

## About

[Parts](https://parts.ifs.tools) is a toolkit for therapists working with the [Internal Family Systems model](https://en.wikipedia.org/wiki/Internal_Family_Systems_Model). It provides a tool for easy, collaborative parts mapping, which can be used to facilitate conversations with clients during sessions.

## Development

### Run Clojure REPL

```shell
make repl
```

This will start a Clojure REPL that includes shadow-cljs (which we use to build our frontend). When we connect to this from CIDER (with `cider-connect-clj`), the `dev/repl` namespace will be automatically loaded.

From that namespace:

- The app server can be (re)started with `(go)`. This will also start the `shadow-cljs` process for building the frontend and watching for changes.
- We can swtich to the ClojureScript REPL with `(cljs-repl)`
- Use `:cljs/quit` to return to the Clojure REPL

### Unit tests

Run unit tests of the service using the kaocha test runner

```shell
make test
```

> If additional libraries are required to support tests, add them to the `:test/env` alias definition in `deps.edn`

`make test-watch` will run tests on file save, stopping the current test run on the first failing test.  Tests will continue to be watched until `Ctrl-c` is pressed.

## Deployment

We use Kamal to deploy a Docker container that will run the uberjar built with `make dist`.

Make sure that the `KAMAL_REGISTRY_PASSWORD` env var is exported so that the deploy can work.

Also edit config/deploy.yml to ensure it matches your setup. See the [Kamal docs](https://kamal-deploy.org/docs/installation/).

### Build and test locally

It’s possible to build an uberjar and run it locally to test before deploying:

```shell
make dist
java -jar target/parts-standalone.jar
```

### Deploy

A deployment can be started via:

```shell
make deploy
```

## License

Copyright © 2025 Gosha Tcherednitchenko / A Possible Space Ltd

[The GNU General Public License v3](https://www.gnu.org/licenses/gpl.html)

[badge-license]: https://img.shields.io/badge/license-GPL_3-green.svg
