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

If the build fails with `cannot find symbol ...`, the sibling spec repo is probably on the wrong branch. Check out the one that matches this repo's branch, `beta` for `main`:

```bash
cd <REPO_ROOT>/Connect-API-Code && git checkout beta
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

## 3. Enter credentials (first time, or after clearing)

1. Open http://localhost:8090 → **Settings** tab — one card per environment (BETA / DEV)
2. Enter the member username and password on the right card → **Save Credentials**; **Make Active** to switch environments
3. The header badge should flip to green with your company name (that's `/api/me` succeeding against the active environment)

Point each environment card at a member account you control on that environment. For bidirectional testing you need two member companies that are partnered with published process forms (each can search and submit to the other). Store their credentials in your own `.env`/credential store, not in this repo.

Credentials persist to `~/.tsanet-demo-ui/credentials.properties` (mode 600, never in git) — subsequent startups skip this step. **Settings → Clear** wipes them.

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
plaintext in the service configuration. The two `GUEST` values are optional: set them to give
members a second login with the same rights. Without a guest login, delete those two names from
the loop (SSM refuses an empty SecureString) and the two matching `secrets` entries below. To
keep the default guest username (`guest`), delete only the `GUEST_USER` name and entry.

```bash
for v in TSANET_DEMO_AUTH_USER TSANET_DEMO_AUTH_PASSWORD TSANET_DEMO_AUTH_GUEST_USER TSANET_DEMO_AUTH_GUEST_PASSWORD TSANET_DEMO_ENTRA_TENANT TSANET_DEMO_ENTRA_AUDIENCE_BETA TSANET_DEMO_ENTRA_AUDIENCE_DEV; do
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

**Build, push, create.** Build the jar (step 1), then:

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
readable from `describe-express-gateway-service`):

```json
{
  "image": "<ECR_REGISTRY>/connect-sdk-demo:<SHA>",
  "containerPort": 8090,
  "secrets": [
    {"name": "TSANET_DEMO_AUTH_USER", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_USER"},
    {"name": "TSANET_DEMO_AUTH_PASSWORD", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_PASSWORD"},
    {"name": "TSANET_DEMO_AUTH_GUEST_USER", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_GUEST_USER"},
    {"name": "TSANET_DEMO_AUTH_GUEST_PASSWORD", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_AUTH_GUEST_PASSWORD"},
    {"name": "TSANET_DEMO_ENTRA_TENANT", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_ENTRA_TENANT"},
    {"name": "TSANET_DEMO_ENTRA_AUDIENCE_BETA", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_ENTRA_AUDIENCE_BETA"},
    {"name": "TSANET_DEMO_ENTRA_AUDIENCE_DEV", "valueFrom": "arn:aws:ssm:<REGION>:<AWS_ACCOUNT_ID>:parameter/connect-sdk-demo/TSANET_DEMO_ENTRA_AUDIENCE_DEV"}
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
deploy. `TSANET_DEMO_CREDENTIALS_LOCKED=true` (a plain entry in the container's `environment` list,
`"environment":[{"name":"TSANET_DEMO_CREDENTIALS_LOCKED","value":"true"}]`, beside `secrets`) turns
Settings into a read-only "Signed in as" view with the environment switch and refuses credential
saves and clears. <!-- PROVISIONAL(tsanetgit/Connect_SDK#152): the two sentences that follow describe the
hosted gap; once credentials are seeded at startup, replace them with the lock-on procedure. -->
On this deployment it is not yet usable as intended: the lock is read at startup,
so turning it on is an `update-express-gateway-service` call, which redeploys and empties the data
dir, leaving a locked demo with nothing to sign in as. It becomes usable once credentials survive
a deploy (seeded from SSM at startup, or a persistent data dir), tracked on
`tsanetgit/Connect_SDK#152`. Until then run the hosted demo unlocked, or lock a local run whose
data dir persists. The gate passwords live only in SSM and your local untracked notes. Rotating either
password is a new SSM value plus the same `update-express-gateway-service` call, which starts a
new deployment even when the container settings are unchanged (observed: an update with an
identical container block started a new deployment; the app reads both logins once at startup,
and ECS resolves secrets when a task starts). A gate configuration the app refuses (a guest
password with no operator password, or the same username for both logins) exits the task at
startup, so the deployment never reaches `SUCCESSFUL` in `list-service-deployments`; the reason
is the `APPLICATION FAILED TO START` block in `aws logs tail <LOG_GROUP>`.

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
