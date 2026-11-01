# Demo startup runbook

How to bring the Connect SDK Demo up from cold, once you have BETA member credentials.

## Conventions

Commands use placeholders for anything environment-specific. Substitute your own values:

| Placeholder | Meaning |
|---|---|
| `<REPO_ROOT>` | Parent directory holding your checkouts (this repo and the sibling spec repo) |
| `<DEMO_REPO>` | This repo's checkout, i.e. `<REPO_ROOT>/Connect_SDK` |
| `<JAVA_21_HOME>` | Your JDK 21 install (e.g. Homebrew keg-only `openjdk@21`) |
| `<AWS_ACCOUNT_ID>`, `<REGION>` | Your AWS account and region (deploy default region: `us-west-2`) |
| `<AWS_CLI_PROFILE>` | The AWS CLI profile/user with ECS + ECR + IAM + SSM + logs access |
| `<SERVICE_ARN>` | Your ECS Express Mode service ARN (`arn:aws:ecs:<REGION>:<AWS_ACCOUNT_ID>:service/default/connect-sdk-demo`) |
| `<EXECUTION_ROLE_ARN>`, `<INFRA_ROLE_ARN>` | The task execution role and the Express Mode infrastructure role (section 6) |
| `<LOG_GROUP>` | The CloudWatch log group Express Mode creates for the service |
| `<ECR_REGISTRY>` | `<AWS_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com` |

Keep your real values in a local, untracked file (e.g. `docs/RUNBOOK.local.md`) or your `.env` — never commit them.

## One-time prerequisites

- JDK 21 (a keg-only Homebrew `openjdk@21` needs the `JAVA_HOME` pin below)
- Sibling spec repo at `<REPO_ROOT>/Connect-API-Code` on the branch that matches this repo's: **`beta`** for `main` (what the hosted demo deploys), `develop` for `develop`
- ngrok installed and authenticated (only needed for sharing a public URL)
- AWS CLI configured with a deploy profile (only needed for hosted deploys)

## 1. Build (skip if no code changed since last build)

```bash
export JAVA_HOME=<JAVA_21_HOME>
export PATH="$JAVA_HOME/bin:$PATH"
cd <DEMO_REPO>
mvn -q -pl connect-library -am install -DskipTests
mvn -q -pl demo-ui -am package -DskipTests
```

If the build fails with `cannot find symbol ...`, the sibling spec repo is probably on the wrong branch. Check out the one that matches this repo's branch:

```bash
# This repo on main
cd <REPO_ROOT>/Connect-API-Code && git checkout beta

# This repo on develop
cd <REPO_ROOT>/Connect-API-Code && git checkout develop
```

## 2. Start the server

```bash
export JAVA_HOME=<JAVA_21_HOME>
"$JAVA_HOME/bin/java" -jar <DEMO_REPO>/demo-ui/target/demo-ui-0.0.1-SNAPSHOT.jar
```

Server listens on **http://localhost:8090**. Leave this terminal running; Ctrl+C stops it.

Both configured environments (BETA, DEV) are https and the app refuses to start if any
`api-base-url` is plain http. `tsanet.demo.allow-insecure-http=true` admits one for a local
mock only; leave it unset everywhere else.

To exercise end-to-end encryption locally, export the demo's private JWK set first (generate it as
in section 6; the value is the generator's whole one-line JSON):

```bash
export TSANET_DEMO_E2EE_PRIVATE_JWKS='<generator output>'
```

Unset, E2EE is off and the demo is unchanged: no Encrypt checkbox, no chips, no `/.well-known/jwks.json`.

## 3. Enter credentials (first time, or after clearing)

