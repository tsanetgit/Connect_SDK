# Hosting options for the Connect demo UI

> **Deprecation note (2026-07-13).** AWS stopped accepting new App Runner
> customers on 2026-04-30; existing services remain operational (security and
> availability maintained, no new features). The successor is **Amazon ECS
> Express Mode**, which consumes a pre-built ECR image — exactly this repo's
> pipeline — so migration is a small config change when warranted. Plan: keep
> the existing paused service for now; do not build deeper on App Runner
> features (e.g. custom domains); migrate to ECS Express Mode if the demo
> becomes long-lived. The recommendation below predates the announcement.

Research only — nothing provisioned. `demo-ui` is a single Spring Boot jar (Tomcat embedded, serves its own static frontend) that calls out to `connect2.tsanet.net` (BETA) over HTTPS. No inbound webhook receiver, no persistent state beyond a local SQLite cache file.

## AWS options

| Option | Fit | Notes |
|---|---|---|
| **App Runner** | Best fit | Point at the GitHub repo or a container image, it builds/deploys/scales automatically, gives you a public HTTPS URL out of the box. Cheapest to operate for a low-traffic demo (scales to zero-ish, pay per use). No VPC/ALB/cluster to manage. |
| **ECS Fargate** | Overkill for this | Container orchestration for a single stateless jar is more moving parts (task def, service, ALB, security groups) than the app needs. Reach for this only if the demo grows into multiple services. |
| **Elastic Beanstalk** | Viable, dated | Handles the jar deploy + load balancer + scaling for you, but AWS has been steering new workloads toward App Runner/ECS; more legacy console surface to learn for a one-off demo. |
| **Amplify** | Wrong shape | Built for static/SSR frontends with Lambda functions, not a monolithic Spring Boot backend. Would force splitting frontend/backend for no benefit here. |
| **EC2** | Avoid | Full manual ops (patching, TLS termination, process supervision) for something App Runner does managed. Only worth it if you need arbitrary long-running processes or non-HTTP ports. |

**Recommendation: AWS App Runner.** Lowest operational overhead for a single branded demo jar, built-in HTTPS, autoscaling down to near-zero cost between demos, straightforward source (GitHub) or image-based deploy.

## Alternatives outside AWS

- **Fly.io** — one-command deploy of a Docker image, generous free tier, very low friction for a demo. Worth considering if you want to skip AWS account/IAM setup entirely.
- **Railway / Render** — similar "push a repo, get a URL" experience to App Runner, arguably even less config.
- **Local + tunnel (ngrok/Cloudflare Tunnel)** — zero hosting cost, good for a live walkthrough on a call, not suitable for an always-on link you hand out.

## Decision (2026-07-12): on-demand, not always-on

Shawn confirmed the demo link doesn't need to stay live between calls — spin it up around specific customer/prospect demos, not a persistent 24/7 URL. That changes the recommendation:

- **App Runner still works**, but use its **pause/resume** capability (via console or `aws apprunner pause-service` / `resume-service`) between demos — paused services incur no compute charges, and resume takes under a minute. This is the best fit if the demo stays inside AWS.
- **Simpler alternative for pure on-demand use: local + Cloudflare Tunnel (or ngrok).** Run `demo-ui` locally right before a call, point a tunnel at `localhost:8090`, hand out the tunnel URL, kill both when done. Zero hosting cost, zero AWS setup, and matches the actual usage pattern (a handful of scheduled demos) better than any managed hosting tier.

No infrastructure has been provisioned either way — this stays a decision doc until Shawn wants to stand something up for an actual scheduled demo.
