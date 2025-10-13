<p>
  <img src="https://raw.github.com/apossiblespace/parts/main/resources/public/images/parts-logo-horizontal.svg" alt="Parts Logo"/>
</p>

-----

[![Clojure CI](https://github.com/apossiblespace/parts/actions/workflows/all-tests.yaml/badge.svg)](https://github.com/apossiblespace/parts/actions/workflows/all-tests.yaml)
[![License GPL 3][badge-license]](http://www.gnu.org/licenses/gpl-3.0.txt)

## About

[Parts](https://parts.ifs.tools) is a toolkit for therapists working with the [Internal Family Systems model](https://en.wikipedia.org/wiki/Internal_Family_Systems_Model). It provides a tool for easy, collaborative parts mapping, which can be used to facilitate conversations with clients during sessions.

## Development

Before starting development, install dependencies with:

```shell
make deps
```

This will install both the Clojure and the NPM dependencies required.

### Run Clojure REPL

```shell
make repl
```

This will start a Clojure REPL that includes shadow-cljs (which we use to build our frontend). When we connect to this from CIDER (with `cider-connect-clj`), the `dev/repl` namespace will be automatically loaded.

From that namespace:

- The app server can be (re)started with `(go)`. This will also start the `shadow-cljs` process for building the frontend and watching for changes.
- We can switch to the ClojureScript REPL with `(cljs-repl)`
- Use `:cljs/quit` to return to the Clojure REPL

### Build CSS

```shell
make css-watch
```

This will start PostCSS to watch for changes in `resources/styles/*.css`.

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
make dist && make run-dist
```

### Deploy

A deployment can be started via:

```shell
# Always run tests before deploying
make test && make deploy
```

## Production REPL Access

The application includes an nREPL server that can be enabled in production for debugging and live system inspection. The REPL is configured to:

- Only bind to localhost (127.0.0.1) for security
- Run on port 7888 by default
- Start automatically when `PARTS_REPL_PORT` environment variable is set

### Connecting to the Production REPL

#### Method 1: SSH Tunnel (Recommended)

Create an SSH tunnel to the production server:

```bash
# Replace 'parts' with your actual server hostname
ssh -L 7888:localhost:7888 parts
```

Then connect from your local machine:

```bash
# Using Clojure CLI
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' \
    -M -m nrepl.cmdline --connect --host localhost --port 7888

# Or using the project's nREPL alias
clj -M:nrepl --port 7888

# Using your editor (Emacs/CIDER, VS Code/Calva, etc.)
# Connect to localhost:7888
```

#### Method 2: Kamal App Exec

Use Kamal's exec command to connect directly within the container:

```bash
# Connect to REPL inside the container
kamal app exec --interactive 'clojure -Sdeps "{:deps {nrepl/nrepl {:mvn/version \"1.3.1\"}}}" -M -m nrepl.cmdline --connect --host localhost --port 7888'

# Or create a shell first
kamal app exec --interactive bash
# Then inside the container:
clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' \
        -M -m nrepl.cmdline --connect --host localhost --port 7888
```

#### Method 3: Kamal Alias (Optional)

Add this to your `config/deploy.yml` aliases section:

```yaml
aliases:
  repl: app exec --interactive 'clojure -Sdeps "{:deps {nrepl/nrepl {:mvn/version \"1.3.1\"}}}" -M -m nrepl.cmdline --connect --host localhost --port 7888'
```

Then use:
```bash
kamal repl
```

### Security Considerations

1. **Network Binding**: The REPL only binds to localhost by default. Never expose it to the public internet.

2. **Environment Variables**: Control REPL availability via environment variables:
   - `PARTS_REPL_PORT`: Set to enable REPL (e.g., "7888")
   - `PARTS_REPL_HOST`: Bind address (default: "127.0.0.1")

3. **Production Access**: Only enable REPL when needed. Consider removing `PARTS_REPL_PORT` from production config when not actively debugging.

### Useful REPL Commands

Once connected, you can inspect and modify the running system:

```clojure
;; Check system state
(require '[aps.parts.db :as db])
(db/query ["SELECT COUNT(*) FROM users"])

;; View current configuration
(require '[aps.parts.config :as conf])
(conf/config)

;; Monitor logs
(require '[com.brunobonacci.mulog :as mulog])
(mulog/log ::test-event :message "Hello from REPL")

;; Inspect HTTP server
(require '[aps.parts.server])
;; Access running server state...
```

### Troubleshooting

1. **REPL not starting**: Check logs for "nREPL server started" message
   ```bash
   kamal app logs --tail 100
   ```

2. **Connection refused**: Ensure SSH tunnel is active and REPL is enabled via environment variable

3. **Port already in use**: The application may have crashed without cleaning up. Restart the container:
   ```bash
   kamal app restart
   ```

### Disabling REPL

To disable REPL in production, remove or comment out the REPL environment variables in `config/deploy.yml`:

```yaml
env:
  clear:
    PARTS_DB_PATH: /app/db/parts.db
    # PARTS_REPL_PORT: 7888  # Commented out to disable
    # PARTS_REPL_HOST: 127.0.0.1
```

Then redeploy:
```bash
kamal deploy
```

## License

Copyright © 2025 Gosha Tcherednitchenko / A Possible Space Ltd

[The GNU General Public License v3](https://www.gnu.org/licenses/gpl.html)

[badge-license]: https://img.shields.io/badge/license-GPL_3-green.svg
