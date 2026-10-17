# Supported simulation rules

- IAM: users, groups, roles, explicit Allow/Deny, default deny, action/resource `*` patterns and group membership evaluation. Explicit deny overrides matching allows. EC2 role access needs `ec2.amazonaws.com` trust plus resource permission. Policy JSON containing Condition or unsupported semantics is rejected. Boundaries, SCP, sessions, real MFA and STS are not modeled.
- Resource mutations require IAM authorization and mission allowlist. Training principal selection is an explicit simulator control. Read-only CLI queries also authorize, using `*` for list/describe operations. The console is an instructor view of the world and does not restrict resource visibility per principal yet.
- EC2: creation validates supported instance type, existing AMI/subnet/SG, region and AZ compatibility. start only from stopped; stop/reboot only from running; terminate from running/stopped. Terminated cannot restart. Transitional states settle atomically in this MVP, with no asynchronous pending/stopping interval. Health depends on running state, application state and system flag.
- AMI records source ID and app state; clone inherits captured app state. No real image filesystem is emulated.
- EBS: valid positive size, independent fsSize and used space, same-region/AZ attachment, grow only after association. Expanding volume does not grow the filesystem. No shrinking. Deletion rejects references from remaining resources.
- Metrics: CLI reports deterministic CPU, simulated AgentMemory and health derived from current state. No random pass/fail. No real CloudWatch account, alarm evaluation or historical chart engine.
- Costs: version 1 teaching table, one virtual second per command, running instances and allocated volumes only. Not AWS prices. RDS/NAT/transfer/load-balancer prices are unimplemented.
- Audit: every attempted command, actor, tick, success and message. Replaying the same commands from the same initial world reproduces state in JVM tests. Claims and hints also use commands; failed save never publishes partial state.
- VPC/subnet/SG are configuration references only. **No NetworkEvaluator yet.** IGW/NAT, routing, SG/NACL packet evaluation and real reachability must not be inferred from diagrams.

The unsupported-operation response is explicit. CLI does not invoke Shell, AWS SDK, network APIs or execute user code. EC2 create CLI intentionally uses a simulator ID argument; this is not a full AWS CLI emulator.