1. Open http://localhost:8090 → **Settings** tab — one card per environment (BETA / DEV)
2. Enter the member username and password on the right card → **Save Credentials**; **Make Active** to switch environments
3. The header badge should flip to green with your company name (that's `/api/me` succeeding against the active environment)

Point each environment card at a member account you control on that environment. For bidirectional testing you need two member companies that are partnered with published process forms (each can search and submit to the other). Store their credentials in your own `.env`/credential store, not in this repo.

Credentials persist per environment to `~/.tsanet-demo-ui/credentials-<env>.properties` (mode 600, never in git) — subsequent startups skip this step. **Settings → Clear** wipes them.

Badge decoder:
- **Green "Company — email"** — authenticated, everything live
- **Amber "Not configured"** — no credentials saved yet
- **Amber "Auth failed: Authentication Failed — …"** — the environment rejected the credentials (HTTP 401 problem details; a wrong password looks like this)

## 4. First-session verification sweep (once, with the first real credentials)

Two things were built against assumptions that need one live payload to confirm — run through these and note anything that renders wrong:

1. **New Collaboration** → search a partner → select → check the process form: do dropdowns show one option per line (options delimiter), and do all field types render as sensible inputs?
2. **Case detail** on any existing case → do the lifecycle action buttons match what the case state actually allows?

Report anomalies — the fixes are typically one-liners in `app.js` field-type mapping.

## 5. Optional: public URL for a live demo (ngrok)

```bash
ngrok http 8090
```

Copy the `https://*.ngrok-free.dev` URL it prints. Caveats (free tier): visitors see a one-click interstitial page first, and the URL changes every restart. Kill with Ctrl+C when the call is done — don't leave the tunnel up unattended.

## 6. Optional: hosted on AWS (ECS Express Mode)

App Runner stopped accepting new customers on 2026-04-30, so a new hosted demo goes on
**Amazon ECS Express Mode**: one Fargate task behind a managed Application Load Balancer with
TLS, created from the same image this repo's Dockerfile builds. Express Mode itself is free;
you pay for Fargate, the load balancer and logs while the service exists (there is no pause).

**Auth gate** is mandatory on any hosted deploy. Put the gate values and the Entra values in
SSM Parameter Store as SecureStrings and let the task read them as secrets, so nothing sits in
plaintext in the service configuration. The three `GUEST` values are optional: set the user and password to give
members a second login with the same rights and a lower upload limit. A guest's `POST`, `PUT` or `PATCH` must declare a
`Content-Length`, and any request's `Content-Length` must be within the limit. `TSANET_DEMO_AUTH_GUEST_MAX_UPLOAD_BYTES` is that limit in bytes,
1 GB when it isn't set; it isn't a secret, and goes through SSM here because that's this deploy's one route for the task's settings.
Without a guest login, delete all three names from the loop (SSM refuses an empty SecureString) and the three matching
`secrets` entries below. To keep the default guest username (`guest`), delete only the `GUEST_USER` name and entry. To keep
the 1 GB default, delete only the `GUEST_MAX_UPLOAD_BYTES` name and entry. The four
`SEED` values are the member credentials written into each environment's store at startup (see
the lock paragraph below); without them, delete those names, their `secrets` entries and the
`environment` block, and run the demo unlocked.

```bash
for v in TSANET_DEMO_AUTH_USER TSANET_DEMO_AUTH_PASSWORD TSANET_DEMO_AUTH_GUEST_USER TSANET_DEMO_AUTH_GUEST_PASSWORD TSANET_DEMO_AUTH_GUEST_MAX_UPLOAD_BYTES TSANET_DEMO_ENTRA_TENANT TSANET_DEMO_ENTRA_AUDIENCE_BETA TSANET_DEMO_ENTRA_AUDIENCE_DEV TSANET_DEMO_SEED_BETA_USERNAME TSANET_DEMO_SEED_BETA_PASSWORD TSANET_DEMO_SEED_DEV_USERNAME TSANET_DEMO_SEED_DEV_PASSWORD; do
  aws ssm put-parameter --region <REGION> --name "/connect-sdk-demo/$v" --type SecureString --value "<value>" --overwrite
done
```

