# BDS-waterdogpe

[日本語](README.ja.md)

A PMMP-style dynamic multi-world setup for vanilla Minecraft Bedrock
Dedicated Server (BDS), built on [WaterdogPE](https://github.com/WaterdogPE/WaterdogPE)
and AWS ECS Fargate. Players connect to a single WaterdogPE proxy; worlds
are provisioned/torn down on demand as separate BDS tasks, without ever
restarting the proxy.

## Why

BDS has no built-in concept of multiple loaded worlds or a plugin API -
each world is a separate server process. WaterdogPE fills the "multiple
worlds behind one address" role that a plugin-extensible server (like
PMMP) gets for free, and its plugin API can register/unregister
downstream servers at runtime. This repo wires that up to ECS Fargate so
a whole world - provisioning the task, waiting for it to become healthy,
registering it with the proxy - happens behind a single API call.

## Architecture

```
Bedrock client
      │ UDP 19132 (RakNet)
      ▼
WaterdogPE task (ECS Fargate, public)
  ├─ WaterdogPE proxy process
  └─ WorldControl plugin (same JVM)
        - GET/POST/DELETE /worlds (TCP 8081, VPC-internal only)
        - calls AWS ECS RunTask/DescribeTasks/StopTask to provision
          or tear down a world, then ProxyServer.registerServerInfo()/
          removeServerInfo() to add/remove it from the proxy - no
          restart required
        │
        │ RunTask / StopTask (IAM task role, scoped to this cluster
        │ and the BDS task definition only)
        ▼
BDS world tasks (ECS Fargate, private - reachable only from the
Waterdog task's security group)
```

Nothing calls back into BDS: the Bedrock Dedicated Server binary has no
plugin system, so "add a world" can only ever be triggered from outside
it (the WorldControl API, or hakomc scripts calling that API from
inside a *different* running world - see below).

## Layout

- **`waterdog/`** - the WaterdogPE proxy image and the `WorldControl`
  plugin (Java/Gradle) that provisions worlds via the AWS SDK. See
  [`waterdog/config.yml`](waterdog/config.yml) for the proxy config and
  [`waterdog/plugin/src`](waterdog/plugin/src) for the plugin.
- **`infra/`** - AWS CDK (TypeScript) stack: VPC (public subnets only,
  no NAT Gateway - there's no load balancer or multi-instance HA in
  scope, so it isn't needed), security groups, the ECS cluster/task
  definitions, and the IAM policy that scopes WorldControl's AWS access
  to `RunTask`/`StopTask`/`DescribeTasks` on this cluster and
  `PassRole` on exactly the BDS task's two roles.
- **`hakomc/`** - a [hakomc](https://github.com/hakomc/hakomc) dev
  environment (Bedrock Scripting API), bootstrapped from
  [hakomc-server](https://github.com/hakomc/hakomc-server). Its
  `src/worldControl.ts` is a small client for the WorldControl API so a
  behavior pack running on *one* world can add or remove *other*
  worlds, using `@minecraft/server-net`'s HTTP client (the only way to
  make outbound HTTP calls from BDS-side scripts).

## Status

IaC and application code only - **nothing here has been deployed**.
`cdk synth` and both Gradle/npm builds are verified to succeed; see the
commit history for what was actually checked (built jar contents, the
synthesized IAM policy, clean-install builds, etc.) versus what's
still an assumption to confirm at deploy time.

Known rough edges, called out in code comments where they matter:

- `@minecraft/server-net` and `@minecraft/server-admin` are both
  pre-release Bedrock modules. The manifest dependency version string
  convention and the exact server-side `variables` config file/flag
  that `worldControl.ts` reads `worldControlApiUrl` from are both worth
  reconfirming against current Bedrock docs before relying on them.
- The WaterdogPE Fargate task has no load balancer, so its public IP
  changes if the task is ever replaced. There's no Elastic IP/Route53
  update wired up for that yet.
- BDS defaulting to the NetherNet transport (which WaterdogPE's RakNet
  downstream connection can't reach) is handled by passing
  `TRANSPORT=raknet` as a RunTask container override - itzg's image
  maps that into `server.properties` correctly as of
  [itzg/docker-minecraft-bedrock-server#675](https://github.com/itzg/docker-minecraft-bedrock-server/pull/675).

## Building

```bash
# WaterdogPE + plugin image (multi-stage: builds the plugin jar, then
# assembles the proxy image)
docker build -t waterdogpe ./waterdog

# CDK infra - type-checks and synthesizes CloudFormation templates,
# does not deploy anything
cd infra && npm install && npx cdk synth

# hakomc dev environment + WorldControl operations library
cd hakomc && npm install && npm run build
```

## License

Not yet decided for this repo's own code (`waterdog/`, `infra/`).
`hakomc/` carries its own GPLv3 license, inherited from the
[hakomc-server](https://github.com/hakomc/hakomc-server) template it
was bootstrapped from.
