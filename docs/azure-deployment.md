# Deploying the demo to Azure Container Apps

> **Status: written, not executed.** No Azure subscription was available when this
> was drafted, so nothing here has been run end to end. Every command was taken
> from Microsoft Learn on 2026-08-04 rather than from memory, and the sources are
> linked at the bottom, but treat the first run as a shakedown rather than a
> repeat of a known-good procedure. The AWS App Runner path in
> [RUNBOOK.md](RUNBOOK.md) §6 *is* runtime-verified; this is its Azure counterpart.

Azure Container Apps is the closest analogue to App Runner for this app: it takes
a prebuilt container image, gives you a managed HTTPS URL, and scales to zero
between demos. That matters because [aws-hosting-options.md](aws-hosting-options.md)
records App Runner as closed to new customers, so a second cloud is a live option
rather than a hypothetical.

The app itself is unchanged. It is the same `linux/amd64` image the AWS path
builds: a single Spring Boot jar on port 8090, calling out to Connect over HTTPS,
with no inbound webhook receiver and no state worth preserving.

## Conventions

Placeholders follow [RUNBOOK.md](RUNBOOK.md).

| Placeholder | Meaning |
|---|---|
| `<SUBSCRIPTION_ID>` | Target Azure subscription |
| `<RESOURCE_GROUP>` | Resource group holding every resource below |
| `<LOCATION>` | Azure region, e.g. `westus2` |
| `<ACR_NAME>` | Registry name, 5-50 lowercase alphanumerics, globally unique |
| `<LOGIN_SERVER>` | The registry's real hostname. **Read it from the create output; do not assume it is `<ACR_NAME>.azurecr.io`.** See the DNL gotcha below |
| `<ENVIRONMENT>` | Container Apps environment name |
| `<APP_NAME>` | Container app name, becomes part of the public URL |
| `<IDENTITY>` | User-assigned managed identity used for the image pull |

## One-time setup

```bash
az login
az account set --subscription <SUBSCRIPTION_ID>
az upgrade
az extension add --name containerapp --upgrade
az provider register --namespace Microsoft.App
az provider register --namespace Microsoft.OperationalInsights
```

```bash
az group create --name <RESOURCE_GROUP> --location <LOCATION>
```

### Container registry

```bash
az acr create \
  --resource-group <RESOURCE_GROUP> \
  --name <ACR_NAME> \
  --sku Standard \
  --dnl-scope TenantReuse
```

**Read `loginServer` out of that output and use it everywhere below.** Azure's
Domain Name Label feature appends a hash to the registry hostname, so a registry
named `connectsdkdemo` becomes something like
`connectsdkdemo-e7ggejfuhzhgedc8.azurecr.io`. The `--dnl-scope` choice is
**immutable after creation**. Pass `--dnl-scope Unsecure` if you want the plain
`<ACR_NAME>.azurecr.io` form, at the cost of the subdomain-takeover protection
the hash exists to provide. Either way, hardcoding `<ACR_NAME>.azurecr.io` into a
script is the single easiest way to make this deployment fail with an image-pull
error that reads like a permissions problem.

### Container Apps environment

```bash
az containerapp env create \
  --name <ENVIRONMENT> \
  --resource-group <RESOURCE_GROUP> \
  --location <LOCATION>
```

### Managed identity for the image pull

Microsoft recommends a user-assigned managed identity over registry admin
credentials. This avoids a registry password living in the app config.

```bash
az identity create --name <IDENTITY> --resource-group <RESOURCE_GROUP>

IDENTITY_ID=$(az identity show \
  --name <IDENTITY> --resource-group <RESOURCE_GROUP> \
  --query id --output tsv)
```

Managed-identity pulls require the registry to accept ARM audience tokens. Check,
and enable if needed:

```bash
az acr config authentication-as-arm show -r <ACR_NAME>
az acr config authentication-as-arm update -r <ACR_NAME> --status enabled
```

The Azure CLI assigns the `acrpull` role automatically when you pass
`--registry-identity` on create. The PowerShell path does not; it needs an
explicit role assignment.

## Build and push the image

Identical to the AWS path up to the tag. The jar is built **outside** Docker
because `connect-library` codegen needs the sibling `../Connect-API-Code`
checkout on the `beta` branch, which the build context does not carry.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl demo-ui -am package -DskipTests
```

```bash
docker build --platform linux/amd64 -t connect-sdk-demo:latest .
```

`--platform linux/amd64` is not optional when building on Apple Silicon. Container
Apps runs amd64, and an arm64 image fails at start with an exec-format error.

```bash
az acr login --name <ACR_NAME>
docker tag connect-sdk-demo:latest <LOGIN_SERVER>/connect-sdk-demo:latest
docker push <LOGIN_SERVER>/connect-sdk-demo:latest
```

Tag the git sha alongside `latest`, the same convention the AWS path uses, so
"what is actually running?" stays answerable:

```bash
SHA=$(git rev-parse --short HEAD)
docker tag connect-sdk-demo:latest <LOGIN_SERVER>/connect-sdk-demo:$SHA
docker push <LOGIN_SERVER>/connect-sdk-demo:$SHA
```

## Create the container app

The auth gate password is a real secret, so it goes in `--secrets` and is
referenced from the env var rather than being passed as a plain value. The Entra
tenant and audiences are identifiers rather than secrets and can be plain env
vars, which matches how the App Runner service holds them today.

```bash
az containerapp create \
  --name <APP_NAME> \
  --resource-group <RESOURCE_GROUP> \
  --environment <ENVIRONMENT> \
  --image <LOGIN_SERVER>/connect-sdk-demo:latest \
  --registry-server <LOGIN_SERVER> \
  --registry-identity "$IDENTITY_ID" \
  --user-assigned "$IDENTITY_ID" \
  --target-port 8090 \
  --ingress external \
  --min-replicas 0 \
  --max-replicas 1 \
  --secrets "demo-auth-password=<GATE_PASSWORD>" \
  --env-vars \
      "TSANET_DEMO_AUTH_USER=<GATE_USER>" \
      "TSANET_DEMO_AUTH_PASSWORD=secretref:demo-auth-password" \
      "TSANET_DEMO_ENTRA_TENANT=<TENANT_ID>" \
      "TSANET_DEMO_ENTRA_AUDIENCE_BETA=<BETA_AUDIENCE>" \
      "TSANET_DEMO_ENTRA_AUDIENCE_DEV=<DEV_AUDIENCE>" \
  --query properties.configuration.ingress.fqdn
