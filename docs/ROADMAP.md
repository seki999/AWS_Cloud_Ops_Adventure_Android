# Honest roadmap

## Delivered (content version 2, app 0.2.0)

- 60 playable, state-validated missions across 10 chapters; every one has a JVM regression covering initial failure, invalid-command atomicity, reference solution, save/load verdict equality, deterministic replay, idempotent rewards, reset, and "incomplete solution must fail".
- Service simulators behind `SimulatorRegistry`: VPC/network, S3/EFS/EBS snapshots, RDS/DynamoDB, ALB/ASG/Route 53/CloudFront, CloudWatch/SNS/Logs/CloudTrail/dashboards, Lambda (predefined behaviours only)/Scheduler/SSM/CloudFormation subset, ECR/ECS/EKS subset, Organizations SCP/KMS/cross-region copies/Well-Architected checks.
- `NetworkEvaluator`: CIDR math, longest-prefix routes, IGW/public IP, NAT egress, stateful SG, ordered stateless NACL with ephemeral return path, listener and health checks.
- Versioned save format (`SaveCodec`, v1→v2 migration) keyed by stable mission IDs.
- Chapter map with 10 named districts, lock state and per-chapter stars; CloudWatch metric chart in the console.

## Next: depth and polish

- Story: per-mission NPC dialogue for Nova, Mira and Dr. Cloud in chapters 3–10 (currently one templated briefing each); learning-summary screen with wrong-step analysis and recommended architecture.
- UI: per-service console pages with typed forms instead of the generic `key=value` operation form; tablet two-pane layout; TalkBack labels; persisted in-game font size applied globally; `strings.xml` resource catalogs.
- Localize engine error messages and objective labels (currently English identifiers).
- Topology editor: add/delete connections through `SimulationCommand`s; currently only derived links and node dragging.
- Achievement UTC timestamps alongside simulation ticks; optional coin spending.
- Asynchronous lifecycle states (pending/stopping) on the virtual clock; EC2 OS diagnostics tree for mission 12.
- IAM: permission boundaries, session policies and Conditions remain rejected (fail closed), not modelled.
- CLI: durable history; more `aws` verbs mapped onto existing simulator operations (many mutations are reachable only via `sim <op>` today).
- Release signing pipeline (see BUILD.md); custom launcher icon; data-extraction rules.

## Verification gaps

- Instrumentation tests were last executed on 2026-10-17 against the v0.1 code; the API 35 system image has since been removed from this machine, so they were not re-run for 0.2.0. See VALIDATION.md.
- No physical phone/tablet/landscape matrix or flight-mode run on a device.