**One-time setup** (needs admin): an ECR repository `connect-sdk-demo`, a default VPC with
public subnets in `<REGION>`, and two roles per the ECS guide
(https://docs.aws.amazon.com/AmazonECS/latest/developerguide/express-service-getting-started.html):
`ecsTaskExecutionRole` (trust `ecs-tasks.amazonaws.com`, managed policy
`AmazonECSTaskExecutionRolePolicy`, plus an inline policy allowing `ssm:GetParameters` on
`arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/*`) and
`ecsInfrastructureRoleForExpressServices` (trust `ecs.amazonaws.com`, managed policy
`AmazonECSInfrastructureRoleforExpressGatewayServices`). Wait a minute after creating roles
before the first create call: IAM is eventually consistent, and a create that runs too early
fails with "infrastructure role doesn't have enough permission".

**End-to-end encryption** (`tsanetgit/Connect_SDK#149`; optional). The demo holds one private
key pair of its own, read from `TSANET_DEMO_E2EE_PRIVATE_JWKS`, and publishes the public half at
`/.well-known/jwks.json` outside the gate. Generate the set once from the built jar; it prints the
whole JSON on one line to standard output and nowhere else (never log it, never commit it):

```bash
"$JAVA_HOME/bin/java" -cp <DEMO_REPO>/demo-ui/target/demo-ui-0.0.1-SNAPSHOT.jar \
  -Dloader.main=com.tsanet.demo.E2eeKeyGenerator org.springframework.boot.loader.launch.PropertiesLauncher
```

Put it in SSM as a SecureString like the gate values (a 2048-bit RSA private JWK plus a P-256 JWK
is under the standard tier's 4 KB), piped straight from the generator so it never sits in a file or
on a command line. Its `secrets` entry is already in the `primary-container.json` example below:

```bash
"$JAVA_HOME/bin/java" -cp <DEMO_REPO>/demo-ui/target/demo-ui-0.0.1-SNAPSHOT.jar -Dloader.main=com.tsanet.demo.E2eeKeyGenerator org.springframework.boot.loader.launch.PropertiesLauncher \
  | aws ssm put-parameter --region <REGION> --name /connect-sdk-demo/TSANET_DEMO_E2EE_PRIVATE_JWKS --type SecureString --overwrite --value file:///dev/stdin
```

One key pair serves both environments. The JWKS URL to give a partner is
`https://<service endpoint>/.well-known/jwks.json`; it answers 404 while the variable is unset. A
value that is set but unusable (not a JWK set, a public-only set, two RSA keys, a key under 2048
bits) fails startup with a message naming the variable and the rule, never the value. Rotation is
not supported: to replace the pair, put a new value and redeploy; records encrypted to the old
pair stop verifying.

The partner key table (which partner company ids the demo encrypts for and verifies from) is
entered per environment on the Settings card and stored in the data dir, so it is lost on redeploy
like the credentials. To restore it at startup set `TSANET_DEMO_E2EE_PARTNERS_BETA` and/or
`TSANET_DEMO_E2EE_PARTNERS_DEV` (SSM SecureStrings, with `secrets` entries in the example below) to the
table as JSON: each partner company id maps to `{"jwksUrl": "https://..."}` or
`{"jwks": {"keys": [...]}}` (public keys only; a set with a private key is refused at startup). The
seed applies only when that environment's table file is absent; a table edited on the card wins
until the next redeploy. On a locked demo (`TSANET_DEMO_CREDENTIALS_LOCKED`) the card cannot
edit the table, so the seed is the only way the pilot's keys get in. The same JSON shape is what the demo writes to `e2ee-partners-<env>.json`
in the data dir, so a table built on the card can be copied out as the seed.

If you give a guest login to people outside your team with E2EE on, run the demo locked, which
needs the member seeds (see the lock paragraph below). On an unlocked demo, any gate login can add
a partner JWKS URL, and the demo task then fetches it.

**Build, push, create.** Build the jar (step 1) from a checkout that has the release tags (`git fetch --tags`), and after the release is tagged: the page header shows `git describe` from the build, so a jar built earlier names the previous tag. Then:

```bash
cd <DEMO_REPO>
SHA=$(git rev-parse --short HEAD)
docker build --platform linux/amd64 -t connect-sdk-demo .
aws ecr get-login-password --region <REGION> | docker login --username AWS --password-stdin <ECR_REGISTRY>
docker tag connect-sdk-demo:latest <ECR_REGISTRY>/connect-sdk-demo:latest
docker tag connect-sdk-demo:latest "<ECR_REGISTRY>/connect-sdk-demo:${SHA}"
docker push <ECR_REGISTRY>/connect-sdk-demo:latest
docker push "<ECR_REGISTRY>/connect-sdk-demo:${SHA}"
```

`primary-container.json` (pin the sha tag, not `latest`, so the running build is always
readable from `describe-express-gateway-service`; delete the `secrets` entry for any value you
did not put in SSM, including the three `E2EE` entries when E2EE is off and any partner seed you
don't use):

```json
{
  "image": "<ECR_REGISTRY>/connect-sdk-demo:<SHA>",
  "containerPort": 8090,
  "secrets": [
    {"name": "TSANET_DEMO_AUTH_USER", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_USER"},
    {"name": "TSANET_DEMO_AUTH_PASSWORD", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_PASSWORD"},
    {"name": "TSANET_DEMO_AUTH_GUEST_USER", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_GUEST_USER"},
    {"name": "TSANET_DEMO_AUTH_GUEST_PASSWORD", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_GUEST_PASSWORD"},
    {"name": "TSANET_DEMO_AUTH_GUEST_MAX_UPLOAD_BYTES", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_GUEST_MAX_UPLOAD_BYTES"},
    {"name": "TSANET_DEMO_ENTRA_TENANT", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_ENTRA_TENANT"},
    {"name": "TSANET_DEMO_ENTRA_AUDIENCE_BETA", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_ENTRA_AUDIENCE_BETA"},
    {"name": "TSANET_DEMO_ENTRA_AUDIENCE_DEV", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_ENTRA_AUDIENCE_DEV"},
    {"name": "TSANET_DEMO_SEED_BETA_USERNAME", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_SEED_BETA_USERNAME"},
    {"name": "TSANET_DEMO_SEED_BETA_PASSWORD", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_SEED_BETA_PASSWORD"},
    {"name": "TSANET_DEMO_SEED_DEV_USERNAME", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_SEED_DEV_USERNAME"},
    {"name": "TSANET_DEMO_SEED_DEV_PASSWORD", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_SEED_DEV_PASSWORD"},
    {"name": "TSANET_DEMO_E2EE_PRIVATE_JWKS", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_E2EE_PRIVATE_JWKS"},
    {"name": "TSANET_DEMO_E2EE_PARTNERS_BETA", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_E2EE_PARTNERS_BETA"},
    {"name": "TSANET_DEMO_E2EE_PARTNERS_DEV", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_E2EE_PARTNERS_DEV"}
  ],
  "environment": [
    {"name": "TSANET_DEMO_CREDENTIALS_LOCKED", "value": "true"}
  ]
}
```

```bash
aws ecs create-express-gateway-service --region <REGION> \
  --service-name connect-sdk-demo \
  --execution-role-arn <EXECUTION_ROLE_ARN> \
  --infrastructure-role-arn <INFRA_ROLE_ARN> \
  --primary-container file://primary-container.json \
  --cpu 256 --memory 512 \
  --health-check-path /healthz \
  --scaling-target '{"minTaskCount":1,"maxTaskCount":1}'
```

On a brand-new account the first call may fail with "Unable to assume the service linked role";
that call creates `AWSServiceRoleForECS`, and a retry half a minute later goes through. Watch the
first deployment (about 12 minutes, it provisions the load balancer and alarms):

```bash
aws ecs list-service-deployments --region <REGION> --cluster default --service connect-sdk-demo
```

The public URL is in `describe-express-gateway-service` under
`activeConfigurations[].ingressPaths[].endpoint` (`https://<name>-<suffix>.ecs.<REGION>.on.aws`).
It is minted per service: deleting and recreating the service changes it. Expect `/healthz` 200
and `/` 401 (the gate).

**Ship a new build:** push the new sha tag, point `primary-container.json` at it, then

```bash
aws ecs update-express-gateway-service --region <REGION> --service-arn <SERVICE_ARN> --primary-container file://primary-container.json
```

Express Mode runs a canary deployment with 5XX rollback alarms. Rollback is the same update
with the previous sha tag. Logs: `aws logs tail <LOG_GROUP> --region <REGION> --since 30m`
(the group name is in `describe-express-gateway-service` under `awsLogsConfiguration`).

The container filesystem is ephemeral: re-enter member credentials in Settings after every
deploy, or seed them so each deploy re-creates them: put each environment's member username and password in
SSM (`/connect-sdk-demo/TSANET_DEMO_SEED_BETA_USERNAME`, `.../TSANET_DEMO_SEED_BETA_PASSWORD`, and
the `DEV` pair), add them to the container's `secrets` like the gate values, and the demo writes
them into the matching store at startup when that store is empty. With the seeds in place, keep
the `environment` block from the example above (`TSANET_DEMO_CREDENTIALS_LOCKED` = `true`) and
deploy: Settings becomes a read-only "Signed in as" view with the environment switch, and
credential saves and clears are refused. The lock covers the E2EE partner table as well: its
add and remove endpoints refuse, the card shows the table read-only, and the pilot's keys come
from `TSANET_DEMO_E2EE_PARTNERS_BETA` and `TSANET_DEMO_E2EE_PARTNERS_DEV` (above), which the locked demo reads at startup. Rotating a seeded credential is a new SSM value plus a
redeploy, and that works only because the ECS filesystem is ephemeral: a seed never overwrites a
stored credential, so on a persisted data dir you would clear `credentials-<env>.properties`
first. Two rules for every value that reaches the demo through an environment variable (seeds and
gate passwords alike): it must not contain `${`, because Spring resolves `${name:default}` inside
a bound value and would seed a different string; and put it with `--value`, not `file://`, so no
trailing newline is stored, since values are seeded exactly as given. A locked demo warns at
startup for every environment whose store is empty: seed both, or expect visitors who switch to
the other one to see a demo signed in as nobody. The gate passwords live only in SSM and your local untracked notes. Rotating either
password is a new SSM value plus the same `update-express-gateway-service` call, which starts a
new deployment even when the container settings are unchanged (observed: an update with an
identical container block started a new deployment; the app reads both logins once at startup,
and ECS resolves secrets when a task starts). A gate configuration the app refuses (a guest
password with no operator password, or the same username for both logins) exits the task at
startup, so the deployment never reaches `SUCCESSFUL` in `list-service-deployments`; the reason
is the `APPLICATION FAILED TO START` block in `aws logs tail <LOG_GROUP>`. A seed the demo cannot
write (an unwritable data dir) or a seed naming an environment that does not exist fails startup
the same way. With E2EE on, so does a partner seed the demo cannot use (a private key, or a JWKS
URL that has no host or isn't HTTPS; HTTP to loopback is the one exception).

**Turning it off:** there is no pause. Delete the service with
`aws ecs delete-express-gateway-service --service-arn <SERVICE_ARN>` when the demo is not
needed for a while, and recreate it (new URL) when it is. The image stays in ECR.

**Existing App Runner services** (created before the 2026-04-30 cutoff) keep working; the
earlier version of this section, with pause/resume and `start-deployment`, is in this file's
git history.

## 7. Shutdown

```bash
# Ctrl+C in the server/ngrok terminals, or:
pkill -f demo-ui-0.0.1-SNAPSHOT.jar
pkill -f ngrok
```

Credentials stay on disk for next time unless you clear them in Settings.

## 8. A publish run that failed after the GitHub Release was published

Publishing a GitHub Release starts `publish.yml`, and creates the tag if the Release was created without an existing one, so a failed run can leave a public Release and tag with no complete package behind them. There's no separate test step: `mvn deploy` is the build, and each module deploys as soon as its own build finishes, starting with the parent pom. So any failure in the "Build, test and publish" step, a failing test included, normally leaves at least the parent pom published under that version. Check the repository's Packages page to see what got out.

| Where the run failed | What to do |
|---|---|
| "Build, test and publish" | Keep the tag, say in the Release notes that the version is incomplete, and mark it a pre-release (`gh release edit <TAG> --repo tsanetgit/Connect_SDK --prerelease`) so the latest-release link doesn't point at it. Don't reuse the version. Fix the cause through a PR and cut the next patch version |
| "Require a commit on main", or "Resolve the version to publish" (`-SNAPSHOT`, or not `x.y.z`) | Nothing was built. Delete the Release and its tag, then cut it again with a correct tag, from a commit on `main`'s first-parent line |
| Any other step before "Build, test and publish", such as a missing `CONNECT_API_CODE_TOKEN` or a failed checkout | Nothing was built, and the commit is fine. Keep the Release and tag, fix the cause and re-run the failed run. A re-run uses `publish.yml` from the tagged commit, so if the cause is in that file, delete the Release and tag and cut it again from the fixed commit |

To delete a Release and its tag:

```bash
gh release delete <TAG> --repo tsanetgit/Connect_SDK --cleanup-tag
```