```

`--query properties.configuration.ingress.fqdn` prints the public hostname.

**Never deploy without the gate.** The app is open when `TSANET_DEMO_AUTH_PASSWORD`
is unset, which is fine locally and unacceptable on a public URL. `/healthz`
stays open by design so the platform can probe it.

**Never point a hosted deploy at production.** BETA is `connect2.tsanet.net`;
`connect2.tsanet.org` is production and is deliberately not selectable in the app.

### Why the Entra variables matter

If `TSANET_DEMO_ENTRA_TENANT` and the audience variables are missing, the app
starts and looks healthy, `oauthAvailable` reports false for every environment,
and any data call returns **428** with `has no Entra tenant/audience configured`.
That message reads like a credentials fault but is a config fault. This was
observed for real on a local run, so it is the first thing to check if the
deployed app rejects a correctly-entered OAuth credential.

## Redeploy a new image

```bash
docker build --platform linux/amd64 -t connect-sdk-demo:latest .
az acr login --name <ACR_NAME>
docker tag connect-sdk-demo:latest <LOGIN_SERVER>/connect-sdk-demo:latest
docker push <LOGIN_SERVER>/connect-sdk-demo:latest

az containerapp update \
  --name <APP_NAME> \
  --resource-group <RESOURCE_GROUP> \
  --image <LOGIN_SERVER>/connect-sdk-demo:latest
```

Unlike App Runner, where pushing a tag does nothing until an explicit
`start-deployment`, `az containerapp update` creates and activates a new revision
directly. Container Apps sets each container's image pull policy to `always`, so
a moved `latest` tag is picked up on the next container start.

Changing a secret does **not** create a revision on its own. After
`az containerapp secret set`, deploy a new revision or restart the existing one,
or the running app keeps the old value.

## Cost control between demos

Scale to zero replaces App Runner's pause/resume. With `--min-replicas 0` there
are no resource-consumption charges while the app sits idle, and the first request
after idle pays a cold start. Idle-rate billing only applies to revisions with a
minimum replica count above zero, so leaving the minimum at zero is the cheapest
configuration.

To force it down immediately rather than waiting for the scale-in:

```bash
az containerapp update --name <APP_NAME> --resource-group <RESOURCE_GROUP> \
  --min-replicas 0 --max-replicas 1
```

**Unverified:** whether the Container Apps *environment* itself carries a standing
charge while every app in it is scaled to zero. Confirm on the billing page before
assuming an idle deployment is free.

## App Runner to Container Apps parity

| App Runner | Container Apps | Note |
|---|---|---|
| Service | Container app | |
| ECR | ACR | Login server may carry a DNL hash |
| `start-deployment` | `az containerapp update --image` | Container Apps deploys on update; no separate trigger |
| `AutoDeploymentsEnabled` | Pull policy is always `always` | A moved tag is picked up on next start |
| `pause-service` / `resume-service` | `--min-replicas 0` | Scale to zero, cold start on first hit |
| `RuntimeEnvironmentVariables` | `--env-vars` | Plaintext in config either way |
| (no equivalent) | `--secrets` + `secretref:` | Better than App Runner for the gate password |
| Health check `/healthz` | Ingress probes | Probe syntax not verified here, see Learn |
| Ephemeral instance storage | Ephemeral container filesystem | Same consequence, see below |

## Carried-over behaviour

- **The container filesystem is ephemeral.** Member credentials live under
  `TSANET_DEMO_DATA_DIR` and are lost on every new revision, exactly as on App
  Runner. Re-enter them per environment under Settings after each deploy. This is
  the documented runbook step, not a defect.
- **Credentials are per environment.** Configuring BETA does not configure DEV.
- **Scale to zero means a cold start.** A demo audience hitting a cold URL waits
  for a JVM boot. Send one warm-up request before a call.

## Sources

Verified 2026-08-04:

- [Deploy an existing container image with the command line](https://learn.microsoft.com/en-us/azure/container-apps/get-started-existing-container-image)
- [Image pull from ACR with managed identity](https://learn.microsoft.com/en-us/azure/container-apps/managed-identity-image-pull)
- [Manage secrets in Azure Container Apps](https://learn.microsoft.com/en-us/azure/container-apps/manage-secrets)
- [Create a private container registry with the Azure CLI](https://learn.microsoft.com/en-us/azure/container-registry/container-registry-get-started-azure-cli)
- [Billing in Azure Container Apps](https://learn.microsoft.com/en-us/azure/container-apps/billing)
- [Scaling in Azure Container Apps](https://learn.microsoft.com/en-us/azure/container-apps/scale-app)
