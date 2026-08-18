# Running and hosting the SDK demo

The demo (`demo-ui`) tells the member integration story end to end in a browser:
authenticate, find a partner, fill the partner's process form, submit a collaboration
request, and work the case lifecycle from both sides. It exists to show what the SDK
gives a member out of the box.

Authoritative repo docs: `docs/RUNBOOK.md` (build, start, credentials, tunnel,
shutdown) and `docs/USER_GUIDE.md` (how to drive the app). Read them from the checkout
when available. This reference compresses them and adds context they assume.

## What you need before starting

- JDK 21 (Homebrew's `openjdk@21` is keg-only; export `JAVA_HOME` explicitly)
- A source checkout with the private spec sibling (see SKILL.md "Building from
  source"): the demo is not published as an artifact, so someone with
  `tsanetgit/Connect-API-Code` access must build it, or supply the jar or image
- Member credentials for the environment you will demo against. For a bidirectional
  demo (both sides of a case), two member companies partnered with published process
  forms, each able to search and submit to the other

## Local run

```bash
export JAVA_HOME=<JAVA_21_HOME>
export PATH="$JAVA_HOME/bin:$PATH"
cd <CONNECT_SDK_CHECKOUT>
mvn -q -pl connect-library -am install -DskipTests
mvn -q -pl demo-ui -am package -DskipTests
java -jar demo-ui/target/demo-ui-0.0.1-SNAPSHOT.jar
```

The server listens on **http://localhost:8090**. Ctrl+C stops it, or
`pkill -f demo-ui-0.0.1-SNAPSHOT.jar`.

## Credentials and the header badge

1. Open the app, go to **Settings**. There is one card per environment; enter the
   member username and password on the environment you were issued, **Save
   Credentials**, then **Make Active**.
2. The header badge is the connection truth:
   - **Green "Company — email"**: authenticated; that is the live `/api/me` answer
   - **Amber "Not configured"**: nothing saved yet
   - **Amber "Auth failed: Connect API returned 500 — Error processing request"**: the
     environment rejected the credentials. The API's legacy error mode returns 500 for
     bad logins, so this almost always means a wrong username or password, not an
     outage.

Credentials persist per environment to `~/.tsanet-demo-ui/` (mode 600, never in git),
with an isolated SQLite cache per environment, so restarts skip this step. Settings →
**Clear** wipes an environment. The active environment persists across restarts.

## First session with real credentials: verification sweep

Two areas were built against assumptions and deserve one live confirmation. Walk them
once and note anything odd:

1. **New Collaboration** → search a partner → open the process form. Do dropdowns show
   one option per line, and does every field type render as a sensible input?
2. Open any **case detail**. Do the lifecycle action buttons match what the case state
   actually allows?

Rendering anomalies are typically one-line field-type mapping fixes; report them to
TSANet rather than working around them.

## Sharing options, weakest to strongest

### One call: ngrok tunnel

```bash
ngrok http 8090
```

Share the printed `https://` URL. Free-tier caveats: visitors click through an
interstitial page, and the URL changes on every restart. Kill the tunnel when the call
ends; never leave it up unattended, because the local app has no auth gate.

### Standing deployment: container

The root `Dockerfile` builds a **runtime-only** image: it packages a jar you already
built (the codegen needs the spec sibling, which the Docker build context lacks).
Build the jar first, then:

```bash
docker build --platform linux/amd64 -t connect-sdk-demo .
docker run --rm -p 8090:8090 \
  -e TSANET_DEMO_AUTH_USER=<PICK_A_USER> \
  -e TSANET_DEMO_AUTH_PASSWORD=<PICK_A_PASSWORD> \
  connect-sdk-demo
```

`--platform linux/amd64` matters when building on Apple silicon for an amd64 host.
The image runs as a non-root user and stores per-environment credentials and SQLite
caches under `TSANET_DEMO_DATA_DIR` (defaults to `/tmp/tsanet-demo`), which is
**ephemeral by design**: after every container restart or redeploy, re-enter the
member credentials in Settings.

### The auth gate is not optional when hosted

The app runs **open** when `TSANET_DEMO_AUTH_PASSWORD` is unset; that is only
acceptable on localhost. Any deployment reachable by others MUST set
`TSANET_DEMO_AUTH_USER` and `TSANET_DEMO_AUTH_PASSWORD`, which puts a browser
password prompt in front of everything. `/healthz` stays open for platform health
checks. Remember what sits behind the gate: a UI that holds live member credentials
and can create real collaboration cases against partner companies.

## Hosting notes

Any container host that can run an amd64 image with one exposed port and a health
check on `/healthz` works: AWS App Runner, ECS, Azure Container Apps, Cloud Run, or a
plain VM with Docker.

Operational pattern that has worked well: keep the service **paused or scaled to
zero between demos** (no compute billing, nothing exposed), resume a few minutes
before, pause after. Two cautions from experience:

- Do not delete and recreate a service to turn it off; public URLs are minted per
  service, and every share link dies. Pause is the off switch.
- AWS App Runner specifically stopped accepting new customers on 2026-04-30. Existing
  services keep running, but for a new deployment pick a different host (on AWS, ECS
  Express Mode fed by the same image is the natural successor).

Redeploy loop for an image-based host: rebuild the jar, rebuild and push the image,
trigger the host's deploy, then re-enter member credentials (ephemeral filesystem).

## The console app, if a browser is overkill

`TSANet-integration-app` packages the same library behind CLI commands, useful for
scripted walkthroughs or terminal-only environments:

```bash
mvn -q -pl TSANet-integration-app -am package -DskipTests
java -jar TSANet-integration-app/target/TSANet-integration-app-0.0.1-SNAPSHOT.jar
```

Then interactively: `api-login <MEMBER_USERNAME> <MEMBER_PASSWORD>`, `requests`,
`partners --search <NAME>`, `create-request ...`, `notes add ...`, `sync`. The full
command table is in `connect-library/README.md`. The app's webhook bridge listens on
port 8090 by default (`tsanet.webhook.port`), so do not run it alongside demo-ui
without changing one of the ports.
