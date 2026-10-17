# Mission content version 1

| ID | Learning / state requirement |
|---|---|
| aws-001 | Region plus bases in Tokyo AZ a and c |
| aws-002 | Create operator and grant reads of rescue objects |
| aws-003 | Replace broad policy with least privilege |
| aws-004 | Trusted EC2 role, object permission, instance association |
| aws-005 | Enable simulated MFA and secure login flag |
| aws-006 | Resource-specific read grant, deletion denied |
| aws-007 | Create healthy EC2 with valid base AMI/subnet/SG/AZ |
| aws-008 | Start stopped instance and restore health |
| aws-009 | Expand attached EBS and separately grow filesystem |
| aws-010 | Create AMI from source and launch healthy clone |
| aws-011 | Right-size running service to ≤ 0.04 virtual units/hour |
| aws-012 | Inspect stopped application on running instance; restore service |

Each executable JSON has three-language title/story/lesson/hints, initial resources, fault identifier, typed objectives, allowed actions, reference commands and rewards. Reference commands are for test automation and reference, never replayed automatically by a mission UI button. Reference-created IDs are considered valid objective references by the content validator.

Every mission is simplified. Mission 1 uses base markers rather than a separate architecture explanation quiz. Mission 5 uses a simulated MFA flag rather than OTP enrollment. Mission 11 supports micro and small within a budget, but does not simulate workload capacity. Mission 12 has one actual app-state fault, rather than a full OS failure tree. The 48 future entries are catalog plans, not executable mission JSON, and cannot be unlocked.
